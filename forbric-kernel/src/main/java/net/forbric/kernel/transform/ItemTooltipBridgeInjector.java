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

import java.util.ArrayList;
import java.util.List;

import net.forbric.kernel.util.ForbricLog;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Posts NeoForge's {@code ItemTooltipEvent} where the merged base only posts MinecraftForge's.
 *
 * <h2>The exact instruction this hangs on</h2>
 *
 * <p>{@code ItemStack.getTooltipLines(Item$TooltipContext, Player, TooltipFlag)} ends:
 *
 * <pre>
 *   96: aload_0     this      (ItemStack)
 *   97: aload_2     Player
 *   98: aload 5     the List&lt;Component&gt; being built
 *  100: aload_3     TooltipFlag
 *  101: invokestatic ForgeEventFactory.onItemTooltip(ItemStack, Player, List, TooltipFlag)
 *  104: pop         its return value is discarded
 *  105: aload 5
 *  107: areturn
 * </pre>
 *
 * <p>The call is inserted AFTER the {@code pop}, not between it and the invoke: with the returned list still on
 * the stack, an inserted call would have to reason about what is under its arguments. After the pop the frame is
 * clean and every value this needs is a local — the list is 5, the stack is 0, the player is 2, the flag is 3.
 *
 * <p>The descriptor passed to the runtime method is {@code (List, Object, Object, Object)V} rather than the exact
 * Minecraft types. The verifier accepts a narrower argument for a wider parameter, and keeping Minecraft types out
 * of this class means the transformer still loads on an instance whose game classes were reshaped.
 *
 * <h2>When it declines</h2>
 *
 * <p>Applied only when the target method exists and holds exactly one {@code ForgeEventFactory.onItemTooltip}
 * call followed by a {@code pop}. Anything else — a reshaped tail, two calls, a return instead of a discard —
 * leaves the class byte-identical and says so once, because silently half-wiring a tooltip bridge is the failure
 * this whole path exists to end.
 */
public final class ItemTooltipBridgeInjector implements ClassTransformer {
	static final String TARGET = "net/minecraft/world/item/ItemStack";
	static final String METHOD = "getTooltipLines";
	static final String FORGE_FACTORY = "net/minecraftforge/event/ForgeEventFactory";
	static final String FORGE_CALL = "onItemTooltip";
	static final String RUNTIME = "net/forbric/kernel/runtime/KernelItemTooltip";
	static final String HOOK = "afterForge";
	static final String HOOK_DESC = "(Ljava/util/List;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V";

	/**
	 * Where the tooltip context lives: slot 1 of {@code getTooltipLines}, straight from its own signature
	 * {@code (Item$TooltipContext, Player, TooltipFlag)}. The same slot on both lines.
	 */
	private static final int CONTEXT_LOCAL = 1;

	/**
	 * The local a 26.2 base keeps its {@code TooltipDisplay} in. Only read when the runtime resolved the
	 * six-parameter event shape; a 1.21.1 base has no such type and never asks for this slot.
	 */
	private static final int DISPLAY_LOCAL = 4;

	/**
	 * How many argument loads to read off the {@code onItemTooltip} call site to learn where its four values live.
	 *
	 * <p>They used to be four hard-coded constants ({@code stack=0, player=2, list=5, flag=3}) and the list one was
	 * WRONG for this base: measured with javap, {@code getTooltipLines} is
	 * {@code (Item$TooltipContext, Player, TooltipFlag)} on an instance method, so its locals are
	 * {@code 0=this, 1=context, 2=player, 3=flag, 4=list} — reading slot 5 loaded whatever came after, and the
	 * bridge either failed verification or passed the wrong object. Reading the call's own operand loads removes
	 * the guess for every line at once, which is what the constants were trying to pin down by hand.
	 */
	private static final int OPERANDS = 4;

	private static volatile boolean reported;

	@Override
	public String name() {
		return "ItemTooltipBridgeInjector";
	}

