package net.forbric.kernel.runtime;

import net.neoforged.bus.api.Event;
import net.neoforged.fml.ModLoader;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.minecraftforge.client.ForgeHooksClient;

/** Both registration APIs populate the same live BlockColors instance. */
public final class KernelForgeBlockColors {
	private KernelForgeBlockColors() { }

	public static void postBlockTintSources(Event event) {
		RegisterColorHandlersEvent.BlockTintSources registration = (RegisterColorHandlersEvent.BlockTintSources) event;
		ModLoader.postEvent(registration);
		if (!"off".equalsIgnoreCase(System.getProperty("forbric.forgeClientInit", "on"))) {
			ForgeHooksClient.onBlockColorsInit(registration.getBlockColors());
		}
	}

	/**
	 * Lines whose {@code BlockColors.createDefault} hands the live colours straight to NeoForge's own
	 * {@code ClientHooks.onBlockColorsInit} never build a NeoForge event this kernel could wrap, so the Forge event is
	 * posted here instead — before NeoForge's, over the same instance, which is what Forge's own hooks do.
	 */
	public static void postBlockTintColors(net.minecraft.client.color.block.BlockColors colors) {
		if (!"off".equalsIgnoreCase(System.getProperty("forbric.forgeClientInit", "on"))) {
			ForgeHooksClient.onBlockColorsInit(colors);
		}
	}
}
