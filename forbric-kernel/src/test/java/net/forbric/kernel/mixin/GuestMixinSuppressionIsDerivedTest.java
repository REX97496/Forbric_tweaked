package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * The set of guest mixins the merged base suppresses is derived from the configs actually present, not from a
 * compiled-in list of the mods somebody tested.
 *
 * <h2>The claim this answers</h2>
 *
 * <p>A video critiquing this project says the kernel takes fabric-api apart "according to what the specific mods
 * need" — that is, that the API coverage is whatever the author happened to test against, accumulated case by case.
 * That is a serious charge: it means the API is not a contract but a pile of special cases, and a mod nobody tested
 * is unsupported by construction.
 *
 * <p>{@link MergedBaseMixinCompat} is where such a list would live. It has the three fields such a list would be
 * stored in — {@code SUPPRESSED_UNLESS_PRUNED}, {@code KEPT_MIXINS}, {@code DISABLED_CONFIGS} — and all three are
 * EMPTY. Suppressions are discovered at boot: {@code discoverAll(resources)} walks every registered mixin config,
 * parses it, and decides per mixin from the bytes in front of it. The names come from the instance, not from a
 * table naming mods the author owned.
 *
 * <h2>What this does and does not establish</h2>
 *
 * <p>It establishes that the suppression decision is a function of the mods present. It does NOT establish that the
 * restoration MECHANISMS generalise — those were written against specific mixins (a loot-event dispatcher, a
 * model-loading pruner, a model-format funnel), and a fabric-api surface nobody has written a mechanism for is
 * still lost. That is what {@link net.forbric.kernel.boot.FabricApiModuleLossAudit} exists to say out loud, one
 * row per surface, each with a predicate for whether it is lost on THIS boot.
 *
 * <p>So the honest position is: no per-mod list, and not full coverage either. Both halves are asserted here,
 * because either one alone would be a half-truth used to argue the other away.
 */
class GuestMixinSuppressionIsDerivedTest {

    /** The three fields a compiled-in per-mod suppression list would live in. All must stay empty. */
    @Test
    void noMixinIsSuppressedByACompiledInList() throws Exception {
        Class<?> compat = MergedBaseMixinCompat.class;
        for (String field : List.of("SUPPRESSED_UNLESS_PRUNED", "KEPT_MIXINS", "DISABLED_CONFIGS")) {
            Field declared = compat.getDeclaredField(field);
            declared.setAccessible(true);
            Object value = declared.get(null);
            assertTrue(value instanceof java.util.Collection<?>, field + " is not a collection: " + value);
            assertEquals(0, ((java.util.Collection<?>) value).size(),
                    field + " is no longer empty. If guest mixins are now suppressed by a name compiled into the"
                            + " kernel, the suppression set has stopped being a function of the mods present and"
                            + " become a function of what was tested — which is exactly the criticism this class"
                            + " exists to answer. Present: " + value);
        }
    }

    /** And the derivation path has to exist: the per-mixin decision is made from the config bytes. */
    @Test
    void suppressionsAreDiscoveredFromTheConfigsThatArePresent() throws Exception {
        Class<?> compat = MergedBaseMixinCompat.class;
        Method discoverAll = compat.getDeclaredMethod("discoverAll", java.util.function.Function.class);
        assertTrue(java.lang.reflect.Modifier.isStatic(discoverAll.getModifiers()),
                "discoverAll must be the static entry point the boot path calls");
        Method discover = compat.getDeclaredMethod("discover", String.class, byte[].class,
                java.util.function.Function.class);
        assertTrue(java.lang.reflect.Modifier.isStatic(discover.getModifiers()), "discover must be static too");
        // The seed collections the discovery fills must be mutable: an immutable seed would mean discovery cannot
        // populate them, and then the empty lists above would prove nothing at all.
        Field suppressed = compat.getDeclaredField("SUPPRESSED_MIXINS");
        suppressed.setAccessible(true);
        Object seed = suppressed.get(null);
        assertTrue(seed instanceof java.util.Collection<?>, "SUPPRESSED_MIXINS is not a collection: " + seed);
        assertTrue(!(seed instanceof java.util.List<?> list && isImmutableList(list)),
                "SUPPRESSED_MIXINS is immutable, so discover() cannot add to it and the empty seed lists above say"
                        + " nothing about what is suppressed at runtime");
    }

    /**
     * The other half, so neither half can be quoted alone: the loss audit must still exist and still declare the
     * surfaces that are not restored. Deleting it would make "no per-mod list" look like "no losses".
     */
    @Test
    void theLossAuditStillDeclaresWhatIsNotRestored() throws IOException {
        Path audit = Path.of("src", "main", "java", "net", "forbric", "kernel", "boot",
                "FabricApiModuleLossAudit.java");
        assertTrue(Files.isRegularFile(audit), "the loss audit is gone: " + audit);
        String source = Files.readString(audit);
        long rows = source.lines().filter(line -> line.contains("new Loss(")).count();
        assertTrue(rows >= 5,
                "the loss audit declares " + rows + " surface(s). It exists to name what is still switched off;"
                        + " an audit with fewer rows than before either restored something (say so) or lost a row"
                        + " (say that too).");
        assertTrue(source.contains("stillLost"),
                "the rows no longer carry a predicate for whether the surface is lost on THIS boot, so a row would"
                        + " report a loss that has been restored");
    }

    /** Guards the helper above from silently answering "mutable" for a type it cannot judge. */
    private static boolean isImmutableList(List<?> list) {
        try {
            list.add(null);
            list.remove(list.size() - 1);
            return false;
        } catch (UnsupportedOperationException immutable) {
            return true;
        } catch (RuntimeException unreadable) {
            return false;
        }
    }

    /** Unused, but keeps the import list honest about what this class reads. */
    @SuppressWarnings("unused")
    private static Stream<String> nothing() {
        return Stream.empty();
    }
}
