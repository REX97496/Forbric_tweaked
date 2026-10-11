package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import net.forbric.api.Ecosystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The redundant-mod suppression: which jars it takes off, and — more importantly — which ones it must not.
 *
 * <h2>The rule that matters most</h2>
 *
 * <p>{@code fabric_api} is the id of Forgified Fabric API AND of the real Fabric API. Suppressing that id outright
 * would switch the Fabric API off on every Fabric-side install: the notice meant to help would break the game, and
 * it would break it silently, because the classpath would simply be missing an API the mods were compiled against.
 * The rule that prevents it — the shared id is only redundant when a Forge-family jar declares it — is asserted
 * here first, before anything about the other three, because it is the one whose mistake is worse than not having
 * the feature at all.
 *
 * <h2>Why the jars are built rather than downloaded</h2>
 *
 * <p>The real artifacts were used to DISCOVER the ids (two of the four differ from their project names), but a test
 * that needs four jars from Modrinth is a test that skips on most machines, and a skipping test proves nothing
 * about the rule. These jars are written here with the manifests the real ones carry, so the parse and the
 * selection are exercised everywhere.
 *
 * <p>Deliberately not JUnit's {@code @TempDir}: on this platform that machinery leaves undeletable directories and
 * costs a known block of failures. The tree is made and removed by this class instead.
 */
class RedundantModsTest {

    private Path mods;

    @BeforeEach
    void makeModsDir() throws IOException {
        RedundantMods.forget();
        mods = Path.of("build", "redundant-mods-test", String.valueOf(System.nanoTime()));
        Files.createDirectories(mods);
    }

    @AfterEach
    void removeModsDir() {
        // Best effort, and never a failure: a directory this test made must not be able to fail a suite on a
        // platform whose handles are released late.
        try (var walk = Files.walk(mods)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) {
        }
    }

    // --- the rule whose mistake is worse than the feature being absent -------------------------------

    @Test
    void theRealFabricApiSurvivesWhileTheForgedTranslationIsSuppressed() throws Exception {
        Path realFabricApi = fabricJar("fabric-api-0.161.0+26.2.jar", "fabric_api");
        Path forged = neoforgeJar("forgified-fabric-api-0.161.0+26.2+4.0.1.jar", "fabric_api");

        Set<Path> suppressed = RedundantMods.suppress(mods, Set.of());

        assertTrue(suppressed.contains(forged),
                "Forgified Fabric API declares fabric_api from a NeoForge manifest and is redundant, but was not"
                        + " suppressed: " + suppressed);
        assertFalse(suppressed.contains(realFabricApi),
                "the REAL Fabric API declares the same id from a Fabric manifest and was suppressed. This would"
                        + " switch Fabric API off on every Fabric install, so mods compiled against it would fail"
                        + " with NoClassDefFoundError and nothing would say why. Suppressed: " + suppressed);
    }

    @Test
    void theFabricApiIdIsOnlyRedundantWhenAForgeFamilyJarDeclaresIt() {
        List<RedundantMods.Redundant> fabricId = RedundantMods.BUILT_IN.stream()
                .filter(rule -> rule.id().equals("fabric_api")).toList();
        assertEquals(1, fabricId.size(), "expected exactly one rule for fabric_api: " + RedundantMods.BUILT_IN);
        assertTrue(fabricId.getFirst().forgedOnly(),
                "the fabric_api rule is not marked forgedOnly, so the real Fabric API would be suppressed with it");
    }

    // --- the ids themselves ---------------------------------------------------------------------------

