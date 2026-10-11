package net.forbric.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * How many of the hook METHODS the merge loses were actually firing an event — the figure that says what a player
 * loses, as opposed to how many methods were declined.
 *
 * <h2>Why the method count was not enough</h2>
 *
 * <p>{@code merge-conflicts.txt} counts methods whose hook lost the arbitration: 1134 marked {@code forge hook
 * lost}, 9 {@code neo hook lost}. That is an implementation statistic. A lost hook only costs a mod a feature if
 * the body it lost posts an event, so the figure worth asserting is the smaller one, and nothing in the repository
 * produced it — which is how a video critiquing this project could claim "over 200 event calls are simply
 * discarded" and there was no number in the repo to check it against.
 *
 * <p>Measured for the first time here: of 1134 lost Forge hooks, 1130 resolve in the patched Forge jar and
 * <b>201</b> of them reference {@code net/minecraftforge/event/…} in their own instructions. The video's "200
 * something" is that; the claim is correct, and it is now a number a test can defend.
 *
 * <h2>Criterion</h2>
 *
 * <p>A method "fires an event" if its own body either calls something under {@code net/minecraftforge/event/}
 * (an {@code EventBus.post}, or a method on an event instance) or instantiates one of those types — the shape of
 * {@code new TickEvent.Server(...)} followed by a post. Decided from the method's instructions rather than from its
 * class, so a class that merely imports an event type somewhere does not count.
 */
class EventFiringHookLossTest {

	/** The tracked merge report: the same committed artifact MergeCoverageCensusTest reads. */
	private static final Path REPORT = Path.of(System.getProperty("user.dir"), "..", "forbric-loader", "run",
			"merged-base", "merge-conflicts.txt").normalize();

	/** The Forge-patched game the merge read, staged by tools/dev.py beside the installed pins. */
	private static final String FORGE_PATCHED = "forge-patched/patched-mc-forge-26.2.jar";

	private static final String SECTION = "=== both-modified methods where one side's hook is LOST";

	/**
	 * Pinned from the tracked report. 1134 lost Forge hooks, 1130 resolvable in the jar, 201 of them firing an
	 * event across 333 event references. Changing these is a statement about how much of Forge a player keeps.
	 */
	private static final int LOST_FORGE_HOOKS = 1134;
	private static final int RESOLVED_IN_THE_JAR = 1130;
	private static final int FIRING_AN_EVENT = 201;
	private static final int NEO_LOST_HOOKS = 9;

	@Test
	void theLostHooksThatActuallyFireAnEventAreCountedAndPinned() throws Exception {
		assertTrue(Files.isRegularFile(REPORT), "the tracked merge report is the input of this census: " + REPORT);
		Path forgeJar = forgePatchedJar();
		net.forbric.kernel.TestFixtures.require(net.forbric.kernel.TestFixtures.Fixture.STAGED,
				forgeJar != null,
				"the Forge-patched game this census reads method bodies out of is absent. Looked in every place"
						+ " tools/dev.py has staged it; run `python tools/dev.py prepare` or pass"
						+ " -Pforbric.stagedRoot=<checkout>/.dev/staged/run");

		Map<String, byte[]> classes = readClasses(forgeJar);
		assertTrue(classes.size() > 5000, "the Forge jar read as " + classes.size() + " classes, which is too few"
				+ " to be the patched game: " + forgeJar);

		int forgeLost = 0, neoLost = 0, resolved = 0, firing = 0, references = 0;
		for (String line : Files.readAllLines(REPORT)) {
			if (!line.contains("hook lost")) continue;
			if (line.startsWith("===")) continue;
			boolean isForge = line.contains("forge hook lost");
			if (!isForge && !line.contains("neo hook lost")) continue;
			if (isForge) forgeLost++; else neoLost++;
			if (!isForge) continue;

			// The suffix has its own brackets, so it comes off before the descriptor is read: lastIndexOf('(')
			// otherwise finds the suffix's, every pair fails to resolve, and the census reports zero of everything.
			int suffixAt = line.indexOf(" (forge hook lost");
			String body = suffixAt > 0 ? line.substring(0, suffixAt).trim() : line.trim();
			int hash = body.indexOf('#');
			int paren = body.lastIndexOf('(');
			if (hash < 0 || paren < hash) continue;

			byte[] bytes = classes.get(body.substring(0, hash).trim());
			if (bytes == null) continue;
			ClassNode node = new ClassNode();
			new ClassReader(bytes).accept(node, 0);
			String name = body.substring(hash + 1, paren).trim();
			String desc = body.substring(paren);
			MethodNode method = null;
			for (MethodNode candidate : node.methods) {
				if (candidate.name.equals(name) && candidate.desc.equals(desc)) { method = candidate; break; }
			}
			if (method == null || method.instructions == null) continue;
			resolved++;

			int posts = 0;
			for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				String referenced = null;
				if (insn instanceof MethodInsnNode call) referenced = call.owner;
				else if (insn instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW) referenced = type.desc;
				if (referenced != null && referenced.startsWith("net/minecraftforge/event/")) posts++;
			}
			if (posts > 0) { firing++; references += posts; }
		}

