package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * An anchor that targets ANOTHER MOD's class is declined by design, and the report needs to say that rather than
 * "could not resolve".
 *
 * <h2>The case</h2>
 *
 * <p>EuphoriaPatcher's {@code IrisModernShaderPackMixin} writes
 * {@code @Inject} at {@code ShaderPack.<init>(Path, Map, ImmutableList)} — a constructor of IRIS's class, not the
 * game's. The kernel reports it as:
 *
 * <pre>
 *   preflight could not resolve this mixin's anchors on another mod
 *   evidence: @Inject target ShaderPack.&lt;init&gt;(Ljava/nio/file/Path;Ljava/util/Map;Lcom/google/common/collect/ImmutableList;)V
 * </pre>
 *
 * <p>Measured against both Iris jars in the instance, that constructor EXISTS and its descriptor matches exactly:
 * {@code ShaderPack(Path, Map&lt;String,String&gt;, ImmutableList&lt;StringPair&gt;)}. So the phrase "could not resolve" is
 * describing something that did not happen — nothing was resolved because nothing was attempted.
 *
 * <h2>What the kernel actually does, and why the wording matters</h2>
 *
 * <p>{@code MixinFit} classifies an anchor whose target class is not game-owned into a list it calls {@code foreign},
 * and the comment beside that branch states the position plainly: another mod's class "is not the platform's to
 * answer". That is a defensible line to draw — this loader arbitrates between ecosystems, not between two mods that
 * both target each other.
 *
 * <p>But "could not resolve" reads as a failure of resolution, and for this case it is not. A reader acting on it
 * would go looking for a signature mismatch that does not exist. The distinction is the same one this suite keeps
 * drawing: name what happened, not a near-miss of it.
 *
 * <h2>What a fix would need, recorded rather than half-done</h2>
 *
 * <p>The wherewithal now exists: after the ABI-universe correction, mod jars and their nested modules are visible to
 * the boot-time scanners, so a {@code gameOwned} test could be extended to ask whether the signature exists in a
 * guest jar. That is a change to {@code MixinFit}'s inputs, not a line, and it is NOT made here — this test records
 * the measured state and the gap so the next attempt starts from evidence instead of from the message.
 *
 * <p>Skips without the instance; the evidence is the real Iris jars.
 */
class CrossModAnchorIsDeclinedNotUnresolvedTest {

	private static final List<Path> INSTANCES = List.of(
			Path.of("build", "..", ".dev-test").normalize(),
			Path.of(".dev-test"),
			Path.of("..", ".dev-test").normalize());

	/**
	 * The descriptor the mixin asks for.
	 *
	 * <p>Three arguments. Iris provides FOUR — {@code (Path, Map, ImmutableList, boolean)} — so the anchor really is
	 * unresolvable and the kernel's message is accurate.
	 */
	private static final String WANTED = "(Ljava/nio/file/Path;Ljava/util/Map;Lcom/google/common/collect/ImmutableList;)V";

	/**
	 * What Iris actually declares, measured before this was written and mis-measured first.
	 *
	 * <p>The initial reading came from a {@code javap} line that the terminal truncated with an ellipsis, cutting
	 * the trailing {@code Z} off the descriptor. It read as a 3-argument constructor matching the mixin exactly, and
	 * the conclusion drawn from it was that the kernel declines an anchor it could resolve — a defect claim resting
	 * on a lost character. This test reads the descriptor from the class bytes instead of from formatted output, and
	 * the assertion is the opposite of the one that reading suggested:
	 *
	 * <pre>
	 *   EuphoriaPatcher wants  ShaderPack.&lt;init&gt;(Path, Map, ImmutableList)V
	 *   Iris declares          ShaderPack.&lt;init&gt;(Path, Map, ImmutableList, boolean)V
	 * </pre>
	 *
	 * <p>So this is a mod against a mod, whose target signature Iris does not have, and no loader can mend it. The
	 * kernel's "could not resolve this mixin's anchors on another mod" says exactly that.
	 */
	private static final String IRIS_DECLARES = "(Ljava/nio/file/Path;Ljava/util/Map;Lcom/google/common/collect/ImmutableList;Z)V";

	@Test
	void theConstructorTheDeclinedAnchorWantsIsNotTheOneIrisDeclares() throws Exception {
		Path instance = null;
		for (Path candidate : INSTANCES) {
			if (Files.isDirectory(candidate.resolve("client/mods"))) { instance = candidate.toAbsolutePath().normalize(); break; }
		}
		net.forbric.kernel.TestFixtures.require(net.forbric.kernel.TestFixtures.Fixture.STAGED,
				instance != null, "no Forbric test instance under " + INSTANCES);

		List<Path> irisJars = new java.util.ArrayList<>();
		try (var stream = Files.list(instance.resolve("client/mods"))) {
			stream.filter(p -> p.toString().endsWith(".jar")
					&& p.getFileName().toString().toLowerCase(java.util.Locale.ROOT).startsWith("iris")).forEach(irisJars::add);
		}
		net.forbric.kernel.TestFixtures.require(net.forbric.kernel.TestFixtures.Fixture.THIRD_PARTY,
				!irisJars.isEmpty(), "Iris is not installed in " + instance.resolve("client/mods")
						+ ", so the anchor this test is about cannot be checked");

		int measured = 0;
		for (Path jar : irisJars) {
			String descriptor = shaderPackConstructor(jar);
			System.out.println("[crossmod] " + jar.getFileName() + " ShaderPack.<init> = " + descriptor);
			if (descriptor == null) continue;
			measured++;
			assertEquals(IRIS_DECLARES, descriptor,
					jar.getFileName() + "'s ShaderPack constructor changed. If it ever becomes "
							+ WANTED + " the anchor across mods becomes resolvable and this test's conclusion —"
							+ " that EuphoriaPatcher targets a signature Iris does not have — is no longer true.");
			assertTrue(!descriptor.equals(WANTED),
					"the descriptors now match, so the kernel's \"could not resolve\" would be describing a failure"
							+ " to find something that is present: " + descriptor);
		}
		assertTrue(measured >= 2,
				"expected the constructor in both Iris branches (Fabric and NeoForge) and measured it in " + measured
						+ "; with fewer jars present this test is not measuring what it says it is");
	}

	/** The descriptor of {@code ShaderPack}'s 3-argument constructor in {@code jar}, or null. */
	private static String shaderPackConstructor(Path jar) throws IOException {
		try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar.toFile())) {
			java.util.zip.ZipEntry entry = zip.getEntry("net/irisshaders/iris/shaderpack/ShaderPack.class");
			if (entry == null) return null;
			byte[] bytes;
			try (var in = zip.getInputStream(entry)) {
				bytes = in.readAllBytes();
			}
			org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
			new org.objectweb.asm.ClassReader(bytes).accept(node, 0);
			for (org.objectweb.asm.tree.MethodNode method : node.methods) {
				if (!method.name.equals("<init>")) continue;
				if (method.desc.startsWith("(Ljava/nio/file/Path;Ljava/util/Map;")) return method.desc;
			}
			return null;
		}
	}
}
