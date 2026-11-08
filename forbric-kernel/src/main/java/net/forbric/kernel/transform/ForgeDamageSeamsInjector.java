/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import java.util.ArrayList;
import java.util.List;

import net.forbric.kernel.util.ForbricLog;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * MinecraftForge's Hurt, Damage and player-Attack events get their positions back in NeoForge's damage pipeline.
 *
 * <p>The merged {@code actuallyHurt} and {@code Player.hurtServer} are NeoForge's: nothing calls
 * {@code ForgeHooks.onLivingHurt}, {@code onLivingDamage} or {@code onPlayerAttack}, and no NeoForge event sits where
 * the first two were, so a bridge has nothing to listen to. Tombstone's ghost immunity, its Voodoo Poppet and its
 * damage perks are exactly these listeners, and all of them did nothing. Three seams, each an {@code invokestatic}
 * into {@code KernelLivingDamage} with nothing else that another mod's injector could match:
 *
 * <ul>
 *   <li><b>Hurt</b>, in {@code actuallyHurt} right after the {@code isInvulnerableTo} check and before armour —
 *       MinecraftForge's position. True from the helper returns, as MinecraftForge's {@code amount <= 0} does; a
 *       listener that killed the entity returns too, so NeoForge's "killed during LivingDamageEvent.Pre" check
 *       cannot throw.</li>
 *   <li><b>Damage</b>, right after the health damage is read back from the container (after armour, magic and
 *       absorption; after NeoForge's Pre and its dead check), rewriting that local.</li>
 *   <li><b>player Attack</b>, at the head of {@code Player.hurtServer}: before difficulty scaling and before the
 *       zero-damage return, so a snowball is still an attack. {@code Player.<clinit>} tells the attack forward the
 *       seam is in, so players are asked here and only here.</li>
 * </ul>
 *
 * <p>Each seam is placed only when its proof holds and no MinecraftForge call is already there.
 * {@code -Dforbric.forgeDamageSeams=off} leaves both classes as merged (the attack forward then asks every entity).
 */
public final class ForgeDamageSeamsInjector implements ClassTransformer {
	public static final String PROPERTY = "forbric.forgeDamageSeams";
	static final String LIVING = "net/minecraft/world/entity/LivingEntity";
	static final String PLAYER = "net/minecraft/world/entity/player/Player";
	static final String CONTAINER = "net/neoforged/neoforge/common/damagesource/DamageContainer";
	static final String RUNTIME = "net/forbric/kernel/runtime/KernelLivingDamage";
	static final String SOURCE = "Lnet/minecraft/world/damagesource/DamageSource;";
	static final String HURT_DESC = "(Lnet/minecraft/server/level/ServerLevel;" + SOURCE + "F)V";
	/** 1.21.1's shape: no {@code ServerLevel} parameter, and the container is peeked off {@code damageContainers}. */
	static final String SINGLE_HURT_DESC = "(" + SOURCE + "F)V";
	static final String SERVER_DESC = "(Lnet/minecraft/server/level/ServerLevel;" + SOURCE + "F)Z";
	static final String CONTAINER_STACK = "Ljava/util/Stack;";

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	@Override public String name() { return "forbric-forge-damage-seams"; }

	@Override public AnchorSet anchors() {
		if (!enabled()) return AnchorSet.scanned("MinecraftForge's Hurt, Damage and player-Attack events left undelivered "
				+ "with -D" + PROPERTY + "=off");
		String cost = "MinecraftForge's LivingHurtEvent and LivingDamageEvent never fire — damage perks, immunity and "
				+ "death-prevention in MinecraftForge mods do nothing";
		return AnchorSet.of(new AnchorSet.Anchor(LIVING.replace('/', '.'), AnchorSet.Severity.REQUIRED, cost),
				new AnchorSet.Anchor(PLAYER.replace('/', '.'), AnchorSet.Severity.REQUIRED, cost));
	}

	@Override public byte[] transform(String className, byte[] bytes, TransformContext context) {
		if (!enabled() || bytes == null || bytes.length == 0) return bytes;
		boolean player = className.equals(PLAYER.replace('/', '.'));
		if (!player && !className.equals(LIVING.replace('/', '.'))) return bytes;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		List<String> placed = repair(node);
		if (placed.isEmpty()) return bytes;
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		ForbricLog.info("[Forbric/Damage] %s: MinecraftForge's %s back in NeoForge's damage pipeline — nothing called "
				+ "them on the merged base", className, String.join(", ", placed));
		return writer.toByteArray();
	}

	/** Places every seam whose proof holds; the names placed. */
	static List<String> repair(ClassNode node) {
		List<String> placed = new ArrayList<>();
		MethodNode hurt = own(node, "actuallyHurt", HURT_DESC);
		if (hurt != null && hurtSeams(hurt)) placed.add("Hurt and Damage in actuallyHurt");
		MethodNode single = own(node, "actuallyHurt", SINGLE_HURT_DESC);
		if (single != null && singleSeams(single)) placed.add("Hurt and Damage in actuallyHurt (1.21.1 shape)");
		if (node.name.equals(PLAYER)) {
			MethodNode server = own(node, "hurtServer", SERVER_DESC);
			if (server != null && attackSeam(server) && noteSeam(node)) placed.add("player Attack in hurtServer");
		}
		return placed;
	}

	/**
	 * The same two seams on the 1.21.1 shape, where {@code actuallyHurt} takes {@code (DamageSource, float)} and reads
	 * its container with {@code this.damageContainers.peek()} rather than receiving one.
	 *
	 * <p>Measured on {@code patched-mc-merged-1.21.1.jar}: the method opens {@code aload_0; aload_1;
	 * invokevirtual isInvulnerableTo; ifne <end>} — note there is no {@code aload_2}, which is the single reason the
	 * container-shaped proof never matched this line. The damage local is the {@code fstore} directly after
	 * {@code CommonHooks.onLivingDamagePre} (offset 86 → {@code fstore_3}), not one fed by
	 * {@code DamageContainer.getNewDamage()}.
	 *
	 * <p>The container expression is legal here precisely because the injected code lands inside
	 * {@code LivingEntity} itself: a private field of this class is addressable from its own bytecode.
	 */
	static boolean singleSeams(MethodNode method) {
		if ((method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT)) != 0) return false;
		if (calls(method, "net/minecraftforge/common/ForgeHooks", "onLivingHurt") != 0
				|| calls(method, "net/minecraftforge/common/ForgeHooks", "onLivingDamage") != 0
				|| calls(method, RUNTIME, "hurt") != 0 || calls(method, RUNTIME, "damage") != 0) return false;
		List<AbstractInsnNode> code = code(method);
		// aload0 aload1 invokevirtual isInvulnerableTo; ifne <end>
		if (code.size() < 4 || !load(code.get(0), Opcodes.ALOAD, 0) || !load(code.get(1), Opcodes.ALOAD, 1)
				|| !(code.get(2) instanceof MethodInsnNode invulnerable) || !invulnerable.name.equals("isInvulnerableTo")
				|| code.get(3).getOpcode() != Opcodes.IFNE) return false;
		// The one fstore right after NeoForge's Pre: the health damage this hit goes on to apply.
		AbstractInsnNode pre = null, store = null;
		int stores = 0;
		for (AbstractInsnNode insn : code) {
			if (insn instanceof MethodInsnNode call && call.owner.equals("net/neoforged/neoforge/common/CommonHooks")
					&& call.name.equals("onLivingDamagePre")) pre = insn;
			if (pre != null && insn instanceof VarInsnNode v && v.getOpcode() == Opcodes.FSTORE
					&& nextReal(pre) == insn) {
				store = insn;
				stores++;
			}
		}
		if (stores != 1) return false;

		LabelNode end = new LabelNode(), go = new LabelNode();
		InsnList hurt = new InsnList();
		hurt.add(new VarInsnNode(Opcodes.ALOAD, 0));
		peeked(hurt);
		hurt.add(new VarInsnNode(Opcodes.ALOAD, 1));
		hurt.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "hurt",
				"(L" + LIVING + ";L" + CONTAINER + ";" + SOURCE + ")Z", false));
		hurt.add(new JumpInsnNode(Opcodes.IFNE, end));
		hurt.add(new VarInsnNode(Opcodes.ALOAD, 0));
		hurt.add(new FieldInsnNode(Opcodes.GETFIELD, LIVING, "dead", "Z"));
		hurt.add(new JumpInsnNode(Opcodes.IFEQ, go));
		hurt.add(end);
		hurt.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
		hurt.add(new InsnNode(Opcodes.RETURN));
		hurt.add(go);
		hurt.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
		method.instructions.insert(code.get(3), hurt);

		int slot = ((VarInsnNode) store).var;
		InsnList damage = new InsnList();
		damage.add(new VarInsnNode(Opcodes.ALOAD, 0));
		peeked(damage);
		damage.add(new VarInsnNode(Opcodes.ALOAD, 1));
		damage.add(new VarInsnNode(Opcodes.FLOAD, slot));
		damage.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "damage",
				"(L" + LIVING + ";L" + CONTAINER + ";" + SOURCE + "F)F", false));
		damage.add(new VarInsnNode(Opcodes.FSTORE, slot));
		method.instructions.insert(store, damage);
		return true;
	}

	/** {@code this.damageContainers.peek()}, cast back to the container — only legal inside {@link #LIVING}. */
	private static void peeked(InsnList out) {
		out.add(new VarInsnNode(Opcodes.ALOAD, 0));
		out.add(new FieldInsnNode(Opcodes.GETFIELD, LIVING, "damageContainers", CONTAINER_STACK));
		out.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/Stack", "peek", "()Ljava/lang/Object;", false));
		out.add(new TypeInsnNode(Opcodes.CHECKCAST, CONTAINER));
	}

	/** The first instruction after {@code from} that an interpreter would execute. */
	private static AbstractInsnNode nextReal(AbstractInsnNode from) {
		AbstractInsnNode insn = from.getNext();
		while (insn != null && insn.getOpcode() < 0) insn = insn.getNext();
		return insn;
	}

	/** Hurt after the invulnerability check, Damage after the health damage is read back. */
	static boolean hurtSeams(MethodNode method) {
		if ((method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT)) != 0) return false;
		if (calls(method, "net/minecraftforge/common/ForgeHooks", "onLivingHurt") != 0
				|| calls(method, "net/minecraftforge/common/ForgeHooks", "onLivingDamage") != 0
				|| calls(method, RUNTIME, "hurt") != 0) return false;
		List<AbstractInsnNode> code = code(method);
		// aload0 aload1 aload2 invokevirtual isInvulnerableTo; ifne <end>
		if (code.size() < 5 || !load(code.get(0), Opcodes.ALOAD, 0) || !load(code.get(1), Opcodes.ALOAD, 1)
				|| !load(code.get(2), Opcodes.ALOAD, 2)
				|| !(code.get(3) instanceof MethodInsnNode invulnerable) || !invulnerable.name.equals("isInvulnerableTo")
				|| code.get(4).getOpcode() != Opcodes.IFNE) return false;
		if (calls(method, "net/neoforged/neoforge/common/CommonHooks", "onLivingDamagePre") != 1
				|| calls(method, "com/google/common/base/Preconditions", "checkArgument") != 1) return false;
		// The one `getNewDamage(); fstore 3` after NeoForge's Pre: the health damage the method goes on to apply.
		AbstractInsnNode pre = null, store = null;
		int stores = 0;
		for (AbstractInsnNode insn : code) {
			if (insn instanceof MethodInsnNode call && call.name.equals("onLivingDamagePre")) pre = insn;
			if (pre != null && insn instanceof VarInsnNode v && v.getOpcode() == Opcodes.FSTORE && v.var == 3
					&& v.getPrevious() instanceof MethodInsnNode read && read.owner.equals(CONTAINER)
					&& read.name.equals("getNewDamage")) {
				store = insn;
				stores++;
			}
		}
		if (stores != 1) return false;

		LabelNode end = new LabelNode(), go = new LabelNode();
		InsnList hurt = new InsnList();
		hurt.add(new VarInsnNode(Opcodes.ALOAD, 0));
		container(hurt);
		hurt.add(new VarInsnNode(Opcodes.ALOAD, 2));
		hurt.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "hurt",
				"(L" + LIVING + ";L" + CONTAINER + ";" + SOURCE + ")Z", false));
		hurt.add(new JumpInsnNode(Opcodes.IFNE, end));
		hurt.add(new VarInsnNode(Opcodes.ALOAD, 0));
		hurt.add(new FieldInsnNode(Opcodes.GETFIELD, LIVING, "dead", "Z"));
		hurt.add(new JumpInsnNode(Opcodes.IFEQ, go));
		hurt.add(end);
		hurt.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
		hurt.add(new InsnNode(Opcodes.RETURN));
		hurt.add(go);
		hurt.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
		method.instructions.insert(code.get(4), hurt);

		InsnList damage = new InsnList();
		damage.add(new VarInsnNode(Opcodes.ALOAD, 0));
		container(damage);
		damage.add(new VarInsnNode(Opcodes.ALOAD, 2));
		damage.add(new VarInsnNode(Opcodes.FLOAD, 3));
		damage.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "damage",
				"(L" + LIVING + ";L" + CONTAINER + ";" + SOURCE + "F)F", false));
		damage.add(new VarInsnNode(Opcodes.FSTORE, 3));
		method.instructions.insert(store, damage);
		return true;
	}

	/** {@code if (!KernelLivingDamage.playerAttack(this, source, amount)) return false;} at the head. */
	static boolean attackSeam(MethodNode method) {
		if ((method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT)) != 0) return false;
		if (calls(method, "net/minecraftforge/common/ForgeHooks", "onPlayerAttack") != 0
				|| calls(method, RUNTIME, "playerAttack") != 0) return false;
		// Nothing jumps back to the first instruction: the seam's F_SAME frames are then relative to the entry frame.
		for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null && insn.getOpcode() < 0; insn = insn.getNext()) {
			if (insn instanceof FrameNode) return false;
		}
		if (code(method).isEmpty()) return false;
		LabelNode go = new LabelNode();
		InsnList attack = new InsnList();
		attack.add(new VarInsnNode(Opcodes.ALOAD, 0));
		attack.add(new VarInsnNode(Opcodes.ALOAD, 2));
		attack.add(new VarInsnNode(Opcodes.FLOAD, 3));
		attack.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "playerAttack", "(L" + LIVING + ";" + SOURCE + "F)Z", false));
		attack.add(new JumpInsnNode(Opcodes.IFNE, go));
		attack.add(new InsnNode(Opcodes.ICONST_0));
		attack.add(new InsnNode(Opcodes.IRETURN));
		attack.add(go);
		attack.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
		method.instructions.insert(attack);
		return true;
	}

	/** {@code KernelLivingDamage.notePlayerSeam()} first thing in {@code Player.<clinit>}. */
	static boolean noteSeam(ClassNode node) {
		MethodNode clinit = own(node, "<clinit>", "()V");
		if (clinit == null) return false;
		clinit.instructions.insert(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, "notePlayerSeam", "()V", false));
		return true;
	}

	/** {@code (DamageContainer) this.damageContainers.peek()} — NeoForge's own way of reading the hit's container. */
	private static void container(InsnList list) {
		list.add(new VarInsnNode(Opcodes.ALOAD, 0));
		list.add(new FieldInsnNode(Opcodes.GETFIELD, LIVING, "damageContainers", "Ljava/util/Stack;"));
		list.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/Stack", "peek", "()Ljava/lang/Object;", false));
		list.add(new TypeInsnNode(Opcodes.CHECKCAST, CONTAINER));
	}

	private static boolean load(AbstractInsnNode insn, int opcode, int slot) {
		return insn instanceof VarInsnNode v && v.getOpcode() == opcode && v.var == slot;
	}

	private static List<AbstractInsnNode> code(MethodNode method) {
		List<AbstractInsnNode> out = new ArrayList<>();
		for (AbstractInsnNode insn : method.instructions) if (insn.getOpcode() >= 0) out.add(insn);
		return out;
	}

	private static int calls(MethodNode method, String owner, String name) {
		int n = 0;
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name)) n++;
		}
		return n;
	}

	private static MethodNode own(ClassNode node, String name, String desc) {
		for (MethodNode method : node.methods) if (method.name.equals(name) && method.desc.equals(desc)) return method;
		return null;
	}
}
