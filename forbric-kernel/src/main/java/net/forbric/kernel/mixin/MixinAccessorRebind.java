/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

import net.forbric.kernel.remap.GuestJarRemap;
import net.forbric.kernel.util.ForbricLog;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Points an {@code @Accessor} or {@code @Invoker} at the member the base actually has, when the base has exactly one
 * member of the shape the annotation asked for.
 *
 * <p>An accessor names a member by name; Mixin looks that name up in the target and refuses the whole accessor mixin
 * when it is not there ({@code @Accessor field AxeItem.STRIPPED_BLOCKS cannot bind} — the base spells it
 * {@code STRIPPABLES}). Thirty-eight of those refusals, across twelve fabric modules, are in one boot's log of this
 * line, and each one leaves a mod without a table it wanted to read or write. The name difference comes from the
 * author's line: fabric wrote these against a version whose private fields were named the way the deobfuscator named
 * them, and the merged base keeps the javac name.
 *
 * <p>What is used as the answer is the target itself, by shape: if no class in the target's hierarchy declares a
 * field (or method) of the name the annotation asks for, and exactly ONE member of the same type and static-ness is
 * there, that is the member the accessor means. Two candidates and nothing is changed, because two candidates is a
 * guess. This is the same rule {@link MixinShadowFieldBind} applies to {@code @Shadow}, applied to the annotation that
 * carries a name of its own — and the same rule the kernel's suppressed-accessor stubs use at call time, so a member
 * that is rebound here and a stub that is implemented there agree about which member is meant.
 */
final class MixinAccessorRebind {
	private static final String ACCESSOR = "Lorg/spongepowered/asm/mixin/gen/Accessor;";
	private static final String INVOKER = "Lorg/spongepowered/asm/mixin/gen/Invoker;";

	private MixinAccessorRebind() {
	}

	static void bind(ClassNode node, Function<String, ClassNode> base) {
		if (node == null) return;
		List<String> targets = MixinShadowFieldBind.targetsOf(node);
		if (targets.isEmpty()) return;
		List<String> resolved = new ArrayList<>();
		for (String target : targets) {
			resolved.add(target);
			String renamed = GuestJarRemap.classRenamedTo(target);
			if (renamed != null && !renamed.equals(target)) resolved.add(renamed);
		}
		List<ClassNode> readable = new ArrayList<>();
		for (String target : resolved) {
			try {
				ClassNode targetNode = base.apply(target);
				if (targetNode != null) readable.add(targetNode);
			} catch (RuntimeException unreadable) {
				// A spelling the loader will not hand out says nothing about what the target declares.
			}
		}
		if (readable.isEmpty()) return;
		List<String> bound = new ArrayList<>();
		for (FieldNode field : node.fields) {
			AnnotationNode accessor = annotationIn(field.visibleAnnotations, ACCESSOR);
			if (accessor == null) accessor = annotationIn(field.invisibleAnnotations, ACCESSOR);
			if (accessor == null) continue;
			String asked = stringValue(accessor);
			String name = asked == null ? field.name : asked;
			if (declaresField(base, readable, name, field.desc)) continue;
			Set<String> candidates = new TreeSet<>();
			for (ClassNode targetNode : readable) {
				for (FieldNode candidate : targetNode.fields) {
					if (!candidate.desc.equals(field.desc)) continue;
					if ((candidate.access & Opcodes.ACC_STATIC) != (field.access & Opcodes.ACC_STATIC)) continue;
					candidates.add(candidate.name);
				}
			}
			if (candidates.size() != 1) {
				if (!candidates.isEmpty()) {
					ForbricLog.debug("[Forbric/Accessor] %s reads %s.%s, which the target does not declare; %d field(s)"
							+ " of its type are there, so which one it means is a guess and nothing is changed",
							node.name.replace('/', '.'), readable.get(0).name.replace('/', '.'), name, candidates.size());
				}
				continue;
			}
			String actual = candidates.iterator().next();
			setStringValue(accessor, actual);
			bound.add(field.name + " reads " + actual);
		}
		for (MethodNode method : node.methods) {
			AnnotationNode invoker = annotationIn(method.visibleAnnotations, INVOKER);
			if (invoker == null) invoker = annotationIn(method.invisibleAnnotations, INVOKER);
			if (invoker == null) {
				// An @Accessor on a METHOD is how fabric declares its private-field holders: an interface method whose
				// body Mixin replaces with a field read or write. Its field type comes from the method descriptor, not
				// from a field declaration, which is why the field loop above never sees AxeItemAccessor and friends.
				AnnotationNode accessor = annotationIn(method.visibleAnnotations, ACCESSOR);
				if (accessor == null) accessor = annotationIn(method.invisibleAnnotations, ACCESSOR);
				if (accessor != null) accessorFieldName(node, base, readable, method, accessor, bound);
				continue;
			}
			String asked = stringValue(invoker);
			String name = asked == null ? method.name : asked;
			if (declaresMethod(base, readable, name, method.desc)) continue;
			Set<String> candidates = new TreeSet<>();
			for (ClassNode targetNode : readable) {
				for (MethodNode candidate : targetNode.methods) {
					if (!candidate.desc.equals(method.desc)) continue;
					if ((candidate.access & Opcodes.ACC_STATIC) != (method.access & Opcodes.ACC_STATIC)) continue;
					candidates.add(candidate.name);
				}
			}
			if (candidates.size() != 1) continue;
			String actual = candidates.iterator().next();
			setStringValue(invoker, actual);
			bound.add(method.name + " calls " + actual);
		}
		if (bound.isEmpty()) return;
		ForbricLog.info("[Forbric/Accessor] pointed %d accessor member(s) of %s at the only member of their shape in"
				+ " the target, because it answers to neither the name the annotation asks for nor any refmap row: %s",
				bound.size(), node.name.replace('/', '.'), bound);
	}

