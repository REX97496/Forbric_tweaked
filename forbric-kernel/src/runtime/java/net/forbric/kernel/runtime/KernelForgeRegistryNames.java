/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.google.common.collect.BiMap;

import net.forbric.kernel.util.ForbricLog;

/**
 * Answers a Forge registry lookup that used a name the merge renamed.
 *
 * <p>Forge 1.21.1 declares four registries of its own as {@code forge:…} in {@code ForgeRegistries$Keys}:
 * {@code forge:biome_modifier_serializers}, {@code forge:structure_modifier_serializers}, {@code
 * forge:condition_codecs} and {@code forge:ingredient_serializers}. The merged base is built from NeoForge's patched
 * sources, and there the same registry objects are registered under {@code neoforge:…} — the base's own
 * {@code reports/registry_order.json} lists {@code neoforge:biome_modifier_serializers}, {@code
 * neoforge:condition_codecs} and friends, and nothing under {@code forge:}.
 *
 * <p>So Forge's own {@code ObjectHolderRegistry} asked {@code RegistryManager.getRegistry(forge:condition_codecs)},
 * got null, and threw {@code IllegalStateException: Unable to find registry with key forge:condition_codecs for mod
 * "forge"} — 26 of those at every shutdown, wrapped in {@code Failed to apply some object holders}. Measured on the
 * 1.21.1 line on 2026-11-13.
 *
 * <p>Two routes were tried and only one works. A bytecode repair on {@code RegistryManager.getRegistry} was
 * registered and never handed the class: {@code net.minecraftforge.*} classes do not pass through this transformer
 * chain (round 122). The field {@code registries} however is not final ({@code javap -p}), so it can be replaced by
 * {@link AliasingBiMap} — a delegating {@code BiMap} whose {@code get} retries a {@code forge:} name under
 * {@code neoforge:} and which forwards every mutator, {@code inverse()} and the view methods to the real map, so the
 * freeze/snapshot paths keep working unchanged.
 *
 * <p>The duplicate-key problem is why the name has to be translated rather than added: {@code registries} is a
 * {@code BiMap}, and a {@code BiMap} cannot hold two keys for one value.
 *
 * <p>Reflection-only and free of Minecraft and Forge types: this class is compiled into both lines, and 26.2 ships
 * no {@code net.minecraftforge.registries} package at all.
 */
public final class KernelForgeRegistryNames {
	private KernelForgeRegistryNames() {
	}

	private static final Set<String> SAID = ConcurrentHashMap.newKeySet();
	private static final int SAID_LIMIT = 24;
	/** Misses that were said out loud already, so one bad name cannot fill the log. */
	private static final Set<String> MISSES = ConcurrentHashMap.newKeySet();

	/** Names Forge had not created yet the last time we looked — see {@link #aboutToBeCreated(Object)}. */
	private static final Set<String> PENDING = ConcurrentHashMap.newKeySet();
	private static volatile boolean PENDING_READ;
	private static final Map<Object, Object> ALIASES = new ConcurrentHashMap<>();

	/**
	 * Replaces {@code RegistryManager.registeries} on every manager with a {@link AliasingBiMap}.
	 *
	 * <p>Idempotent, and silent on a line that has no Forge registry manager. Called from the registry enumeration
	 * that registration already performs, so it is in place long before {@code ObjectHolderRegistry} validates.
	 */
	public static void install() {
		Class<?> manager;
		try {
			manager = Class.forName("net.minecraftforge.registries.RegistryManager");
		} catch (ClassNotFoundException noForgeRegistries) {
			return;
		}
		int wrapped = 0;
		for (String instance : new String[] {"ACTIVE", "VANILLA", "FROZEN"}) {
			try {
				Object target = manager.getField(instance).get(null);
				Field field = manager.getDeclaredField("registries");
				field.setAccessible(true);
				Object current = field.get(target);
				if (!(current instanceof BiMap<?, ?> map) || current instanceof AliasingBiMap) continue;
				field.set(target, new AliasingBiMap<>(map, KernelForgeRegistryNames::renamedName, null));
				wrapped++;
			} catch (Throwable failed) {
				ForbricLog.warn("[Forbric/Forge] could not wrap RegistryManager." + instance
						+ ".registries; a 'forge:' name keeps missing the 'neoforge:' registry it was renamed to",
						failed);
			}
		}
		if (wrapped > 0) {
			ForbricLog.info("[Forbric/Forge] RegistryManager asked for a 'forge:' registry name now falls back to "
					+ "the 'neoforge:' one this base registered it under (%d manager(s) wrapped)", wrapped);
		}
	}

