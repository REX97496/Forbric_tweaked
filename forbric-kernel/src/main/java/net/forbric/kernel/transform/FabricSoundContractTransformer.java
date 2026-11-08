/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;
import java.util.function.Predicate;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
/**
 * Change only the native default. A sound overriding the native method still owns stream creation.
 *
 * <p>The guard used to be {@code node.interfaces.contains(API)}, which was always false: the interface is added by
 * fabric-sound-api-v1's SoundInstanceMixin, and transforms run before any mixin is applied. Measured on the 1.21.1
 * line as {@code [Forbric/Anchor] forbric-fabric-sound-contracts was handed net.minecraft.client.resources.sounds.
 * SoundInstance and made no edit — its anchor is gone}, because SoundSystemMixin (the half of Fabric API that would
 * have called the override) resolves no anchor on the merged base and is suppressed. The replacement body asks the
 * instance first and keeps vanilla's own resolution as the fallback, so it is correct whether or not the interface is
 * ever added.
 */
public final class FabricSoundContractTransformer implements ClassTransformer {
 public static final String PROPERTY="forbric.fabricSoundContracts",SOUND="net/minecraft/client/resources/sounds/SoundInstance",API="net/fabricmc/fabric/api/client/sound/v1/FabricSoundInstance";
 public static final String LIBRARY="Lnet/minecraft/client/sounds/SoundBufferLibrary;",DETAIL="Lnet/minecraft/client/resources/sounds/Sound;",FUTURE="Ljava/util/concurrent/CompletableFuture;",DESC="("+LIBRARY+DETAIL+"Z)"+FUTURE;
 public static final String HELPER="net/forbric/kernel/runtime/KernelSounds",HELPER_DESC="(Lnet/minecraft/client/resources/sounds/SoundInstance;"+LIBRARY+DETAIL+"Z)"+FUTURE;
 private final Predicate<String> present;
 public FabricSoundContractTransformer(Predicate<String> present){this.present=present;}
 private boolean enabled(){return !"off".equalsIgnoreCase(System.getProperty(PROPERTY,"on"))&&present.test(API);}
 @Override public String name(){return "forbric-fabric-sound-contracts";}
 @Override public AnchorSet anchors(){return enabled()?AnchorSet.of(new AnchorSet.Anchor(SOUND.replace('/','.'),AnchorSet.Severity.REQUIRED,"vanilla's default stream lookup must ask a Fabric sound instance before the buffer library")):AnchorSet.scanned("Fabric sound API absent or adapter explicitly disabled");}
 @Override public byte[] transform(String name,byte[] bytes,TransformContext context){
  if(!SOUND.replace('/','.').equals(name)||bytes==null||!enabled())return bytes;
  ClassNode node=new ClassNode();new ClassReader(bytes).accept(node,0);
  MethodNode method=node.methods.stream().filter(m->m.name.equals("getStream")&&m.desc.equals(DESC)).findFirst().orElse(null);
  if(method==null)return bytes;
  method.instructions.clear();method.tryCatchBlocks.clear();method.localVariables=null;var out=method.instructions;
  out.add(new VarInsnNode(Opcodes.ALOAD,0));out.add(new VarInsnNode(Opcodes.ALOAD,1));out.add(new VarInsnNode(Opcodes.ALOAD,2));out.add(new VarInsnNode(Opcodes.ILOAD,3));
  out.add(new MethodInsnNode(Opcodes.INVOKESTATIC,HELPER,"stream",HELPER_DESC,false));
  out.add(new InsnNode(Opcodes.ARETURN));method.maxStack=4;method.maxLocals=4;
  ClassWriter writer=new ClassWriter(0);node.accept(writer);return writer.toByteArray();
 }
}
