package net.forbric.kernel.transform;

import java.util.ArrayList;
import java.util.List;

import net.forbric.api.Ecosystem;
import net.forbric.api.GameEventBridge;
import net.forbric.api.EventBridges;
import net.forbric.api.ForeignType;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Route MinecraftForge's block-tint registrations onto the live {@code BlockColors}, in whichever of the two shapes the
 * line's {@code createDefault} ends with: one that builds and posts the NeoForge event itself (26.2), or one that hands
 * the colours straight to NeoForge's own {@code ClientHooks.onBlockColorsInit} (1.21.1), where there is no event object
 * to retarget and the Forge event is posted alongside instead.
 */
public final class ForgeBlockTintInjector implements ClassTransformer {
	static final String TARGET = "net.minecraft.client.color.block.BlockColors";
	static final String HOOK = "net/forbric/kernel/runtime/KernelForgeBlockColors";
	static final String POST = "(Lnet/neoforged/bus/api/Event;)V";
	static final String CREATE_DEFAULT = "()Lnet/minecraft/client/color/block/BlockColors;";
	/** NeoForge's own colours init; on 1.21.1 it is the last thing createDefault does, and it posts the event itself. */
	static final String NEO_CLIENT_HOOKS = "net/neoforged/neoforge/client/ClientHooks";
	static final String COLORS_DESC = "(Lnet/minecraft/client/color/block/BlockColors;)V";

	@Override public String name() { return "forbric-forge-block-tints"; }
	@Override public AnchorSet anchors() {
		return AnchorSet.of(new AnchorSet.Anchor(TARGET, AnchorSet.Severity.REQUIRED,
				"MinecraftForge block tint registrations would never populate the live block colors"));
	}

	@Override public byte[] transform(String name, byte[] bytes, TransformContext context) {
		if (!TARGET.equals(name) || "off".equalsIgnoreCase(System.getProperty("forbric.forgeClientInit", "on"))) return bytes;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		MethodNode createDefault = null;
		for (var method : node.methods) {
			if (method.name.equals("createDefault") && method.desc.equals(CREATE_DEFAULT)) { createDefault = method; break; }
		}
		if (createDefault == null) return bytes;
		List<MethodInsnNode> posts = new ArrayList<>();
		List<MethodInsnNode> inits = new ArrayList<>();
		int repaired = 0;
		for (var instruction : createDefault.instructions) {
			if (!(instruction instanceof MethodInsnNode call)) continue;
			// Already ours: either shape counts as repaired, so a second transform pass is a no-op.
			if (call.owner.equals(HOOK) && ((call.name.equals("postBlockTintSources") && call.desc.equals(POST))
					|| (call.name.equals("postBlockTintColors") && call.desc.equals(COLORS_DESC)))) repaired++;
			if (call.getOpcode() == Opcodes.INVOKESTATIC && call.owner.equals(NEO_CLIENT_HOOKS)
					&& call.name.equals("onBlockColorsInit") && call.desc.equals(COLORS_DESC)) inits.add(call);
			if (call.getOpcode() != Opcodes.INVOKESTATIC
					|| !call.owner.equals(ForeignType.FML_MOD_LOADER.internal(Ecosystem.NEOFORGE))
					|| !call.name.equals("postEvent") || !call.desc.equals(POST)) continue;
			AbstractInsnNode previous = call.getPrevious();
			while (previous != null && previous.getOpcode() < 0) previous = previous.getPrevious();
			if (!(previous instanceof MethodInsnNode constructor)
					|| constructor.getOpcode() != Opcodes.INVOKESPECIAL || !constructor.name.equals("<init>")
					|| !constructor.owner.equals(ForeignType.BLOCK_TINT_EVENT.internal(Ecosystem.NEOFORGE))
					|| !constructor.desc.equals(COLORS_DESC)) return bytes;
			posts.add(call);
		}
		if (repaired != 0) return bytes;
		if (posts.size() == 1) {
			// 26.2: retarget the post so the kernel can add Forge's event over the same instance.
			MethodInsnNode target = posts.getFirst();
			target.owner = HOOK;
			target.name = "postBlockTintSources";
		} else if (posts.isEmpty() && inits.size() == 1) {
			// 1.21.1: the colours are on the stack under NeoForge's call; DUP keeps them there for Forge's event too.
			InsnList before = new InsnList();
			before.add(new InsnNode(Opcodes.DUP));
			before.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "postBlockTintColors", COLORS_DESC, false));
			createDefault.instructions.insertBefore(inits.getFirst(), before);
		} else {
			return bytes;
		}
		EventBridges.installed(GameEventBridge.BLOCK_TINT_SOURCES);
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}
}
