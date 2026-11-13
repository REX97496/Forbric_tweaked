/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import java.util.function.Predicate;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

/**
 * Lets {@code RegistryManager.getRegistry} answer a name the merge renamed.
 *
 * <p>Forge 1.21.1 declares {@code forge:condition_codecs} and three siblings in {@code ForgeRegistries$Keys}; the
 * merged base registers the identical registry objects under {@code neoforge:condition_codecs} and friends, because
 * its {@code RegistryManager} half comes from NeoForge's patched sources. Forge's own {@code ObjectHolderRegistry}
 * then asks for the {@code forge:} name, gets null, and throws 26 {@code Unable to find registry with key … for mod
 * "forge"} entries at shutdown (measured 2026-11-13, wrapped in {@code Failed to apply some object holders}).
 *
 * <p>{@code registries} is a {@code BiMap}, so the same registry cannot be stored twice — the lookup is the only
 * place a second name can be honoured. The replaced body is three instructions: call the helper, cast the result
 * back to the declared return type, return.
 */
public final class ForgeRegistryNameFallbackTransformer implements ClassTransformer {
	public static final String PROPERTY = "forbric.forgeRegistryNames";
	public static final String TARGET = "net/minecraftforge/registries/RegistryManager";
	public static final String ID = "Lnet/minecraft/resources/ResourceLocation;";
	public static final String REGISTRY = "Lnet/minecraftforge/registries/ForgeRegistry;";
	public static final String DESC = "(" + ID + ")" + REGISTRY;
	public static final String HELPER = "net/forbric/kernel/runtime/KernelForgeRegistryNames";
	public static final String HELPER_DESC = "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;";

	private final Predicate<String> present;

	public ForgeRegistryNameFallbackTransformer(Predicate<String> present) {
		this.present = present;
	}

	private boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on")) && present.test(TARGET);
	}

	@Override
	public String name() {
		return "forbric-forge-registry-names";
	}

	@Override
	public AnchorSet anchors() {
		// Scanned, not required, and the reason is measured rather than assumed: on 2026-11-13 the transformer was
		// registered (the ledger's declared count went 197 -> 198) and its target never arrived — no
		// "[Forbric/Anchor] forbric-forge-registry-names was handed ..." line, and the shutdown still produced the
		// same 26 "Unable to find registry with key forge:..." entries. net.minecraftforge.* classes are loaded by
		// Forge's own loader instead of passing through this chain, so a REPAIR claim here would be a claim the
		// pipeline cannot keep. The body stays in place for the day that class does load through; the lookup fix
		// has to come from the runtime side instead.
		return AnchorSet.scanned("net.minecraftforge.* classes load outside the transformer chain, so this repair "
				+ "cannot land; the 'forge:' to 'neoforge:' fallback needs the runtime (see KernelForgeRegistryNames)");
	}

	@Override
	public byte[] transform(String name, byte[] bytes, TransformContext context) {
		if (!TARGET.equals(name) || bytes == null || !enabled()) return bytes;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		MethodNode method = null;
		for (MethodNode each : node.methods) {
			if (each.name.equals("getRegistry") && each.desc.equals(DESC)) {
				method = each;
				break;
			}
		}
		if (method == null) return bytes;

		method.instructions.clear();
		method.tryCatchBlocks.clear();
		method.localVariables = null;
		var out = method.instructions;
		out.add(new VarInsnNode(Opcodes.ALOAD, 0));
		out.add(new VarInsnNode(Opcodes.ALOAD, 1));
		out.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "lookup", HELPER_DESC, false));
		out.add(new TypeInsnNode(Opcodes.CHECKCAST, "net/minecraftforge/registries/ForgeRegistry"));
		out.add(new InsnNode(Opcodes.ARETURN));
		method.maxStack = 2;
		method.maxLocals = 2;

		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}
}
