/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;

/** Registry names use equality; registered objects use identity, as in vanilla MappedRegistry. */
public final class IdentityValueBiMap<K, V> extends AbstractMap<K, V> implements BiMap<K, V> {
	private final Map<K, V> forward;
	private final Map<V, K> backward;
	private final boolean identityKeys;
	private final IdentityValueBiMap<V, K> inverse;

	public IdentityValueBiMap() {
		forward = new LinkedHashMap<>();
		backward = new IdentityHashMap<>();
		identityKeys = false;
		inverse = new IdentityValueBiMap<>(this);
	}

	private IdentityValueBiMap(IdentityValueBiMap<V, K> original) {
		forward = original.backward;
		backward = original.forward;
		identityKeys = true;
		inverse = original;
	}

	/** Keep every other Forge registry's native implementation. No game types cross this signature. */
	public static <K, V> BiMap<K, V> forRegistry(Object name) {
		return "minecraft:sound_event".equals(String.valueOf(name)) ? new IdentityValueBiMap<>() : HashBiMap.create();
	}

	public static <K, V> Map<K, V> delegatesForRegistry(Object name) {
		return "minecraft:sound_event".equals(String.valueOf(name)) ? new IdentityHashMap<>() : new java.util.HashMap<>();
	}

	@Override public int size() { return forward.size(); }
	@Override public boolean containsKey(Object key) { return forward.containsKey(key); }
	@Override public boolean containsValue(Object value) { return backward.containsKey(value); }
	@Override public V get(Object key) { return forward.get(key); }
	@Override public BiMap<V, K> inverse() { return inverse; }
	@Override public Set<V> values() { return inverse.keySet(); }
	@Override public V put(K key, V value) { return put(key, value, false); }
	@Override public V forcePut(K key, V value) { return put(key, value, true); }

	private boolean sameKey(Object a, Object b) { return identityKeys ? a == b : Objects.equals(a, b); }
	private boolean sameValue(Object a, Object b) { return identityKeys ? Objects.equals(a, b) : a == b; }

	private V put(K key, V value, boolean force) {
		boolean hadKey = forward.containsKey(key);
		V old = forward.get(key);
		if (hadKey && sameValue(old, value)) return old;
		if (backward.containsKey(value)) {
			K occupied = backward.get(value);
			if (!sameKey(occupied, key)) {
				if (!force) throw new IllegalArgumentException("value already present: " + value);
				forward.remove(occupied);
			}
		}
		if (hadKey) backward.remove(old);
		forward.put(key, value);
		backward.put(value, key);
		return old;
	}

	@Override public V remove(Object key) {
		if (!forward.containsKey(key)) return null;
		V old = forward.remove(key);
		backward.remove(old);
		return old;
	}

	@Override public void clear() { forward.clear(); backward.clear(); }

	@Override public Set<K> keySet() {
		return new AbstractSet<>() {
			@Override public int size() { return forward.size(); }
			@Override public boolean contains(Object key) { return containsKey(key); }
			@Override public boolean remove(Object key) {
				if (!containsKey(key)) return false;
				IdentityValueBiMap.this.remove(key);
				return true;
			}
			@Override public void clear() { IdentityValueBiMap.this.clear(); }
			@Override public Iterator<K> iterator() {
				Iterator<Entry<K, V>> entries = entrySet().iterator();
				return new Iterator<>() {
					@Override public boolean hasNext() { return entries.hasNext(); }
					@Override public K next() { return entries.next().getKey(); }
					@Override public void remove() { entries.remove(); }
				};
			}
		};
	}

	@Override public Set<Entry<K, V>> entrySet() {
		return new AbstractSet<>() {
			@Override public int size() { return forward.size(); }
			@Override public void clear() { IdentityValueBiMap.this.clear(); }
			@Override public boolean contains(Object candidate) {
				return candidate instanceof Entry<?, ?> entry && containsKey(entry.getKey())
						&& sameValue(get(entry.getKey()), entry.getValue());
			}
			@Override public boolean remove(Object candidate) {
				if (!contains(candidate)) return false;
				IdentityValueBiMap.this.remove(((Entry<?, ?>) candidate).getKey());
				return true;
			}
			@Override public Iterator<Entry<K, V>> iterator() {
				Iterator<Entry<K, V>> entries = forward.entrySet().iterator();
				return new Iterator<>() {
					private Entry<K, V> current;
					@Override public boolean hasNext() { return entries.hasNext(); }
					@Override public Entry<K, V> next() {
						current = entries.next();
						K key = current.getKey();
						return new Entry<>() {
							@Override public K getKey() { return key; }
							@Override public V getValue() { return get(key); }
							@Override public V setValue(V value) { return put(key, value); }
							@Override public boolean equals(Object other) {
								return other instanceof Entry<?, ?> e && sameKey(key, e.getKey())
										&& sameValue(getValue(), e.getValue());
								}
							@Override public int hashCode() {
								return (identityKeys ? System.identityHashCode(key) : Objects.hashCode(key))
										^ (identityKeys ? Objects.hashCode(getValue()) : System.identityHashCode(getValue()));
								}
						};
					}
					@Override public void remove() {
						if (current == null) throw new IllegalStateException();
						V value = current.getValue();
						entries.remove();
						backward.remove(value);
						current = null;
					}
				};
			}
		};
	}
}
