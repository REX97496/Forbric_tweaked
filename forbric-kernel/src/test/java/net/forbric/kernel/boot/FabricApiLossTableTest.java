package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * The fabric-api loss table's rows, pinned and checked for what a duplicated row would do to the report.
 *
 * <h2>Why this table needs the same treatment as the dead-event tables</h2>
 *
 * <p>{@code FabricApiModuleLossAudit} accumulates into {@code Map<Loss, Set<String>>}, and {@code Loss} is a
 * record — so two rows that agree on every component are ONE key, and the jars that named either of them are
 * merged into a single row of the report. The table is a {@code List.of}, which refuses nothing: unlike the
 * repaired-event set, which is a {@code Set.of} and would throw, a repeated row here is accepted and the report
 * silently loses a line.
 *
 * <p>Worth naming because the rows legitimately repeat their {@code module} (three rows are
 * {@code fabric-model-loading-api-v1}) and two of them are a package PREFIX and a CLASS inside that package —
 * the shape a copy-paste edit produces. The rows the report shows are keyed by the whole record, so the check is
 * on the whole record's visible components, not on the module.
 *
 * <p>The {@code stillLost} supplier is deliberately excluded from the check. It is a lambda, and lambda identity
 * is not something this test should reason about: two rows differing ONLY in a supplier would still print as the
 * same row to a player, which is the defect, so they are compared as equal here and flagged.
 */
class FabricApiLossTableTest {

    private static final int ROWS = 6;

    @Test
    void everyRowIsDistinctInWhatTheReportWouldShow() {
        var losses = FabricApiModuleLossAudit.LOSSES;
        assertEquals(ROWS, losses.size(), "the loss table changed size: " + losses.size() + " rows");

        Map<String, Integer> seen = new LinkedHashMap<>();
        for (var loss : losses) {
            String shown = loss.module() + " | " + loss.needle() + " | " + loss.side() + " | " + loss.cost();
            Integer previous = seen.put(shown, seen.getOrDefault(shown, 0) + 1);
            assertTrue(previous == null,
                    "two rows are identical in everything a report shows, so they are ONE key in the audit's "
                            + "Map<Loss, ...> and the jars that named either are merged into one line: " + shown);
        }
        assertEquals(losses.size(), seen.size(), "every row is its own line of the report");
    }

    /** Every row must state what a mod author loses, in words a report can print. */
    @Test
    void everyRowStatesWhatIsLostAndWhere() {
        for (var loss : FabricApiModuleLossAudit.LOSSES) {
            assertTrue(loss.module() != null && !loss.module().isBlank(), "a row with no module: " + loss.needle());
            assertTrue(loss.needle() != null && !loss.needle().isBlank(), "a row with no needle: " + loss.module());
            assertTrue(loss.cost() != null && loss.cost().length() > 20,
                    "the consequence is too short to tell a mod author anything: " + loss.needle());
            assertTrue(loss.stillLost() != null,
                    "a row with no switch can never stop being reported, which is how a repaired loss keeps "
                            + "accusing mods: " + loss.needle());
        }
    }

    /**
     * A needle that is a prefix of another needle is legitimate and must stay so — one row judges a whole package,
     * the other a class within it, and {@code ByteScan.containsWhich} reports each independently. Asserted so that
     * a future "clean-up" that merges them has to delete this test rather than quietly narrow the audit.
     */
    @Test
    void aPackageRowAndAClassRowInsideItAreBothKeptAndReportedSeparately() {
        String packageRow = "net/fabricmc/fabric/api/client/model/loading/v1/";
        Set<String> needles = new LinkedHashSet<>();
        for (var loss : FabricApiModuleLossAudit.LOSSES) needles.add(loss.needle());
        assertTrue(needles.contains(packageRow), "the package row was removed: " + needles);

        long inside = FabricApiModuleLossAudit.LOSSES.stream()
                .filter(l -> !l.needle().equals(packageRow) && l.needle().startsWith(packageRow)).count();
        assertEquals(1, inside, "exactly one class row sits inside the package row, and both are reportable");
    }
}
