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
	static final String HOOK_DESC = "(Ljava/util/List;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V";

	/** Indices of the locals the merged base's own tail uses, read from its bytecode rather than assumed. */
	private static final int LIST_LOCAL = 5;
	private static final int STACK_LOCAL = 0;
	private static final int PLAYER_LOCAL = 2;
	private static final int FLAG_LOCAL = 3;

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
			AbstractInsnNode discard = forge.getNext();
			if (discard == null || discard.getOpcode() != Opcodes.POP) {
				ForbricLog.warn("[Forbric/EventMux] %s.%s calls %s.%s but does not discard its result — tooltip"
						+ " bridge not installed, NeoForge mods cannot add tooltip lines",
						TARGET, METHOD, FORGE_FACTORY, FORGE_CALL);
				continue;
			}
			method.instructions.insert(discard, hook());
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

	/** {@code KernelItemTooltip.afterForge(list, stack, player, flag)}. */
	private static InsnList hook() {
		InsnList code = new InsnList();
		code.add(new VarInsnNode(Opcodes.ALOAD, LIST_LOCAL));
		code.add(new VarInsnNode(Opcodes.ALOAD, STACK_LOCAL));
		code.add(new VarInsnNode(Opcodes.ALOAD, PLAYER_LOCAL));
		code.add(new VarInsnNode(Opcodes.ALOAD, FLAG_LOCAL));
		code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, RUNTIME, HOOK, HOOK_DESC, false));
		return code;
	}
}
