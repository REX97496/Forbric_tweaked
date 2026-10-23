package net.forbric.kernel.runtime;

import java.util.List;

import net.forbric.kernel.util.ForbricLog;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import net.minecraftforge.client.ForgeHooksClient;
import net.minecraftforge.client.model.geometry.GeometryLoaderManager;
import net.neoforged.neoforge.client.ClientHooks;

/** Restores Forge's own client hooks at the same lifecycle sites that already serve NeoForge. */
public final class KernelForgeClientInit {
	private KernelForgeClientInit() {}

	private static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty("forbric.forgeClientInit", "on"));
	}

	public static void initClientHooks(Minecraft minecraft, ReloadableResourceManager resources) {
		if (!enabled()) {
			ClientHooks.initClientHooks(minecraft, resources);
			return;
		}
		// The scratch owns no packs. Its listeners must enter NeoForge's graph while that graph is built:
		// adding them directly to resources would be undone by NeoForge's updateListenersFrom afterwards.
		List<PreparableReloadListener> listeners;
		try (ReloadableResourceManager scratch = new ReloadableResourceManager(PackType.CLIENT_RESOURCES)) {
			ForgeHooksClient.initClientHooks(minecraft, scratch);
			listeners = listenersOf(scratch);
		}
		minecraft.options.load(true);
		boolean drained = ForgeClientReloadCapture.withCaptured(listeners,
				() -> ClientHooks.initClientHooks(minecraft, resources));
		if (!drained && !listeners.isEmpty()) {
			if ("off".equalsIgnoreCase(System.getProperty("forbric.unifiedEvents", "on"))) {
				ForbricLog.warn("[Forbric/ForgeClient] client reload bridge is disabled; %d captured listener(s) were not installed", listeners.size());
			} else {
				throw new IllegalStateException("Forge client reload capture was not consumed by NeoForge's registration event");
			}
		}
		ForbricLog.info("[Forbric/ForgeClient] applied MinecraftForge client init: %d captured reload listener(s), %d key mapping(s)",
				listeners.size(), minecraft.options.keyMappings.length);
	}

	public static void onRegisterParticleProviders(ParticleEngine particles) {
		if (enabled()) ForgeHooksClient.onRegisterParticleProviders(particles);
		ClientHooks.onRegisterParticleProviders(particles);
	}

	/** Forge's original ModelManager calls this on every reload, before starting any model-loading future. */
	public static void initGeometryLoaders() {
		if (enabled()) GeometryLoaderManager.init();
	}

	/**
	 * What was registered with a manager this kernel never reloads.
	 *
	 * <p>26.2 has a public accessor for exactly this — {@code ReloadableResourceManager#getListeners()} — and this
	 * line does not (javap -p both: 1.21.1 has the accessor ABSENT and keeps only
	 * {@code private final java.util.List listeners}, 26.2 has both). The accessor's own bytecode is
	 * {@code getfield listeners; areturn}, so reading that field is the same read without the public name; 1.21.1
	 * makes it {@code final}, which is why this only ever reads it.
	 *
	 * <p>Throws rather than returning an empty list: an empty capture reads as "Forge registered nothing", which
	 * is a silently wrong answer, whereas a reflective failure here means the field was renamed again.
	 */
	static List<PreparableReloadListener> listenersOf(ReloadableResourceManager manager) {
		try {
			java.lang.reflect.Field field = ReloadableResourceManager.class.getDeclaredField("listeners");
			field.setAccessible(true);
			@SuppressWarnings("unchecked")
			List<PreparableReloadListener> listeners = (List<PreparableReloadListener>) field.get(manager);
			return List.copyOf(listeners);
		} catch (ReflectiveOperationException | RuntimeException absent) {
			throw new IllegalStateException(
					"1.21.1 ReloadableResourceManager no longer has the `listeners` field; the reload capture has no "
							+ "other source", absent);
		}
	}
}
