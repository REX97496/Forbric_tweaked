/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.atomic.AtomicBoolean;

import net.forbric.kernel.util.ForbricLog;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * NeoForge's {@code ScreenEvent.Opening} and {@code Closing}, posted from the merged {@code Gui.setScreen}.
 *
 * <p>The merge kept MinecraftForge's {@code setScreen}, which asks MinecraftForge's Opening and Closing and never
 * NeoForge's, so a NeoForge mod that swaps or refuses a screen did nothing: Controlling never replaced the key binds
 * screen, JEI never learned a screen opened, Balm and PuzzlesLib never saw one change. NeoScreenEventsInjector calls in
 * right after MinecraftForge's own hooks. MinecraftForge is asked first and its refusal is final; NeoForge then sees
 * MinecraftForge's choice as the new screen and decides last — a cancel, a replacement, or null to close.
 *
 * <p>The replacement is carried from the post to the assignment through one field: {@code setScreen} runs on the
 * render thread, and a listener that opens another screen re-enters and finishes inside the post, before it is set.
 */
public final class KernelScreenEvents {
	private static final Object NOTHING = new Object();
	private static final AtomicBoolean WARNED = new AtomicBoolean();
	private static final AtomicBoolean INSTALLED = new AtomicBoolean();
	private static Object replacement = NOTHING;
	private static MethodHandle FORGE_OPENING;
	private static MethodHandle FORGE_CLOSING;

	private KernelScreenEvents() {
	}

	/** After MinecraftForge's Opening: null refuses the screen, anything else goes on (NeoForge's pick is kept aside). */
	public static Screen neoForgeOpening(Screen forgeChoice, Screen current) {
		if (forgeChoice == null) return null;
		replacement = NOTHING;
		try {
			ScreenEvent.Opening event = new ScreenEvent.Opening(current, forgeChoice);
			NeoForge.EVENT_BUS.post(event);
			if (event.isCanceled()) return null;
			if (event.getNewScreen() != forgeChoice) replacement = event.getNewScreen();
		} catch (Throwable t) {
			warn("ScreenEvent.Opening", t);
		}
		return forgeChoice;
	}

	/** The screen {@code setScreen} goes on with: NeoForge's replacement (null closes), or the one it had. */
	public static Screen takeNeoForgeNewScreen(Screen screen) {
		Object picked = replacement;
		replacement = NOTHING;
		return picked == NOTHING ? screen : (Screen) picked;
	}

	/** After MinecraftForge's Closing, for the same screen. */
	public static void postNeoForgeClosing(Screen closing) {
		try {
			NeoForge.EVENT_BUS.post(new ScreenEvent.Closing(closing));
		} catch (Throwable t) {
			warn("ScreenEvent.Closing", t);
		}
	}

	/**
	 * MinecraftForge's {@code ScreenEvent.Opening} and {@code Closing}, posted by riding NeoForge's own.
	 *
	 * <p>On a base where vanilla's {@code Minecraft.setScreen} posts NeoForge's two events itself — 1.21.1 does, at
	 * {@code 151: new ScreenEvent$Opening} and {@code 197: new ScreenEvent$Closing} — there is no seam left in that
	 * body to put MinecraftForge's next to, and deriving the screens from the bytecode would need a branch the
	 * transformer cannot add without stack map frames. Riding the events has no such cost and is exact: NeoForge posts
	 * Opening only when a screen is really opening and Closing only when the screen really is replaced, which is the
	 * condition MinecraftForge's two calls were written for. Highest priority so other NeoForge listeners see the
	 * screen MinecraftForge chose, exactly as if MinecraftForge had been asked first.
	 *
	 * <p>Reflective because MinecraftForge's event package is not on 26.2's classpath at all: where the factory is
	 * absent this returns without installing anything, and the base keeps whatever it already had.
	 */
	public static void installForgeScreenEvents() {
		if ("off".equalsIgnoreCase(System.getProperty("forbric.forgeScreenEvents", "on"))) return;
		if (!INSTALLED.compareAndSet(false, true)) return;
		Class<?> factory;
		try {
			factory = Class.forName("net.minecraftforge.client.event.ForgeEventFactoryClient", false,
					KernelScreenEvents.class.getClassLoader());
		} catch (ClassNotFoundException | LinkageError absent) {
			return;
		}
		try {
			MethodHandle opening = MethodHandles.publicLookup().findStatic(factory, "onScreenOpening",
					MethodType.methodType(Screen.class, Screen.class, Screen.class));
			MethodHandle closing = MethodHandles.publicLookup().findStatic(factory, "onScreenClose",
					MethodType.methodType(void.class, Screen.class));
			FORGE_OPENING = opening;
			FORGE_CLOSING = closing;
			NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, ScreenEvent.Opening.class,
					KernelScreenEvents::askMinecraftForgeFirst);
			NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, ScreenEvent.Closing.class,
					KernelScreenEvents::tellMinecraftForgeFirst);
			ForbricLog.info("[Forbric/Screens] MinecraftForge's ScreenEvent.Opening and Closing now ride NeoForge's on "
					+ "this base, ahead of every other listener");
		} catch (ReflectiveOperationException | RuntimeException | LinkageError changed) {
			ForbricLog.warn("[Forbric/Screens] MinecraftForge's screen-event factory could not be reached, so its "
					+ "mods still miss every screen change: " + changed);
		}
	}

	/** MinecraftForge's refusal is final; its replacement becomes the screen NeoForge's other listeners see. */
	private static void askMinecraftForgeFirst(ScreenEvent.Opening event) {
		MethodHandle call = FORGE_OPENING;
		if (call == null) return;
		try {
			Screen chosen = (Screen) call.invokeWithArguments(event.getCurrentScreen(), event.getNewScreen());
			if (chosen == null) {
				event.setCanceled(true);
			} else if (chosen != event.getNewScreen()) {
				event.setNewScreen(chosen);
			}
		} catch (Throwable failed) {
			warn("ScreenEvent.Opening", failed);
		}
	}

	private static void tellMinecraftForgeFirst(ScreenEvent.Closing event) {
		MethodHandle call = FORGE_CLOSING;
		if (call == null) return;
		try {
			call.invokeWithArguments(event.getScreen());
		} catch (Throwable failed) {
			warn("ScreenEvent.Closing", failed);
		}
	}

	private static void warn(String event, Throwable t) {
		if (WARNED.compareAndSet(false, true)) {
			ForbricLog.warn("[Forbric/Screens] NeoForge's " + event + " failed — the screen change went on as "
					+ "MinecraftForge decided", t);
		}
	}
}