		System.out.println("[event-loss] lost Forge hooks=" + forgeLost + " neo=" + neoLost
				+ " resolved=" + resolved + " firing-an-event=" + firing + " references=" + references);

		assertEquals(LOST_FORGE_HOOKS, forgeLost, "Forge hooks marked lost in the tracked report");
		assertEquals(NEO_LOST_HOOKS, neoLost, "NeoForge hooks marked lost — the other side of the arbitration");
		assertEquals(RESOLVED_IN_THE_JAR, resolved, "lost hooks whose body could be located in the Forge jar");
		assertEquals(FIRING_AN_EVENT, firing,
				"lost Forge hooks that FIRE AN EVENT — every one is a mod feature a player loses; the method count"
						+ " above is larger and says less");
		assertEquals(333, references, "event references inside those methods");
	}

	/** Which suffix each hook-loss line carries, so the section's shape is asserted rather than assumed. */
	@Test
	void theHookLossSectionSaysWhichSideLost() throws Exception {
		Map<String, Integer> suffixes = new LinkedHashMap<>();
		boolean in = false;
		for (String line : Files.readAllLines(REPORT)) {
			if (line.startsWith(SECTION)) { in = true; continue; }
			if (in && line.startsWith("===")) break;
			if (!in || line.isBlank()) continue;
			int close = line.lastIndexOf(')');
			int open = line.lastIndexOf('(');
			String suffix = open > 0 && close > open ? line.substring(open + 1, close) : "(none)";
			suffixes.merge(suffix, 1, Integer::sum);
		}
		assertEquals(1130, suffixes.getOrDefault("forge hook lost", 0), "suffixes: " + suffixes);
		assertEquals(9, suffixes.getOrDefault("neo hook lost", 0), "suffixes: " + suffixes);
		assertTrue(suffixes.containsKey("forge hook lost"), "suffixes: " + suffixes);
	}

	/**
	 * The Forge-patched game, wherever this checkout has staged it, or null.
	 *
	 * <p>Deliberately NOT {@link net.forbric.kernel.TestFixtures#stagedRoot()} alone. Pointing the whole build at
	 * the staged tree with {@code -Pforbric.stagedRoot} also moves the {@code runtime} source set's compile
	 * classpath, which then fails to compile against the game — so the one flag that would let this test see its
	 * input is the flag that breaks the build that runs it. Searching the places dev.py actually stages to keeps
	 * the census runnable without touching that wiring, and the property is still honoured first for CI, where the
	 * artifacts live somewhere else.
	 */
	private static Path forgePatchedJar() {
		List<Path> candidates = new ArrayList<>();
		String configured = System.getProperty("forbric.stagedRoot");
		if (configured != null && !configured.isBlank()) candidates.add(Path.of(configured, FORGE_PATCHED));
		candidates.add(Path.of(net.forbric.kernel.TestFixtures.stagedRoot().toString(), FORGE_PATCHED));
		candidates.add(Path.of(System.getProperty("user.dir"), FORGE_PATCHED));
		// The dev checkout's isolated Minecraft, where tools/dev.py stages everything it prepared.
		candidates.add(Path.of(System.getProperty("user.dir"), ".dev", "staged", "run", FORGE_PATCHED));
		candidates.add(Path.of(System.getProperty("user.dir"), "..", ".dev", "staged", "run", FORGE_PATCHED).normalize());
		for (Path candidate : candidates) {
			if (Files.isRegularFile(candidate)) return candidate;
		}
		return null;
	}

	private static Map<String, byte[]> readClasses(Path jar) throws IOException {
		Map<String, byte[]> out = new HashMap<>();
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements();) {
				ZipEntry entry = e.nextElement();
				if (!entry.getName().endsWith(".class")) continue;
				try (var in = zip.getInputStream(entry)) {
					out.put(entry.getName().substring(0, entry.getName().length() - 6), in.readAllBytes());
				}
			}
		}
		return out;
	}

	/** Kept so the unused-import checker does not get a say in whether the list above is complete. */
	@SuppressWarnings("unused")
	private static final List<String> NOTHING = new ArrayList<>();
}