    /**
     * The three ids that only these mods claim are suppressed from either family. Kilt ships a fabric.mod.json, so
     * a rule that only looked at Forge manifests would miss it entirely.
     */
    @Test
    void theOtherThreeAreSuppressedWhicheverFamilyDeclaresThem() throws Exception {
        Path connector = neoforgeJar("connector-3.0.0-beta.6+26.1.2-full.jar", "connector");
        Path kilt = fabricJar("Kilt-21.1.15.jar", "kilt");
        Path extras = neoforgeJar("ConnectorExtras-2.0.2+26.1.2.jar", "connectorextras");

        Set<Path> suppressed = RedundantMods.suppress(mods, Set.of());

        assertTrue(suppressed.contains(connector), "Sinytra Connector was not suppressed: " + suppressed);
        assertTrue(suppressed.contains(kilt), "Kilt (a FABRIC manifest) was not suppressed: " + suppressed);
        assertTrue(suppressed.contains(extras), "Connector Extras was not suppressed: " + suppressed);
        assertEquals(3, suppressed.size(), "an unrelated jar was suppressed too: " + suppressed);
    }

    /**
     * The ids are pinned to what the published jars actually declare, not to their project names. Two of the four
     * differ, and a list keyed on the slugs would be dead code for exactly the two that look most obvious.
     */
    @Test
    void theIdsAreTheOnesThePublishedJarsDeclareNotTheProjectNames() {
        Map<String, String> actual = new LinkedHashMap<>();
        for (RedundantMods.Redundant rule : RedundantMods.BUILT_IN) actual.put(rule.id(), rule.display());

        assertEquals(Set.of("connector", "kilt", "fabric_api", "connectorextras"), actual.keySet(),
                "the id list changed. Forgified Fabric API's id is fabric_api and Connector Extras' is"
                        + " connectorextras — read out of the published neoforge.mods.toml, and neither matches its"
                        + " Modrinth slug. If an id was 'tidied' back to the project name the rule is now dead code"
                        + " and nothing will ever be suppressed by it. Present: " + actual.keySet());
        assertFalse(actual.containsKey("forgified-fabric-api"),
                "the slug forgified-fabric-api is back in the id list; the jar does not declare it");
        assertFalse(actual.containsKey("connector-extras"),
                "the slug connector-extras is back in the id list; the jar does not declare it");
    }

    // --- manifest parsing ------------------------------------------------------------------------------

    /**
     * A Forge-family manifest lists its dependencies as further {@code modId} entries. Connector Extras declares
     * {@code neoforge} that way, and reading every {@code modId} would suppress a jar for depending on the loader.
     */
    @Test
    void aDependencyIdIsNotMistakenForTheModsOwnId() {
        String toml = """
                modLoader="javafml"
                loaderVersion="[1,)"

                [[mods]]
                modId="connectorextras"
                version="2.0.2"

                [[dependencies.connectorextras]]
                    modId="neoforge"
                    mandatory=true

                [[dependencies.connectorextras]]
                    modId="minecraft"
                    mandatory=true
                """;
        assertEquals("connectorextras", RedundantMods.firstOwnModId(toml),
                "the parser read a dependency id instead of the mod's own, so a jar could be suppressed for naming"
                        + " the loader it runs on");
    }

    @Test
    void aFabricManifestIdIsReadFromTheTopLevelKey() {
        assertEquals("kilt", RedundantMods.jsonId("""
                {"schemaVersion":1,"id":"kilt","version":"21.1.15","depends":{"fabricloader":">=0.14"}}"""),
                "a fabric.mod.json id was not read");
    }

