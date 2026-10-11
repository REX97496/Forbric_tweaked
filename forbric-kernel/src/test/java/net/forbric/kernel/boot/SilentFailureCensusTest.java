package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * How many {@code catch} blocks in the shipping sources handle a failure without saying anything, held to a
 * ceiling so the number cannot grow unnoticed.
 *
 * <h2>Why this exists as a number rather than as a policy</h2>
 *
 * <p>This kernel's most repeated failure is not a crash: it is an absence. A file that could not be read becomes
 * {@code null}, which the caller's contract calls "not there"; a class that cannot be proven safe becomes "not
 * provable", which aborts a build; a library that was never downloaded becomes "unsafe code". Each of those was
 * found by chasing a symptom, because the site that swallowed the cause had nothing to say.
 *
 * <p>That is a critique aimed at this project from outside, and it was right: the count below is 647 of 1193 catch
 * blocks — over half — in a codebase whose whole job is to make three modding ecosystems coexist, where "it did not
 * throw" is routinely mistaken for "it worked". A first pass with a cruder detector said 387; proper brace
 * matching found the rest, which is itself the lesson: the undercount came from looking at a window instead of the
 * whole block.
 *
 * <h2>What the number means, and what it does not</h2>
 *
 * <p>A silent catch is not automatically a defect. Some are deliberate and documented — a probe asking "can this
 * class be parsed" treats a parse failure as an answer, and the comment beside it says so. Auditing all of them is
 * a judgement call per site, and this test does not make it. What it does make is the DIRECTION: the count is a
 * ceiling, so adding one is a decision that has to be argued here rather than a line nobody reads. Lowering it is
 * always welcome and needs no explanation.
 *
 * <p>The classifier is deliberately simple and biased toward "reports": a body counts as reporting if it mentions
 * anything a reader could find later — a log call, a throw, the compatibility findings, a failure record. Bodies
 * that only return a value are counted as silent, which is the honest reading: a caller that receives {@code null}
 * learns nothing about why.
 */
class SilentFailureCensusTest {

    /**
     * The ceiling: 647 silent catch blocks out of 1193, measured across {@code src/main} and {@code src/runtime}.
     *
     * <p>A change that adds one fails here and has to say what the new site is for. It only ever goes down — and
     * when it does, the figure below is lowered in the same commit, because a ceiling left above the real count is
     * permission for the difference.
     */
    private static final int SILENT_CATCH_CEILING = 647;

    /** What a catch body has to mention to count as reporting the failure somewhere. */
    private static final List<String> REPORTING = List.of(
            "ForbricLog", "LOGGER", "log.", "throw ", "CompatibilityFindings", "Failures", "System.err",
            "record(", "Failures.", "addFinding", "report(");

    @Test
    void theShippingSourcesDoNotGainNewSilentCatches() throws Exception {
        Map<String, List<Integer>> silent = new TreeMap<>();
        int catchBlocks = 0;
        for (Path file : sources()) {
            List<String> lines = Files.readAllLines(file);
            for (int i = 0; i < lines.size(); i++) {
                if (!lines.get(i).contains("catch")) continue;
                int open = lines.get(i).indexOf('{', lines.get(i).indexOf("catch"));
                if (open < 0) continue;
                String body = blockBody(lines, i, open);
                if (body == null) continue;
                catchBlocks++;
                if (!reports(body)) silent.computeIfAbsent(file.getFileName().toString(), f -> new ArrayList<>()).add(i + 1);
            }
        }

        int total = silent.values().stream().mapToInt(List::size).sum();
        System.out.println("[silent-catch] " + total + " of " + catchBlocks
                + " catch block(s) in the shipping sources report nothing, across " + silent.size() + " file(s)");
        silent.entrySet().stream().limit(12).forEach(entry ->
                System.out.println("[silent-catch]   " + entry.getKey() + " : " + entry.getValue()));

        assertTrue(catchBlocks > 0, "the walk found no catch blocks at all, so it is not measuring the sources");
        assertTrue(total <= SILENT_CATCH_CEILING,
                "silent catch blocks grew from " + SILENT_CATCH_CEILING + " to " + total + ". A new one is a decision,"
                        + " not a detail: say in the log what failed, or say in a comment here why it must stay quiet."
                        + " Files that grew: " + silent.entrySet().stream()
                                .filter(e -> e.getValue().size() > 2).limit(8).toList());
        // A ratchet that never tightens stops being one, so a DROP has to be recorded in the same commit. This is
        // the cheap half of the intent and it is what keeps the figure from quietly becoming a ceiling nobody can
        // reach, which reads as permission for the difference.
        assertEquals(SILENT_CATCH_CEILING, total,
                "the count DROPPED to " + total + " — lower SilentFailureCensusTest.SILENT_CATCH_CEILING to that"
                        + " figure in this same commit, so the gain is locked in rather than left as headroom");
    }

    /** The shipping kernel sources: main and runtime. Tests are excluded, since a test may swallow freely. */
    private static List<Path> sources() throws IOException {
        List<Path> out = new ArrayList<>();
        for (String set : List.of("main", "runtime")) {
            Path root = Path.of("src", set, "java");
            if (!Files.isDirectory(root)) continue;
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(p -> p.toString().endsWith(".java")).forEach(out::add);
            }
        }
        return out;
    }

    /**
     * The body of the block that opens at {@code openLine}:{@code openColumn}, by matching braces so a
     * multi-line body is read whole rather than guessed at from a line window.
     */
    private static String blockBody(List<String> lines, int openLine, int openColumn) {
        StringBuilder body = new StringBuilder();
        int depth = 0;
        for (int i = openLine; i < lines.size(); i++) {
            String line = lines.get(i);
            int from = i == openLine ? openColumn : 0;
            for (int c = from; c < line.length(); c++) {
                char ch = line.charAt(c);
                if (ch == '{') depth++;
                else if (ch == '}') {
                    depth--;
                    if (depth == 0) return body.toString();
                }
            }
            body.append(line).append('\n');
            // A catch body that has not closed within a screenful is not a swallow this test can judge.
            if (i - openLine > 400) return null;
        }
        return null;
    }

    private static boolean reports(String body) {
        for (String needle : REPORTING) if (body.contains(needle)) return true;
        return false;
    }
}
