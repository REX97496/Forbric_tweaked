/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Whether an anchor's class is in any artefact this line actually ships.
 *
 * <p>The anchor ledger has one word for two different situations, and that word was hiding the more interesting
 * one. "Never loaded" covers both "this session did not touch that feature" and "no jar on this line contains that
 * class at all" — and the second is not a missing feature, it is a declaration written against another Minecraft
 * line. Measured at shutdown on 1.21.1 (Round 142): of 41 absent targets, 30 were in none of
 * {@code patched-mc-merged-1.21.1.jar}, {@code neoforge-runtime-1.21.1.jar} or {@code forge-runtime-1.21.1.jar},
 * including {@code net.minecraft.client.gui.Hud} (this line calls it {@code Gui}) and
 * {@code net.minecraft.world.entity.monster.zombie.Zombie} (this line keeps it in {@code .monster}).
 *
 * <p>Asked as a resource, never as a class: {@code getResource("net/minecraft/client/gui/Gui.class")} answers
 * without loading, running a static initialiser or handing the class to the transform chain, which is the whole
 * point — the ledger is asking this question about classes it has NOT seen. A class that exists and was never
 * loaded keeps its "never loaded" reading, which is what that phrase should mean.
 */
final class LineResources {

	private LineResources() {
	}

	private static final Map<String, Boolean> SHIPPED = new ConcurrentHashMap<>();

	/** True when some loader can see {@code dotted + ".class"} as a resource. Cached: the answer cannot change. */
	static boolean shipped(String dotted) {
		if (dotted == null || dotted.isBlank()) return false;
		return SHIPPED.computeIfAbsent(dotted, LineResources::lookup);
	}

	private static boolean lookup(String dotted) {
		String path = dotted.replace('.', '/') + ".class";
		ClassLoader[] candidates = {
				LineResources.class.getClassLoader(),
				Thread.currentThread().getContextClassLoader(),
				ClassLoader.getSystemClassLoader()
		};
		for (ClassLoader loader : candidates) {
			if (loader == null) continue;
			try {
				if (loader.getResource(path) != null) return true;
			} catch (Throwable unanswerable) {
				// A loader that refuses the question is not evidence that the class is absent.
			}
		}
		return false;
	}
}