	/**
	 * The alias key for a {@code forge:} name, or null when the name is not one Forge renamed.
	 *
	 * <p>Cached because {@code ObjectHolderRegistry} asks the same four names once per holder.
	 */
	/**
	 * Whether a {@code DeferredRegister} with a registry factory is still waiting to create {@code key}.
	 *
	 * <p>Read once, lazily: the list of waiting registrars only shrinks, and every miss happens during the same
	 * registration window, so one snapshot answers every question. Any reflection failure means "not pending" — the
	 * miss then gets reported, which is the honest default.
	 */
	private static boolean aboutToBeCreated(Object key) {
		if (!PENDING_READ) readPending();
		return PENDING.contains(String.valueOf(key));
	}

	private static void readPending() {
		PENDING_READ = true;
		try {
			Class<?> forgeRegistries = Class.forName("net.minecraftforge.registries.ForgeRegistries", false,
					KernelForgeRegistryNames.class.getClassLoader());
			java.lang.reflect.Field list = forgeRegistries.getDeclaredField("registries");
			list.setAccessible(true);
			Object value = list.get(null);
			if (!(value instanceof java.util.List)) return;
			for (Object each : (java.util.List<?>) value) {
				if (each == null) continue;
				java.lang.reflect.Field factory = each.getClass().getDeclaredField("registryFactory");
				factory.setAccessible(true);
				if (factory.get(each) == null) continue;
				java.lang.reflect.Field registryKey = each.getClass().getDeclaredField("registryKey");
				registryKey.setAccessible(true);
				Object name = registryKey.get(each);
				if (name == null) continue;
				java.lang.reflect.Method location = name.getClass().getMethod("location");
				PENDING.add(String.valueOf(location.invoke(name)));
			}
		} catch (Throwable t) {
			ForbricLog.debug("[Forbric/Forge] could not read the list of registries Forge is still creating: " + t);
		}
	}

	private static Object renamedName(Object key) {
		if (key == null) return null;
		Object cached = ALIASES.get(key);
		if (cached != null) return cached;
		String namespace = call(key, "getNamespace");
		String path = call(key, "getPath");
		if (!"forge".equals(namespace) || path == null) return null;
		Object alias = aliasOf(key.getClass(), path);
		if (alias == null) return null;
		ALIASES.put(key, alias);
		return alias;
	}

