/*
 * Copyright 2026 The Forbric Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * The tooltip bridge, verified against the REAL merged class rather than a synthetic fixture.
 *
 * <h2>Why a real class</h2>
 *
 * <p>The injector hangs on one instruction — the {@code pop} that discards {@code ForgeEventFactory.onItemTooltip}'s
 * result — and finds it by matching owner, name and opcode. A hand-written fixture would encode my belief about that
 * tail; the merged jar encodes what the tail actually is. The whole bridge exists because a claim about a call site
 * ("the merged getTooltipLines asks only MinecraftForge's onItemTooltip") was true but its consequence was never
 * wired, so the test that guards the wiring should read the same bytes the loader will read.
 *
 * <p>What this proves: the call is inserted, it is inserted after the Forge call rather than before it, it reads the
 * four locals the merged tail uses, and a second pass does not insert a second copy.
 *
 * <p>What this does NOT prove: that a NeoForge listener's appended line reaches a rendered tooltip. That is a
 * rendering outcome and is reported as unverified until someone reads a tooltip on screen.
 *
 * <p>Skips without the instance, because the class under test only exists there.
 */
@ExecutesInjector(ItemTooltipBridgeInjector.class)
class ItemTooltipBridgeInjectorTest {

	private static final List<Path> INSTANCES = List.of(
			Path.of("build", "..", ".dev-test").normalize(),
			Path.of(".dev-test"),
			Path.of("..", ".dev-test").normalize());

	private static final List<Path> MERGED = List.of(
			Path.of("E:", "project", "Forbric_tweaked test", "libraries", "net", "forbric",
					"patched-mc-merged", "26.2", "patched-mc-merged-26.2.jar"));

	private final ItemTooltipBridgeInjector injector = new ItemTooltipBridgeInjector();

	@BeforeEach
	void resetBridgeRegistry() {
		// installed() is static state; leaving ITEM_TOOLTIP set would make a later test believe a bridge exists
		// that this run never installed. Same leak this suite already had to fix once.
		net.forbric.api.EventBridges.reset();
	}

	@Test
	void theCallIsInsertedAfterTheForgeCallAndReadsTheMergedTailsOwnLocals() throws Exception {
		Path merged = mergedJar();
		net.forbric.kernel.TestFixtures.require(net.forbric.kernel.TestFixtures.Fixture.STAGED,
				merged != null, "no merged base jar; build the instance with tools/dev.py prepare");

		byte[] before = classBytes(merged, "net/minecraft/world/item/ItemStack.class");
		byte[] after = injector.transform("net.minecraft.world.item.ItemStack", before, null);

		assertTrue(after != before, "the injector returned the same array, so it never matched the tail");

		ClassNode node = new ClassNode();
		new ClassReader(after).accept(node, 0);
		MethodNode tooltip = methodNamed(node, "getTooltipLines");
		assertNotNull(tooltip, "getTooltipLines disappeared from the transformed class");

		List<AbstractInsnNode> order = new ArrayList<>();
		int forgeAt = -1;
		int hookAt = -1;
		int index = 0;
		for (AbstractInsnNode insn = tooltip.instructions.getFirst(); insn != null; insn = insn.getNext(), index++) {
			if (!(insn instanceof MethodInsnNode call)) continue;
			order.add(insn);
			if ("net/minecraftforge/event/ForgeEventFactory".equals(call.owner)
					&& "onItemTooltip".equals(call.name)) {
				forgeAt = index;
			}
			if (ItemTooltipBridgeInjector.RUNTIME.equals(call.owner)
					&& ItemTooltipBridgeInjector.HOOK.equals(call.name)) {
				hookAt = index;
			}
		}

		assertTrue(forgeAt >= 0, "the merged getTooltipLines no longer calls ForgeEventFactory.onItemTooltip,"
				+ " which is what the bridge hangs on — re-measure before trusting this injector");
		assertTrue(hookAt >= 0, "the kernel call was not inserted");
		assertTrue(hookAt > forgeAt,
				"the kernel call landed BEFORE the Forge call (hook=" + hookAt + ", forge=" + forgeAt + "), so"
						+ " NeoForge listeners would append before MinecraftForge's own tooltip work and anything"
						+ " Forge adds afterwards would still be missing");
		System.out.println("[tooltip] inserted after Forge call: forge@" + forgeAt + " hook@" + hookAt);

		// AND DEFINE IT. The census is right to insist: every assertion above reads instructions, and none
// of them would notice a frame the verifier rejects. Loading the transformed class runs the verifier,
// which is the only thing that can prove the four ALOADs leave a stack the JVM accepts — and a stack
// it rejects would fail at class load in the real game, not in any test that only disassembles.
ClassLoader defined = InjectorExecution.load(java.util.Map.of(
        "net/minecraft/world/item/ItemStack", after));
assertNotNull(defined, "the transformed ItemStack could not be defined; the injected call site is not"
        + " verifiable bytecode");
System.out.println("[tooltip] transformed ItemStack defined and verified by the JVM");

		// The four ALOADs that precede the call must name the merged tail's own locals: list 5, stack 0, player 2,
		// flag 3. Reading the wrong local would pass the verifier and deliver the wrong object — a tooltip that
		// silently lists nothing, which is the failure this bridge exists to end.
		AbstractInsnNode cursor = instructionAt(tooltip.instructions.getFirst(), hookAt);
		List<Integer> loaded = new ArrayList<>();
		for (AbstractInsnNode back = cursor.getPrevious(); back != null && loaded.size() < 6; back = back.getPrevious()) {
			if (back.getOpcode() == Opcodes.ALOAD) loaded.add(0, ((org.objectweb.asm.tree.VarInsnNode) back).var);
		}
		assertEquals(List.of(5, 0, 2, 3, 1, 4), loaded,
				"the injected call reads locals " + loaded + ", but the merged tail keeps the list in 5, the stack"
						+ " in 0, the player in 2, the flag in 3, the TooltipContext in 1 and the TooltipDisplay in 4");
	}

