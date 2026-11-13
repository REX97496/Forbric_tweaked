package net.forbric.kernel.runtime;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.Reflect;

/**
 * Drops the {@code @ObjectHolder} handlers whose registry this base never created, so Forge's own shutdown
 * validation stops throwing over names that cannot be answered.
 *
 * <p>Measured on the 1.21.1 line, twice, on 2026-11-13. Forge's {@code ForgeRegistries.Keys} still names six
 * registries ({@code forge:biome_modifier_serializers}, {@code forge:structure_modifier_serializers},
 * {@code forge:condition_codecs}, {@code forge:ingredient_serializers}, {@code forge:holder_set_type},
 * {@code forge:fluid_type}) and Forge's {@code ObjectHolderRegistry} registers a handler for each name a
 * {@code @ObjectHolder} mentions. At shutdown {@code GameData.revertToFrozen} → {@code applyObjectHolders} runs those
 * handlers, {@code RegistryObject.registryExists} asks {@code RegistryManager.ACTIVE}, gets null, and throws
 * {@code IllegalStateException: Unable to find registry with key ... for mod "forge"}. Twenty-six of them were caught
 * into a list and rethrown as one {@code RuntimeException: Failed to apply some object holders}, which killed
 * {@code ServerLifecycleHooks.handleServerStopped} — so no MinecraftForge mod observed the world stopping.
 *
 * <p>Two facts decided the fix. First, the merged base registers those registries in its NeoForge-shaped registry
 * space; {@code RegistryManager.ACTIVE} on this line holds 30 entries, all {@code minecraft:}, so the Forge names have
 * no answer under either namespace — a rename cannot fix a registry that does not exist. Second,
 * {@code net.minecraftforge.*} classes never reach this kernel's transformer chain (measured in the same round), so
 * the validation itself cannot be edited.
 *
 * <p>What is left is the handler set. A handler whose registry never existed has never run: dropping it changes no
 * mod's state, it only stops Forge from reporting the absence as a shutdown failure. The names are logged, one line,
 * so the absence stays visible instead of silent.
 */
public final class KernelForgeObjectHolders {

	/** Keys already reported, so a second world in one session does not repeat the same paragraph. */
	private static final Set<String> SAID = java.util.concurrent.ConcurrentHashMap.newKeySet();

	private KernelForgeObjectHolders() {
	}

	/**
	 * Removes every {@code namedHandlers} entry whose registry is in neither Forge's manager nor the vanilla one.
	 *
	 * <p>Reflective throughout: on a line without these Forge classes every lookup misses and the method returns
	 * without touching anything.
	 */
	public static void dropHandlersForMissingRegistries() {
		List<String> dropped = new ArrayList<>();
		try {
			Class<?> registry = Class.forName("net.minecraftforge.registries.ObjectHolderRegistry");
			Field field = registry.getDeclaredField("namedHandlers");
			field.setAccessible(true);
			Object raw = field.get(null);
			if (!(raw instanceof Map<?, ?> handlers)) {
				ForbricLog.warn("[Forbric/Forge] ObjectHolderRegistry.namedHandlers is a %s, not a map; the holders "
						+ "that name a registry this base never created stay in place", raw == null ? "null"
								: raw.getClass().getName());
				return;
			}
			List<Object> missing = new ArrayList<>();
			for (Object each : new ArrayList<>(handlers.keySet())) {
				if (!exists(each)) missing.add(each);
			}
			tell("looked at " + handlers.size() + " @ObjectHolder registry name(s), " + missing.size()
					+ " have no registry on this base");
			if (missing.isEmpty()) {
				// Still have to look at the validator list: each RegistryObject also registers a runnable that throws
				// the "Unable to find registry" exception, and those are what Forge rethrows as one failure.
				dropValidators(registry, dropped);
				if (!dropped.isEmpty()) tell(dropped);
				return;
			}
			for (Object each : missing) {
				try {
					handlers.remove(each);
					dropped.add(String.valueOf(each));
				} catch (RuntimeException refused) {
					// An unmodifiable map here would be a different design; say so rather than hide it.
					ForbricLog.warn("[Forbric/Forge] could not drop the object-holder handlers for %s; Forge's "
							+ "shutdown validation will still report it", each, refused);
				}
			}
		} catch (ClassNotFoundException absent) {
			return;
		} catch (Throwable failed) {
			ForbricLog.warn("[Forbric/Forge] could not look at the object-holder registry, so a shutdown may still "
					+ "fail on registries this base never created", Reflect.unwrap(failed));
			return;
		}
		dropValidatorsSafely(dropped);
		if (!dropped.isEmpty()) tell(dropped);
	}

