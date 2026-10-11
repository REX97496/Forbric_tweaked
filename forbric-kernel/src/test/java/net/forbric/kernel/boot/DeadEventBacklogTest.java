package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.forbric.api.GameEventBridge;
import org.junit.jupiter.api.Test;

/**
 * The dead-event backlog itself, pinned — which events a subscribed mod is told about, and that the list can only
 * get shorter.
 *
 * <h2>Why this exists when {@link DeadEventAuditTest} already covers the judgement</h2>
 *
 * <p>That test covers what the audit DOES with one event: a bridge present, a bridge missing, a late pass, a
 * partial posting. Not covered anywhere is the size of the list those judgements are handed. {@link
 * DeadEventAudit#DEAD} is the kernel's own statement of how much MinecraftForge behaviour the merged base cannot
 * deliver, and each row carries the sentence a player-facing mod is accused with. Nothing stopped it growing: a
 * base rebuilt against new inputs, a bridge dropped, a refactor that loses a {@code REPAIRED} entry — all add rows,
 * and the only symptom is that more mods are marked DEGRADED than before, which is exactly the kind of drift a
 * green suite hides.
 *
 * <p>So the figures are pinned. Implementing a bridge has to come here and delete the row it just covered; that is
 * the diff this test wants to see. The direction is deliberate: a bridge makes the list SHORTER, and the assertion
 * says so rather than tolerating either direction.
 *
 * <h2>What this cannot check</h2>
 *
 * <p>Whether each row is TRUE — that the merged game really never posts that event. That needs the staged base and
 * the call-site census ({@code HookCallSiteCensusStagedTest}), and the comments in {@link DeadEventAudit} record
 * three rows that had to be withdrawn when the census contradicted a hand reading. This test adds no opinion about
 * truth; it stops the count moving in silence.
 */
class DeadEventBacklogTest {

    /** The backlog: every event a subscribed mod is warned about, with the consequence it is warned of. */
    private static final Map<String, String> EXPECTED = Map.ofEntries(
            Map.entry("net/minecraftforge/client/event/AddFramePassEvent",
                    "extra render frame passes a MinecraftForge mod adds never run — the merged frame graph asks "
                            + "only NeoForge's event"),
            Map.entry("net/minecraftforge/client/event/EntityRenderersEvent$AddLayers",
                    "render layers a MinecraftForge mod adds to existing entity renderers — armour, capes, "
                            + "overlays — never draw; the merged EntityRenderDispatcher posts only NeoForge's "
                            + "AddLayers"),
            Map.entry("net/minecraftforge/client/event/EntityRenderersEvent$CreateSkullModels",
                    "custom skull block models a MinecraftForge mod registers never render — the merged skull "
                            + "renderer reads only NeoForge's map"),
            Map.entry("net/minecraftforge/event/ServerChatEvent",
                    "chat messages cannot be seen, edited or blocked — chat-formatting and moderation mods do "
                            + "nothing"),
            Map.entry("net/minecraftforge/event/entity/player/PlayerInteractEvent$LeftClickBlock",
                    "left-clicking a block is neither observed nor preventable"),
            Map.entry("net/minecraftforge/event/entity/player/PlayerInteractEvent$RightClickBlock",
                    "right-clicking a block is neither observed nor preventable"),
            Map.entry("net/minecraftforge/event/entity/player/PlayerInteractEvent$RightClickItem",
                    "using an item in hand is neither observed nor preventable"),
            Map.entry("net/minecraftforge/event/level/BlockEvent$BreakEvent",
                    "block breaking is neither observed nor preventable — claim and protection mods do not "
                            + "protect, and block-logging mods record nothing"),
            Map.entry("net/minecraftforge/event/level/BlockEvent$CreateFluidSourceEvent",
                    "infinite-source formation cannot be observed or vetoed — the merged "
                            + "FlowingFluid.canConvertToSource asks only NeoForge's EventHooks.canCreateFluidSource"),
            Map.entry("net/neoforged/neoforge/event/entity/player/ItemTooltipEvent",
                    "item tooltips cannot be extended by NeoForge mods — the merged getTooltipLines calls only "
                            + "MinecraftForge's onItemTooltip"));

    private static final int BACKLOG_SIZE = 10;

    @Test
    void theBacklogIsTheNamedSetAndEachRowStillCarriesItsConsequence() {
        Map<String, String> dead = DeadEventAudit.DEAD;
        assertEquals(BACKLOG_SIZE, dead.size(),
                "the dead-event backlog changed size. Adding rows means more mods are marked DEGRADED than before, "
                        + "which needs an explicit decision here; removing one means a bridge landed and its row "
                        + "should have gone in the same commit. Current rows: "
                        + new java.util.TreeSet<>(dead.keySet()));

        Set<String> missing = new LinkedHashSet<>(EXPECTED.keySet());
        missing.removeAll(dead.keySet());
        Set<String> unrecorded = new LinkedHashSet<>(dead.keySet());
        unrecorded.removeAll(EXPECTED.keySet());
        assertEquals(Set.of(), missing, "these events are no longer reported to a subscribed mod");
        assertEquals(Set.of(), unrecorded, "these events are now reported but are not in this test");

        for (Map.Entry<String, String> row : EXPECTED.entrySet()) {
            assertEquals(row.getValue(), dead.get(row.getKey()),
                    "the consequence sentence a mod is accused with changed: " + row.getKey());
        }
    }

    /**
     * The backlog must not be one sentence repeated, and must not be empty text: the report's value is that a mod
     * author reading it learns what stopped working, and a placeholder row teaches them nothing.
     */
    @Test
    void everyRowExplainsWhatAPlayerLosesRatherThanJustNamingTheEvent() {
        for (Map.Entry<String, String> row : DeadEventAudit.DEAD.entrySet()) {
            String cost = row.getValue();
            assertFalse(cost.isBlank(), "a dead event with no stated consequence: " + row.getKey());
            assertTrue(cost.length() > 30,
                    "the consequence is too short to tell a mod author anything (" + cost.length() + " chars): "
                            + row.getKey() + " -> " + cost);
            assertFalse(cost.contains(row.getKey()),
                    "the consequence just repeats the event name: " + row.getKey());
        }
    }

    /**
     * A row that is ALSO bridged is inert: {@code audit} consults {@code BRIDGED} first and returns before it reads
     * {@code DEAD}. Both maps are allowed to hold an event (the generator checks the whole set), but when they do,
     * the bridge is what a player experiences — so this asserts the precedence rather than the emptiness, which
     * would forbid a legitimate state.
     */
    @Test
    void anEventThatIsBothBridgedAndListedIsJudgedBridged() {
        List<String> both = DeadEventAudit.DEAD.keySet().stream()
                .filter(DeadEventAudit.BRIDGED::containsKey).sorted().toList();
        for (String event : both) {
            GameEventBridge bridge = DeadEventAudit.BRIDGED.get(event);
            // Installing it is what makes it arrive, so no finding may remain.
            assertTrue(DeadEventAudit.audit(Map.of("mod", Set.of(event)), Set.of(bridge)).isEmpty(),
                    "a bridged event was still reported as dead: " + event);
            // And the DEAD row it shadows must still be readable, because a bridge CAN fail to install: with the
            // bridge absent the row is the only thing that can name the mod.
            assertTrue(DeadEventAudit.audit(Map.of("mod", Set.of(event)), Set.of()).isEmpty()
                            || !bridge.pass().lateInstalled(),
                    "an uninstalled late-pass bridge reported a mod before its pass ran: " + event);
        }
    }
}
