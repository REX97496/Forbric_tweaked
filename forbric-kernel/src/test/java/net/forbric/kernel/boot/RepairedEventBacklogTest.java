package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * The repaired-event set, pinned — the events a merged-base REPAIR delivers, which no bridge covers.
 *
 * <h2>Why this list needs its own test rather than riding on {@link DeadEventAuditTest}</h2>
 *
 * <p>This is the third of the three tables that decide what a mod is told about, and the only one that existed to
 * make a report go AWAY. Every entry here suppresses a finding: {@code audit} consults it before {@code DEAD}, so
 * while an event sits in this set a subscribed mod is not named. That makes it the one table where a mistake
 * removes information rather than adds it, and it is the table nothing pinned — the two others were given snapshot
 * tests; this one was only ever read incidentally by a staged test that skips without the staged base.
 *
 * <p>So the entries and the SUPPRESSION are both asserted. A row added here without a repair behind it silently
 * un-reports a broken event, which is the failure this test exists to catch: the row's justification is the
 * injector named in the comment beside it, and that injector is what has to keep existing.
 */
class RepairedEventBacklogTest {

    /** Each repaired event, with the injector whose repair is the only reason it may suppress a finding. */
    private static final Map<String, String> EXPECTED = Map.of(
            "net/neoforged/neoforge/event/furnace/FurnaceFuelBurnTimeEvent",
            "FurnaceTickCallsInjector (fuel burn time)",
            "net/minecraftforge/event/entity/living/MobSpawnEvent$FinalizeSpawn",
            "NativeCoremodParity / KernelFinalizeSpawn (the spawner's call and NeoForge's coremod redirects)",
            "net/minecraftforge/event/AddPackFindersEvent",
            "the pack-finder repair",
            "net/minecraftforge/event/entity/living/LivingHurtEvent",
            "ForgeDamageSeamsInjector (no NeoForge event sits where these were)",
            "net/minecraftforge/event/entity/living/LivingDamageEvent",
            "ForgeDamageSeamsInjector (no NeoForge event sits where these were)");

    @Test
    void theRepairedSetIsTheNamedEventsAndNothingElse() {
        Set<String> repaired = DeadEventAudit.REPAIRED;
        assertEquals(EXPECTED.size(), repaired.size(),
                "the repaired-event set changed size. An entry here SUPPRESSES a finding rather than adding one, "
                        + "so a new row needs the repair behind it named and this test updated: " + repaired);

        Set<String> missing = new LinkedHashSet<>(EXPECTED.keySet());
        missing.removeAll(repaired);
        Set<String> unrecorded = new LinkedHashSet<>(repaired);
        unrecorded.removeAll(EXPECTED.keySet());
        assertEquals(Set.of(), missing, "these events are no longer treated as repaired");
        assertEquals(Set.of(), unrecorded, "these events are suppressed now but are not in this test");
    }

    /**
     * The suppression itself, which is the whole point of the table: a listener on a repaired event is not a
     * finding, and it is not one even where the event ALSO sits in {@code DEAD} — {@code audit} reads this set
     * first, so a row that is both repaired and listed- dead must still report nothing.
     */
    @Test
    void aRepairedEventIsNeverReportedEvenWhenItIsAlsoListedAsDead() {
        for (String event : DeadEventAudit.REPAIRED) {
            assertTrue(DeadEventAudit.audit(Map.of("mod", Set.of(event)), Set.of()).isEmpty(),
                    "a repaired event was reported to a subscribed mod: " + event);
        }
        // The overlap is a real state (the tables are written for different readers), so assert the precedence on
        // an event that is in both, rather than asserting the sets are disjoint.
        Set<String> both = new LinkedHashSet<>(DeadEventAudit.REPAIRED);
        both.retainAll(DeadEventAudit.DEAD.keySet());
        for (String event : both) {
            assertTrue(DeadEventAudit.audit(Map.of("mod", Set.of(event)), Set.of()).isEmpty(),
                    "an event that is both repaired and listed dead was still reported: " + event);
        }
    }

    /** Every row has to be a real internal event name, since a typo here would suppress nothing and look fine. */
    @Test
    void everyRowIsAnInternalNameThatLooksLikeAnEvent() {
        for (String event : DeadEventAudit.REPAIRED) {
            assertTrue(event.contains("/"), "a repaired row must be an internal name: " + event);
            assertTrue(event.endsWith("Event") || event.contains("Event$"),
                    "a repaired row should name an event class: " + event);
            assertTrue(EXPECTED.containsKey(event),
                    "a repaired row with no repair named beside it — the comment justifying it is the only thing "
                            + "that makes suppressing a finding safe: " + event);
        }
    }
}
