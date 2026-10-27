/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;
import net.forbric.kernel.interop.HudContextCallbackScope;

public final class KernelHudContextQuery {
	private KernelHudContextQuery() { }
	/** The open callbacks' answer, composed; the native selection runs on the HUD the innermost one passes on. */
	/**
	 * 1.21.1 has neither net.minecraft.client.gui.Hud nor Hud$ContextualInfo: the contextual-info bar is a
	 * 26.x structure, and this line's Gui has no hud field at all. The transformer that would call this method
	 * targets updateContextualBarRenderer, which does not exist here either, so the method is unreachable on
	 * this line. It therefore answers null -- "no native selection" -- rather than pretending to ask.
	 */
	public static Object next(Object hud){return HudContextCallbackScope.compose(hud,passed->null);}
}
