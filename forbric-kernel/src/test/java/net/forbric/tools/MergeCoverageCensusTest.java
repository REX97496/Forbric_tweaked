package net.forbric.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * How much of the two Forge families' work the byte merge could not compose, split by whether a player can ever
 * reach it — read from the tracked report, so "as much of a mod's change as possible is applied" becomes a number
 * a change has to move deliberately.
 *
 * <h2>Why a census rather than another assertion about one row</h2>
 *
 * <p>The merge composes three shapes and REFUSES everything else, naming why ({@code AdditiveMethodMerger}, whose
 * refusals {@code MergedBaseBuilder} aggregates by reason). Widening a grammar is therefore the lever for "apply
 * more of what mods changed", and its effect is invisible per-row: a reopened grammar moves rows out of one bucket
 * and into another, and nobody notices that the total moved, or which rows moved.
 *
 * <p>So the counts are pinned here. Whichever change alters them — a new composition shape, a fix to a selector,
 * a base rebuilt against different inputs — fails this test and has to state the new figure and why, instead of
 * the coverage drifting a few rows per release with a green suite.
 *
 * <h2>What the split means</h2>
 *
 * <p>{@link #DATAGEN} is not a footnote. Measure it and it is a third of the losses: {@code ModelProvider},
 * {@code BlockModelGenerators}, {@code ItemModelGenerators} and the recipe/tag providers run inside a data
 * generator, which a player's game never starts. A hook lost there costs generated JSON, not gameplay, so
 * counting it in the same figure as {@code LivingEntity#baseTick} overstates what a player loses by a third and
 * makes every priority argument look the same. They are tracked separately for that reason, not to hide it.
 */
class MergeCoverageCensusTest {

    /** The tracked report: the one build output that is committed, and what LostHookAttributionTest reads too. */
    private static final Path REPORT = Path.of(System.getProperty("user.dir"), "..", "forbric-loader", "run", "merged-base", "merge-conflicts.txt").normalize();

    /**
     * Owner prefixes whose hooks only run in a data generator. Prefix-matched, not class-matched: a new provider
     * added to those packages belongs in this bucket the day it appears.
     */
    private static final List<String> DATAGEN = List.of(
            "net/minecraft/client/data/models/",
            "net/minecraft/client/data/",
            "net/minecraft/data/");

    // Pinned figures from the tracked report, taken from the parser this test uses rather than from a shell count:
    // the first draft of these came from a grep with looser row-matching and was wrong by 5 on the total and by 5
    // on the datagen split. Changing any of them is a deliberate statement about coverage.
    private static final int TOTAL_HOOK_LOSSES = 1006;
    private static final int DATAGEN_HOOK_LOSSES = 314;
    private static final int RUNTIME_HOOK_LOSSES = 692;
    /** Distinct runtime owners. The loss is spread thin: the largest single owner holds far less than half. */
    private static final int RUNTIME_OWNERS = 400;

    @Test
    void theUntouchedByTheMergeWorkIsCountedAndSplitByWhetherAPlayerReachesIt() throws Exception {
        assertTrue(Files.isRegularFile(REPORT),
                "the tracked merge report is this census's input; it is committed for exactly this kind of reading: " + REPORT);
        List<LostHookAttribution.Conflict> rows = LostHookAttribution.conflicts(REPORT);

        List<LostHookAttribution.Conflict> datagen = rows.stream().filter(MergeCoverageCensusTest::isDatagen).toList();
        List<LostHookAttribution.Conflict> runtime = rows.stream().filter(row -> !isDatagen(row)).toList();
        long runtimeOwners = runtime.stream().map(LostHookAttribution.Conflict::owner).distinct().count();

        System.out.println("[census] hook losses=" + rows.size() + " datagen=" + datagen.size() + " ("
                + percent(datagen.size(), rows.size()) + "%) runtime=" + runtime.size() + " over " + runtimeOwners
                + " owners");
        System.out.println("[census] by lost family: forge="
                + rows.stream().filter(row -> "forge".equals(row.lostFamily())).count() + " neo="
                + rows.stream().filter(row -> "neo".equals(row.lostFamily())).count());

        assertEquals(TOTAL_HOOK_LOSSES, rows.size(), "the total the tracked report records");
        assertEquals(DATAGEN_HOOK_LOSSES, datagen.size(), "of which unreachable in a player's game (data generators)");
        assertEquals(RUNTIME_HOOK_LOSSES, runtime.size(), "and the part a player can reach");
        assertEquals(TOTAL_HOOK_LOSSES, datagen.size() + runtime.size(), "every row lands in exactly one bucket");
        assertEquals(RUNTIME_OWNERS, runtimeOwners, "the runtime losses are spread across this many classes");
    }

    /**
     * The loss is overwhelmingly MinecraftForge's, which is what the merged base's precedence decision means: the
     * review kept NeoForge's body wherever the two collide, so Forge's hook is the one that goes.
     */
    @Test
    void theLostHooksAreAlmostEntirelyMinecraftForges() throws Exception {
        List<LostHookAttribution.Conflict> rows = LostHookAttribution.conflicts(REPORT);
        long forge = rows.stream().filter(row -> "forge".equals(row.lostFamily())).count();
        long neo = rows.stream().filter(row -> "neo".equals(row.lostFamily())).count();
        assertEquals(1001, forge, "MinecraftForge hooks lost by the merge");
        assertEquals(5, neo, "NeoForge hooks lost — the other side of the same precedence decision");
        assertEquals(rows.size(), forge + neo, "every row names one of the two families");
        assertTrue(forge > rows.size() * 0.99,
                "the whole difficulty of this merge is one family's hooks: Forge=" + forge + " of " + rows.size());
    }

    private static boolean isDatagen(LostHookAttribution.Conflict row) {
        for (String prefix : DATAGEN) if (row.owner().startsWith(prefix)) return true;
        return false;
    }

    private static String percent(int part, int whole) {
        return whole == 0 ? "0" : Integer.toString((int) Math.round(100.0 * part / whole));
    }
}
