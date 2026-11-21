/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.ArrayList;
import java.util.LinkedHashSet;
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
 *
 * <p>{@link #staysUnbound} answers the same question without changing anything, for the report that used to say a
 * member "cannot bind" before this pass had run: what is left after the rebind is the part that really will throw.
 */
final class MixinAccessorRebind {
	private static final String ACCESSOR = "Lorg/spongepowered/asm/mixin/gen/Accessor;";
	private static final String INVOKER = "Lorg/spongepowered/asm/mixin/gen/Invoker;";

	private MixinAccessorRebind() {
	}

	static void bind(ClassNode node, Function<String, ClassNode> base) {
		Plan plan = plan(node, base);
		if (plan == null) return;
		for (String guess : plan.guesses) ForbricLog.debug("[Forbric/Accessor] %s", guess);
		if (!plan.unbound.isEmpty()) {
			// Said here, where the target could be read, and nowhere else: the config scan that used to say it could
			// not read the target yet and called every one of these missing (thirty-eight of them, while this pass was
			// pointing forty-five). What is left in this list is what really will throw.
			ForbricLog.info("[Forbric/Accessor] %s asks this base for %d member(s) it does not declare, and no single"
					+ " member of the same shape is there to point them at, so the generated accessor will throw when"
					+ " called: %s", node.name.replace('/', '.'), plan.unbound.size(), plan.unbound);
		}
		if (plan.points.isEmpty()) return;
		for (int i = 0; i < plan.points.size(); i++) setStringValue(plan.points.get(i), plan.aims.get(i));
		ForbricLog.info("[Forbric/Accessor] pointed %d accessor member(s) of %s at the only member of their shape in"
				+ " the target, because it answers to neither the name the annotation asks for nor any refmap row: %s",
				plan.points.size(), node.name.replace('/', '.'), plan.described);
	}

	/** Everything one pass decides, held so that the same pass can be either applied or only asked about. */
	private static final class Plan {
		private final List<AnnotationNode> points = new ArrayList<>();
		private final List<String> aims = new ArrayList<>();
		private final List<String> described = new ArrayList<>();
		private final List<String> guesses = new ArrayList<>();
		private final Set<String> unbound = new LinkedHashSet<>();
	}

	private static Plan plan(ClassNode node, Function<String, ClassNode> base) {
		if (node == null) return null;
		List<String> targets = MixinShadowFieldBind.targetsOf(node);
		if (targets.isEmpty()) return null;
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
		Plan plan = new Plan();
		for (FieldNode field : node.fields) {
			AnnotationNode accessor = annotationIn(field.visibleAnnotations, ACCESSOR);
			if (accessor == null) accessor = annotationIn(field.invisibleAnnotations, ACCESSOR);
			if (accessor == null) continue;
			String asked = stringValue(accessor);
			String name = asked == null ? field.name : asked;
			String answer = readable.isEmpty() ? null
					: answerField(base, readable, name, field.desc, (field.access & Opcodes.ACC_STATIC) != 0);
			if (NAME_DECLARED.equals(answer)) continue;
			if (answer == null) {
				plan.unbound.add(name + ":" + field.desc);
				guess(plan, node, readable, name, candidatesOfFields(readable, field.desc, field.access));
				continue;
			}
			plan.points.add(accessor);
			plan.aims.add(answer);
			plan.described.add(field.name + " reads " + answer);
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
				if (accessor != null) accessorFieldName(node, base, readable, method, accessor, plan);
				continue;
			}
			String asked = stringValue(invoker);
			String name = asked == null ? method.name : asked;
			String answer = readable.isEmpty() ? null
					: answerMethod(base, readable, name, method.desc, (method.access & Opcodes.ACC_STATIC) != 0);
			if (NAME_DECLARED.equals(answer)) continue;
			if (answer == null) {
				plan.unbound.add(name + ":" + method.desc);
				continue;
			}
			plan.points.add(invoker);
			plan.aims.add(answer);
			plan.described.add(method.name + " calls " + answer);
		}
		return plan;
	}

	/** The sentinel {@link #answerField}/{@link #answerMethod} return when the target declares the name as asked. */
	private static final String NAME_DECLARED = "\u0000declared";

	/**
	 * Points one {@code @Accessor} on a method at the field the target has, and records it into {@code plan}.
	 *
	 * <p>A getter asks for the type it returns, a setter for the type it takes; anything else is not a field accessor
	 * and is left alone.
	 */
	private static void accessorFieldName(ClassNode node, Function<String, ClassNode> base, List<ClassNode> readable,
			MethodNode method, AnnotationNode accessor, Plan plan) {
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
		String answer = readable.isEmpty() ? null
				: answerField(base, readable, name, desc, (method.access & Opcodes.ACC_STATIC) != 0);
		if (NAME_DECLARED.equals(answer)) return;
		if (answer == null) {
			plan.unbound.add(name + ":" + desc);
			guess(plan, node, readable, name, candidatesOfFields(readable, desc, method.access));
			return;
		}
		plan.points.add(accessor);
		plan.aims.add(answer);
		plan.described.add(method.name + " reads " + answer);
	}

	/** Records the debug line for a name that is not there and has more than one member of its shape. */
	private static void guess(Plan plan, ClassNode node, List<ClassNode> readable, String name, Set<String> candidates) {
		if (candidates.isEmpty() || readable.isEmpty()) return;
		plan.guesses.add(String.format("%s names %s, which the target does not declare; %d field(s) of its type are"
				+ " there, so which one it means is a guess and nothing is changed",
				node.name.replace('/', '.'), name, candidates.size()));
	}

	private static Set<String> candidatesOfFields(List<ClassNode> readable, String desc, int access) {
		Set<String> candidates = new TreeSet<>();
		for (ClassNode targetNode : readable) {
			for (FieldNode candidate : targetNode.fields) {
				if (!candidate.desc.equals(desc)) continue;
				if ((candidate.access & Opcodes.ACC_STATIC) != (access & Opcodes.ACC_STATIC)) continue;
				candidates.add(candidate.name);
			}
		}
		return candidates;
	}

	/**
	 * The one field of that shape the target has, {@link #NAME_DECLARED} when it declares the name as asked, null when
	 * there is nothing or more than one thing to choose from.
	 */
	private static String answerField(Function<String, ClassNode> base, List<ClassNode> readable, String name,
			String desc, boolean isStatic) {
		for (ClassNode target : readable) {
			for (FieldNode field : target.fields) {
				if (field.name.equals(name) && field.desc.equals(desc)) return NAME_DECLARED;
			}
		}
		Set<String> candidates = new TreeSet<>();
		for (ClassNode targetNode : readable) {
			for (FieldNode candidate : targetNode.fields) {
				if (!candidate.desc.equals(desc)) continue;
				if (((candidate.access & Opcodes.ACC_STATIC) != 0) != isStatic) continue;
				candidates.add(candidate.name);
			}
		}
		return candidates.size() == 1 ? candidates.iterator().next() : null;
	}

	private static String answerMethod(Function<String, ClassNode> base, List<ClassNode> readable, String name,
			String desc, boolean isStatic) {
		for (ClassNode target : readable) {
			for (MethodNode method : target.methods) {
				if (method.name.equals(name) && method.desc.equals(desc)) return NAME_DECLARED;
			}
		}
		Set<String> candidates = new TreeSet<>();
		for (ClassNode targetNode : readable) {
			for (MethodNode candidate : targetNode.methods) {
				if (!candidate.desc.equals(desc)) continue;
				if (((candidate.access & Opcodes.ACC_STATIC) != 0) != isStatic) continue;
				candidates.add(candidate.name);
			}
		}
		return candidates.size() == 1 ? candidates.iterator().next() : null;
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
