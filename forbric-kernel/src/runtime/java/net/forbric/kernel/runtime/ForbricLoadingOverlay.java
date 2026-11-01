/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.neoforged.neoforgespi.earlywindow.ImmediateWindowProvider;

/**
 * Answers NeoForge's "who draws the loading overlay" question when there is no early window to draw it.
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
 * <p>ImmediateWindowHandler.loadingOverlay is four instructions wide: complete the progress meter, then forward to
 * {@code provider.loadingOverlay(first, second, onError, fade)} and return its Supplier. DisplayWindow answers that one
 * with a sentinel: "How did you get here?" is the class saying this call is not part of the path it supports.
 *
 * <p>So the answer is the caller's own overlay, not the provider's: return the FIRST Supplier it passed in. That is
 * what a session with no early display should use, and it is the only argument that is a complete answer on its own.
 *
 * <p>WHAT THIS DOES NOT DO: it does not draw a splash. There is no early window here, so nothing fades out; the caller's
 * overlay is shown from the start. The `fade` flag and the error Consumer are accepted and ignored.
 *
 * <p>WHY A SEPARATE CLASS: retargeting the one {@code invokeinterface} at offset 13 keeps the instruction count
 * identical and preserves the stack shape (provider plus four arguments are popped by both forms), so no
 * StackMapTable is needed -- and this kernel can only ever use COMPUTE_MAXS.
 */
public final class ForbricLoadingOverlay {

	private ForbricLoadingOverlay() {
	}

	/** The caller's own overlay, because no early window exists to hand one back. */
	@SuppressWarnings("unchecked")
	public static <T> Supplier<T> overlayOrFirst(ImmediateWindowProvider provider, Supplier<?> first,
			Supplier<?> second, Consumer<Optional<Throwable>> onError, boolean fade) {
		return (Supplier<T>) first;
	}
}
