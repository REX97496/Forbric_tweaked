/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
				ForbricLog.debug("[Forbric/RegistryHolders] could not read built-in registry field", t);
				continue;
			}
			if (registry == null) continue;
			String type = registry.getClass().getName();
			try {
				Field mapField = findField(registry.getClass(), "holdersByName");
				if (mapField == null) continue;
				mapField.setAccessible(true);
				@SuppressWarnings("unchecked")
				Map<Object, Object> byName = (Map<Object, Object>) mapField.get(registry);
				if (byName == null) continue;
				// The wrapper holds the plain MappedRegistry; its holder map is named differently per version, so
				// every Map field on the delegate that is keyed by ResourceLocation is considered.
				int before = byName.size();
				copyFrom(registry, byName);
				// Every field whose VALUE is a Registry is copied from, not one guessed by type. The previous version
				// picked a single field whose declared type differed from the superclass, and the ATTRIBUTE wrapper
				// was processed (+1611 holders landed somewhere) while neoforge:swim_speed still did not arrive --
				// so the one field it chose was not the delegate holding the NeoForge entries.
				java.util.List<String> sources = new java.util.ArrayList<>();
				for (Class<?> c = registry.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
					for (Field d : c.getDeclaredFields()) {
						try {
							d.setAccessible(true);
							Object v = d.get(registry);
							if (v instanceof Registry<?> && v != registry) {
								copyFrom(v, byName);
								sources.add(c.getSimpleName() + "." + d.getName());
							}
						} catch (Throwable notReadable) {
							ForbricLog.debug("[Forbric/RegistryHolders] could not read a holder map on "
									+ registry.getClass().getName(), notReadable);
						}
					}
				}
				int added = byName.size() - before;
				if (added > 0) {
					copied += added;
					ForbricLog.info("[Forbric/RegistryHolders] field=%s %s: +%d holder(s) copied (sources=%s) -- without the field "
							+ "name this could not say whether ATTRIBUTE was among them", f.getName(), type, added, sources);
				}
			} catch (Throwable t) {
				ForbricLog.debug("[Forbric/RegistryHolders] could not backfill " + type, t);
			}
		}
		return copied;
	}

	/**
	 * THE ONE THAT MATTERS: fills the wrapper's holdersByName from its ForgeRegistry delegate.
	 *
	 * <p>Measured with javap, and this is the whole answer to the swim_speed failure:
	 *
	 * <pre>
	 *   NamespacedWrapper.get(ResourceLocation)        -&gt; delegate.getRaw(id)      (a ForgeRegistry)
	 *   NamespacedWrapper.getHolder(ResourceKey)      -&gt; holdersByName.get(key.location())
	 * </pre>
	 *
	 * <p>Two different stores on the same object. The NeoForge attributes live in the delegate -- which is why
	 * get(ResourceLocation) finds them, and why a whole round of AttrCheck printed PercentageAttribute / RangedAttribute
	 * / BooleanAttribute -- and holdersByName, which getHolder reads, is a separate map the ForgeRegistry path never
	 * fills.
	 *
	 * <p>And the earlier backfills could not have worked: copyFrom copies from fields whose value is a
	 * net.minecraft.core.Registry, and the delegate is a net.minecraftforge.registries.ForgeRegistry, which is NOT one.
	 * So the only source that actually holds the NeoForge entries was the only source being skipped.
	 *
	 * <p>ForgeRegistry exposes getKeys() and getRaw(id), and the wrapper exposes wrapAsHolder(value), so every id the
	 * delegate knows becomes a Holder.Reference in holdersByName under the same ResourceLocation.
	 *
	 * @return how many entries were added
	 */
	public static int backfillFromTheForgeDelegate() {
		int copied = 0;
		for (Field f : BuiltInRegistries.class.getFields()) {
			if (!Registry.class.isAssignableFrom(f.getType())) continue;
			Object registry;
			try {
				registry = f.get(null);
			} catch (Throwable notReadable) {
				// Was debug. In the delegate pass this is the only exit before the probe, so a debug-level line here
				// made "the probe never printed" indistinguishable from "the method never ran".
				ForbricLog.warn("[Forbric/RegistryHolders] delegate pass could not read field %s: %s: %s", f.getName(),
						notReadable.getClass().getName(), notReadable.getMessage());
				continue;
			}
			if (registry == null) {
				ForbricLog.info("[Forbric/RegistryHolders] delegate probe %s: registry field is null", f.getName());
				continue;
			}
			Field mapField = findField(registry.getClass(), "holdersByName");
			Field delegateField = findField(registry.getClass(), "delegate");
			// Unconditional, because two rounds produced no output at all and there was no way to tell a skipped
			// registry from an uncalled method.
			ForbricLog.info("[Forbric/RegistryHolders] delegate probe %s (%s): holdersByName=%s delegate=%s",
					f.getName(), registry.getClass().getSimpleName(),
					mapField == null ? "absent" : "found", delegateField == null ? "absent" : "found");
			if (mapField == null || delegateField == null) continue;
			try {
				mapField.setAccessible(true);
				@SuppressWarnings("unchecked")
				Map<Object, Object> byName = (Map<Object, Object>) mapField.get(registry);
				delegateField.setAccessible(true);
				Object delegate = delegateField.get(registry);
				if (byName == null || delegate == null) continue;
				java.util.Set<?> keys = (java.util.Set<?>) delegate.getClass().getMethod("getKeys").invoke(delegate);
				Method getRaw = delegate.getClass().getMethod("getRaw", keys.isEmpty() ? Object.class
						: keys.iterator().next().getClass());
				Method wrap = registry.getClass().getMethod("wrapAsHolder", Object.class);
				int before = byName.size();
				for (Object key : keys) {
					if (byName.containsKey(key)) continue;
					Object raw = getRaw.invoke(delegate, key);
					if (raw == null) continue;
					Object holder = wrap.invoke(registry, raw);
					if (holder != null) byName.put(key, holder);
				}
				int added = byName.size() - before;
				if (added > 0) {
					copied += added;
					ForbricLog.info("[Forbric/RegistryHolders] DELEGATE-pass %s: +%d holder(s) came from the "
							+ "ForgeRegistry, which get() reads and getHolder() does not", f.getName(), added);
				}
			} catch (Throwable t) {
				ForbricLog.warn("[Forbric/RegistryHolders] delegate pass failed on %s: %s: %s", f.getName(),
						t.getClass().getName(), t.getMessage());
			}
		}
		return copied;
	}

	/**
	 * Puts exactly three known ids into holdersByName by asking the delegate directly.
	 *
	 * <p>Exists because enumerating the delegate with getKeys() copied zero. The ids NeoForge registers are known --
	 * NeoForgeMod declares SWIM_SPEED, NAMETAG_DISTANCE and CREATIVE_FLIGHT and nothing else -- and AttrCheck already
	 * proved delegate.getRaw finds all three by ResourceLocation. So there is no need to enumerate: ask for the three.
	 *
	 * <p>The id type is taken from getRaw's own parameter rather than named, because it is Identifier on 1.21.1 and
	 * ResourceLocation elsewhere and this file must compile on both.
	 *
	 * @return how many were added
	 */
	public static int backfillTheKnownNeoForgeAttributes() {
		int added = 0;
		try {
			for (Field f : BuiltInRegistries.class.getFields()) {
				if (!"ATTRIBUTE".equals(f.getName())) continue;
				Object registry = f.get(null);
				if (registry == null) continue;
				Field mapField = findField(registry.getClass(), "holdersByName");
				Field delegateField = findField(registry.getClass(), "delegate");
				if (mapField == null || delegateField == null) {
					ForbricLog.warn("[Forbric/RegistryHolders] ATTRIBUTE has holdersByName=%s delegate=%s",
							mapField == null ? "absent" : "found", delegateField == null ? "absent" : "found");
					continue;
				}
				mapField.setAccessible(true);
				delegateField.setAccessible(true);
				@SuppressWarnings("unchecked")
				Map<Object, Object> byName = (Map<Object, Object>) mapField.get(registry);
				Object delegate = delegateField.get(registry);
				if (byName == null || delegate == null) continue;
				Method getRaw = null;
				Class<?> idType = null;
				for (Method m : delegate.getClass().getMethods()) {
					if (m.getName().equals("getRaw") && m.getParameterCount() == 1) {
						getRaw = m;
						idType = m.getParameterTypes()[0];
						break;
					}
				}
				if (getRaw == null) {
					ForbricLog.warn("[Forbric/RegistryHolders] the ATTRIBUTE delegate has no getRaw(id)");
					continue;
				}
				Method parse = idType.getMethod("parse", String.class);
				Method wrap = registry.getClass().getMethod("wrapAsHolder", Object.class);
				for (String name : new String[] {"swim_speed", "nametag_distance", "creative_flight"}) {
					Object id;
					try {
						id = parse.invoke(null, "neoforge:" + name);
					} catch (Throwable parseFailed) {
						ForbricLog.warn("[Forbric/RegistryHolders] could not parse neoforge:%s", name);
						continue;
					}
					if (byName.containsKey(id)) continue;
					Object raw = getRaw.invoke(delegate, id);
					Object holder = raw == null ? null : wrap.invoke(registry, raw);
					Object viaWrapper;
					try {
						viaWrapper = registry.getClass().getMethod("get", idType).invoke(registry, id);
					} catch (Throwable noGet) {
						ForbricLog.debug("[Forbric/RegistryHolders] the wrapper has no get(id) for this id type", noGet);
						viaWrapper = null;
					}
					ForbricLog.info("[Forbric/RegistryHolders] neoforge:%s delegate.getRaw=%s wrapper.get=%s holder=%s",
							name, raw == null ? "null" : raw.getClass().getSimpleName(),
							viaWrapper == null ? "null" : viaWrapper.getClass().getSimpleName(),
							holder == null ? "null" : holder.getClass().getSimpleName());
					if (holder != null) {
						byName.put(id, holder);
						added++;
					}
				}
			}
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/RegistryHolders] known-attribute pass failed: %s: %s", t.getClass().getName(),
					t.getMessage());
		}
		return added;
	}

	/**
	 * Backfills the registries reached through the ROOT registry, which are not the same objects as the fields.
	 *
	 * <p>Measured: [Forbric/AttrCheck] reports that {@code BuiltInRegistries.REGISTRY.get(minecraft:attribute)} is a
	 * NamespacedWrapper, and backfilling {@code BuiltInRegistries.ATTRIBUTE}'s wrapper copied three holders -- yet
	 * the lookup DeferredHolder performs still missed. The two ATTRIBUTE lines in the log (+31 then +3) are two
	 * DIFFERENT wrappers reporting the same field name, so the entries went into the one the field holds while the
	 * root hands back another. This walks the root's own entries as well.
	 */
	public static int backfillFromRoot() {
		int copied = 0;
		try {
			Field rootField = BuiltInRegistries.class.getDeclaredField("REGISTRY");
			rootField.setAccessible(true);
			Object root = rootField.get(null);
			if (!(root instanceof Registry<?> rootRegistry)) return 0;
			ForbricLog.warn("[Forbric/RegistryHolders] root pass is disabled: iterating the root registry deadlocks "
					+ "during registration, so this is the identity report only");
			if (rootRegistry == null) return 0;
			for (Object value : new Object[0]) {
				if (!(value instanceof Registry<?> inner)) continue;
				Field mapField = findField(inner.getClass(), "holdersByName");
				if (mapField == null) continue;
				mapField.setAccessible(true);
				@SuppressWarnings("unchecked")
				Map<Object, Object> byName = (Map<Object, Object>) mapField.get(inner);
				if (byName == null) continue;
				int before = byName.size();
				copyFrom(inner, byName);
				for (Class<?> c = inner.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
					for (Field d : c.getDeclaredFields()) {
						try {
							d.setAccessible(true);
							Object v = d.get(inner);
							if (v instanceof Registry<?> && v != inner) copyFrom(v, byName);
						} catch (Throwable notReadable) {
							ForbricLog.debug("[Forbric/RegistryHolders] root pass: a registry field was unreadable", notReadable);
						}
					}
				}
				int added = byName.size() - before;
				if (added > 0) {
					copied += added;
					ForbricLog.info("[Forbric/RegistryHolders] ROOT-pass %s: +%d holder(s) -- these are the wrappers the "
							+ "root registry hands out, which are not the ones the BuiltInRegistries fields hold",
							inner.getClass().getName(), added);
				}
			}
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/RegistryHolders] root pass did not run: %s: %s", t.getClass().getName(),
					t.getMessage());
		}
		reportIdentity();
		return copied;
	}

	/** Prints whether the root registry hands back the same attribute registry the field holds. */
	private static void reportIdentity() {
		try {
			Field rootField = BuiltInRegistries.class.getDeclaredField("REGISTRY");
			rootField.setAccessible(true);
			Object root = rootField.get(null);
			Field attrField = BuiltInRegistries.class.getDeclaredField("ATTRIBUTE");
			attrField.setAccessible(true);
			Object attrFieldValue = attrField.get(null);
			Object fromRoot = null;
			try {
				Method get = root.getClass().getMethod("get", Class.forName("net.minecraft.resources.Identifier"));
				fromRoot = get.invoke(root, Class.forName("net.minecraft.resources.Identifier")
						.getMethod("parse", String.class).invoke(null, "minecraft:attribute"));
			} catch (Throwable noIdentifier) {
				ForbricLog.debug("[Forbric/RegistryHolders] noIdentifier while reading the registry", noIdentifier);
				Method get = root.getClass().getMethod("get", Class.forName("net.minecraft.resources.ResourceLocation"));
				fromRoot = get.invoke(root, Class.forName("net.minecraft.resources.ResourceLocation")
						.getMethod("parse", String.class).invoke(null, "minecraft:attribute"));
			}
			ForbricLog.info("[Forbric/RegistryHolders] identity: ATTRIBUTE field=%s (%d)  REGISTRY.get=%s (%d)  same=%s",
					attrFieldValue == null ? "null" : attrFieldValue.getClass().getSimpleName(),
					System.identityHashCode(attrFieldValue),
					fromRoot == null ? "null" : fromRoot.getClass().getSimpleName(),
					System.identityHashCode(fromRoot),
					attrFieldValue == fromRoot);
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/RegistryHolders] identity check failed: %s: %s", t.getClass().getName(),
					t.getMessage());
		}
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
				} catch (Throwable notAMap) {
					ForbricLog.debug("[Forbric/RegistryHolders] a map field could not be read or iterated", notAMap);
				}
			}
		}
	}

	private static Field findField(Class<?> type, String name) {
		for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
			try {
				return c.getDeclaredField(name);
			} catch (NoSuchFieldException next) {
				ForbricLog.debug("[Forbric/RegistryHolders] " + c.getName() + " has no " + name + " -- walking up");
			}
		}
		return null;
	}
}
