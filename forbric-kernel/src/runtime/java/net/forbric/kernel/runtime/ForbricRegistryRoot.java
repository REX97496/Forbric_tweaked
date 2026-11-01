/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import java.lang.reflect.Field;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.forbric.kernel.util.ForbricLog;

/**
 * The root registry NeoForge's DeferredHolder should consult, which on a merged base is not the one it names.
 *
 * <p>DeferredHolder.getRegistry() is
 *
 * <pre>
 *   return (Registry) BuiltInRegistries.REGISTRY.get(key.registry());
 * </pre>
 *
 * and then bind() does {@code registry.getHolder(key)}. On this base that returned empty for
 * {@code neoforge:swim_speed} five times, while the kernel's own count of {@code BuiltInRegistries.ATTRIBUTE.keySet()}
 * reported three neoforge attributes present. Both facts are measured; they only reconcile if the two are different
 * objects.
 *
 * <p>And the kernel already says so in its own words, in KernelLifecycle.rootRegistry:
 *
 * <pre>
 *   REGISTRY is the public read view and on the merged base it is Forge-wrapped; WRITABLE_REGISTRY is the plain
 *   MappedRegistry that Registry.register actually validates against.
 * </pre>
 *
 * <p>So the field DeferredHolder reads is the Forge-wrapped view, and everything the registration window actually
 * wrote -- NeoForge's own attributes included -- is in the plain one behind it.
 *
 * <p>WHAT THIS DOES NOT DO: it does not change what {@code BuiltInRegistries.REGISTRY} IS, and it does not touch the
 * Forge wrapper. Only DeferredHolder's resolution is redirected. A caller that reaches the root registry by its own
 * path still sees the wrapped view, which is correct for that caller.
 */
public final class ForbricRegistryRoot {

	private static Boolean writableExists;

	private ForbricRegistryRoot() {
	}

	/** The plain root registry if this line has one, else the public view. */
	/**
	 * Off by default. This redirect was written on the hypothesis that DeferredHolder resolved through the wrong root;
	 * AttrCheck then showed BuiltInRegistries.REGISTRY.get(minecraft:attribute) reaches the attributes fine, so the
	 * redirect may be the interference rather than the cure. It now returns exactly what the original bytecode returned
	 * unless -Dforbric.registryRoot=writable asks for the A/B test.
	 */
	public static Registry<?> forLookup() {
		if (!"writable".equals(System.getProperty("forbric.registryRoot", "off"))) {
			return BuiltInRegistries.REGISTRY;
		}
		if (writableExists == null) {
			try {
				Field f = BuiltInRegistries.class.getDeclaredField("WRITABLE_REGISTRY");
				f.setAccessible(true);
				Object plain = f.get(null);
				writableExists = plain instanceof Registry;
				if (writableExists) {
					ForbricLog.info("[Forbric/RegistryRoot] DeferredHolder resolves through WRITABLE_REGISTRY — "
							+ "REGISTRY is Forge-wrapped on this base and the registration window writes to the plain "
							+ "registry behind it, so `neoforge:swim_speed` was present in ATTRIBUTE.keySet() while "
							+ "getHolder() missed it");
				}
			} catch (Throwable absent) {
				writableExists = Boolean.FALSE;
				ForbricLog.debug("[Forbric/RegistryRoot] no WRITABLE_REGISTRY on this line — using REGISTRY");
			}
		}
		if (writableExists) {
			try {
				Field f = BuiltInRegistries.class.getDeclaredField("WRITABLE_REGISTRY");
				f.setAccessible(true);
				return (Registry<?>) f.get(null);
			} catch (Throwable t) {
				ForbricLog.warn("[Forbric/RegistryRoot] WRITABLE_REGISTRY became unreadable — falling back to REGISTRY", t);
			}
		}
		return BuiltInRegistries.REGISTRY;
	}
}