	/**
	 * Points one {@code @Accessor} on a method at the field the target has, and reports it into {@code bound}.
	 *
	 * <p>A getter asks for the type it returns, a setter for the type it takes; anything else is not a field accessor
	 * and is left alone.
	 */
	private static void accessorFieldName(ClassNode node, Function<String, ClassNode> base, List<ClassNode> readable,
			MethodNode method, AnnotationNode accessor, List<String> bound) {
		Type[] args = Type.getArgumentTypes(method.desc);
		Type ret = Type.getReturnType(method.desc);
		String desc;
		if (args.length == 0 && ret.getSort() != Type.VOID) {
			desc = ret.getDescriptor();
		} else if (args.length == 1 && ret.getSort() == Type.VOID) {
			desc = args[0].getDescriptor();
		} else {
			return;
		}
		String asked = stringValue(accessor);
		String name = asked == null ? method.name : asked;
		if (declaresField(base, readable, name, desc)) return;
		Set<String> candidates = new TreeSet<>();
		for (ClassNode targetNode : readable) {
			for (FieldNode candidate : targetNode.fields) {
				if (!candidate.desc.equals(desc)) continue;
				if ((candidate.access & Opcodes.ACC_STATIC) != (method.access & Opcodes.ACC_STATIC)) continue;
				candidates.add(candidate.name);
			}
		}
		if (candidates.size() != 1) {
			if (!candidates.isEmpty()) {
				ForbricLog.debug("[Forbric/Accessor] %s names %s, which the target does not declare; %d field(s) of"
						+ " its type are there, so which one it means is a guess and nothing is changed",
						node.name.replace('/', '.'), name, candidates.size());
			}
			return;
		}
		String actual = candidates.iterator().next();
		setStringValue(accessor, actual);
		bound.add(method.name + " reads " + actual);
	}

	private static boolean declaresField(Function<String, ClassNode> base, List<ClassNode> targets, String name,
			String desc) {
		for (ClassNode target : targets) {
			for (FieldNode field : target.fields) {
				if (field.name.equals(name) && field.desc.equals(desc)) return true;
			}
		}
		return false;
	}

	private static boolean declaresMethod(Function<String, ClassNode> base, List<ClassNode> targets, String name,
			String desc) {
		for (ClassNode target : targets) {
			for (MethodNode method : target.methods) {
				if (method.name.equals(name) && method.desc.equals(desc)) return true;
			}
		}
		return false;
	}

	private static AnnotationNode annotationIn(List<AnnotationNode> annotations, String desc) {
		if (annotations == null) return null;
		for (AnnotationNode annotation : annotations) {
			if (annotation != null && desc.equals(annotation.desc)) return annotation;
		}
		return null;
	}

	/** The {@code value} of {@code @Accessor}/{@code @Invoker}, or null when the annotation does not carry one. */
	private static String stringValue(AnnotationNode annotation) {
		if (annotation.values == null) return null;
		for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
			if (!"value".equals(String.valueOf(annotation.values.get(i)))) continue;
			Object value = annotation.values.get(i + 1);
			return value instanceof String text ? text : null;
		}
		return null;
	}

	private static void setStringValue(AnnotationNode annotation, String value) {
		if (annotation.values == null) annotation.values = new ArrayList<>();
		for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
			if (!"value".equals(String.valueOf(annotation.values.get(i)))) continue;
			annotation.values.set(i + 1, value);
			return;
		}
		annotation.values.add("value");
		annotation.values.add(value);
	}


}
