package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import net.forbric.api.EventBridges;
import net.forbric.api.GameEventBridge;

import org.junit.jupiter.api.Test;

/**
 * No pass may be declared without something comparing what it achieved against what it declared.
 *
 * <h2>The gap this closes</h2>
 *
 * <p>{@link EventBridges#verify} reports a missing bridge loud and in full, naming the cost. Five of the seven
 * passes had a call site for it. {@code CLIENT_HUD} and {@code ON_DEMAND} had none, and the two bridges declared in
 * them were never installed anywhere either:
 *
 * <ul>
 *   <li>{@code GUI_OVERLAY_LAYERS} — a MinecraftForge mod's HUD overlay layers never draw.
 *   <li>{@code ITEM_TOOLTIP} — a NeoForge mod that adds an item tooltip line adds it to nothing.
 * </ul>
 *
 * <p>Declared, describable, never installed, never compared: absolutely silent. The mechanism was correct and its
 * coverage was not, which is worse than having no mechanism, because it looks finished.
 *
 * <p>Found by reading a video that criticises this project for exactly this — "103 kinds of Forge event and one
 * NeoForge event are never triggered in any form" — and then looking for how the kernel would know.
 */
class EventBridgeCoverageTest {

    /** A call to the all-pass sweep, which is what makes "every pass is verified" true rather than intended. */
    @Test
    void everyPassIsVerifiedBecauseTheAllPassSweepIsCalled() throws IOException {
        List<String> callSites = new ArrayList<>();
        Path root = Path.of("src", "main", "java");
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    // Counted as a call, never as the declaration: the sweep's own body loops over the enum and
                    // calls verify per pass, so a match inside EventBridges.java would be the definition of the
                    // coverage rather than evidence that anything runs it.
                    if (lines.get(i).contains("EventBridges.verifyAll()")
                            && !file.getFileName().toString().equals("EventBridges.java")) {
                        callSites.add(file.getFileName() + ":" + (i + 1));
                    }
                }
            }
        }
        assertFalse(callSites.isEmpty(),
                "EventBridges.verifyAll() has NO call site in src/main, so the passes without their own verify()"
                        + " — CLIENT_HUD and ON_DEMAND — are unverified again and their bridges can go missing in"
                        + " silence. A per-pass call site is not a substitute: that is the arrangement that left"
                        + " two passes uncovered.");
    }

    /** And the sweep actually reports: with nothing installed, it must say so rather than answer true. */
    @Test
    void theSweepFailsWhenTheBridgesAreNotInstalled() {
        EventBridges.reset();
        assertFalse(EventBridges.verifyAll(),
                "with no bridge installed, verifyAll() answered true — it is not comparing anything, so a missing"
                        + " bridge would be reported as present");
    }

    /** Every pass the sweep has to reach. Pinned so a new Pass value cannot slip in unverified. */
    @Test
    void theSweepCoversEveryDeclaredPass() {
        List<String> passes = new ArrayList<>();
        for (GameEventBridge.Pass pass : GameEventBridge.Pass.values()) passes.add(pass.name());
        for (String expected : List.of("GAME_BUS", "CLIENT_GAME_BUS", "CLIENT_INIT", "CLIENT_MOD_BUS",
                "REGISTRATION", "CLIENT_HUD", "ON_DEMAND")) {
            assertTrue(passes.contains(expected),
                    expected + " is gone from GameEventBridge.Pass, so whatever verified it no longer exists."
                            + " Present: " + passes);
        }
        assertTrue(passes.size() >= 7, "passes dropped to " + passes.size() + ": " + passes);
    }

    /** The two bridges whose passes had no verify call site. Named so removing them is a decision. */
    @Test
    void theTwoBridgesTheUnverifiedPassesCarriedStillExist() {
        List<String> names = new ArrayList<>();
        for (GameEventBridge bridge : GameEventBridge.values()) names.add(bridge.name());
        assertTrue(names.contains("GUI_OVERLAY_LAYERS"), "Present: " + names);
        assertTrue(names.contains("ITEM_TOOLTIP"), "Present: " + names);
    }
}
