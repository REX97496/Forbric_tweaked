package net.forbric.kernel.boot;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Measures what the boot-side jar audits cost, so "make startup faster" has a baseline to move and a
 * regression to catch. Writes its table to build/boot-scan-timings.txt rather than asserting on wall-clock
 * time, because a threshold assertion on a shared machine fails for reasons that have nothing to do with this
 * code — the number is the product, and a human reads it.
 *
 * <p>The jars are whatever the local Gradle cache holds. That is not a real mods folder, but it is the same
 * SHAPE: hundreds of classes plus resources per jar, which is what the audits iterate. Scaling to a full pack
 * is stated honestly in the output rather than guessed at.
 */
class ZzBootScanTimingTest {

    @Test
    void measureTheThreeJarAudits() throws Exception {
        // Opt-in: this runs the three audits ten times each over the local jar set, which is tens of seconds of
        // work for a NUMBER rather than a verdict. A default suite should not pay that, and the number is only
        // wanted when someone is about to change a scanner or is wondering where boot time goes.
        //
        // `present = required(OPT_IN)` is the project's own spelling for "run only when asked for": required
        // means the caller asked, so the test proceeds; not required aborts with the [fixture:opt-in] tag that
        // FixturePolicyExtension counts. -Pforbric.requireFixtures=opt-in turns it on.
        net.forbric.kernel.TestFixtures.require(net.forbric.kernel.TestFixtures.Fixture.OPT_IN,
                net.forbric.kernel.TestFixtures.required(net.forbric.kernel.TestFixtures.Fixture.OPT_IN),
                "boot-side jar scan timing is a measurement, not a verdict; ask for it with "
                        + "-Pforbric.requireFixtures=opt-in");

        List<Path> jars = sampleJars();
        // A measurement over nothing reports 0.0 ms for every audit and reads like the fastest possible boot.
        // Refuse to produce the table at all rather than let a path mistake become a performance claim.
        if (jars.isEmpty()) {
            throw new AssertionError("no jars found to measure — a timing table over zero jars would report 0.0 ms "
                    + "for every audit and read as a result. Checked the workspace-local and default Gradle caches; "
                    + "neither held a jar. user.dir=" + System.getProperty("user.dir"));
        }
        long classCount = 0;
        long bytes = 0;
        for (Path jar : jars) {
            try (JarFile file = new JarFile(jar.toFile())) {
                bytes += Files.size(jar);
                classCount += file.stream().filter(e -> e.getName().endsWith(".class")).count();
            }
        }

        StringBuilder out = new StringBuilder();
        out.append("Forbric boot-side jar scan timings\n")
           .append("==================================\n")
           .append(String.format("jars   : %d%n", jars.size()))
           .append(String.format("classes: %d%n", classCount))
           .append(String.format("size   : %.1f MB%n", bytes / 1024.0 / 1024.0))
           .append(String.format("runs   : %d per audit, median reported%n%n", RUNS));

        // Warm up before measuring anything. A single run pays disk I/O and JIT for work the next run does not,
        // and reporting that as the algorithm's cost is how a measurement misleads: the first version of this
        // test reported one run per audit, and a source change that could not plausibly have cost 3 ms moved the
        // number by more than that.
        for (int i = 0; i < WARMUP; i++) {
            FabricApiModuleLossAudit.scan(jars);
            FieldDriftAudit.scan(jars);
            AbiLinkAudit.scan(jars, List.of());
        }

        out.append(String.format("%-28s %10s %10s%n", "audit", "median ms", "min ms"));
        out.append("-".repeat(52)).append('\n');

        long fabric = report(() -> FabricApiModuleLossAudit.scan(jars), out, "FabricApiModuleLossAudit");
        long drift = report(() -> FieldDriftAudit.scan(jars), out, "FieldDriftAudit");
        long abi = report(() -> AbiLinkAudit.scan(jars, List.of()), out, "AbiLinkAudit");

        // The pure I/O and iteration floor: open every jar, read every class entry, do nothing with the bytes.
        // Whatever a fused scan could remove has to be found above this line, not above zero -- an audit cannot
        // cost less than reading the bytes it judges.
        long floor = report(() -> readEveryClassByte(jars), out, "floor: read bytes, no audit");

        long total = fabric + drift + abi;
        long min = Math.min(fabric, Math.min(drift, abi));
        out.append("-".repeat(52)).append('\n')
           .append(String.format("%-28s %10.1f%n", "three, one after another", total / 1_000_000.0))
           .append(String.format("%-28s %10.1f%n", "floor (IO/iteration alone)", floor / 1_000_000.0))
           .append(String.format("%-28s %10.1f%n", "fused upper bound (one pass min)", min / 1_000_000.0))
           .append(String.format("%-28s %10.1f%n", "removable by fusing", (total - min) / 1_000_000.0))
           .append(String.format("%-28s %10.0f%%%n", "of which, pure IO/iteration", 100.0 * floor / (total - min)))
           .append('\n')
           .append("Reading: 'fused upper bound' is what the best case looks like -- one pass costing its most\n")
           .append("expensive member. 'removable by fusing' is the ceiling on the saving, and 'pure IO/iteration'\n")
           .append("says how much of that ceiling is bytes-over-the-disk rather than per-audit work, because only\n")
           .append("the I/O part is genuinely removable.\n")
           .append("These are GRADLE-CACHE jars, not a mods folder: same shape (classes plus resources), but fewer of\n")
           .append("them. A 150-jar pack scales this roughly linearly in jar COUNT.\n");

        // The sharing experiment, INTERLEAVED. Two runs of the same full-cache configuration in this suite came
        // back at 98.3 ms and 198.6 ms -- a factor of two on identical code -- so a single sequential block cannot
        // settle it. Alternating the two candidates inside one loop spreads any drift across both, and the reported
        // figures are medians of the same number of runs, which is the only way the comparison means anything.
        out.append('\n').append("Three separate passes vs one read shared by all three, INTERLEAVED\n")
           .append("-".repeat(52)).append('\n');
        long[] separate = new long[RUNS];
        long[] shared = new long[RUNS];
        for (int run = 0; run < RUNS; run++) {
            long before = System.nanoTime();
            FabricApiModuleLossAudit.scan(jars);
            FieldDriftAudit.scan(jars);
            AbiLinkAudit.scan(jars, List.of());
            separate[run] = System.nanoTime() - before;

            before = System.nanoTime();
            readThroughBoundedCache(jars, Integer.MAX_VALUE);
            shared[run] = System.nanoTime() - before;
        }
        java.util.Arrays.sort(separate);
        java.util.Arrays.sort(shared);
        long separateMedian = separate[RUNS / 2];
        long sharedMedian = shared[RUNS / 2];
        out.append(String.format("%-28s %10.1f%n", "three, separate (interleaved)", separateMedian / 1_000_000.0))
           .append(String.format("%-28s %10.1f%n", "one read shared (interleaved)", sharedMedian / 1_000_000.0))
           .append(String.format("%-28s %10.0f%%%n", "saving", 100.0 * (separateMedian - sharedMedian) / separateMedian))
           .append("Reading: 'one read shared' is the cache in this file -- the READ cost of a single pass that\n")
           .append("hands the same bytes to all three audits. The audits' own per-class work is NOT included, so\n")
           .append("this is the ceiling a real fused scan could save, not what it would deliver.\n");

        // What a REAL fused scan would cost, measured rather than modelled: one traversal, each class inflated
        // once, its bytes handed to every audit that has a per-class entry point. Two of the three do (public
        // static note); AbiLinkAudit keeps per-jar state across its own traversal and is therefore NOT INCLUDED,
        // so this figure is honest about what it covers.
        out.append('\n').append("Two audits fused through one traversal (AbiLinkAudit excluded, see below)\n")
           .append("-".repeat(52)).append('\n');
        long[] apart = new long[RUNS];
        long[] together = new long[RUNS];
        for (int run = 0; run < RUNS; run++) {
            long before = System.nanoTime();
            FabricApiModuleLossAudit.scan(jars);
            FieldDriftAudit.scan(jars);
            apart[run] = System.nanoTime() - before;

            before = System.nanoTime();
            fuseTwoAudits(jars);
            together[run] = System.nanoTime() - before;
        }
        java.util.Arrays.sort(apart);
        java.util.Arrays.sort(together);
        long apartMedian = apart[RUNS / 2];
        long togetherMedian = together[RUNS / 2];
        out.append(String.format("%-28s %10.1f%n", "two scans, one after another", apartMedian / 1_000_000.0))
           .append(String.format("%-28s %10.1f%n", "two audits, one traversal", togetherMedian / 1_000_000.0))
           .append(String.format("%-28s %10.0f%%%n", "saving",
                   100.0 * (apartMedian - togetherMedian) / apartMedian))
           .append("Reading: both sides do the audits' real per-class work, so unlike the figures above this is what\n")
           .append("fusing would DELIVER, not a ceiling. AbiLinkAudit is absent from both sides, so the saving over\n")
           .append("all three is smaller by AbiLinkAudit's own inflation cost -- unless it is given the same entry\n")
           .append("point, which its per-jar state currently does not allow.\n");

        Files.createDirectories(Path.of("build"));
        Files.writeString(Path.of("build", "boot-scan-timings.txt"), out.toString(), StandardCharsets.UTF_8);
        System.out.println(out);
    }

