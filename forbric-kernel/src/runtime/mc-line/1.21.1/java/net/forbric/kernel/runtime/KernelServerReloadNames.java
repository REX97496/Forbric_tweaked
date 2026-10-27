/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.neoforged.neoforge.resource.VanillaServerListeners;

/** Preserve native listener names; give foreign listeners stable, collision-free graph keys. */
public final class KernelServerReloadNames {
	private KernelServerReloadNames() { }
	/**
	 * 1.21.1 has no net.neoforged.neoforge.resource.VanillaServerListeners: that name table arrived with the
	 * 26.x sorted-listener work. With no table, every listener is foreign, so every listener gets a
	 * synthesised key -- which is what the fallback below already did for the classes 26.2 did not know.
	 */
	public static ResourceLocation nameFor(Class<? extends PreparableReloadListener> type) {
		return ResourceLocation.fromNamespaceAndPath("forbric", "foreign_reload/"
				+ HexFormat.of().formatHex(type.getName().getBytes(StandardCharsets.UTF_8)));
	}
}
