package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * The tables that decide what a mod is told are built by {@code put} into a map, so a duplicated event name
 * OVERWRITES: the first row and its consequence sentence disappear without a word, and the only symptom is that a
 * mod is told less than the table's author meant. This demonstrates the hazard and pins the guard that closes it.
 *
 * <h2>Why a duplicate is worth this much attention</h2>
 *
 * <p>These rows are hand-written internal names, two of them differing only by a nested class
 * ({@code BlockEvent$BreakEvent} against {@code BlockEvent$CreateFluidSourceEvent}), and they are edited by
 * adding a line in the middle of a long list. A copy-paste that keeps a name and changes only the sentence has no
 * visible effect at all — the map is still valid, the suite is still green, and the clause the author intended to
 * ADD was dropped. {@code REPAIRED} cannot have this problem because {@code Set.of} throws on a repeat; the two
 * maps could, and did not.
 */
class DeadEventTableIntegrityTest {

    /**
     * The hazard itself, on the shape the production code uses, so the guard below has something to be a guard
     * against: a repeated key leaves one entry, not two.
     */
    @Test
    void aMapBuiltByPutLosesTheEarlierRowWhenAnEventNameRepeats() {
        Map<String, String> table = new LinkedHashMap<>();
        table.put("net/minecraftforge/event/ServerChatEvent", "chat cannot be seen, edited or blocked");
        table.put("net/minecraftforge/event/ServerChatEvent", "a second sentence the author meant to add");
        Map<String, String> built = Map.copyOf(table);

        assertEquals(1, built.size(), "a repeated key collapses to one entry");
        assertEquals("a second sentence the author meant to add", built.get("net/minecraftforge/event/ServerChatEvent"),
                "and it is the LATER row that wins, so the first row's consequence is what disappears");
    }

    /**
     * The guard: the same repeated key now fails at the point of construction, naming the event, instead of
     * shrinking the table in silence.
     */
    @Test
    void theGuardRefusesARepeatedEventNameAndNamesIt() {
        Map<String, String> table = new LinkedHashMap<>();
        DeadEventAudit.putOnce("a/First", "one", table);
        IllegalStateException repeated = assertThrows(IllegalStateException.class,
                () -> DeadEventAudit.putOnce("a/First", "two", table));
        assertTrue(repeated.getMessage().contains("a/First"),
                "the failure must name the event, or the author sees only that a table lost a row: "
                        + repeated.getMessage());
        assertEquals("one", table.get("a/First"), "the first row is left as it was");
    }

    @Test
    void theGuardAcceptsDistinctEventNames() {
        Map<String, String> table = new LinkedHashMap<>();
        DeadEventAudit.putOnce("a/First", "one", table);
        DeadEventAudit.putOnce("a/Second", "two", table);
        assertEquals(2, table.size());
        assertEquals("one", table.get("a/First"));
        assertEquals("two", table.get("a/Second"));
    }

    /**
     * And the production tables really do contain one row per event today — the guard would have failed at class
     * initialisation otherwise, so this asserts the state the guard now protects rather than the guard itself.
     */
    @Test
    void bothTablesHoldExactlyTheRowsTheyList() {
        assertEquals(10, DeadEventAudit.DEAD.size(), "the dead table's rows: " + DeadEventAudit.DEAD.keySet());
        assertEquals(7, DeadEventAudit.PARTIAL.size(), "the partial table's rows: " + DeadEventAudit.PARTIAL.keySet());
    }
}