	/**
	 * Builds the same location under {@code neoforge}. The id class is named differently on each line
	 * ({@code ResourceLocation} here, {@code Identifier} on 26.2), so the factory is found by name on whatever class
	 * the caller handed over rather than by a compile-time type.
	 */
	private static Object aliasOf(Class<?> idClass, String path) {
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

	private static String keyString(String namespace, String path) {
		return namespace + ":" + path;
	}

	private static void say(String line) {
		if (SAID.size() >= SAID_LIMIT || !SAID.add(line)) return;
		ForbricLog.info("[Forbric/Forge] " + line);
	}

	/**
	 * A {@code BiMap} that answers a renamed key and delegates everything else. Guava 33 declares {@code BiMap} as an
	 * interface, so this implements it rather than extending it.
	 *
	 * <p>Deliberately a delegate and not a reimplementation: {@code GameData} snapshots, {@code freezeData} and
	 * {@code RegistryManager.getName} all go through {@code inverse()}, {@code forcePut} and the collection views, so
	 * every one of those has to keep the original map's behaviour rather than a copy of it.
	 */
	static final class AliasingBiMap<K, V> implements BiMap<K, V> {
		private final BiMap<K, V> delegate;
		private final KeyTranslator translator;
		private AliasingBiMap<V, K> inverse;

		AliasingBiMap(BiMap<K, V> delegate, KeyTranslator translator, AliasingBiMap<V, K> inverse) {
			this.delegate = delegate;
			this.translator = translator;
			this.inverse = inverse;
		}

		@Override
		public int size() {
			return delegate.size();
		}

		@Override
		public boolean isEmpty() {
			return delegate.isEmpty();
		}

		@Override
		public boolean containsKey(Object key) {
			return get(key) != null;
		}

		@Override
		public boolean containsValue(Object value) {
			return delegate.containsValue(value);
		}

		@Override
		public V get(Object key) {
			V found = delegate.get(key);
			if (found != null) return found;
			Object alias = translator.translate(key);
			if (alias == null) return null;
			V viaAlias = delegate.get(alias);
			if (viaAlias == null) explainMiss(key, alias);
			else say("Registry '" + key + "' is not in this base under its Forge name; answered with '" + alias
					+ "' instead");
			return viaAlias;
		}

		/**
		 * Says why a renamed lookup missed even though the wrapper is still installed.
		 *
		 * <p>Added after a run answered seven {@code forge:} names during startup and then threw the same 26
		 * "Unable to find registry" lines at shutdown while {@code install()} reported nothing left to wrap: the
		 * wrapper was in place, so the answer has to come from the delegate's own contents rather than from the
		 * alias name.
		 */
		private void explainMiss(Object key, Object alias) {
			// A miss is EXPECTED while Forge is still building its own registries: DeferredRegister.makeRegistry
			// asks RegistryManager.ACTIVE whether the name already exists precisely so it can create it when it does
			// not. Measured on the 1.21.1 instance: nine such probes printed nine scary warnings before the nine
			// registries appeared, and every one of those names existed a moment later.
			if (aboutToBeCreated(key)) return;
			String note = key + " -> " + alias + " (delegate " + delegate.getClass().getSimpleName() + ", "
					+ delegate.size() + " entries)";
			if (MISSES.size() >= SAID_LIMIT || !MISSES.add(note)) return;
			StringBuilder sample = new StringBuilder();
			for (Object each : delegate.keySet()) {
				if (sample.length() > 1200) break;
				sample.append(sample.length() == 0 ? "" : ", ").append(each);
			}
			ForbricLog.warn("[Forbric/Forge] a renamed registry lookup missed both ways: %s; the map holds %s",
					note, sample);
		}

		@Override
		public V put(K key, V value) {
			return delegate.put(key, value);
		}

		@Override
		public V forcePut(K key, V value) {
			return delegate.forcePut(key, value);
		}

		@Override
		public void putAll(Map<? extends K, ? extends V> other) {
			delegate.putAll(other);
		}

		@Override
		public V remove(Object key) {
			return delegate.remove(key);
		}

		@Override
		public void clear() {
			delegate.clear();
		}

		@Override
		public Set<K> keySet() {
			return delegate.keySet();
		}

		@Override
		public Set<V> values() {
			return delegate.values();
		}

		@Override
		public Set<Entry<K, V>> entrySet() {
			return delegate.entrySet();
		}

		@Override
		public BiMap<V, K> inverse() {
			AliasingBiMap<V, K> answer = inverse;
			if (answer == null) {
				// Value → key needs no translation: a registry is registered under exactly one name.
				answer = new AliasingBiMap<>(delegate.inverse(), key -> null, this);
				inverse = answer;
			}
			return answer;
		}

		@Override
		public String toString() {
			return delegate.toString();
		}
	}

	/** Translates a key that missed to another key, or null when it is not a renamed one. */
	@FunctionalInterface
	interface KeyTranslator {
		Object translate(Object key);
	}
}
