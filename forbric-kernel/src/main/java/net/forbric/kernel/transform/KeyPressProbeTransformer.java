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
 * Reports every key the client's handler is given, when {@code -Dforbric.probeKeys=true}.
 *
 * <p>This exists because "F3 does nothing" has two very different causes - the event never arrives, or it arrives
 * and the branch is skipped - and only the second one is a bug in the client. The probe adds one call at the head
 * of {@code KeyboardHandler.keyPress} that hands the handler, the key and the action to
 * {@link net.forbric.kernel.runtime.KernelKeyProbe}, which reads the screen and {@code handledDebugKey} back from
 * the instance and logs each distinct combination once.
 *
 * <p>It edits nothing else and installs only under the property, so the shipped client is unchanged: the ledger
 * would otherwise count a diagnostic as a repair.
 */
public final class KeyPressProbeTransformer implements ClassTransformer {
	static final String PROPERTY = "forbric.probeKeys";
	static final String TARGET = "net.minecraft.client.KeyboardHandler";
	static final String METHOD = "keyPress";
	static final String DESC = "(JIIII)V";
	static final String HELPER = "net/forbric/kernel/runtime/KernelKeyProbe";
	static final String HELPER_DESC = "(Ljava/lang/Object;II)V";

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
			probe.add(new VarInsnNode(Opcodes.ILOAD, 3));
			probe.add(new VarInsnNode(Opcodes.ILOAD, 5));
			probe.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "record", HELPER_DESC, false));
			method.instructions.insert(probe);
			ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
			node.accept(writer);
			if (!said) {
				said = true;
				ForbricLog.info("[Forbric/Probe] keyPress is being watched: every key, its action, the screen in hand "
						+ "and handledDebugKey are logged once per distinct combination (" + PROPERTY + ")");
			}
			return writer.toByteArray();
		}
		return classBytes;
	}

	@Override
	public String name() {
		return "forbric:key-press-probe";
	}

	@Override
	public AnchorSet anchors() {
		return AnchorSet.scanned("a diagnostic that is only installed under -D" + PROPERTY);
	}
}