	private static void dropValidatorsSafely(List<String> dropped) {
		try {
			dropValidators(Class.forName("net.minecraftforge.registries.ObjectHolderRegistry"), dropped);
		} catch (Throwable failed) {
			ForbricLog.warn("[Forbric/Forge] could not look at the object-holder validators; Forge's shutdown may "
					+ "still fail on registries this base never created", Reflect.unwrap(failed));
		}
	}

	/**
	 * Removes the object-holder validators whose registry this base never created.
	 *
	 * <p>{@code goThroughValidators} in this class holds one {@code Runnable} per {@code RegistryObject}; each reads
	 * the registry it names and throws {@code IllegalStateException("Unable to find registry with key ... for mod
	 * ...")} when it is absent. Dropping a validator whose registry is absent removes nothing that could ever have
	 * succeeded — the runnable's only outcome there is that exception — and it is the runnable, not the handler set,
	 * that Forge collects into the list it rethrows.
	 */
	private static void dropValidators(Class<?> registry, List<String> dropped) throws Exception {
		Field field = registry.getDeclaredField("validators");
		field.setAccessible(true);
		Object raw = field.get(null);
		if (!(raw instanceof List<?> validators)) return;
		int before = validators.size();
		for (Object each : new ArrayList<>(validators)) {
			Object name = namedLocation(each);
			if (name == null || exists(name)) continue;
			@SuppressWarnings("unchecked")
			List<Object> mutable = (List<Object>) validators;
			mutable.remove(each);
			dropped.add(String.valueOf(name));
		}
		if (validators.size() != before) {
			tell("dropped " + (before - validators.size()) + " object-holder validator(s) for registries this base "
					+ "never created");
		}
	}

	/**
	 * The registry a validator names, read off the lambda's captured argument.
	 *
	 * <p>Reflective because a lambda's fields are synthetic; the first captured value whose type is the id class is
	 * the registry name, and a validator that mentions no such value is left alone.
	 */
	private static Object namedLocation(Object validator) {
		if (validator == null) return null;
		for (Field field : validator.getClass().getDeclaredFields()) {
			Class<?> type = field.getType();
			if (!"net.minecraft.resources.ResourceLocation".equals(type.getName())
					&& !"net.minecraft.resources.Identifier".equals(type.getName())) continue;
			try {
				field.setAccessible(true);
				return field.get(validator);
			} catch (Throwable unreadable) {
				return null;
			}
		}
		return null;
	}

	/** True when Forge's manager or the vanilla registry has an entry under this name. */
	private static boolean exists(Object name) {
		if (name == null) return true;
		try {
			Object manager = Class.forName("net.minecraftforge.registries.RegistryManager").getField("ACTIVE")
					.get(null);
			Method get = manager.getClass().getMethod("getRegistry",
					Class.forName("net.minecraft.resources.ResourceLocation"));
			if (get.invoke(manager, name) != null) return true;
		} catch (Throwable notForge) {
			return true;
		}
		try {
			Object builtIn = Class.forName("net.minecraft.core.registries.BuiltInRegistries").getField("REGISTRY")
					.get(null);
			Method contains = builtIn.getClass().getMethod("containsKey",
					Class.forName("net.minecraft.resources.ResourceLocation"));
			return Boolean.TRUE.equals(contains.invoke(builtIn, name));
		} catch (Throwable unknown) {
			return true;
		}
	}

	private static void tell(List<String> dropped) {
		tell(dropped.size() + " @ObjectHolder registry name(s) this base never created, so Forge's shutdown "
				+ "validation no longer reports them as failures: " + dropped);
	}

	private static void tell(String line) {
		if (!SAID.add(line)) return;
		ForbricLog.info("[Forbric/Forge] %s", line);
	}
}
