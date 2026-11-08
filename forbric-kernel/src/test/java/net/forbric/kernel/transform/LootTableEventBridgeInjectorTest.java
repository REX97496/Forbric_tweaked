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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;

/** The two owner swaps over the REAL merged {@code ReloadableServerRegistries}, and the seam shape they rely on. */
class LootTableEventBridgeInjectorTest {
	private static final Path MERGED_BASE = TestFixtures.stagedRoot().resolve("merged-base/patched-mc-merged-26.2.jar");
	private static final String OWNER = LootTableEventBridgeInjector.TARGET.replace('.', '/');

	@AfterEach
	void reset() {
		System.clearProperty(LootTableEventBridgeInjector.PROPERTY);
	}

	@Test
	void bothSeamsAreRoutedOnceAndTheOriginalsAreGone() throws Exception {
		byte[] original = bytesOf(OWNER);
		ClassNode before = parse(original);
		// Which line this build carries decides what the real class contains: 26.2 keeps both seams in this class,
		// 1.21.1 keeps the loot call in LootDataType and has no tag seam at all. Both facts are premises of this
		// test, so it skips on the line it was not written for rather than failing there — the 1.21.1 shape has its
		// own test below.
		Assumptions.assumeTrue(
				calls(before, LootTableEventBridgeInjector.EVENT_HOOKS, LootTableEventBridgeInjector.LOAD_LOOT_TABLE) == 1
						&& calls(before, LootTableEventBridgeInjector.TAG_LOADER, LootTableEventBridgeInjector.LOAD_TAGS) == 1,
				"this line's ReloadableServerRegistries does not carry both seams (1.21.1 does not)");

		byte[] routed = new LootTableEventBridgeInjector().transform(LootTableEventBridgeInjector.TARGET, original, null);
		assertNotSame(original, routed);
		ClassNode after = parse(routed);
		assertEquals(0, calls(after, LootTableEventBridgeInjector.EVENT_HOOKS, LootTableEventBridgeInjector.LOAD_LOOT_TABLE));
		assertEquals(0, calls(after, LootTableEventBridgeInjector.TAG_LOADER, LootTableEventBridgeInjector.LOAD_TAGS));
		assertEquals(1, calls(after, LootTableEventBridgeInjector.BRIDGE, LootTableEventBridgeInjector.LOAD_LOOT_TABLE));
		assertEquals(1, calls(after, LootTableEventBridgeInjector.BRIDGE, LootTableEventBridgeInjector.LOAD_TAGS));

		// Descriptors untouched — the shim declares the same ones, so the swap is the whole edit.
		for (MethodNode m : after.methods) {
			for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (!(insn instanceof MethodInsnNode call) || !LootTableEventBridgeInjector.BRIDGE.equals(call.owner)) continue;
				String expected = LootTableEventBridgeInjector.LOAD_LOOT_TABLE.equals(call.name)
						? LootTableEventBridgeInjector.LOAD_LOOT_TABLE_DESC : LootTableEventBridgeInjector.LOAD_TAGS_DESC;
				assertEquals(expected, call.desc, call.name);
				assertEquals(Opcodes.INVOKESTATIC, call.getOpcode());
			}
			new Analyzer<>(new BasicVerifier()).analyze(after.name, m);
		}
	}

	/** The seam read off javap: the loot call's result goes to slot 5, and a null there drops the table. */
	@Test
	void theLootCallStillStoresItsResultInSlotFive() throws Exception {
		byte[] original = bytesOf(OWNER);
		Assumptions.assumeTrue(
				calls(parse(original), LootTableEventBridgeInjector.EVENT_HOOKS, LootTableEventBridgeInjector.LOAD_LOOT_TABLE) == 1,
				"this line does not keep the loot call here (1.21.1 keeps it in LootDataType)");
		ClassNode after = parse(new LootTableEventBridgeInjector().transform(LootTableEventBridgeInjector.TARGET, original, null));
		MethodInsnNode call = null;
		for (MethodNode m : after.methods) {
			for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn instanceof MethodInsnNode c && LootTableEventBridgeInjector.BRIDGE.equals(c.owner)
						&& LootTableEventBridgeInjector.LOAD_LOOT_TABLE.equals(c.name)) call = c;
			}
		}
		assertNotNull(call);
		AbstractInsnNode next = call.getNext();
		while (next != null && next.getOpcode() < 0) next = next.getNext();
		assertTrue(next instanceof VarInsnNode store && store.getOpcode() == Opcodes.ASTORE && store.var == 5,
				"the merged lambda stores NeoForge's answer in slot 5 before the null check; the shape has drifted if not");
	}

	@Test
	void aSecondPassChangesNothingFurther() throws Exception {
		byte[] original = bytesOf(OWNER);
		ClassNode before = parse(original);
		Assumptions.assumeTrue(
				calls(before, LootTableEventBridgeInjector.EVENT_HOOKS, LootTableEventBridgeInjector.LOAD_LOOT_TABLE) == 1
						&& calls(before, LootTableEventBridgeInjector.TAG_LOADER, LootTableEventBridgeInjector.LOAD_TAGS) == 1,
				"only the two-seam shape is routed twice here");
		LootTableEventBridgeInjector injector = new LootTableEventBridgeInjector();
		byte[] once = injector.transform(LootTableEventBridgeInjector.TARGET, original, null);
		assertSame(once, injector.transform(LootTableEventBridgeInjector.TARGET, once, null));
		assertEquals(2, injector.routedSites());
	}

	/** Half a seam is worse than none: with only the loot call present the class must come back untouched. */
	@Test
	void bothOrNothing_aClassWithOnlyTheLootCallIsLeftAlone() {
		byte[] half = synthetic(true, false);
		assertSame(half, new LootTableEventBridgeInjector().transform(LootTableEventBridgeInjector.TARGET, half, null));
		byte[] otherHalf = synthetic(false, true);
		assertSame(otherHalf, new LootTableEventBridgeInjector().transform(LootTableEventBridgeInjector.TARGET, otherHalf, null));
		byte[] both = synthetic(true, true);
		assertNotSame(both, new LootTableEventBridgeInjector().transform(LootTableEventBridgeInjector.TARGET, both, null));
	}

	@Test
	void anUnrelatedClassPassesThroughByIdentity() {
		byte[] both = synthetic(true, true);
		assertSame(both, new LootTableEventBridgeInjector().transform("net.minecraft.server.Other", both, null));
	}

	/**
	 * 1.21.1 loads loot tables in {@code LootDataType} and has no {@code TagLoader.loadTagsForRegistry} anywhere in
	 * its base, so the loot seam is routed alone there — {@code REPLACE}/{@code MODIFY} fire instead of nothing.
	 */
	@Test
	void theLineThatLoadsLootTablesElsewhereGetsItsLootCallRoutedAlone() {
		byte[] lootOnly = synthetic("net.minecraft.world.level.storage.loot.LootDataType",
				LootTableEventBridgeInjector.LOAD_LOOT_TABLE_DESC_1211, true, false);
		LootTableEventBridgeInjector injector = new LootTableEventBridgeInjector();
		byte[] routed = injector.transform(LootTableEventBridgeInjector.TARGET_1211, lootOnly, null);

		assertNotSame(lootOnly, routed, "the 1.21.1 seam is one owner swap, and it must happen");
		ClassNode after = parse(routed);
		assertEquals(0, calls(after, LootTableEventBridgeInjector.EVENT_HOOKS, LootTableEventBridgeInjector.LOAD_LOOT_TABLE));
		assertEquals(1, calls(after, LootTableEventBridgeInjector.BRIDGE, LootTableEventBridgeInjector.LOAD_LOOT_TABLE));
		assertEquals(1, injector.routedSites());
		// The 1.21.1 descriptor is kept verbatim: the 1.21.1 bridge declares that same ResourceLocation shape.
		for (MethodNode m : after.methods) {
			for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn instanceof MethodInsnNode call && LootTableEventBridgeInjector.BRIDGE.equals(call.owner)) {
					assertEquals(LootTableEventBridgeInjector.LOAD_LOOT_TABLE_DESC_1211, call.desc);
				}
			}
		}
	}

	/** That class is not where 26.2 keeps the seam, so there it must stay untouched rather than half-routed. */
	@Test
	void theLootOnlyShapeIsScoredAsOneSiteNotThePair() {
		byte[] withTags = synthetic("net.minecraft.world.level.storage.loot.LootDataType",
				LootTableEventBridgeInjector.LOAD_LOOT_TABLE_DESC_1211, true, true);
		assertSame(withTags, new LootTableEventBridgeInjector()
				.transform(LootTableEventBridgeInjector.TARGET_1211, withTags, null),
				"a tag site in the 1.21.1 class means the shape is not the one this rule was written for");
	}

	@Test
	void switchedOffItStandsDownAndDeclaresNoAnchor() throws Exception {
		System.setProperty(LootTableEventBridgeInjector.PROPERTY, "off");
		byte[] original = bytesOf(OWNER);
		assertSame(original, new LootTableEventBridgeInjector().transform(LootTableEventBridgeInjector.TARGET, original, null));
		assertTrue(new LootTableEventBridgeInjector().anchors().anchors().isEmpty(), "off is a request, not a missed anchor");
	}

	// ---------------------------------------------------------------------------------------------------------------

	private static int calls(ClassNode node, String owner, String name) {
		int n = 0;
		for (MethodNode m : node.methods) {
			for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn instanceof MethodInsnNode c && owner.equals(c.owner) && name.equals(c.name)) n++;
			}
		}
		return n;
	}

	/** A class shaped like the seam: a static method calling the loot hook and/or the tag loader. */
	private static byte[] synthetic(boolean loot, boolean tags) {
		return synthetic(OWNER, LootTableEventBridgeInjector.LOAD_LOOT_TABLE_DESC, loot, tags);
	}

	/**
	 * The same seam under another name and descriptor, so the 1.21.1 shape ({@code LootDataType}, and
	 * {@code ResourceLocation} where 26.2 says {@code Identifier}) can be exercised without that base.
	 */
	private static byte[] synthetic(String internalName, String lootDesc, boolean loot, boolean tags) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_STATIC, "seam", "(Lnet/minecraft/core/HolderLookup$Provider;"
				+ "Lnet/minecraft/resources/Identifier;Lnet/minecraft/world/level/storage/loot/LootTable;"
				+ "Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/core/WritableRegistry;)V", null, null);
		mv.visitCode();
		if (loot) {
			mv.visitVarInsn(Opcodes.ALOAD, 0);
			mv.visitVarInsn(Opcodes.ALOAD, 1);
			mv.visitVarInsn(Opcodes.ALOAD, 2);
			mv.visitMethodInsn(Opcodes.INVOKESTATIC, LootTableEventBridgeInjector.EVENT_HOOKS,
					LootTableEventBridgeInjector.LOAD_LOOT_TABLE, lootDesc, false);
			mv.visitInsn(Opcodes.POP);
		}
		if (tags) {
			mv.visitVarInsn(Opcodes.ALOAD, 3);
			mv.visitVarInsn(Opcodes.ALOAD, 4);
			mv.visitMethodInsn(Opcodes.INVOKESTATIC, LootTableEventBridgeInjector.TAG_LOADER,
					LootTableEventBridgeInjector.LOAD_TAGS, LootTableEventBridgeInjector.LOAD_TAGS_DESC, false);
		}
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static byte[] bytesOf(String internal) throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(MERGED_BASE), "staged merged base absent");
		try (ZipFile zip = new ZipFile(MERGED_BASE.toFile())) {
			ZipEntry entry = zip.getEntry(internal + ".class");
			assertNotNull(entry, internal);
			try (InputStream in = zip.getInputStream(entry)) {
				return in.readAllBytes();
			}
		}
	}
}