	/**
	 * What this transformer claims, declared rather than left to be inferred.
	 *
	 * <p>The first version of this class implemented neither this nor {@code claims()}, and the census caught it:
	 * {@code InjectorExecutionCensusTest} reported {@code undeclared: ItemTooltipBridgeInjector} and
	 * {@code TransformerAnchorCensusTest} reported that {@code anchors()} returning undeclared "says nothing
	 * either". Both are right. A transformer that does not say what it hangs on cannot be listed as missing when
	 * its anchor moves — which is precisely how {@code ITEM_TOOLTIP} became a standing ERROR in the first place:
	 * declared in the enum, wired nowhere, reported forever.
	 *
	 * <p>{@code REQUIRED}, not optional: the merged tail always has the Forge call this hangs on, and if it ever
	 * stops having it the tooltip bridge is gone. Saying so keeps that loss visible.
	 */
	@Override
	public AnchorSet anchors() {
		return AnchorSet.of(new AnchorSet.Anchor(TARGET, AnchorSet.Severity.REQUIRED,
				"NeoForge mods cannot add lines to item tooltips — ItemTooltipEvent is never posted, so a mod that"
						+ " appends one adds it to nothing. The bridge exists because this was declared in"
						+ " GameEventBridge and wired nowhere"));
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		String normalised = className.replace('.', '/');
		if (!TARGET.equals(normalised)) return classBytes;

		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);
		boolean changed = false;
		for (MethodNode method : node.methods) {
			if (!METHOD.equals(method.name) || method.instructions == null) continue;
			MethodInsnNode forge = findForgeCall(method);
			if (forge == null) continue;
			AbstractInsnNode discard = nextReal(forge);
			if (discard == null || discard.getOpcode() != Opcodes.POP) {
				ForbricLog.warn("[Forbric/EventMux] %s.%s calls %s.%s but does not discard its result — tooltip"
						+ " bridge not installed, NeoForge mods cannot add tooltip lines",
						TARGET, METHOD, FORGE_FACTORY, FORGE_CALL);
				continue;
			}
			int[] operands = operandsOf(forge);
			if (operands == null) {
				ForbricLog.warn("[Forbric/EventMux] %s.%s's %s.%s call does not read its four values straight out of"
						+ " locals — tooltip bridge not installed, NeoForge mods cannot add tooltip lines",
						TARGET, METHOD, FORGE_FACTORY, FORGE_CALL);
				continue;
			}
			method.instructions.insert(discard, hook(operands));
			changed = true;
		}

		if (changed) {
			// The bridge is live the moment the tail is woven: the call site exists, the runtime method resolves
			// it, and nothing further has to happen. Recording it here is what stops EventBridges.verifyAll()
			// reporting ITEM_TOOLTIP as a silent feature loss — which is the whole point of the registry.
			net.forbric.api.EventBridges.installed(net.forbric.api.GameEventBridge.ITEM_TOOLTIP);
		}

		if (!changed) {
			if (!reported) {
				reported = true;
				// Once, not per tooltip: this runs on class load, but a reshape that removes the Forge call would
				// otherwise be invisible — the same silent absence the bridge exists to end.
				ForbricLog.warn("[Forbric/EventMux] %s.%s has no %s.%s call to hang the tooltip bridge on —"
						+ " NeoForge mods cannot add tooltip lines this session",
						TARGET, METHOD, FORGE_FACTORY, FORGE_CALL);
			}
			return classBytes;
		}
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		return writer.toByteArray();
	}

	/** The single {@code ForgeEventFactory.onItemTooltip} call in {@code method}, or null when absent or doubled. */
	private static MethodInsnNode findForgeCall(MethodNode method) {
		List<MethodInsnNode> found = new ArrayList<>(1);
		for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC
					&& FORGE_FACTORY.equals(call.owner) && FORGE_CALL.equals(call.name)) {
				found.add(call);
			}
		}
		return found.size() == 1 ? found.get(0) : null;
	}

	/** The next instruction that actually executes — skips labels, line numbers and frames. */
	private static AbstractInsnNode nextReal(AbstractInsnNode from) {
		for (AbstractInsnNode insn = from.getNext(); insn != null; insn = insn.getNext()) {
			if (insn.getOpcode() >= 0) return insn;
		}
		return null;
	}

	/**
	 * The four local slots the {@code onItemTooltip} call loads its arguments from, in call order
	 * ({@code stack, player, list, flag}), or null when the call site is not exactly four plain loads.
	 *
	 * <p>Read rather than assumed: the values are already in locals when the call happens, so the loads that push
	 * them ARE the answer, and no guess about a particular base's slot layout can be wrong. Anything else — a
	 * computed operand, more or fewer than four, a receiver on the stack — means this tail is not the shape the
	 * bridge knows how to extend, and declining is better than weaving a call with the wrong arguments.
	 */
	private static int[] operandsOf(MethodInsnNode call) {
		int[] slots = new int[OPERANDS];
		AbstractInsnNode cursor = call.getPrevious();
		for (int i = OPERANDS - 1; i >= 0; i--) {
			while (cursor != null && cursor.getOpcode() < 0) cursor = cursor.getPrevious();
			if (!(cursor instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD) return null;
			slots[i] = load.var;
			cursor = cursor.getPrevious();
		}
		return slots;
	}

	/** {@code KernelItemTooltip.afterForge(list, stack, player, flag, context, display)}. */
	private static InsnList hook(int[] operands) {
		InsnList code = new InsnList();
		code.add(new VarInsnNode(Opcodes.ALOAD, operands[2])); // list
		code.add(new VarInsnNode(Opcodes.ALOAD, operands[0])); // stack
		code.add(new VarInsnNode(Opcodes.ALOAD, operands[1])); // player
		code.add(new VarInsnNode(Opcodes.ALOAD, operands[3])); // flag
		code.add(new VarInsnNode(Opcodes.ALOAD, CONTEXT_LOCAL));
		code.add(new VarInsnNode(Opcodes.ALOAD, DISPLAY_LOCAL));
		code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, HOOK, HOOK_DESC, false));
		return code;
	}
}
