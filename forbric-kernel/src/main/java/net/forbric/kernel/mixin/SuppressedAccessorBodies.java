/*
 * Forbric - a Minecraft mod loader that runs Fabric and NeoForge mods on one merged base.
 * Copyright (c) Forbric contributors. All rights reserved.
 */

package net.forbric.kernel.mixin;

import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Turns the {@code Implemented via mixin} stubs of a suppressed {@code @Accessor}/{@code @Invoker} into calls to
 * {@code net.forbric.kernel.runtime.KernelSuppressedAccessor}, which reads or writes the field reflectively.
 *
 * <p>Measured need: on the merged base {@code fabric-data-attachment-api-v1}'s {@code CustomPayloadS2CPacketAccessor}
 * cannot bind — the kernel removes it from the config, the loader then refused the class entirely, and
 * {@code AttachmentChange.<clinit>} died on {@code IllegalClassLoadError} and took the mod's whole entrypoint. Defining
 * the class unchanged only moved the failure to {@code UnsupportedOperationException: Implemented via mixin} at
 * {@code CustomPayloadS2CPacketAccessor.getMaxPayloadSize(:28)}. The field it asks for exists on the base
 * ({@code ServerboundCustomPayloadPacket.MAX_PAYLOAD_SIZE}, private static int), so the accessor can keep its meaning.</p>
 */
public final class SuppressedAccessorBodies {

	private static final String ACCESSOR = "Lorg/spongepowered/asm/mixin/gen/Accessor;";
	private static final String INVOKER = "Lorg/spongepowered/asm/mixin/gen/Invoker;";
	private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
	private static final String HELPER = "net/forbric/kernel/runtime/KernelSuppressedAccessor";
	private static final String UNSUPPORTED = "java/lang/UnsupportedOperationException";
	private static final String IMPLEMENTED_VIA_MIXIN = "Implemented via mixin";

	private SuppressedAccessorBodies() {
	}

	/** The rewritten bytes (the same array when nothing matched) and how many method bodies were replaced. */
	public record Result(byte[] bytes, int implemented) {
	}

	public static Result implement(byte[] original) {
		if (original == null) return new Result(null, 0);
		ClassNode node = new ClassNode();
		new ClassReader(original).accept(node, 0);
		List<String> targets = mixinTargets(node);
		if (targets.isEmpty()) return new Result(original, 0);

		int implemented = 0;
		for (MethodNode method : node.methods) {
			AnnotationNode accessor = find(method, ACCESSOR);
			AnnotationNode invoker = accessor == null ? find(method, INVOKER) : null;
			if (accessor == null && invoker == null) continue;
			if (!isImplementedViaMixinStub(method)) continue;
			boolean rewritten = accessor != null
					? replaceAccessor(node.name, method, accessor, targets)
					: replaceInvoker(node.name, method, invoker, targets);
			if (rewritten) implemented++;
		}
		if (implemented == 0) return new Result(original, 0);

		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		return new Result(writer.toByteArray(), implemented);
	}

	private static List<String> mixinTargets(ClassNode node) {
		List<String> targets = new ArrayList<>(2);
		for (List<AnnotationNode> list : List.of(orEmpty(node.visibleAnnotations), orEmpty(node.invisibleAnnotations))) {
			for (AnnotationNode annotation : list) {
				if (!MIXIN.equals(annotation.desc)) continue;
				collectTypes(annotation, targets);
				collectNames(annotation, targets);
			}
		}
		return targets;
	}

	private static List<AnnotationNode> orEmpty(List<AnnotationNode> list) {
		return list == null ? List.of() : list;
	}

	private static void collectTypes(AnnotationNode annotation, List<String> into) {
		Object value = element(annotation, "value");
		if (!(value instanceof List<?> list)) return;
		for (Object each : list) {
			if (each instanceof Type type && (type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY)) {
				add(into, type.getClassName());
			} else if (each instanceof String name) {
				add(into, name.replace('/', '.'));
			}
		}
	}

	private static void collectNames(AnnotationNode annotation, List<String> into) {
		Object value = element(annotation, "targets");
		if (!(value instanceof List<?> list)) return;
		for (Object each : list) {
			if (each instanceof String name) add(into, name.replace('/', '.'));
		}
	}

	private static void add(List<String> into, String name) {
		if (name == null || name.isBlank() || name.startsWith("[") || into.contains(name)) return;
		into.add(name);
	}

