/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.forbric.kernel.util.ForbricLog;

/**
 * Answers a Forge registry lookup that used a name the merge renamed.
 *
 * <p>Forge 1.21.1 declares four registries of its own as {@code forge:…} in {@code ForgeRegistries$Keys}:
 * {@code forge:biome_modifier_serializers}, {@code forge:structure_modifier_serializers}, {@code
 * forge:condition_codecs} and {@code forge:ingredient_serializers}. The merged base is built from NeoForge's
 * patched sources, and there the same registry objects are registered under {@code neoforge:…} — the base's own
 * {@code reports/registry_order.json} lists {@code neoforge:biome_modifier_serializers}, {@code
 * neoforge:condition_codecs} and friends, and nothing under {@code forge:}.
 *
 * <p>So Forge's own {@code ObjectHolderRegistry} asked {@code RegistryManager.getRegistry(forge:condition_codecs)},
 * got null, and threw {@code IllegalStateException: Unable to find registry with key forge:condition_codecs for mod
 * "forge"} — 26 of those at every shutdown, wrapped in {@code Failed to apply some object holders}. Measured on the
 * 1.21.1 line on 2026-11-13.
 *
 * <p>{@code RegistryManager.registries} is a Guava {@code BiMap}, so the same {@code ForgeRegistry} cannot be
 * inserted under a second key (values must stay unique). The name therefore has to be translated on the way in,
 * which is what {@link #lookup} does: try the name as asked, then, only for a {@code forge:} name, retry under
 * {@code neoforge:}.
 *
 * <p>Deliberately reflection-only and free of Minecraft and Forge types: this class is compiled into both lines,
 * and 26.2 ships no {@code net.minecraftforge.registries} package at all.
 */
public final class KernelForgeRegistryNames {
	private KernelForgeRegistryNames() {
	}

	/** The four names are the whole population; anything else is answered as asked. */
	private static final Set<String> SAID = ConcurrentHashMap.newKeySet();
	private static final int SAID_LIMIT = 24;
	private static final Map<Object, Map<?, ?>> MAPS = Collections.synchronizedMap(new IdentityHashMap<>());

	/**
	 * {@code RegistryManager.getRegistry}'s body, with one extra question asked before answering null.
	 *
	 * @param manager the {@code RegistryManager} the call was made on (never its {@code ACTIVE} singleton only —
	 *                {@code VANILLA} and {@code FROZEN} are asked the same question)
	 * @param name    the {@code ResourceLocation} the caller asked for
	 * @return the registry, or null exactly when the unmodified method would have returned null
	 */
	public static Object lookup(Object manager, Object name) {
		if (manager == null || name == null) return null;
		Map<?, ?> registries = registriesOf(manager);
		if (registries == null) return null;
		Object found = registries.get(name);
		if (found != null) return found;
		String namespace = call(name, "getNamespace");
		String path = call(name, "getPath");
		if (!"forge".equals(namespace) || path == null) return null;
		Object alias = renamed(name.getClass(), path);
		if (alias == null) return null;
		Object viaNeoForge = registries.get(alias);
		if (viaNeoForge != null) {
			say("Forge registry 'forge:" + path + "' is registered as 'neoforge:" + path
					+ "' on this base; answered with that one");
		}
		return viaNeoForge;
	}

	/**
	 * {@code RegistryManager.registries} is package-private and a {@code BiMap}; read it once per manager.
	 */
	private static Map<?, ?> registriesOf(Object manager) {
		Map<?, ?> cached = MAPS.get(manager);
		if (cached != null) return cached;
		try {
			Field field = manager.getClass().getDeclaredField("registries");
			field.setAccessible(true);
			Map<?, ?> map = (Map<?, ?>) field.get(manager);
			if (map != null) MAPS.put(manager, map);
			return map;
		} catch (Throwable unreadable) {
			ForbricLog.warn("[Forbric/Forge] could not read RegistryManager.registries, so a 'forge:' name cannot "
					+ "fall back to the 'neoforge:' registry beside it", unreadable);
			return null;
		}
	}

	/**
	 * Builds the same location under {@code neoforge}. The id class is named differently on each line
	 * ({@code ResourceLocation} here, {@code Identifier} on 26.2), so the factory is found by name on whatever class
	 * the caller handed over rather than by a compile-time type.
	 */
	private static Object renamed(Class<?> idClass, String path) {
		try {
			Method factory = idClass.getMethod("fromNamespaceAndPath", String.class, String.class);
			return factory.invoke(null, "neoforge", path);
		} catch (Throwable absent) {
			return null;
		}
	}

	private static String call(Object target, String method) {
		try {
			return (String) target.getClass().getMethod(method).invoke(target);
		} catch (Throwable absent) {
			return null;
		}
	}

	private static void say(String line) {
		if (SAID.size() >= SAID_LIMIT || !SAID.add(line)) return;
		ForbricLog.info("[Forbric/Forge] " + line);
	}
}
