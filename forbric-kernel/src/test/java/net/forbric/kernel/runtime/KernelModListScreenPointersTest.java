package net.forbric.kernel.runtime;

import net.forbric.kernel.TestFixtures;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * The files the Mods screen sends a player to have to be there when it does.
 *
 * <p>Read off the compiled screen rather than drawn: its text is built inside a frame only a real client runs. The
 * pointers are string constants (plain, or a concatenation recipe), and which file each names is the contract.
 */
class KernelModListScreenPointersTest {
	private static List<String> strings() throws Exception {
		Path screen = Path.of("build/classes/java/runtime/net/forbric/kernel/runtime/KernelModListScreen.class");
		TestFixtures.requireFiles(TestFixtures.Fixture.GAME_SIDE, "the GAME-side classes are compiled before the tests", screen);
		ClassNode node = new ClassNode();
		new ClassReader(Files.readAllBytes(screen)).accept(node, 0);
		List<String> out = new ArrayList<>();
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String s) out.add(s);
				if (insn instanceof InvokeDynamicInsnNode indy) {
					for (Object arg : indy.bsmArgs) if (arg instanceof String s) out.add(s);
				}
			}
		}
		return out;
	}

	@Test
	void theRestOfAModsSuspicionsArePointedAtTheReportThatAlwaysListsThem() throws Exception {
		// load-report.txt is written only when something failed, and a boot whose only findings are suspicions --
		// every fabric-api boot has two dozen -- writes none; the machine report always has every one of them.
		List<String> more = strings().stream().filter(s -> s.contains(" more in ")).toList();
		assertFalse(more.isEmpty(), "the pane still says how many notes it did not show");
		for (String pointer : more) {
			assertTrue(pointer.contains(".forbric-kernel/compatibility-report.json"), pointer);
			assertFalse(pointer.contains("load-report.txt"), pointer);
		}
	}

	@Test
	void confirmedLossesStillPointAtTheLoadReportTheyKeep() throws Exception {
		// The negative control: a row that did not finish, and a confirmed loss no row carries, are exactly what
		// keeps load-report.txt, so those pointers stay on it.
		List<String> all = strings();
		assertTrue(all.stream().anyMatch(s -> s.equals("see .forbric-kernel/load-report.txt")), all.toString());
		assertTrue(all.stream().anyMatch(s -> s.contains("not tied to one mod (see .forbric-kernel/load-report.txt)")),
				all.toString());
	}

	@Test
	void theTwoStatusesAreSaidAsTwoDifferentSentences() throws Exception {
		// ModCatalog.failures() returns FAILED *and* DEGRADED. Telling them as one sentence read "31 did not finish
		// loading" on a measured launch where every one of those 31 was loaded and running with one mixin left out
		// -- and the same file's rows said "part of this mod did not run" about each of them. Both sentences have to
		// be present, because a row of one status alone must still be able to say its own thing.
		// The literals arrive from javac's string-concat recipe as concat arguments, so they are matched by content:
		// each sentence has to be somewhere in the class, and the two have to be different constants.
		List<String> all = strings();
		List<String> partial = all.stream().filter(s -> s.contains("running with part left out")).toList();
		List<String> missing = all.stream().filter(s -> s.contains("did not finish loading")).toList();
		assertTrue(!partial.isEmpty() && !missing.isEmpty(),
				"partial=" + partial + " missing=" + missing);
		assertTrue(partial.stream().noneMatch(s -> s.contains("did not finish loading")),
				"one sentence for both statuses is the bug being prevented: " + partial);
	}
}
