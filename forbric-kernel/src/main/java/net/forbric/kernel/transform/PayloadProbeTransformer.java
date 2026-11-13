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
	/**
	 * The client half. Curios opens an inventory whose container holds the same 46 slots as vanilla's, and the slots it
	 * adds come from a server-to-client payload — so whether the client dispatches clientbound custom payloads at all
	 * is the next thing to measure, and its type ids say which ones arrive.
	 */
	static final String CLIENT_TARGET = "net.minecraft.client.multiplayer.ClientPacketListener";
	/**
	 * The merged base's client entry point takes the payload itself, not the packet: NeoForge's patch routes the packet
	 * to {@code handleCustomPayload(CustomPacketPayload)}. A probe pinned to the vanilla packet shape finds nothing and
	 * says nothing, which is exactly how this was measured.
	 */
	static final String CLIENT_DESC = "(Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;)V";
	/**
	 * Where the client throws an unresolvable payload away. Probing both sites is what separates "the payload never
	 * arrived" from "it arrived and the client could not name its type" — from outside the connection those look the same.
	 */
	static final String CLIENT_DROP_METHOD = "handleUnknownCustomPayload";
	/**
	 * The packet's own entry point, one step before the payload reaches {@code ClientPacketListener}. Probing it splits
	 * "the payload arrived and the dispatch lost it" from "nothing was sent at all".
	 */
	static final String CLIENT_COMMON_TARGET = "net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl";
	static final String CLIENT_PACKET_DESC =
			"(Lnet/minecraft/network/protocol/common/ClientboundCustomPayloadPacket;)V";
	static final String HELPER = "net/forbric/kernel/runtime/KernelNetProbe";
	static final String HELPER_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)V";

	private static boolean said;
	private static boolean saidClient;

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (!Boolean.getBoolean(PROPERTY)) return classBytes;
		boolean server = TARGET.equals(className);
		boolean client = CLIENT_TARGET.equals(className);
		boolean clientPacket = CLIENT_COMMON_TARGET.equals(className);
		if (!server && !client && !clientPacket) return classBytes;
		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);
		String desc = server ? DESC : client ? CLIENT_DESC : CLIENT_PACKET_DESC;
		boolean touched = false;
		for (MethodNode method : node.methods) {
			if (!desc.equals(method.desc)) continue;
			String entry;
			if (server && METHOD.equals(method.name)) {
				entry = "see";
			} else if (client && METHOD.equals(method.name)) {
				entry = "seeClient";
			} else if (client && CLIENT_DROP_METHOD.equals(method.name)) {
				entry = "seeUnknown";
			} else if (clientPacket && METHOD.equals(method.name)) {
				entry = "seeClientPacket";
			} else {
				continue;
			}
			InsnList probe = new InsnList();
			probe.add(new VarInsnNode(Opcodes.ALOAD, 0));
			probe.add(new VarInsnNode(Opcodes.ALOAD, 1));
			probe.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, entry, HELPER_DESC, false));
			method.instructions.insert(probe);
			touched = true;
			if (server && !said) {
				said = true;
				ForbricLog.info("[Forbric/Probe] serverbound custom payloads are being watched: the thread, its group, "
						+ "the connection's direction and MinecraftForge's EffectiveSide, on every one (" + PROPERTY + ")");
			}
			if (client && !saidClient) {
				saidClient = true;
				ForbricLog.info("[Forbric/Probe] clientbound custom payloads are being watched too: the registered type "
						+ "id of every one that reaches the client, and every one it throws away (" + PROPERTY + ")");
			}
		}
		if (!touched) return classBytes;
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		return writer.toByteArray();
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
