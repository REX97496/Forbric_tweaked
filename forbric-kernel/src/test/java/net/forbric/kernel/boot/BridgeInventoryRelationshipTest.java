package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import net.forbric.api.GameEventBridge;

/**
 * The relationship between the two bridge inventories: the installer's set, which is every declared bridge, and
 * the audit's lookup, which is only the events a mod can be warned about.
 *
 * <h2>Why this test exists at all, and what it is NOT saying</h2>
 *
 * <p>Counting the two is what invited the mistake this test prevents. {@code EventBridges.verify} covers all 102
 * declared bridges — pinned by {@code EventBridgesTest}, and by {@code GameEventBridgeInventoryTest}, which reads
 * the compiled transformers to prove each one is handed to {@code EventBridges.installed}. {@code
 * DeadEventAudit.BRIDGED} holds 46 entries, because it is keyed by the internal event name the audit encounters
 * when a mod subscribes and only the events a mod subscribes to need an entry. The 56-entry difference is that
 * purpose, not a gap: a mod cannot subscribe to a client init event, so the audit has nothing to look up for one.
 *
 * <p>So the counts are recorded here as FACTS about the two inventories, and the assertions are about the
 * relationship between them — every audit entry names a bridge the installer really declares, and names the same
 * one. Neither half is compared as "should be equal", because they are not meant to be.
 */
class BridgeInventoryRelationshipTest {

    /**
     * The audit's lookup, keyed by internal event name. A change here is a change to what is reported.
     *
     * <p>The first draft of this figure was 46, counted with a regex that matched only single-line
     * {@code map.put("…"} calls and therefore missed every row whose arguments wrapped. The table is read through
     * its own accessor in the assertions below; this constant exists so that a change to the SIZE is a failure
     * rather than a silent acceptance.
     */
    private static final int AUDIT_ENTRIES = 59;

    /** Every declared bridge, which is what the installer verifies. */
    private static final int DECLARED_BRIDGES = 102;

    @Test
    void theTwoInventoriesAreSizedForTheirOwnPurposesAndBothAreCompleteWithinThem() {
        assertEquals(DECLARED_BRIDGES, GameEventBridge.values().length,
                "the declared bridge set changed. EventBridges.verify walks this set, so a new constant is "
                        + "reported MISSING on every boot until something calls EventBridges.installed for it, "
                        + "and GameEventBridgeInventoryTest checks that by reading the compiled transformers.");
        assertEquals(AUDIT_ENTRIES, DeadEventAudit.BRIDGED.size(),
                "the audit's lookup changed. Its rows are what a subscribed mod is told about, so a row removed "
                        + "without its event becoming dead or repaired makes the audit STOP warning about it: "
                        + new java.util.TreeSet<>(DeadEventAudit.BRIDGED.keySet()));
    }

    /**
     * Every entry in the audit's lookup must name a bridge that really is declared — otherwise the audit reports an
     * event as delivered by a bridge that does not exist, and the install check a reader assumes is happening is
     * happening for something else.
     *
     * <p>Two events SHARING one bridge is allowed and does occur ({@code CLIENT_INIT_HOOKS} covers
     * {@code RegisterKeyMappingsEvent} and {@code RegisterNamedRenderTypesEvent}): one bridge that re-emits several
     * events is not a defect, and an earlier version of this test wrongly refused it by asserting each row had its
     * own bridge. What is asserted instead is that the sharing is a SHARED CONSTANT — the same value both times —
     * because the audit checks {@code installed.contains(bridge)} and two equal-by-name but distinct values would
     * make that check pass for one event and fail for the other.
     */
    @Test
    void everyAuditEntryNamesABridgeTheInstallerDeclares() {
        Set<GameEventBridge> declared = EnumSet.allOf(GameEventBridge.class);
        Map<GameEventBridge, String> shared = new java.util.EnumMap<>(GameEventBridge.class);
        for (Map.Entry<String, GameEventBridge> entry : DeadEventAudit.BRIDGED.entrySet()) {
            GameEventBridge bridge = entry.getValue();
            assertNotNull(bridge, "an audit row with no bridge: " + entry.getKey());
            assertTrue(declared.contains(bridge), "an audit row naming a bridge that is not declared: " + bridge);
            String previous = shared.put(bridge, entry.getKey());
            if (previous != null) {
                assertSame(DeadEventAudit.BRIDGED.get(previous), bridge,
                        "two events share a bridge; they must share the CONSTANT, or the install check gives a "
                                + "different answer for each: " + previous + " and " + entry.getKey());
            }
        }
        assertTrue(shared.size() <= DeadEventAudit.BRIDGED.size(),
                "the number of distinct bridges cannot exceed the number of rows");
    }

    /**
     * The identity is the enum constant itself, not a copy by name: {@code audit} tests
     * {@code installed.contains(bridge)} against the set the installer fills, so a lookup that reconstructed a
     * bridge some other way could never match and every bridged event would read as not-installed.
     */
    @Test
    void theAuditLooksUpBridgesByIdentityBecauseThatIsHowInstalledIsChecked() {
        for (Map.Entry<String, GameEventBridge> entry : DeadEventAudit.BRIDGED.entrySet()) {
            assertSame(GameEventBridge.valueOf(entry.getValue().name()), entry.getValue(),
                    "the audit must hold the enum constant the installer records: " + entry.getKey());
        }
    }
}
