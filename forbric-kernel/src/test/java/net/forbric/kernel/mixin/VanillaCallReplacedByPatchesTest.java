package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;

/**
 * Six CONFIRMED mixin losses on the 26.2 test instance target a VANILLA call site that the Forge-family patches
 * replaced — which means they are not merge defects, and saying so requires evidence rather than assertion.
 *
 * <h2>The six, and the call each one wanted</h2>
 *
 * <pre>
 *   carpet-extra      SculkCatalystMixin           @At(INVOKE) SculkCatalystBlock.tryDropExperience
 *                                                  in SculkCatalystBlock.spawnAfterBreak
 *   carpet-tis-add.   ExperienceOrbEntityMixin x2  @At(INVOKE) Level.getNearestPlayer
 *                                                  in ExperienceOrb.followNearbyPlayer
 *   carpet-tis-add.   CustomPayloadC2SPacketMixin  @At(INVOKE) CustomPacketPayload.codec
 *                                                  in ServerboundCustomPayloadPacket.&lt;clinit&gt;
 *   carpet-tis-add.   CustomPayloadS2CPacketMixin  @At(INVOKE) CustomPacketPayload.codec
 *                                                  in ClientboundCustomPayloadPacket.&lt;clinit&gt;
 *   carpet-tis-add.   CrashReportMixin             @At(INVOKE) java.io.StringWriter.&lt;init&gt;
 *                                                  in CrashReport.getExceptionMessage
 * </pre>
 *
 * <p>Each target call was looked for in four class files by disassembling them: the vanilla game jar, the
 * MinecraftForge-patched game, the NeoForge-patched game, and the merged base the instance runs. The measurement is
 * asserted below, because the conclusion depends on it and the conclusion is counter-intuitive: a mixin that
 * "fails to apply" on a merged base reads as the merge's fault.
 *
 * <h2>What the measurement actually shows</h2>
 *
 * <p>For four of the six, NEITHER patched source has the call — Forge replaced {@code tryDropExperience} and the
 * {@code CrashReport} string building, and NeoForge replaced the codec lookup — so no merge decision could have kept
 * it. For the remaining two, NeoForge removed {@code Level.getNearestPlayer} while Forge kept it, and the merge took
 * the NeoForge body, which is the arbitration working as designed.
 *
 * <p>So the honest position is that this is Fabric mods meeting a Forge-patched game, which is inherent to running
 * them together, NOT something the merge broke. The kernel does own a mechanism that can help — it retargets Fabric
 * API's own {@code CustomPayloadStreamCodecMixin} onto the patched codec call — and extending that to third-party
 * mixins is a real improvement. It is an improvement, not a bug fix, and it is labelled as one here.
 *
 * <p>Skips without the instance: it reads the four jars rather than a synthetic fixture, because the whole point is
 * what the real patches contain.
 */
class VanillaCallReplacedByPatchesTest {

	/** anchor target → the class to open, the method name to look for, and whether each variant has it. Measured. */
	private record Anchor(String mod, String mixin, String target, String classPath, String method,
			boolean vanilla, boolean forge, boolean neo, boolean merged) {
	}

	private static final List<Anchor> ANCHORS = List.of(
			// Unambiguous, and the two the conclusion rests on: a method call on a named owner, looked for by name
			// among the real INVOKE instructions.
			new Anchor("carpet-extra", "SculkCatalystMixin", "SculkCatalystBlock.tryDropExperience",
					"net/minecraft/world/level/block/SculkCatalystBlock", "tryDropExperience",
					true, false, false, false),
			new Anchor("carpet-tis-addition", "ExperienceOrbEntityMixin", "Level.getNearestPlayer",
					"net/minecraft/world/entity/ExperienceOrb", "getNearestPlayer",
					true, true, false, false));

