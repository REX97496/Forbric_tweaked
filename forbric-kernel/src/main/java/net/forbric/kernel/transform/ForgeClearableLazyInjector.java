/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import java.util.ArrayList;
import java.util.List;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import net.forbric.kernel.util.ForbricLog;

/**
 * Makes MinecraftForge's {@code ClearableLazy.concurrentOf(...).get()} return the value another thread just computed,
 * not {@code null}.
 *
 * <p>Its double-checked lock reads {@code instance} into a local, and if that is null takes the lock and checks again.
 * When the second check finds the value already there — another thread computed it while this one waited for the
 * lock — it leaves the lock and returns the FIRST read, which was null:
 * <pre>
 *   T ret = instance;
 *   if (ret == null) synchronized (lock) {
 *       if (instance == null) return instance = supplier.get();
 *   }
 *   return ret;            // null on the losing thread
 * </pre>
 * The only {@code ClearableLazy} on the merged game is {@code ChunkGenerator.featuresPerStep}, invalidated by
 * {@code refreshFeaturesPerStep()} (both ecosystems' server-start hooks call it). The next first read races between
 * worldgen threads, and the loser gets a null feature list: {@code Cannot invoke "java.util.List.size()" because
 * "featureList" is null} in {@code applyBiomeDecoration}, a failed chunk and a world that does not finish loading. With
 * C2ME's parallel worldgen it happened in about one start in ten.
 *
 * <p>The repair re-reads the field on that branch, inside the lock, into the same local — three instructions, the
 * return the method already makes then returns the computed value. Only the exact shape is changed; a body that
 * already re-reads is left alone, so the pass is idempotent. {@code -Dforbric.clearableLazyRace=off} leaves Forge's
 * body as it is.
 */
public final class ForgeClearableLazyInjector implements ClassTransformer {
	public static final String PROPERTY = "forbric.clearableLazyRace";
	static final String TARGET = "net.minecraftforge.common.util.ClearableLazy$Concurrent";
	private static final String OWNER = "net/minecraftforge/common/util/ClearableLazy$Concurrent";
	private static final String INSTANCE = "instance";

	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	@Override
	public String name() {
		return "forbric-forge-clearable-lazy";
	}

	@Override
	public AnchorSet anchors() {
		if (!enabled()) return AnchorSet.scanned("ClearableLazy left as MinecraftForge wrote it with -D" + PROPERTY + "=off");
		return AnchorSet.of(new AnchorSet.Anchor(TARGET, AnchorSet.Severity.HEDGE,
				"a worldgen thread that lost the race for ChunkGenerator.featuresPerStep would get a null feature list"));
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (!enabled() || classBytes == null || classBytes.length == 0 || !TARGET.equals(className)) return classBytes;
		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);
		MethodNode get = node.methods.stream()
				.filter(m -> "get".equals(m.name) && "()Ljava/lang/Object;".equals(m.desc)).findFirst().orElse(null);
		if (get == null || !rereadOnTheLosingBranch(get)) return classBytes;
		ForbricLog.info("[Forbric/Forge] ClearableLazy.concurrentOf(...).get() now returns the value another thread "
				+ "computed while it waited for the lock — it returned its first, null read, and a worldgen thread that "
				+ "lost the race for ChunkGenerator.featuresPerStep failed its chunk on a null feature list");
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		return writer.toByteArray();
	}

	/**
	 * Inserts {@code ret = instance} at the start of the branch where the locked check finds the value set.
	 *
	 * @return whether the body had exactly the shape described in the class javadoc and was changed
	 */
	static boolean rereadOnTheLosingBranch(MethodNode get) {
		List<AbstractInsnNode> code = new ArrayList<>();
		for (AbstractInsnNode insn : get.instructions) if (insn.getOpcode() >= 0) code.add(insn);
		// ret = instance; if (ret != null) ...
		if (code.size() < 4 || !isThisLoad(code.get(0)) || !readsInstance(code.get(1))
				|| !(code.get(2) instanceof VarInsnNode store) || store.getOpcode() != Opcodes.ASTORE) return false;
		int ret = store.var;
		// The locked check: GETFIELD instance; IFNONNULL found — after a MONITORENTER.
		JumpInsnNode found = null;
		boolean locked = false;
		for (int i = 3; i < code.size() - 1; i++) {
			if (code.get(i).getOpcode() == Opcodes.MONITORENTER) locked = true;
			if (locked && readsInstance(code.get(i)) && code.get(i + 1) instanceof JumpInsnNode jump
					&& jump.getOpcode() == Opcodes.IFNONNULL) {
				if (found != null) return false;
				found = jump;
			}
		}
		if (found == null) return false;
		// The found branch: ALOAD <lock copy>; MONITOREXIT; GOTO end — and end returns ret.
		AbstractInsnNode first = next(found.label);
		AbstractInsnNode exit = next(first);
		AbstractInsnNode jump = next(exit);
		if (!(first instanceof VarInsnNode) || first.getOpcode() != Opcodes.ALOAD || exit == null
				|| exit.getOpcode() != Opcodes.MONITOREXIT || !(jump instanceof JumpInsnNode go)
				|| go.getOpcode() != Opcodes.GOTO) return false;
		AbstractInsnNode load = next(go.label);
		AbstractInsnNode back = next(load);
		if (!(load instanceof VarInsnNode l) || l.getOpcode() != Opcodes.ALOAD || l.var != ret
				|| back == null || back.getOpcode() != Opcodes.ARETURN) return false;

		InsnList reread = new InsnList();
		reread.add(new VarInsnNode(Opcodes.ALOAD, 0));
		reread.add(new FieldInsnNode(Opcodes.GETFIELD, OWNER, INSTANCE, "Ljava/lang/Object;"));
		reread.add(new VarInsnNode(Opcodes.ASTORE, ret));
		get.instructions.insertBefore(first, reread);
		return true;
	}

	private static boolean isThisLoad(AbstractInsnNode insn) {
		return insn instanceof VarInsnNode v && v.getOpcode() == Opcodes.ALOAD && v.var == 0;
	}

	private static boolean readsInstance(AbstractInsnNode insn) {
		return insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETFIELD && OWNER.equals(f.owner)
				&& INSTANCE.equals(f.name);
	}

	/** The next real instruction after {@code from}, skipping labels, line numbers and frames. */
	private static AbstractInsnNode next(AbstractInsnNode from) {
		for (AbstractInsnNode insn = from == null ? null : from.getNext(); insn != null; insn = insn.getNext()) {
			if (insn.getOpcode() >= 0) return insn;
		}
		return null;
	}
}
