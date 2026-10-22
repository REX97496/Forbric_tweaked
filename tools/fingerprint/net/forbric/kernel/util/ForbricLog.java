package net.forbric.kernel.util;

/** Stub for the standalone fingerprint tool. Only warn is referenced, and only from dump(), which the
 *  fingerprint path never calls. */
public final class ForbricLog {
    private ForbricLog() { }
    public static void warn(String format, Object... args) {
        System.err.println("[warn] " + format);
    }
}
