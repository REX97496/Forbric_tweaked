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
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.kernel.util.ForbricLog;

/**
 * Reports the widget list of a container screen, when {@code -Dforbric.probeScreens=true}.
 *
 * <p>The curios button is created and is not seen on screen. Between "created" and "drawn" there are two
 * separable questions: did the widget reach the screen's {@code children}, and where does it think it is. Pixels
 * cannot answer either one. This inserts a call just before {@code Screen.init(Minecraft,int,int)} returns - that
 * is, after {@code ScreenEvent.Init.Post} has been posted and every listener has had its chance to add a widget -
 * and hands the screen to {@link net.forbric.kernel.runtime.KernelScreenProbe}, which lists the children with
 * their coordinates.
 *
 * <p>It only installs under the property, so nothing is added to a client that is not being measured.
 */
public final class ScreenProbeTransformer implements ClassTransformer {
	static final String PROPERTY = "forbric.probeScreens";
	static final String TARGET = "net.minecraft.client.gui.screens.Screen";
	static final String METHOD = "init";
	static final String DESC = "(Lnet/minecraft/client/Minecraft;II)V";
	static final String HELPER = "net/forbric/kernel/runtime/KernelScreenProbe";
	static final String HELPER_DESC = "(Lnet/minecraft/client/gui/screens/Screen;)V";

	private static boolean said;

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (!TARGET.equals(className) || !Boolean.getBoolean(PROPERTY)) return classBytes;
		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);
		for (MethodNode method : node.methods) {
			if (!METHOD.equals(method.name) || !DESC.equals(method.desc)) continue;
			int added = 0;
			for (AbstractInsnNode instruction = method.instructions.getFirst(); instruction != null; ) {
				AbstractInsnNode next = instruction.getNext();
				if (instruction.getOpcode() == Opcodes.RETURN) {
					InsnList probe = new InsnList();
					probe.add(new VarInsnNode(Opcodes.ALOAD, 0));
					probe.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "dump", HELPER_DESC, false));
					method.instructions.insertBefore(instruction, probe);
					added += 1;
				}
				instruction = next;
			}
			if (added == 0) return classBytes;
			ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
			node.accept(writer);
			if (!said) {
				said = true;
				ForbricLog.info("[Forbric/Probe] container screens are being listed: every widget they end up with, "
						+ "with its coordinates (" + PROPERTY + ")");
			}
			return writer.toByteArray();
		}
		return classBytes;
	}

	@Override
	public String name() {
		return "forbric:screen-widget-probe";
	}

	@Override
	public AnchorSet anchors() {
		return AnchorSet.scanned("a diagnostic that is only installed under -D" + PROPERTY);
	}
}