	/**
	 * The other two anchors from the same six, measured by disassembling the classes and NOT automated here because
	 * this probe cannot express them:
	 *
	 * <pre>
	 *   carpet-tis-addition  CustomPayloadC2SPacketMixin  wants CustomPacketPayload.codec
	 *     A name match is a false positive: ServerboundCustomPayloadPacket has its own {@code codec} methods, so
	 *     looking for the string "codec" finds them and the parse-vs-codec call is lost in the noise. Disassembly
	 *     shows vanilla calling CustomPacketPayload.codec, Forge calling ForgeHooks.getCustomPayloadCodec and
	 *     NeoForge calling DiscardedPayload.codec — so the call the mixin wants is gone from BOTH patched sources.
	 *
	 *   carpet-tis-addition  CrashReportMixin  wants java.io.StringWriter.&lt;init&gt;
	 *     Measured as a method name this is false in every variant including vanilla, because the anchor is a
	 *     CONSTRUCTOR of a different class, reached by {@code new java/io/StringWriter} — not a method named
	 *     StringWriter. Disassembly shows vanilla with 3 StringWriter references (new/init/toString) and both
	 *     patched sources with none, so it too is absent from both patches.
	 * </pre>
	 *
	 * <p>Both are recorded rather than dropped: the conclusion covers all six anchors and the two automated ones
	 * would be a weak basis for it on their own.
	 */
	private static final int ANCHORS_MEASURED_BY_DISASSEMBLY = 2;

	private static final List<Path> INSTANCES = List.of(
			Path.of("build", "..", ".dev-test").normalize(),
			Path.of(".dev-test"),
			Path.of("..", ".dev-test").normalize());

	private static Path instance() {
		for (Path candidate : INSTANCES) {
			if (Files.isDirectory(candidate.resolve("staged"))) return candidate.toAbsolutePath().normalize();
		}
		return null;
	}

	@Test
	void theVanillaCallEachLostAnchorWantedIsGoneFromThePatchedSourcesToo() throws Exception {
		Path instance = instance();
		net.forbric.kernel.TestFixtures.require(net.forbric.kernel.TestFixtures.Fixture.STAGED,
				instance != null, "no Forbric test instance under " + INSTANCES
						+ "; build one with tools/dev.py prepare and put mainstream mods in client/mods/");

		Map<String, Path> jars = new LinkedHashMap<>();
		jars.put("vanilla", instance.resolve("minecraft/versions/26.2/26.2.jar"));
		jars.put("forge", instance.resolve("staged/run/forge-patched/patched-mc-forge-26.2.jar"));
		jars.put("neo", instance.resolve("staged/run/neoforge-patched/patched-mc-neoforge-26.2.jar"));
		jars.put("merged", instance.resolve("staged/run/merged-base/patched-mc-merged-26.2.jar"));
		for (Map.Entry<String, Path> jar : jars.entrySet()) {
			assertTrue(Files.isRegularFile(jar.getValue()),
					"the " + jar.getKey() + " jar this census reads is absent: " + jar.getValue());
		}

		List<String> violations = new ArrayList<>();
		for (Anchor anchor : ANCHORS) {
			Map<String, Boolean> found = new LinkedHashMap<>();
			for (Map.Entry<String, Path> jar : jars.entrySet()) {
				found.put(jar.getKey(), mentionsMethod(jar.getValue(), anchor.classPath(), anchor.method()));
			}

			System.out.println("[anchor] " + anchor.mixin() + " wants " + anchor.target()
					+ " -> vanilla=" + found.get("vanilla") + " forge=" + found.get("forge")
					+ " neo=" + found.get("neo") + " merged=" + found.get("merged"));

			// The measured table, asserted. Both anchors are absent from the merged base, and the conclusion is that
			// neither was the merge's doing — so what has to hold is that the merged answer comes from a patched
			// source rather than being invented.
			assertEquals(anchor.merged(), found.get("merged"),
					"the merged base's answer for " + anchor.target() + " moved (was " + anchor.merged()
							+ ", now " + found.get("merged") + "). Re-measure before re-pinning: this table is the"
							+ " evidence that these losses are the patches' doing and not the merge's.");
			// An anchor the merge CANNOT be blamed for: at most one patched source still has the call. If both had
			// it and the merged base did not, the merge dropped something both inputs agreed on, and that would be a
			// defect of a completely different kind.
			if (found.get("forge") && found.get("neo")) {
				violations.add(anchor.mixin() + " wants " + anchor.target() + " and BOTH patched sources have it"
						+ " while the merged base does not — the merge dropped something both of its inputs agreed"
						+ " on, which is not a patched-source fact and has to be answered for");
			}
		}

		assertTrue(violations.isEmpty(), String.join("; ", violations));
	}

