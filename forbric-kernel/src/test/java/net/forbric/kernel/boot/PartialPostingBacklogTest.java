package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * The partially-posted backlog, pinned — the events the merged base still delivers, but on only some of the paths
 * that used to deliver them.
 *
 * <h2>Why a separate list, and why it needs pinning even more than the dead one</h2>
 *
 * <p>A dead event is easy to describe and easy to notice: nothing arrives. A PARTIALLY posted event is the harder
 * failure and the reason this table exists — the mod works, so neither the author nor the player has a reason to
 * look. It fires for horses and not for players, for saplings and not for the rest, on one of the four ways an
 * item breaks. The row's value is entirely in its NUMBERS: "2 of its 3 paths" is the sentence that tells a mod
 * author where to look, and a base rebuilt against new inputs changes those numbers without changing the event.
 *
 * <p>Nothing pinned them. {@link HookCallSiteCensusStagedTest} reads the table, but only where the staged base is
 * present, so on a machine without it the figures go unchecked, and a census that has learned to see a new call
 * site is exactly the thing that will change them. A stale fraction is worse than no fraction: it sends a mod
 * author to the wrong half of their problem.
 *
 * <p>So both halves are asserted — the row set and the fraction in each sentence — and the structural claim behind
 * the word "partial" is checked rather than assumed: a row whose fraction is not strictly less than its total does
 * not belong in this table at all.
 */
class PartialPostingBacklogTest {

    /** Each row, with the fraction its sentence states. The numbers are the diagnostic; the wording carries them. */
    private static final Map<String, Fraction> EXPECTED = Map.ofEntries(
            Map.entry("net/minecraftforge/event/entity/living/LivingConversionEvent$Post", new Fraction(3, 7)),
            Map.entry("net/minecraftforge/event/entity/player/PlayerDestroyItemEvent", new Fraction(1, 4)),
            Map.entry("net/minecraftforge/event/level/BlockEvent$NeighborNotifyEvent", new Fraction(1, 4)),
            Map.entry("net/minecraftforge/event/level/BlockFeatureGrowEvent", new Fraction(1, 3)),
            Map.entry("net/minecraftforge/event/entity/living/LivingFallEvent", new Fraction(2, 3)),
            Map.entry("net/minecraftforge/event/entity/living/MobEffectEvent$Applicable", new Fraction(1, 2)),
            Map.entry("net/minecraftforge/event/level/BlockEvent$EntityPlaceEvent", new Fraction(1, 2)));

    private static final int BACKLOG_SIZE = 7;

    /**
     * The shipped phrasings: "on 3 of its 7 paths", "1 of the 4 paths that used to report them", "on 2 of its 3
     * paths". The article varies with the sentence, so it is matched rather than assumed.
     */
    private static final Pattern FRACTION = Pattern.compile("(\\d+)\\s+of\\s+(?:its\\s+|the\\s+)?(\\d+)\\s+paths?");

    @Test
    void thePartiallyPostedSetIsTheNamedRowsAndEachStatesItsFraction() {
        Map<String, String> partial = DeadEventAudit.PARTIAL;
        assertEquals(BACKLOG_SIZE, partial.size(),
                "the partially-posted backlog changed size. A row here says an event arrives on SOME paths; losing "
                        + "a path moves an event into this table or out of it, and either way a mod author's "
                        + "diagnosis changed. Current rows: " + new java.util.TreeSet<>(partial.keySet()));

        Set<String> missing = new LinkedHashSet<>(EXPECTED.keySet());
        missing.removeAll(partial.keySet());
        Set<String> unrecorded = new LinkedHashSet<>(partial.keySet());
        unrecorded.removeAll(EXPECTED.keySet());
        assertEquals(Set.of(), missing, "these events are no longer reported as partially posted");
        assertEquals(Set.of(), unrecorded, "these events are now reported as partially posted but are not in this test");

        for (Map.Entry<String, Fraction> row : EXPECTED.entrySet()) {
            String cost = partial.get(row.getKey());
            Fraction actual = Fraction.of(cost);
            assertEquals(row.getValue(), actual,
                    "the fraction a mod author is told changed for " + row.getKey() + " — it was \""
                            + row.getValue() + "\" and the sentence now says \"" + actual + "\": " + cost);
        }
    }

    /**
     * The word in the table's name, checked: an event on every path is not partially posted, and one on none is
     * dead. A row that fails this is in the wrong table, and {@code audit} would accuse a mod that is working.
     */
    @Test
    void everyRowIsStrictlyBetweenNoneAndAllOfItsPaths() {
        for (Map.Entry<String, String> row : DeadEventAudit.PARTIAL.entrySet()) {
            Fraction fraction = Fraction.of(row.getValue());
            assertTrue(fraction.total() > 1,
                    "a single path cannot be partial — that is an all-or-nothing event: " + row.getKey());
            assertTrue(fraction.posted() > 0,
                    "an event posted on no path is dead, not partial, and belongs in DeadEventAudit.DEAD: "
                            + row.getKey());
            assertTrue(fraction.posted() < fraction.total(),
                    "an event posted on every path is delivered and should not be reported at all: " + row.getKey()
                            + " -> " + fraction);
        }
    }

    /** Every row must explain the practical consequence, not just the arithmetic. */
    @Test
    void everyRowSaysWhatAModWillDoForSomeThingsOnly() {
        for (Map.Entry<String, String> row : DeadEventAudit.PARTIAL.entrySet()) {
            String cost = row.getValue();
            assertFalse(cost.isBlank(), "a partial event with no stated consequence: " + row.getKey());
            assertTrue(cost.length() > 40,
                    "the consequence is too short to be useful (" + cost.length() + " chars): " + row.getKey());
            assertFalse(cost.contains("/"),
                    "the consequence spells the event as an internal name instead of describing it: " + row.getKey());
        }
    }

    /** How many of how many paths still post the event. */
    record Fraction(int posted, int total) {
        static Fraction of(String sentence) {
            Matcher matcher = FRACTION.matcher(sentence);
            assertTrue(matcher.find(), "a partial row must state how many of how many paths still post it, so a mod "
                    + "author can tell what is missing: " + sentence);
            return new Fraction(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
        }

        @Override public String toString() {
            return posted + " of " + total;
        }
    }
}
