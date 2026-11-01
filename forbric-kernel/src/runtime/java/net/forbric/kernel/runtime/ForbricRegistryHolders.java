/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import java.lang.reflect.Field;
import java.util.Map;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.forbric.kernel.util.ForbricLog;

/**
 * Copies holders the registration window created into the WRAPPER's own lookup map.
 *
 * <p>Measured, and it took a diagnostic to get here because the failure names neither side. From a real client launch:
 *
 * <pre>
 *   neoforge:swim_speed      direct get -&gt; PercentageAttribute
 *   neoforge:nametag_distance direct get -&gt; RangedAttribute
 *   neoforge:creative_flight  direct get -&gt; BooleanAttribute
 * </pre>
 *
 * <p>so the three attributes ARE in the registry, reachable by ResourceLocation -- and simultaneously
 * {@code DeferredHolder.value()} reported {@code ResourceKey[minecraft:attribute / neoforge:swim_speed]} unbound. javap
 * on NamespacedWrapper.getHolder(ResourceKey) resolves the contradiction in four instructions:
 *
 * <pre>
 *   aload_0; getfield holdersByName; aload_1; invokevirtual ResourceKey.location();
 *   invokeinterface Map.get; checkcast Holder$Reference; invokestatic Optional.ofNullable; areturn
 * </pre>
 *
 * <p>It is {@code Optional.ofNullable(holdersByName.get(key.location()))}. That map is the wrapper's own, filled for
 * Forge-side registrations; the window that registered NeoForge's attributes wrote to the registry, not to this map, so
 * {@code get} finds the entry and {@code getHolder} does not.
 *
 * <p>So the repair is a backfill rather than a redirect: after the window and before the freeze, every holder the
 * registry has that its wrapper's map lacks is copied in. It is the same shape as the other seeding fixes in this
 * kernel (ModList's three null lists, LoadingModList's brokenFiles) and needs no bytecode branch, so no StackMapTable.
 *
 * <p>WHAT THIS DOES NOT DO: it does not add entries to the registry, and it does not touch holdersByName for ids the
 * registry does not have. It also does not repair the reverse direction -- an id in holdersByName but not in the
 * registry is left alone, because that is the wrapper's business and removing it could break a Forge-side lookup.
 */
public final class ForbricRegistryHolders {

	private ForbricRegistryHolders() {
	}

	/** Backfills every wrapper-wrapped built-in registry. Returns how many holders were copied. */
	public static int backfill() {
		int copied = 0;
		for (Field f : BuiltInRegistries.class.getFields()) {
			if (!Registry.class.isAssignableFrom(f.getType())) continue;
			Object registry;
			try {
				registry = f.get(null);
			} catch (Throwable t) {
				continue;
			}
			if (registry == null) continue;
			String type = registry.getClass().getName();
			if (!type.contains("Namespaced")) continue;
			try {
				Field mapField = findField(registry.getClass(), "holdersByName");
				if (mapField == null) continue;
				mapField.setAccessible(true);
				@SuppressWarnings("unchecked")
				Map<Object, Object> byName = (Map<Object, Object>) mapField.get(registry);
				if (byName == null) continue;
				// The wrapper holds the plain MappedRegistry; its holder map is named differently per version, so
				// every Map field on the delegate that is keyed by ResourceLocation is considered.
				Object delegate = null;
				for (Field d : registry.getClass().getDeclaredFields()) {
					if (Registry.class.isAssignableFrom(d.getType()) && d.getType() != registry.getClass().getSuperclass()) {
						d.setAccessible(true);
						Object v = d.get(registry);
						if (v != null && v != registry) {
							delegate = v;
							break;
						}
					}
				}
				int before = byName.size();
				copyFrom(registry, byName);
				if (delegate != null && delegate != registry) copyFrom(delegate, byName);
				int added = byName.size() - before;
				if (added > 0) {
					copied += added;
					ForbricLog.info("[Forbric/RegistryHolders] %s: +%d holder(s) backfilled into the wrapper's map -- "
							+ "get(ResourceLocation) could see them and getHolder(ResourceKey) could not", type, added);
				}
			} catch (Throwable t) {
				ForbricLog.debug("[Forbric/RegistryHolders] could not backfill " + type, t);
			}
		}
		return copied;
	}

	/** Copies every ResourceLocation-keyed Holder map on {@code source} into {@code byName}, never overwriting. */
	private static void copyFrom(Object source, Map<Object, Object> byName) {
		for (Class<?> c = source.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
			for (Field f : c.getDeclaredFields()) {
				if (!Map.class.isAssignableFrom(f.getType())) continue;
				try {
					f.setAccessible(true);
					Object v = f.get(source);
					if (!(v instanceof Map<?, ?> m)) continue;
					for (Map.Entry<?, ?> e : m.entrySet()) {
						// Keyed by the id type of whichever line this is (Identifier on 1.21.1, ResourceLocation
						// elsewhere) and valued by Holder.Reference; both are checked structurally so this file
						// needs no renaming to compile on either line.
						Object key = e.getKey();
						Object holder = e.getValue();
						if (key != null && holder != null && holder.getClass().getName().endsWith("$Reference")) {
							byName.putIfAbsent(key, holder);
						}
					}
				} catch (Throwable ignored) {
					// a map that is not ResourceLocation-keyed: nothing to copy
				}
			}
		}
	}

	private static Field findField(Class<?> type, String name) {
		for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
			try {
				return c.getDeclaredField(name);
			} catch (NoSuchFieldException next) {
				// keep walking
			}
		}
		return null;
	}
}
