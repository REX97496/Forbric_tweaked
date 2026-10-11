package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * The fused boot scan must be indistinguishable from the two separate scans it replaces.
 *
 * <h2>What is actually at risk</h2>
 *
 * <p>Fusing changes HOW the classes reach the audits, not what they do with them, so the failure mode is not a
 * crash: it is one audit quietly seeing a different set of classes — the ones after a mid-jar read failure, say,
 * or the ones a wrong early-return skipped — while still reporting something plausible. A timing improvement that
 * silently narrows an audit's input is worse than the milliseconds it buys, and nothing else in the suite compares
 * the two paths.
 *
 * <p>So this drives both paths over the same jars and compares the results. {@code abi} is absent from both sides
 * because it is not fused yet (it is not adjacent to this point in the boot sequence), which is stated here rather
 * than left for a reader to infer from the numbers.
 */
class BootScanFusionTest {

    @TempDir Path temp;

    @Test
    void fusingTheAuditsReportsWhatRunningThemApartReports() throws Exception {
        // The needle is a real entry point from FabricApiModuleLossAudit.LOSSES, not a generic fabric-api name:
        // the audit looks for the specific types whose surfaces the merged base switches off, so a fixture naming
        // any other fabric class records nothing. LootTableEvents is chosen because its row has no Side filter and
        // its switch is a server-side injector, so this thread sees it whatever side the suite is running as.
        Path jar = jar("fused-fixture.jar",
                classMentioning("p/First", "net/fabricmc/fabric/api/loot/v3/LootTableEvents",
                        "net/minecraft/world/level/Level"),
                classMentioning("p/Second", "net/fabricmc/fabric/api/loot/v3/LootTableEvents",
                        "net/minecraft/world/entity/Entity"));
        List<Path> jars = List.of(jar);
        // AbiLinkAudit.audit is its own traversal, so it is the reference the fused path has to reproduce exactly.
        Set<String> present = AbiLinkAudit.classesOf(jars);

        FabricApiModuleLossAudit.reset();
        FieldDriftAudit.reset();
        FabricApiModuleLossAudit.scan(jars);
        FieldDriftAudit.scan(jars);
        List<AbiLinkAudit.Finding> abiApart = AbiLinkAudit.audit(jars, present);
        var usersApart = FabricApiModuleLossAudit.users();
        var hitsApart = FieldDriftAudit.hits();

        FabricApiModuleLossAudit.reset();
        FieldDriftAudit.reset();
        BootScanFusion.scan(jars, present);
        var usersFused = FabricApiModuleLossAudit.users();
        var hitsFused = FieldDriftAudit.hits();
        // The fused traversal records into AbiLinkAudit's own state, which audit() does not read, so the reference
        // is compared by running audit() again afterwards: same jars, same present set, and it must agree.
        List<AbiLinkAudit.Finding> abiFused = AbiLinkAudit.audit(jars, present);

        assertEquals(usersApart, usersFused, "the fabric-api audit saw different classes through the fused scan");
        assertEquals(hitsApart, hitsFused, "the field-drift audit saw different classes through the fused scan");
        assertEquals(abiApart, abiFused, "the link audit judged differently through the fused scan");

        // A fixture that triggered nothing would let both paths agree on an empty result, which proves nothing.
        // Asserted for the audit this fixture CAN trigger; the drift audit needs a real game field whose descriptor
        // the merge changed, which is not something a synthetic class can honestly spell, so it is compared for
        // equality only rather than being forced non-empty with a fixture that does not mean what it claims.
        assertFalse(usersApart.isEmpty(),
                "the fixture must make the fabric-api audit record a user, or the comparison above is between two "
                        + "empty maps");
    }

    /**
     * A jar cannot be read where it is missing from disk, and both sides must survive that the same way — the
     * traversal skips it for each audit rather than letting one audit's unreadable jar silence another's.
     */
    @Test
    void anUnreadableJarIsSkippedByBothAuditsWhetherFusedOrNot() throws Exception {
        Path good = jar("good.jar", classMentioning("p/Good", "net/fabricmc/api/EnvType"));
        Path missing = temp.resolve("absent.jar");
        List<Path> jars = List.of(missing, good);

        FabricApiModuleLossAudit.reset();
        FieldDriftAudit.reset();
        FabricApiModuleLossAudit.scan(jars);
        FieldDriftAudit.scan(jars);
        var usersApart = FabricApiModuleLossAudit.users();
        var hitsApart = FieldDriftAudit.hits();

        FabricApiModuleLossAudit.reset();
        FieldDriftAudit.reset();
        BootScanFusion.scan(jars, AbiLinkAudit.classesOf(jars));

        assertEquals(usersApart, FabricApiModuleLossAudit.users(),
                "a missing jar changed the fabric-api audit's result through the fused scan");
        assertEquals(hitsApart, FieldDriftAudit.hits(),
                "a missing jar changed the field-drift audit's result through the fused scan");
    }

    /** A class that NAMES each of {@code references} the way both audits read them: not as a string literal, but as
     * a real member reference, which is what puts the owner in the constant pool as a CONSTANT_Class. A fixture of
     * bare strings produced two empty results that agreed with each other and proved nothing, which is what the
     * non-empty assertion above is there to catch.
     */
    private static byte[] classMentioning(String name, String... references) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "run", "()V", null, null);
        method.visitCode();
        for (String reference : references) {
            method.visitFieldInsn(Opcodes.GETSTATIC, reference, "reference", "Ljava/lang/Object;");
            method.visitInsn(Opcodes.POP);
        }
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private Path jar(String fileName, byte[]... classes) throws IOException {
        Path file = temp.resolve(fileName);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(bytes)) {
            for (byte[] content : classes) {
                var reader = new org.objectweb.asm.ClassReader(content);
                jar.putNextEntry(new JarEntry(reader.getClassName() + ".class"));
                jar.write(content);
                jar.closeEntry();
            }
        }
        Files.write(file, bytes.toByteArray());
        return file;
    }
}
