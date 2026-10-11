package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The named boot phases exist, and their SOURCE ORDER is pinned, because that order is load-bearing and was
 * arrived at empirically.
 *
 * <h2>Why this is worth asserting at all</h2>
 *
 * <p>A video critiquing this project describes the startup sequence as "not designed — it was changed again and
 * again after each mistake, from testing against specific mods". That is accurate and it is visible in the source:
 * the phases carry comments naming the mod whose failure put them there (ferritecore's plugin needing
 * {@code FMLPaths.CONFIGDIR} in its static initialiser, Create Fly's TAIL injector, architectury's redirects,
 * balm's single listener). Reverse-engineering three loaders' boot orders cannot be designed from first principles,
 * so an empirically-derived order is not itself the defect.
 *
 * <p>The defect is that nothing recorded it. The order exists only as the sequence of statements in a very long
 * method, so reordering two phases — the kind of edit a refactor makes casually — is invisible: no test fails, no
 * log line changes, and the symptom appears later as some mod's feature silently not working. This is the same
 * shape as the bridge and mixin findings in this suite: the mechanism is right and the CONTRACT is missing.
 *
 * <h2>What is pinned</h2>
 *
 * <p>The nine public phases of {@link KernelLifecycle} and the order their declarations appear in the file. Source
 * order, not call order, because the calls are spread across injected hooks in the game's own classes and there is
 * no single place to read the sequence from. Source order is what a reader sees and what an editor moves.
 */
class BootPhaseOrderTest {

    /** The public lifecycle phases, and the order they are declared in. Removing or moving one fails here. */
    private static final List<String> PHASES_IN_SOURCE_ORDER = List.of(
            "onServerModLoading",
            "onClientModLoading",
            "onEarlyStartupFailure",
            "onClientResourcePacks",
            "onServerDataPacks",
            "onServerModLoadingNoArg",
            "onCreativeTabEntrySkipped",
            "onClientEntrypoints",
            "onNeoClientSetup");

    private static final Path SOURCE = Path.of("src", "main", "java", "net", "forbric", "kernel", "boot",
            "KernelLifecycle.java");

    /** Every pinned phase must still be a public static method, so its callers still resolve. */
    @Test
    void everyBootPhaseStillExistsAndIsCallable() {
        List<String> missing = new ArrayList<>();
        for (String name : PHASES_IN_SOURCE_ORDER) {
            Method found = null;
            for (Method method : KernelLifecycle.class.getDeclaredMethods()) {
                if (method.getName().equals(name)) { found = method; break; }
            }
            if (found == null) { missing.add(name + " (absent)"); continue; }
            if (!Modifier.isStatic(found.getModifiers())) missing.add(name + " (not static)");
            if (!Modifier.isPublic(found.getModifiers())) missing.add(name + " (not public)");
        }
        if (!missing.isEmpty()) {
            fail("a boot phase is gone or no longer callable, so whatever injected a call to it no longer resolves:"
                    + " " + missing + ". If a phase was renamed or merged, update the list in this test in the same"
                    + " commit — the point of the list is that moving a phase is a decision.");
        }
    }

    /**
     * And their declaration order in the file is the pinned one: a reorder is what this catches.
     */
    @Test
    void theBootPhaseOrderInTheSourceIsUnchanged() throws IOException {
        assertTrue(Files.isRegularFile(SOURCE), "the lifecycle source this pins the order of is gone: " + SOURCE);
        List<String> lines = Files.readAllLines(SOURCE);

        Map<String, Integer> declaredAt = new LinkedHashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            for (String name : PHASES_IN_SOURCE_ORDER) {
                if (declaredAt.containsKey(name)) continue;
                if (line.contains("static void " + name + "(")) declaredAt.put(name, i + 1);
            }
        }

        List<String> absent = PHASES_IN_SOURCE_ORDER.stream().filter(n -> !declaredAt.containsKey(n)).toList();
        assertTrue(absent.isEmpty(), "phase(s) not found in " + SOURCE + ": " + absent);

        List<String> actual = declaredAt.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .toList();
        assertTrue(actual.equals(PHASES_IN_SOURCE_ORDER),
                "the boot phases were REORDERED in the source. The order is not cosmetic: it was arrived at by"
                        + " running into a specific mod's failure at each step, and nothing else records it. If the"
                        + " move is intended, pin the new order here and say in the commit what made it safe."
                        + " Pinned=" + PHASES_IN_SOURCE_ORDER + " actual=" + actual
                        + " (declared at lines " + declaredAt + ")");
    }

    /** A ninth phase added without thought is fine; a phase silently dropped is not. */
    @Test
    void thePhaseCountIsPinned() {
        assertTrue(PHASES_IN_SOURCE_ORDER.size() >= 9,
                "the pinned phase list shrank to " + PHASES_IN_SOURCE_ORDER.size() + ": " + PHASES_IN_SOURCE_ORDER);
    }
}