    /**
     * One traversal, each class inflated once, its bytes handed to both audits' per-class entry points — what a
     * fused scan would actually look like.
     *
     * <p>Nothing is buffered: the byte array is live for one iteration and dropped. That is the whole point of
     * measuring it, because the reading itself has to stay well inside the memory a startup may hold.
     */
    private static void fuseTwoAudits(List<Path> jars) {
        for (Path jar : jars) {
            String name = jar.getFileName().toString();
            try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar.toFile())) {
                for (java.util.zip.ZipEntry entry : zip.stream().toList()) {
                    if (!entry.getName().endsWith(".class")) continue;
                    byte[] bytes;
                    try (java.io.InputStream in = zip.getInputStream(entry)) {
                        bytes = in.readAllBytes();
                    }
                    FabricApiModuleLossAudit.note(name, bytes);
                    FieldDriftAudit.note(name, bytes);
                }
            } catch (IOException unreadable) {
                // Same contract as the audits: a jar that cannot be read is skipped, never refused.
            }
        }
    }

    /** Opens every jar and reads every class entry, doing nothing with the bytes: the cost of a shared pass. */
    private static void readEveryClassByte(List<Path> jars) {
        long sink = 0;
        for (Path jar : jars) {
            try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar.toFile())) {
                for (java.util.zip.ZipEntry entry : zip.stream().toList()) {
                    if (!entry.getName().endsWith(".class")) continue;
                    try (java.io.InputStream in = zip.getInputStream(entry)) {
                        sink += in.readAllBytes().length;
                    }
                }
            } catch (IOException unreadable) {
                // Same contract as the audits: a jar that cannot be read is skipped, never refused.
            }
        }
        if (sink < 0) throw new AssertionError("unreachable: keeps the JIT from eliding the reads");
    }

    /**
     * The same three passes, but reading each class's bytes through a bounded cache instead of decompressing them
     * again — the experiment that decides whether sharing is worth it, since the audits iterate the same entries in
     * the same order and each one currently inflates the whole set.
     *
     * <p>DELIBERATELY BOUNDED. Holding every class byte of a 150-jar pack is tens to hundreds of megabytes of live
     * heap for a startup saving, which is why a shared cache cannot simply be unbounded. The bound also decides
     * whether sharing works at all: the hit rate depends entirely on the three passes staying in step, so this is
     * measured rather than assumed.
     *
     * @return the sink, so the reads cannot be elided
     */
    private static long readThroughBoundedCache(List<Path> jars, int bound) {
        return readThroughBoundedCache(jars, bound, null);
    }

    /**
     * @param heldBytes when non-null, {@code [0]} receives the total bytes of the entries the cache retained, so a
     *                  bound can be argued in megabytes instead of in entries. Counted as the peak of a
     *                  least-recently-used map, which is what the bound caps.
     */
    private static long readThroughBoundedCache(List<Path> jars, int bound, long[] heldBytes) {
        java.util.LinkedHashMap<String, byte[]> cache = new java.util.LinkedHashMap<>(1024, 0.75f, true) {
            @Override protected boolean removeEldestEntry(java.util.Map.Entry<String, byte[]> eldest) {
                return size() > bound;
            }
        };
        long sink = 0;
        long peak = 0;
        long distinct = 0;
        for (int pass = 0; pass < 3; pass++) {
            for (Path jar : jars) {
                try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar.toFile())) {
                    for (java.util.zip.ZipEntry entry : zip.stream().toList()) {
                        if (!entry.getName().endsWith(".class")) continue;
                        String key = jar + "!" + entry.getName();
                        byte[] bytes = cache.get(key);
                        if (bytes == null) {
                            try (java.io.InputStream in = zip.getInputStream(entry)) {
                                bytes = in.readAllBytes();
                            }
                            cache.put(key, bytes);
                            long live = 0;
                            for (byte[] held : cache.values()) live += held.length;
                            peak = Math.max(peak, live);
                        }
                        sink += bytes.length;
                        distinct++;
                    }
                } catch (IOException unreadable) {
                    // Same contract as the audits.
                }
            }
        }
        if (heldBytes != null) {
            heldBytes[0] = peak;
            return distinct / 3;   // the entry count, not the total over three passes
        }
        return sink;
    }

    private static final int WARMUP = 2;
    private static final int RUNS = 7;

    /** Times {@code body} RUNS times and appends its median and minimum, in ms. */
    private static long report(Runnable body, StringBuilder out, String label) {
        long[] samples = new long[RUNS];
        for (int i = 0; i < RUNS; i++) {
            long start = System.nanoTime();
            body.run();
            samples[i] = System.nanoTime() - start;
        }
        java.util.Arrays.sort(samples);
        long median = samples[RUNS / 2];
        out.append(String.format("%-28s %10.1f %10.1f%n", label, median / 1_000_000.0, samples[0] / 1_000_000.0));
        return median;
    }

    /**
     * Every non-sources jar in the local Gradle cache: the representative class-plus-resources shape.
     *
     * <p>Resolved against a list of roots rather than one, because the cache's location depends on which
     * GRADLE_USER_HOME the build ran with (this workspace keeps one beside the project, a default install keeps
     * it in the user's home) — and a measurement that silently found ZERO jars would report 0.0 ms and read
     * like a very fast audit.
     */
    private static List<Path> sampleJars() throws IOException {
        Path cwd = Path.of(System.getProperty("user.dir"));
        List<Path> homes = List.of(
                cwd.resolve("../.gradle-home"),                 // the workspace-local GRADLE_USER_HOME
                cwd.resolve(".gradle-home"),
                Path.of(System.getProperty("user.home"), ".gradle"),
                cwd.resolve("../forbric-kernel/.gradle-home"));
        for (Path home : homes) {
            Path root = home.resolve("caches/modules-2/files-2.1").normalize();
            if (!Files.isDirectory(root)) continue;
            List<Path> found = new ArrayList<>();
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".jar"))
                        .filter(p -> {
                            String n = p.getFileName().toString();
                            return !n.contains("-sources") && !n.contains("-javadoc");
                        })
                        .forEach(found::add);
            }
            if (!found.isEmpty()) return found;
        }
        return List.of();
    }
}