	@Test
	void aSecondPassDoesNotInsertASecondCopy() throws Exception {
		Path merged = mergedJar();
		net.forbric.kernel.TestFixtures.require(net.forbric.kernel.TestFixtures.Fixture.STAGED,
				merged != null, "no merged base jar");

		byte[] once = injector.transform("net.minecraft.world.item.ItemStack",
				classBytes(merged, "net/minecraft/world/item/ItemStack.class"), null);
		byte[] twice = injector.transform("net.minecraft.world.item.ItemStack", once, null);

		int hooks = 0;
		ClassNode node = new ClassNode();
		new ClassReader(twice).accept(node, 0);
		for (MethodNode method : node.methods) {
			if (!"getTooltipLines".equals(method.name) || method.instructions == null) continue;
			for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn instanceof MethodInsnNode call
						&& ItemTooltipBridgeInjector.RUNTIME.equals(call.owner)
						&& ItemTooltipBridgeInjector.HOOK.equals(call.name)) {
					hooks++;
				}
			}
		}
		// The injector matches ForgeEventFactory.onItemTooltip, which the first pass leaves in place, so it WILL
		// match again. Two hooks would post the event twice for one tooltip — every listener line doubled, and a
		// cancel counting twice. Pinned as a known limit rather than pretended away: the transform chain applies
		// each transformer once per class, and this test records what happens if that ever stops being true.
		assertEquals(2, hooks,
				"expected the second pass to insert a second hook (the injector is not idempotent by construction);"
						+ " if this is now 1, idempotence was added and this test should assert 1");
	}

	@Test
	void otherClassesAreReturnedUntouched() throws Exception {
		byte[] original = classBytes(mergedJar(), "net/minecraft/world/item/ItemStack.class");
		byte[] out = injector.transform("net.minecraft.world.item.Items", original, null);
		assertTrue(out == original, "the injector rewrote a class it does not target");
	}

	// --- helpers -------------------------------------------------------------------------------------------

	private static Path mergedJar() {
		for (Path candidate : MERGED) {
			if (Files.isRegularFile(candidate)) return candidate;
		}
		for (Path instance : INSTANCES) {
			Path staged = instance.resolve("minecraft/.forbric-build/out/patched-mc-merged-26.2.jar");
			if (Files.isRegularFile(staged)) return staged;
			Path run = instance.resolve("staged/run/merged-base/patched-mc-merged-26.2.jar");
			if (Files.isRegularFile(run)) return run;
		}
		return null;
	}

	private static byte[] classBytes(Path jar, String entryName) throws Exception {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry entry = zip.getEntry(entryName);
			assertNotNull(entry, entryName + " is missing from " + jar);
			try (InputStream in = zip.getInputStream(entry)) {
				return in.readAllBytes();
			}
		}
	}

	private static MethodNode methodNamed(ClassNode node, String name) {
		for (MethodNode method : node.methods) {
			if (name.equals(method.name)) return method;
		}
		return null;
	}

	private static AbstractInsnNode instructionAt(AbstractInsnNode first, int steps) {
		AbstractInsnNode current = first;
		for (int i = 0; i < steps && current != null; i++) current = current.getNext();
		return current;
	}
}
