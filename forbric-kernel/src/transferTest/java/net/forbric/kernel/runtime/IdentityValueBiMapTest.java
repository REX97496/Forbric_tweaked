/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IdentityValueBiMapTest {
	private record Sound(String path) { }

	@Test void equalSoundsHaveSeparateNamesIdsAndInverseLookups() {
		var map = new IdentityValueBiMap<String, Sound>();
		Sound first = new Sound("create:funnel_flap"), compounded = new Sound("create:funnel_flap");
		assertEquals(first, compounded);
		map.put("create:funnel_flap", first);
		map.put("create:funnel_flap_compounded_1", compounded);
		assertEquals(2, map.size());
		assertSame(first, map.get(new String("create:funnel_flap")));
		assertEquals("create:funnel_flap_compounded_1", map.inverse().get(compounded));
		assertFalse(map.containsValue(new Sound(first.path())));
		assertFalse(map.values().contains(new Sound(first.path())));
		assertThrows(IllegalArgumentException.class, () -> map.put("another", first));
		assertEquals(2, map.size());
		assertSame(map, map.inverse().inverse());
	}

	@Test void forceReplacementInverseAndMutableViewsKeepBothIndexesConsistent() {
		var map = new IdentityValueBiMap<String, Sound>();
		Sound a = new Sound("a"), b = new Sound("b"), c = new Sound("c");
		map.put("a", a); map.put("b", b);
		assertSame(a, map.forcePut("a", b));
		assertEquals(1, map.size());
		assertFalse(map.containsValue(a));
		assertEquals("a", map.inverse().put(b, "c"));
		assertFalse(map.containsKey("a"));
		assertSame(b, map.get("c"));
		var iterator = map.entrySet().iterator();
		assertSame(b, iterator.next().setValue(c));
		assertFalse(map.containsValue(b));
		iterator.remove();
		assertTrue(map.isEmpty()); assertTrue(map.inverse().isEmpty());
		map.put("a", a); map.put("b", b);
		assertFalse(map.entrySet().remove(Map.entry("a", new Sound("a"))));
		assertTrue(map.values().remove(a));
		assertFalse(map.containsKey("a"));
		assertTrue(map.inverse().entrySet().remove(Map.entry(b, new String("b"))));
		assertTrue(map.isEmpty());
		map.put("a", a); map.put("b", b);
		map.inverse().clear(); assertTrue(map.isEmpty());
	}

	@Test void onlySoundRegistryChangesAndItsHolderIndexUsesIdentityToo() {
		Sound a = new Sound("same"), b = new Sound("same");
		var sounds = IdentityValueBiMap.<String, Sound>forRegistry("minecraft:sound_event");
		sounds.put("a", a); sounds.put("b", b);
		var blocks = IdentityValueBiMap.<String, Sound>forRegistry("minecraft:block");
		blocks.put("a", a);
		assertThrows(IllegalArgumentException.class, () -> blocks.put("b", b));
		var holders = IdentityValueBiMap.<Sound, String>delegatesForRegistry("minecraft:sound_event");
		holders.put(a, "holder-a"); holders.put(b, "holder-b");
		assertEquals(2, holders.size());
		assertEquals("holder-a", holders.get(a));
		assertEquals("holder-b", holders.get(b));
	}
}