    @Test
    void everyDeclaredIdIsReadWithItsEcosystem() throws Exception {
        Path jar = mods.resolve("both.jar");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar))) {
            entry(out, "fabric.mod.json", "{\"id\":\"kilt\",\"version\":\"1\"}");
            entry(out, "META-INF/neoforge.mods.toml", "[[mods]]\nmodId=\"connector\"\n");
        }
        List<RedundantMods.Declaration> declared = RedundantMods.declaredBy(jar);
        assertEquals(2, declared.size(), "expected one declaration per manifest: " + declared);
        assertTrue(declared.contains(new RedundantMods.Declaration("kilt", Ecosystem.FABRIC)), "got " + declared);
        assertTrue(declared.contains(new RedundantMods.Declaration("connector", Ecosystem.NEOFORGE)),
                "got " + declared);
    }

    @Test
    void anUnreadableJarSuppressesNothingRatherThanEverything() throws Exception {
        Path broken = mods.resolve("broken.jar");
        Files.write(broken, "this is not a zip".getBytes(StandardCharsets.UTF_8));
        assertEquals(List.of(), RedundantMods.declaredBy(broken),
                "a jar that cannot be read reported declarations, so a corrupt file could suppress a real mod");
        assertEquals(Set.of(), RedundantMods.suppress(mods, Set.of()),
                "an unreadable jar on its own caused a suppression");
    }

    // --- switches and reporting ------------------------------------------------------------------------

    @Test
    void theSwitchTurnsItOffEntirely() throws Exception {
        fabricJar("Kilt-21.1.15.jar", "kilt");
        System.setProperty(RedundantMods.SWITCH, "off");
        try {
            assertEquals(Set.of(), RedundantMods.suppress(mods, Set.of()),
                    "the off switch did not stop the suppression");
        } finally {
            System.clearProperty(RedundantMods.SWITCH);
        }
    }

    @Test
    void keepReportsWithoutSuppressingSoTheStackCanBeTestedInPlace() throws Exception {
        fabricJar("Kilt-21.1.15.jar", "kilt");
        System.setProperty(RedundantMods.SWITCH, "keep");
        try {
            assertEquals(Set.of(), RedundantMods.suppress(mods, Set.of()),
                    "-Dforbric.redundantMods=keep must NOT take the jar off the classpath");
            assertFalse(RedundantMods.suppressedThisBoot().isEmpty(),
                    "keep mode suppressed nothing but also reported nothing, so the player would never learn the"
                            + " mod is redundant — which is the whole point of keep");
        } finally {
            System.clearProperty(RedundantMods.SWITCH);
        }
    }

    @Test
    void whatWasSuppressedIsRecordedForTheLoadReportWithItsReason() throws Exception {
        neoforgeJar("connector-3.0.0-beta.6+26.1.2-full.jar", "connector");
        Set<Path> suppressed = RedundantMods.suppress(mods, Set.of());
        assertEquals(1, suppressed.size(), "expected one suppression: " + suppressed);
        List<String> notices = RedundantMods.suppressedThisBoot();
        assertEquals(1, notices.size(), "the load report would have nothing to show: " + notices);
        String notice = notices.getFirst();
        assertTrue(notice.contains("Sinytra Connector"),
                "the notice does not name the mod in the form a player recognises: " + notice);
        assertTrue(notice.contains("信雅互联"),
                "the notice does not carry the Chinese name the mod is known by in the community: " + notice);
        assertTrue(notice.contains("connector") && notice.length() > 40,
                "the notice does not say which id or why: " + notice);
    }

    /** A jar the player already switched off is left alone, so the two lists do not fight over the same file. */
    @Test
    void aJarThePlayerAlreadySwitchedOffIsNotCountedTwice() throws Exception {
        Path kilt = fabricJar("Kilt-21.1.15.jar", "kilt");
        Set<Path> suppressed = RedundantMods.suppress(mods, Set.of(kilt));
        assertEquals(Set.of(), suppressed, "a jar on the player's own list was suppressed again: " + suppressed);
    }

    // --- helpers ---------------------------------------------------------------------------------------

    private Path fabricJar(String name, String id) throws IOException {
        Path jar = mods.resolve(name);
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar))) {
            entry(out, "fabric.mod.json", "{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"1\"}");
        }
        return jar;
    }

    private Path neoforgeJar(String name, String id) throws IOException {
        Path jar = mods.resolve(name);
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar))) {
            entry(out, "META-INF/neoforge.mods.toml", "modLoader=\"javafml\"\n\n[[mods]]\nmodId=\"" + id + "\"\n");
        }
        return jar;
    }

    private static void entry(ZipOutputStream out, String name, String body) throws IOException {
        out.putNextEntry(new ZipEntry(name));
        out.write(body.getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
    }
}
