/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

/**
 * Runs a callback if it is there, and does nothing if it is not.
 *
 * <p>Exists because of one measured frame. NeoForge's client entry calls
 * {@code BackgroundWaiter.runAndTick(Runnable task, Runnable tick)}, and its bytecode is:
 *
 * <pre>
 *    0: ldc "Loading bootstrap resources"
 *    2: invokestatic ImmediateWindowHandler.updateProgress(String)
 *    5: getstatic runner; 8: aload_0; 9: invokeinterface ExecutorService.submit(Runnable)
 *   15: aload_1                                  &lt;- the SECOND parameter, named tick
 *   16: invokeinterface Runnable.run()           &lt;- NullPointerException here
 *   21-27: Thread.sleep(50); 31-37: loop until the future is done; 40-43: runner.shutdown
 * </pre>
 *
 * <p>In a kernel launch there is no early loading window, so the caller passes null for that tick. The loop then calls
 * it anyway and the client dies during Bootstrap, before any game class is up.
 *
 * <p>WHY THIS IS A SEPARATE CLASS rather than a branch inserted into runAndTick: this kernel can only ever use
 * COMPUTE_MAXS (a class cannot be given a StackMapTable while it is being defined), so an inserted {@code ifnull} would
 * need frames that cannot be computed. Retargeting the single {@code invokeinterface} at offset 16 to this
 * {@code invokestatic} keeps the instruction count identical and swaps no stack shapes -- the receiver is popped by
 * both forms -- so no frames are needed at all.
 *
 * <p>WHAT THIS DOES NOT DO: it does not make the tick run. A caller that expected its tick to be called mid-wait gets
 * nothing from this method. That is correct here only because the tick is the splash window's repaint, and a kernel
 * launch has no splash window.
 */
public final class ForbricNullSafe {

	private ForbricNullSafe() {
	}

	/** Runs {@code callback} if it is non-null; otherwise returns immediately. */
	public static void runOrSkip(Runnable callback) {
		if (callback != null) {
			callback.run();
		}
	}
}
