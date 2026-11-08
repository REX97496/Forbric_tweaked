/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;
import org.objectweb.asm.*;import org.objectweb.asm.tree.*;

/**
 * Keep native fluid predicates and event cancellation authoritative while calling the relocated guest hooks.
 *
 * <p>The host method carries a {@code ServerLevel} parameter on 26.2 but only the two air counts on 1.21.1
 * ({@code onLivingBreathe(LivingEntity, int, int)} — javap on the merged 1.21.1 base, which itself reads the level
 * with {@code LivingEntity.level()}). Where the level is not a parameter it is read off the entity the same way, so
 * the guest callbacks are never handed the entity where a level belongs.
 */
public final class BreathingCallbackInjector implements ClassTransformer {
	public static final String TARGET="net.neoforged.neoforge.common.CommonHooks";
	private static final String HOOK="net/forbric/kernel/interop/BreathingCallbackScope";
	static final String LIVING="net/minecraft/world/entity/LivingEntity";
	/** 26.2: the caller passes the level in. */
	static final String DESC_WITH_LEVEL="(L"+LIVING+";Lnet/minecraft/server/level/ServerLevel;II)V";
	/** 1.21.1: no level parameter at all — {@code level()} is read off the entity instead. */
	static final String DESC_PLAIN="(L"+LIVING+";II)V";
	static final String LEVEL_DESC="()Lnet/minecraft/world/level/Level;";
	static final String WATER="(L"+LIVING+";)Z";

	@Override public AnchorSet anchors(){return AnchorSet.of(new AnchorSet.Anchor(TARGET,AnchorSet.Severity.REQUIRED,"guest breathing hooks are lost on the native air-supply path"));}

	@Override public byte[] transform(String name,byte[] bytes,TransformContext context){
		if(!name.equals(TARGET))return bytes;ClassNode node=new ClassNode();new ClassReader(bytes).accept(node,0);
		MethodNode method=node.methods.stream().filter(m->m.name.equals("onLivingBreathe")&&(m.desc.equals(DESC_WITH_LEVEL)||m.desc.equals(DESC_PLAIN))).findFirst().orElse(null);if(method==null)return bytes;
		// Where the level is not the caller's second parameter it is read off the entity, exactly as the host does at 10:.
		boolean levelIsParameter=method.desc.equals(DESC_WITH_LEVEL);
		java.util.List<MethodInsnNode> water=new java.util.ArrayList<>();for(var i:method.instructions)if(i instanceof MethodInsnNode call){if(call.owner.equals(HOOK))return bytes;if(call.owner.equals("net/minecraft/world/effect/MobEffectUtil")&&call.name.equals("hasWaterBreathing")&&call.desc.equals(WATER))water.add(call);}
		if(water.size()!=1)return bytes;
		InsnList start=new InsnList();start.add(new VarInsnNode(Opcodes.ALOAD,0));level(start,levelIsParameter);start.add(new MethodInsnNode(Opcodes.INVOKESTATIC,HOOK,"lava","(Ljava/lang/Object;Ljava/lang/Object;)V",false));method.instructions.insert(start);
		// The entity is already on the stack under the boolean the call leaves, so only the level is pushed after it.
		MethodInsnNode call=water.getFirst();method.instructions.insertBefore(call,new InsnNode(Opcodes.DUP));InsnList after=new InsnList();level(after,levelIsParameter);after.add(new MethodInsnNode(Opcodes.INVOKESTATIC,HOOK,"water","(Ljava/lang/Object;ZLjava/lang/Object;)Z",false));method.instructions.insert(call,after);
		ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);node.accept(writer);return writer.toByteArray();
	}

	/** Leaves the level on the stack: the second parameter, or {@code entity.level()} where there is no parameter. */
	private static void level(InsnList code,boolean levelIsParameter){
		if(levelIsParameter){code.add(new VarInsnNode(Opcodes.ALOAD,1));return;}
		code.add(new VarInsnNode(Opcodes.ALOAD,0));
		code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,LIVING,"level",LEVEL_DESC,false));
	}
}
