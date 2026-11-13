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

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.kernel.util.ForbricLog;

/**
 * Reports where a serverbound custom payload is handled, when {@code -Dforbric.probePayloads=true}.
 *
 * <p>{@code ForgeHooks.onCustomPayload} disconnects the player when the side a packet arrived on differs from
 * {@code EffectiveSide.get()}. The first attempt at that mismatch moved the server thread into MinecraftForge's SERVER
 * thread group, and the disconnect still happened, so the next question is which thread actually runs the check and what
 * each side reads there. This inserts a call at the head of
 * {@code ServerGamePacketListenerImpl.handleCustomPayload} and hands both the listener and the packet to
 * {@link net.forbric.kernel.runtime.KernelNetProbe}.
 *
 * <p>It only installs under the property, so a client that is not being measured gets nothing.
 */
public final class PayloadProbeTransformer implements ClassTransformer {
	static final String PROPERTY = "forbric.probePayloads";
	static final String TARGET = "net.minecraft.server.network.ServerGamePacketListenerImpl";
	static final String METHOD = "handleCustomPayload";
	static final String DESC = "(Lnet/minecraft/network/protocol/common/ServerboundCustomPayloadPacket;)V";
	static final String HELPER = "net/forbric/kernel/runtime/KernelNetProbe";
	static final String HELPER_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)V";

	private static boolean said;

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (!TARGET.equals(className) || !Boolean.getBoolean(PROPERTY)) return classBytes;
		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);
		for (MethodNode method : node.methods) {
			if (!METHOD.equals(method.name) || !DESC.equals(method.desc)) continue;
			InsnList probe = new InsnList();
			probe.add(new VarInsnNode(Opcodes.ALOAD, 0));
			probe.add(new VarInsnNode(Opcodes.ALOAD, 1));
			probe.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "see", HELPER_DESC, false));
			method.instructions.insert(probe);
			ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
			node.accept(writer);
			if (!said) {
				said = true;
				ForbricLog.info("[Forbric/Probe] serverbound custom payloads are being watched: the thread, its group, "
						+ "the connection's direction and MinecraftForge's EffectiveSide, on every one (" + PROPERTY + ")");
			}
			return writer.toByteArray();
		}
		return classBytes;
	}

	@Override
	public String name() {
		return "forbric:payload-site-probe";
	}

	@Override
	public AnchorSet anchors() {
		return AnchorSet.scanned("a diagnostic that is only installed under -D" + PROPERTY);
	}
}