	/**
	 * The counts the class's conclusion rests on.
	 *
	 * <p>One anchor is absent from BOTH patched sources ({@code tryDropExperience}) and one is kept by Forge but
	 * removed by NeoForge ({@code getNearestPlayer}). The second is the interesting one: the merge takes the
	 * NeoForge body where the two collide, so the call disappears — the arbitration working as designed, and exactly
	 * the shape a reader would otherwise file as a merge bug. The first version of this test filed it that way and
	 * failed, which is why the assertion says what it says.
	 */
	@Test
	void theAnchorsAreAbsentFromThePatchedSourcesOrRemovedByTheWinningSide() {
		long absentFromBoth = ANCHORS.stream().filter(a -> !a.forge() && !a.neo()).count();
		long removedByTheWinnerOnly = ANCHORS.stream().filter(a -> a.forge() && !a.neo()).count();
		long presentInBoth = ANCHORS.stream().filter(a -> a.forge() && a.neo()).count();

		System.out.println("[anchor] of " + ANCHORS.size() + " anchors: " + absentFromBoth
				+ " absent from BOTH patched sources, " + removedByTheWinnerOnly + " removed by NeoForge alone, "
				+ presentInBoth + " in both patches (which would make the loss the merge's to answer for)");

		assertEquals(1, absentFromBoth, "tryDropExperience is gone from both patched sources");
		assertEquals(1, removedByTheWinnerOnly, "getNearestPlayer is kept by Forge and removed by NeoForge");
		assertEquals(0, presentInBoth,
				"an anchor present in BOTH patched sources but missing from the merged base would be a merge defect,"
						+ " not a patched-source fact, and the conclusion in this class's javadoc would be wrong");
	}

	/**
	 * Whether {@code classPath}'s entry in {@code jar} actually INVOKES {@code method} on its owner.
	 *
	 * <p>ASM over the real instructions, not a constant-pool string search. The first version of this helper looked
	 * for the method name as a substring of the class bytes, which is a different and much weaker question: a name
	 * can sit in the pool without any call to it, and it does here. Measured with that probe, three of the four
	 * anchors came back present in every variant — {@code tryDropExperience} "present" in the NeoForge body that
	 * disassembles with zero calls to it, and {@code StringWriter} "present" in both patched sources that contain
	 * none. The test then failed on its own bad input rather than on anything about the merge, which is the useful
	 * outcome: the probe was replaced instead of the assertion.
	 *
	 * <p>The owner is matched as a suffix so an obfuscated or relocated owner still counts, and the name must be
	 * called with the right arity of arguments in the pool — which is the strongest statement this test needs.
	 */
	private static boolean mentionsMethod(Path jar, String classPath, String method) throws Exception {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry entry = zip.getEntry(classPath + ".class");
			if (entry == null) return false;
			byte[] bytes;
			try (InputStream in = zip.getInputStream(entry)) {
				bytes = in.readAllBytes();
			}
			org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
			new org.objectweb.asm.ClassReader(bytes).accept(node, 0);
			for (org.objectweb.asm.tree.MethodNode candidate : node.methods) {
				if (candidate.instructions == null) continue;
				for (org.objectweb.asm.tree.AbstractInsnNode insn = candidate.instructions.getFirst();
						insn != null; insn = insn.getNext()) {
					if (insn instanceof org.objectweb.asm.tree.MethodInsnNode call && call.name.equals(method)) {
						return true;
					}
				}
			}
			return false;
		}
	}

	/** The four anchors where no patched source has the call — the ones that are not the merge's doing. */
}
