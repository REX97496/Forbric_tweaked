package net.forbric.kernel.transform;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Creates the server's Netty IO threads in MinecraftForge's SERVER thread group instead of NeoForge's.
 *
 * <p>This is the second half of one bug. Forge's {@code ForgeHooks.onCustomPayload} disconnects the player whenever the
 * side a payload arrived on differs from {@code EffectiveSide.get()}, and {@code EffectiveSide} answers {@code CLIENT}
 * for any thread whose group is not MinecraftForge's own {@code SidedThreadGroup}. Moving the server thread into that
 * group (see {@link net.forbric.kernel.runtime.KernelServerThreads#adoptForgeServerThreadGroup()}) did not stop the
 * disconnect, and a probe at {@code ServerGamePacketListenerImpl.handleCustomPayload} said why: the check runs on
 * {@code Netty Server IO #1}, whose group is still NeoForge's. {@code javap -c ServerConnectionListener} shows the cause
 * in both event-loop factories — they pass {@code net/neoforged/fml/util/thread/SidedThreadGroups.SERVER} to
 * {@code ThreadFactoryBuilder.setThreadFactory}.
 *
 * <p>So the two {@code getstatic} reads are replaced by a call that hands back MinecraftForge's group, which is itself a
 * {@code ThreadFactory}. Nothing else in those lambdas changes: the name format, the daemon flag and the builder stay
 * the class's own.
 *
 * <p>If the field moves or the shape changes, the transform reports a miss rather than pretending: the anchor is
 * {@code REQUIRED}, so a base where this no longer lands shows up as a repair that did not land.
 */
public final class ServerThreadGroupTransformer implements ClassTransformer {
	static final String TARGET = "net.minecraft.server.network.ServerConnectionListener";
	static final String FIELD_OWNER = "net/neoforged/fml/util/thread/SidedThreadGroups";
	static final String FIELD_NAME = "SERVER";
	static final String HELPER = "net/forbric/kernel/runtime/KernelServerThreads";
	static final String HELPER_NAME = "sharedServerThreadFactory";
	static final String HELPER_DESC = "()Ljava/util/concurrent/ThreadFactory;";

	@Override
	public String name() {
		return "forbric:server-thread-group";
	}

	@Override
	public AnchorSet anchors() {
		return AnchorSet.of(new AnchorSet.Anchor(TARGET.replace('/', '.'),
				AnchorSet.Severity.REQUIRED,
				"the server's Netty IO threads must be created in MinecraftForge's SERVER thread group, because Forge's "
						+ "custom-payload check reads the side of the thread it runs on and answers CLIENT for any group "
						+ "that is not its own"));
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (!TARGET.equals(className) || classBytes == null) return classBytes;
		ClassNode node = new ClassNode();
		new ClassReader(classBytes).accept(node, 0);
		int replaced = 0;
		for (MethodNode method : node.methods) {
			if (method.instructions == null) continue;
			for (AbstractInsnNode insn : method.instructions.toArray()) {
				if (!(insn instanceof FieldInsnNode field)) continue;
				if (field.getOpcode() != Opcodes.GETSTATIC) continue;
				if (!FIELD_OWNER.equals(field.owner) || !FIELD_NAME.equals(field.name)) continue;
				method.instructions.set(field, new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, HELPER_NAME,
						HELPER_DESC, false));
				replaced++;
			}
		}
		if (replaced == 0) return classBytes;
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		return writer.toByteArray();
	}
}
