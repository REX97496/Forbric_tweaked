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

package net.forbric.kernel.runtime;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;

import net.forbric.kernel.boot.KernelLifecycle;
import net.forbric.kernel.util.ForbricLog;

/**
 * Delivers NeoForge's {@code ItemTooltipEvent} where the merged base only delivers MinecraftForge's.
 *
 * <h2>What was missing, and why it was invisible</h2>
 *
 * <p>{@code ItemStack.getTooltipLines} in the merged base ends with one call:
 *
 * <pre>
 *   96: aload_0    this
 *   97: aload_2    Player
 *   98: aload 5    the List&lt;Component&gt; being built
 *  100: aload_3    TooltipFlag
 *  101: invokestatic ForgeEventFactory.onItemTooltip(...)
 *  104: pop        the returned list is discarded
 *  105: aload 5    the local list is what is returned
 * </pre>
 *
 * <p>So a NeoForge mod that listens to {@code ItemTooltipEvent} and appends a line had that line appended to
 * nothing: the event was never posted. No exception, nothing in any log except the two lines
 * {@code EventBridges.verifyAll()} emits — and the kernel's own comment in {@code KernelLifecycle} admits the two
 * {@code ON_DEMAND} bridges "were declared, describe a feature a player notices missing, and were never installed".
 *
 * <p>This class is the other half. {@code ItemTooltipBridgeInjector} inserts one call to
 * {@link #afterForge} right after the {@code pop} above — the point where the stack is clear and the list, the
 * stack, the player and the flag are all reachable as locals.
 *
 * <h2>What this deliberately does NOT do</h2>
 *
 * <p>It does not replace the list MinecraftForge built. {@code onItemTooltip}'s own return value is discarded by
 * the merged base, and a NeoForge listener appends to the list it is handed; so the list is mutated in place, which
 * is exactly what a listener written against real NeoForge expects.
 *
 * <p>It does not run when no NeoForge listener exists beyond the event's own bookkeeping. The cost of posting an
 * event nobody listens to is a walk of an empty listener list, which is cheaper than the resolution needed to skip
 * it — and {@code verifyAll} already guarantees this method is only reachable on an installed bridge.
 */
public final class KernelItemTooltip {
	/** {@code net.neoforged.neoforge.event.entity.player.ItemTooltipEvent}. */
	private static final String EVENT = "net.neoforged.neoforge.event.entity.player.ItemTooltipEvent";

	/**
	 * Resolved once. Holds a {@link Resolution} or a {@link Failure} — one field for both, because the two states
	 * are exclusive and a failed resolve has to be remembered just as firmly as a good one, or a broken event costs
	 * a stack trace per hovered item. Volatile: read on every tooltip, written before any tooltip is built.
	 */
	private static volatile Object resolution;

	private KernelItemTooltip() {
	}

	/** One resolved shape: build an event, read its list, and ask whether it was cancelled. */
	private record Resolution(MethodHandle constructor, MethodHandle tooltip, MethodHandle cancelled) {
	}

	/** A resolution that failed, with the reason, so the failure is reported once and not retried per frame. */
	private record Failure(String reason, Throwable cause) {
	}

	/**
	 * Called by the injected call site: after MinecraftForge's {@code onItemTooltip}, before the list is returned.
	 *
	 * @param tooltip the list being built, mutated in place by any NeoForge listener
	 * @param stack   the stack the tooltip is for
	 * @param player  the viewing player, or null while one is not in scope
	 * @param flag    the tooltip flag the game passed down
	 */
	public static void afterForge(List<Object> tooltip, Object stack, Object player, Object flag) {
		if (tooltip == null || stack == null) return;
		Object resolved = resolution;
		if (resolved == null) resolution = resolved = resolve();
		if (resolved instanceof Failure failed) {
			// Reported at ERROR exactly once per launch, because this is the same silent-feature-loss shape the
			// bridge exists to end: a player whose tooltips lose their modded lines deserves a line in the log.
			ForbricLog.error("[Forbric/EventMux] ItemTooltipEvent could not be delivered (%s) — NeoForge mods cannot"
					+ " add lines to item tooltips this session", failed.reason(), failed.cause());
			resolution = failed;
			return;
		}
		Resolution shape = (Resolution) resolved;
		try {
			Object event = shape.constructor().invoke(stack, player, tooltip, flag);
			KernelLifecycle.postModBusEvent(event);
			// NeoForge's contract: a cancelled ItemTooltipEvent leaves the tooltip EMPTY rather than merely
			// un-extended. Honouring only "listeners may append" would let a mod that means to hide a tooltip
			// leave the whole thing on screen.
			if ((boolean) shape.cancelled().invoke(event)) tooltip.clear();
		} catch (Throwable delivery) {
			// Not swallowed: the first failure names itself, then the resolve is retired so a broken event does
			// not cost a stack trace per hovered item.
			ForbricLog.error("[Forbric/EventMux] ItemTooltipEvent delivery failed — later tooltips skip it this"
					+ " session", delivery);
			resolution = new Failure("delivery threw", delivery);
		}
	}

	/** Resolves the event shape against whatever NeoForge this instance carries. */
	private static Object resolve() {
		try {
			ClassLoader loader = KernelItemTooltip.class.getClassLoader();
			Class<?> type = Class.forName(EVENT, false, loader);
			MethodHandles.Lookup lookup = MethodHandles.publicLookup();
			MethodHandle constructor = lookup.findConstructor(type, MethodType.methodType(void.class,
					Class.forName("net.minecraft.world.item.ItemStack", false, loader),
					Class.forName("net.minecraft.world.entity.player.Player", false, loader),
					List.class,
					Class.forName("net.minecraft.world.item.TooltipFlag", false, loader)));
			return new Resolution(constructor,
					lookup.findVirtual(type, "getToolTip", MethodType.methodType(List.class)),
					lookup.findVirtual(type, "isCanceled", MethodType.methodType(boolean.class)));
		} catch (ClassNotFoundException absent) {
			return new Failure("no NeoForge on the classpath", absent);
		} catch (ReflectiveOperationException | LinkageError changed) {
			return new Failure("the event's shape changed", changed);
		}
	}

	/** For tests and for a relaunch in the same JVM: forget what was resolved. */
	public static void reset() {
		resolution = null;
	}
}
