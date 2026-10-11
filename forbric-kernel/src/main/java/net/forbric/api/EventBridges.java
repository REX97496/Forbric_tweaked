/*
 * Copyright 2026 The Forbric Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.forbric.api;

import java.util.EnumSet;
import java.util.Set;

import net.forbric.kernel.util.ForbricLog;

/**
 * Which {@link GameEventBridge}s actually got installed, checked against the ones that were required.
 *
 * <p>The installer records each bridge as it succeeds and then calls {@link #verify}, which is the whole point:
 * a bridge that fails to install costs a feature and throws nothing, so the only way it becomes visible is if
 * something compares the achieved set against the declared set and complains. Before this existed the five
 * game-bus bridges were installed inside one {@code try} — the first setup failure skipped the rest, and the
 * evidence was a single warning plus a smaller number in an unasserted log line.
 *
 * <p>Naming what is MISSING, with {@link GameEventBridge#cost()}, is deliberate. "installed 3 of 5" tells a
 * reader that something is wrong; "SERVER_STARTED missing — MinecraftForge's login gate never opens, so every
 * join is rejected" tells them what they are about to debug.
 */
public final class EventBridges {
	/**
	 * Escape hatch and negative control: {@code -Dforbric.unifiedEvents=off} installs no bridges at all, which is
	 * the pre-multiplexer behaviour (each family receives only the events whose hook won the byte merge). It exists
	 * so a gate can run the same instance both ways — an assertion that both families tick 1:1 is worth little
	 * unless the run that should break it does.
	 */
	public static final String SWITCH_NAME = "forbric.unifiedEvents";

	private static final Set<GameEventBridge> INSTALLED = EnumSet.noneOf(GameEventBridge.class);

	private EventBridges() {
	}

	/** Whether the multiplexer should install anything at all. */
	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(SWITCH_NAME, "on"));
	}

	/** Records that {@code bridge} is live. Called by the installer, once per bridge, on success only. */
	public static synchronized void installed(GameEventBridge bridge) {
		if (bridge != null) INSTALLED.add(bridge);
	}

	/** The bridges installed so far. */
	public static synchronized Set<GameEventBridge> installed() {
		return EnumSet.copyOf(INSTALLED);
	}

	/**
	 * Forgets everything. For tests, which install different sets in one JVM.
	 *
	 * <p>Public because the tests that need it are not all in this package: a coverage test asserting that the
	 * all-pass sweep reports a missing bridge has to start from a state where the bridges are not installed, and
	 * {@code EventBridges} being in {@code api} while the boot tests live in {@code kernel.boot} made that
	 * impossible. Widening it is preferable to the alternative, which was a test that asserted nothing about the
	 * empty case because it could not reach one.
	 */
	public static synchronized void reset() {
		INSTALLED.clear();
	}

	/**
	 * Compares one pass's achieved set against everything declared for it, and says so either way.
	 *
	 * <p>Reports rather than throws. A missing bridge is a degraded instance, not an unusable one, and taking the
	 * boot down would turn "Forge mods lose the tick" into "nothing runs at all" — strictly worse for someone who
	 * just wants to play. The loud line is what makes it diagnosable.
	 *
	 * @return true when every bridge declared for this pass is installed
	 */
	public static boolean verify(GameEventBridge.Pass pass) {
		Set<GameEventBridge> missing = EnumSet.noneOf(GameEventBridge.class);
		int declared = 0;
		for (GameEventBridge bridge : GameEventBridge.values()) {
			if (bridge.pass() != pass) continue;
			declared++;
			if (!installed().contains(bridge)) missing.add(bridge);
		}

		if (missing.isEmpty()) {
			ForbricLog.info("[Forbric/EventMux] all %d %s bridge(s) installed — both Forge families receive these "
					+ "events 1:1", declared, pass);
			return true;
		}

		ForbricLog.error("[Forbric/EventMux] %d of %d %s bridge(s) MISSING — this is a silent feature loss, not a "
				+ "crash, so it is named here in full:", missing.size(), declared, pass);
		for (GameEventBridge bridge : missing) {
			ForbricLog.error("[Forbric/EventMux]   %s (%s) — %s", bridge, bridge.event(), bridge.cost());
		}
		return false;
	}

	/**
	 * Verifies EVERY pass, so no pass can be declared without anything ever comparing its achieved set to its
	 * declared set.
	 *
	 * <h2>Why the per-pass form was not enough</h2>
	 *
	 * <p>{@link #verify} only ever sees the pass it is handed, and five of the seven passes had a call site. The
	 * other two — {@code CLIENT_HUD} and {@code ON_DEMAND} — had none, so the two bridges declared in them were
	 * never compared against anything:
	 *
	 * <ul>
	 *   <li>{@code GUI_OVERLAY_LAYERS} — a MinecraftForge mod's HUD overlay layers never draw, because the merged
	 *       base carries no reference to {@code ForgeLayeredDraw} at all.
	 *   <li>{@code ITEM_TOOLTIP} — a NeoForge mod that adds a line to an item's tooltip adds it to nothing.
	 * </ul>
	 *
	 * <p>Both were declared, both describe a feature a player would notice missing, and neither is installed
	 * anywhere — so both were absolutely silent. That is the failure shape this class exists to prevent, and it
	 * survived inside the class itself: the mechanism was right and its coverage was not, which is worse than no
	 * mechanism because it looks done.
	 *
	 * <p>This reports rather than throws, for the reason {@link #verify} gives. It is called once, late, from the
	 * client setup hook — after the last pass has had its chance to install, and where a missing bridge can still be
	 * named before a player meets the symptom.
	 *
	 * @return true when every bridge in every pass is installed
	 */
	public static boolean verifyAll() {
		boolean all = true;
		for (GameEventBridge.Pass pass : GameEventBridge.Pass.values()) {
			// verify() is the reporter, so calling it per pass keeps ONE message format rather than two.
			if (!verify(pass)) all = false;
		}
		return all;
	}

	/** The passes nothing verifies, so a test can assert this is empty rather than trusting a reader to notice. */
	public static Set<GameEventBridge.Pass> declaredPasses() {
		return EnumSet.allOf(GameEventBridge.Pass.class);
	}
}
