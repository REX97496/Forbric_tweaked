/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.server.packs.resources.ReloadInstance;
import net.neoforged.neoforgespi.earlywindow.ImmediateWindowProvider;

/**
 * Builds the loading overlay when there is no early window to hand one back.
 *
 * <p>Measured frame, from a real client launch:
 *
 * <pre>
 *   IllegalStateException: How did you get here?
 *     at DisplayWindow.loadingOverlay(DisplayWindow.java:597)
 *     at ImmediateWindowHandler.loadingOverlay(:90)
 *     at Minecraft.&lt;init&gt;(Minecraft.java:622)
 * </pre>
 *
 * <p>ImmediateWindowHandler.loadingOverlay is four instructions: complete the progress meter, forward to
 * {@code provider.loadingOverlay(first, second, onError, fade)}, areturn. DisplayWindow answers that one with a
 * sentinel, so this method stands in for it.
 *
 * <p>IT BUILDS THE OVERLAY; IT DOES NOT PICK ONE. Two attempts at returning an argument directly failed, and each
 * failure named the next fact:
 *
 * <pre>
 *   returned first  -&gt; ClassCastException: Minecraft cannot be cast to Overlay
 *   returned second -&gt; ClassCastException: SimpleReloadInstance cannot be cast to Overlay
 * </pre>
 *
 * <p>Because neither argument IS an overlay: Minecraft.&lt;init&gt; passes {@code () -> this} and
 * {@code () -> reloadInstance}, i.e. the two INGREDIENTS, and the provider is what turns them into a screen. The
 * evidence is the constructor of the thing the caller casts to:
 *
 * <pre>
 *   LoadingOverlay(Minecraft, ReloadInstance, Consumer&lt;Optional&lt;Throwable&gt;&gt;, boolean)
 *   loadingOverlay(Supplier&lt;?&gt;, Supplier&lt;?&gt;, Consumer&lt;Optional&lt;Throwable&gt;&gt;, boolean)
 * </pre>
 *
 * <p>-- the last two arguments line up one for one, which is what makes this the intended body.
 *
 * <p>WHAT THIS DOES NOT DO: it does not draw an early-window splash, and the {@code fade} flag has nothing to fade.
 * A provider with a real early display would cross-fade from its own window into this overlay; here the overlay is the
 * first thing shown.
 *
 * <p>WHY A SEPARATE CLASS: retargeting the one {@code invokeinterface} at offset 13 keeps the instruction count
 * identical and preserves the stack shape (the provider plus four arguments are popped by both forms), so no
 * StackMapTable is needed -- and this kernel can only ever use COMPUTE_MAXS.
 */
public final class ForbricLoadingOverlay {

	private ForbricLoadingOverlay() {
	}

	/**
	 * A Supplier that builds vanilla's loading overlay from the two suppliers the caller handed in.
	 *
	 * <p>The overlay is constructed lazily, on {@code get()}, exactly as the provider's own would be -- building it
	 * here would create a screen during Minecraft's constructor rather than when the caller asks for it.
	 */
	public static <T> Supplier<T> buildOverlay(ImmediateWindowProvider provider, Supplier<?> first,
			Supplier<?> second, Consumer<Optional<Throwable>> onError, boolean fade) {
		return () -> {
			@SuppressWarnings("unchecked")
			T overlay = (T) new LoadingOverlay((Minecraft) first.get(), (ReloadInstance) second.get(), onError, fade);
			return overlay;
		};
	}
}