	private static Object element(AnnotationNode annotation, String key) {
		if (annotation.values == null) return null;
		for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
			if (key.equals(annotation.values.get(i))) return annotation.values.get(i + 1);
		}
		return null;
	}

	private static AnnotationNode find(MethodNode method, String desc) {
		for (List<AnnotationNode> list : List.of(orEmpty(method.visibleAnnotations), orEmpty(method.invisibleAnnotations))) {
			for (AnnotationNode annotation : list) {
				if (desc.equals(annotation.desc)) return annotation;
			}
		}
		return null;
	}

	/** True when the body is exactly {@code throw new UnsupportedOperationException("Implemented via mixin")}. */
	private static boolean isImplementedViaMixinStub(MethodNode method) {
		boolean newUnsupported = false;
		boolean message = false;
		boolean thrown = false;
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW && UNSUPPORTED.equals(type.desc)) {
				newUnsupported = true;
			} else if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String text
					&& IMPLEMENTED_VIA_MIXIN.equals(text)) {
				message = true;
			} else if (insn instanceof InsnNode node && node.getOpcode() == Opcodes.ATHROW) {
				thrown = true;
			}
		}
		return newUnsupported && message && thrown;
	}

	private static boolean replaceAccessor(String owner, MethodNode method, AnnotationNode accessor,
			List<String> targets) {
		String field = memberName(element(accessor, "value"), method.name);
		if (field == null) return false;
		Type[] args = Type.getArgumentTypes(method.desc);
		Type ret = Type.getReturnType(method.desc);
		boolean isStatic = (method.access & Opcodes.ACC_STATIC) != 0;
		InsnList code = new InsnList();
		if (args.length == 0 && ret.getSort() != Type.VOID) {
			if (!isStatic) code.add(new VarInsnNode(Opcodes.ALOAD, 0));
			pushLookup(code, owner, targets, field, isStatic);
			code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "get",
					"(Ljava/lang/Class;[Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;"));
			unbox(code, ret);
			code.add(new InsnNode(ret.getOpcode(Opcodes.IRETURN)));
		} else if (args.length == 1 && ret.getSort() == Type.VOID) {
			pushLookup(code, owner, targets, field, isStatic);
			code.add(new VarInsnNode(loadOpcode(args[0]), isStatic ? 0 : 1));
			box(code, args[0]);
			code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "put",
					"(Ljava/lang/Class;[Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V"));
			code.add(new InsnNode(Opcodes.RETURN));
		} else {
			return false;
		}
		replaceBody(method, code);
		return true;
	}

	private static boolean replaceInvoker(String owner, MethodNode method, AnnotationNode invoker,
			List<String> targets) {
		String name = memberName(element(invoker, "value"), method.name);
		if (name == null) return false;
		Type[] args = Type.getArgumentTypes(method.desc);
		Type ret = Type.getReturnType(method.desc);
		boolean isStatic = (method.access & Opcodes.ACC_STATIC) != 0;
		int firstArg = isStatic ? 0 : 1;
		InsnList code = new InsnList();
		code.add(new LdcInsnNode(Type.getObjectType(owner)));
		pushStrings(code, targets);
		code.add(new LdcInsnNode(name));
		if (isStatic) {
			code.add(new InsnNode(Opcodes.ACONST_NULL));
		} else {
			code.add(new VarInsnNode(Opcodes.ALOAD, 0));
		}
		pushBoxedArgs(code, args, firstArg);
		code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "call",
				"(Ljava/lang/Class;[Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;"));
		if (ret.getSort() == Type.VOID) {
			code.add(new InsnNode(Opcodes.POP));
			code.add(new InsnNode(Opcodes.RETURN));
		} else {
			unbox(code, ret);
			code.add(new InsnNode(ret.getOpcode(Opcodes.IRETURN)));
		}
		replaceBody(method, code);
		return true;
	}

	private static void replaceBody(MethodNode method, InsnList code) {
		method.instructions = code;
		method.tryCatchBlocks = new ArrayList<>();
		method.localVariables = null;
		if ((method.access & Opcodes.ACC_ABSTRACT) != 0) method.access &= ~Opcodes.ACC_ABSTRACT;
		if ((method.access & Opcodes.ACC_NATIVE) != 0) method.access &= ~Opcodes.ACC_NATIVE;
	}

	private static void pushLookup(InsnList code, String owner, List<String> targets, String field,
			boolean isStatic) {
		code.add(new LdcInsnNode(Type.getObjectType(owner)));
		pushStrings(code, targets);
		code.add(new LdcInsnNode(field));
		if (isStatic) code.add(new InsnNode(Opcodes.ACONST_NULL));
	}

	private static void pushStrings(InsnList code, List<String> targets) {
		code.add(intConst(targets.size()));
		code.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/String"));
		for (int i = 0; i < targets.size(); i++) {
			code.add(new InsnNode(Opcodes.DUP));
			code.add(intConst(i));
			code.add(new LdcInsnNode(targets.get(i)));
			code.add(new InsnNode(Opcodes.AASTORE));
		}
	}

	private static void pushBoxedArgs(InsnList code, Type[] args, int firstArg) {
		code.add(intConst(args.length));
		code.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
		int slot = firstArg;
		for (int i = 0; i < args.length; i++) {
			code.add(new InsnNode(Opcodes.DUP));
			code.add(intConst(i));
			code.add(new VarInsnNode(loadOpcode(args[i]), slot));
			box(code, args[i]);
			code.add(new InsnNode(Opcodes.AASTORE));
			slot += args[i].getSize();
		}
	}

	private static AbstractInsnNode intConst(int value) {
		if (value >= -1 && value <= 5) return new InsnNode(Opcodes.ICONST_0 + value);
		if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) return new IntInsnNode(Opcodes.BIPUSH, value);
		return new IntInsnNode(Opcodes.SIPUSH, value);
	}

	private static int loadOpcode(Type type) {
		return switch (type.getSort()) {
			case Type.LONG -> Opcodes.LLOAD;
			case Type.FLOAT -> Opcodes.FLOAD;
			case Type.DOUBLE -> Opcodes.DLOAD;
			case Type.OBJECT, Type.ARRAY -> Opcodes.ALOAD;
			default -> Opcodes.ILOAD;
		};
	}

	private static void unbox(InsnList code, Type type) {
		switch (type.getSort()) {
			case Type.BOOLEAN -> {
				code.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Boolean"));
				code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z"));
			}
			case Type.BYTE, Type.SHORT, Type.CHAR, Type.INT -> {
				code.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Number"));
				code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Number", "intValue", "()I"));
				if (type.getSort() == Type.BYTE) code.add(new InsnNode(Opcodes.I2B));
				if (type.getSort() == Type.SHORT) code.add(new InsnNode(Opcodes.I2S));
				if (type.getSort() == Type.CHAR) code.add(new InsnNode(Opcodes.I2C));
			}
			case Type.LONG -> {
				code.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Number"));
				code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Number", "longValue", "()J"));
			}
			case Type.FLOAT -> {
				code.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Number"));
				code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Number", "floatValue", "()F"));
			}
			case Type.DOUBLE -> {
				code.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Number"));
				code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Number", "doubleValue", "()D"));
			}
			case Type.ARRAY -> code.add(new TypeInsnNode(Opcodes.CHECKCAST, type.getDescriptor()));
			case Type.OBJECT -> code.add(new TypeInsnNode(Opcodes.CHECKCAST, type.getInternalName()));
			default -> {
				// void never reaches here
			}
		}
	}

	private static void box(InsnList code, Type type) {
		switch (type.getSort()) {
			case Type.BOOLEAN -> code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf",
					"(Z)Ljava/lang/Boolean;"));
			case Type.BYTE -> code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Byte", "valueOf",
					"(B)Ljava/lang/Byte;"));
			case Type.SHORT -> code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Short", "valueOf",
					"(S)Ljava/lang/Short;"));
			case Type.CHAR -> code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Character", "valueOf",
					"(C)Ljava/lang/Character;"));
			case Type.INT -> code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf",
					"(I)Ljava/lang/Integer;"));
			case Type.LONG -> code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Long", "valueOf",
					"(J)Ljava/lang/Long;"));
			case Type.FLOAT -> code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Float", "valueOf",
					"(F)Ljava/lang/Float;"));
			case Type.DOUBLE -> code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf",
					"(D)Ljava/lang/Double;"));
			default -> {
				// reference types are already objects
			}
		}
	}

	/**
	 * The member name: the annotation's own {@code value} when it names one, else the method name with its
	 * {@code get}/{@code is}/{@code set}/{@code invoke}/{@code call} prefix removed — Mixin's default rule.
	 */
	private static String memberName(Object declared, String method) {
		if (declared instanceof String text && !text.isBlank()) return text;
		if (method.startsWith("get") && method.length() > 3) return decapitalise(method.substring(3));
		if (method.startsWith("is") && method.length() > 2) return decapitalise(method.substring(2));
		if (method.startsWith("set") && method.length() > 3) return decapitalise(method.substring(3));
		if (method.startsWith("invoke") && method.length() > 6) return decapitalise(method.substring(6));
		if (method.startsWith("call") && method.length() > 4) return decapitalise(method.substring(4));
		return null;
	}

	private static String decapitalise(String text) {
		if (text.isEmpty()) return text;
		return Character.toLowerCase(text.charAt(0)) + text.substring(1);
	}
}
