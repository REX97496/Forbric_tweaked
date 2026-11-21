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
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Rebinds an {@code @Shadow} field whose name the target does not answer to the one field the target does have.
 *
 * <p>The two failures this repairs, both measured on this line: {@code fabric-game-rule-api-v1}'s
 * {@code GameRuleCommandVisitorMixin} shadows {@code field_19419} of the anonymous
 * {@code net.minecraft.server.commands.GameRuleCommand$1}, which declares {@code val$literalargumentbuilder}; and
 * {@code fabric-registry-sync-v0}'s {@code ClientRegistriesDynamicRegistriesMixin} shadows {@code field_48769} of
 * {@code RegistryDataCollector$ContentsCollector}, which declares {@code elements}. Mixin refuses the WHOLE mixin
 * for either, four times each per boot ({@code @Shadow field field_19419 was not located in the target class}),
 * and no refmap can help: neither jar's refmap carries a row for those fields, because in fabric's own environment
 * the name in the mixin was already the name in the target.
 *
 * <p>The namespace table does carry rows for them ({@code field_19419 -> val$base}, {@code field_48769 -> elements})
 * and one of those rows is WRONG for the shipped jar: the base kept javac's {@code val$literalargumentbuilder}, not
 * the source-level {@code val$base} the deobfuscator guessed. So the table is not used as the answer here. What is
 * used is the target itself: when no class in the target's hierarchy declares a field of the shadow's name and
 * exactly ONE field of the shadow's type (and static-ness) exists in the target, that field is the one the shadow
 * names. Two candidates and nothing happens, because two candidates is a guess.
 *
 * <p>The rename is applied to the field declaration and to every access to it inside the mixin, so Mixin binds the
 * shadow to the member it will actually find on the base.
 */
final class MixinShadowFieldBind {
	private static final String SHADOW = "Lorg/spongepowered/asm/mixin/Shadow;";
	private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";

	private MixinShadowFieldBind() {
	}

	static void bind(ClassNode node, Function<String, ClassNode> base) {
		if (node == null || node.fields == null || node.fields.isEmpty()) return;
		List<FieldNode> shadows = new ArrayList<>();
		for (FieldNode field : node.fields) {
			if (has(field.visibleAnnotations, SHADOW) || has(field.invisibleAnnotations, SHADOW)) shadows.add(field);
		}
		if (shadows.isEmpty()) return;
		List<String> targets = targetsOf(node);
		ForbricLog.debug("[Forbric/Mixin] shadow census %s: %d @Shadow field(s), targets %s",
				node.name.replace('/', '.'), shadows.size(), targets);
		if (targets.isEmpty()) return;
		// A mixin written against Yarn names its target the Yarn way ("…/server/command/GameRuleCommand$1"); the
		// refmap row that Mixin uses to retarget it was recorded by the remap pass, and the base only answers to the
		// name it carries. Both spellings are tried, so a mixin that already names the base works unchanged.
		List<String> resolved = new ArrayList<>();
		for (String target : targets) {
			resolved.add(target);
			String renamed = GuestJarRemap.classRenamedTo(target);
			if (renamed != null && !renamed.equals(target)) resolved.add(renamed);
		}
		List<String> bound = new ArrayList<>();
		// The target must be READ before anything can be said about it: a Yarn-spelled target the loader will not
		// hand out is not an answer of "it declares that field", and treating it as one is what made the first two
		// attempts do nothing at all.
		List<ClassNode> readable = new ArrayList<>();
		for (String target : resolved) {
			ClassNode targetNode;
			try {
				targetNode = base.apply(target);
			} catch (RuntimeException unreadable) {
				targetNode = null;
			}
			if (targetNode != null) readable.add(targetNode);
		}
		ForbricLog.debug("[Forbric/Mixin] shadow census %s resolved %s, %d readable", node.name.replace('/', '.'),
				resolved, readable.size());
		if (readable.isEmpty()) return;
		for (FieldNode shadow : shadows) {
			boolean declared = false;
			for (ClassNode targetNode : readable) {
				if (declares(base, targetNode, shadow.name, shadow.desc, 0)) declared = true;
			}
			if (declared) continue;
			Set<String> candidates = new TreeSet<>();
			for (ClassNode targetNode : readable) {
				for (FieldNode candidate : targetNode.fields) {
					if (!candidate.desc.equals(shadow.desc)) continue;
					if ((candidate.access & Opcodes.ACC_STATIC) != (shadow.access & Opcodes.ACC_STATIC)) continue;
					candidates.add(candidate.name);
				}
			}
			if (candidates.size() != 1) {
				if (!candidates.isEmpty()) {
					ForbricLog.debug("[Forbric/Mixin] %s shadows %s.%s, which the target does not declare; %d field(s)"
							+ " of its type are there, so which one it means is a guess and nothing is changed",
							node.name.replace('/', '.'), readable.get(0).name.replace('/', '.'), shadow.name, candidates.size());
				}
				continue;
			}
			String renamed = candidates.iterator().next();
			rename(node, shadow, renamed);
			bound.add(shadow.name + " -> " + renamed);
		}
		if (bound.isEmpty()) return;
		ForbricLog.info("[Forbric/Mixin] bound %d @Shadow field(s) of %s to the only field of their type in the"
				+ " target, because the target answers to neither their own name nor any refmap row: %s",
				bound.size(), node.name.replace('/', '.'), bound);
	}

	/** True when this class or any superclass it can read declares the member; an unreadable superclass stops the walk. */
	private static boolean declares(Function<String, ClassNode> base, ClassNode node, String name, String desc, int depth) {
		if (node == null || depth > 12) return false;
		for (FieldNode field : node.fields) {
			if (field.name.equals(name) && field.desc.equals(desc)) return true;
		}
		if (node.superName == null || node.superName.equals(node.name)) return false;
		ClassNode parent;
		try {
			parent = base.apply(node.superName);
		} catch (RuntimeException unreadable) {
			return false;
		}
		return declares(base, parent, name, desc, depth + 1);
	}

	private static void rename(ClassNode node, FieldNode shadow, String renamed) {
		String was = shadow.name;
		String desc = shadow.desc;
		shadow.name = renamed;
		for (MethodNode method : node.methods) {
			if (method.instructions == null) continue;
			for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (!(insn instanceof FieldInsnNode access)) continue;
				if (!access.name.equals(was) || !access.desc.equals(desc)) continue;
				if (access.owner != null && !access.owner.equals(node.name)) continue;
				access.name = renamed;
			}
		}
	}

	private static boolean has(List<AnnotationNode> annotations, String desc) {
		if (annotations == null) return false;
		for (AnnotationNode annotation : annotations) {
			if (annotation != null && desc.equals(annotation.desc)) return true;
		}
		return false;
	}

	/** The classes this mixin applies to, from {@code @Mixin(value = …, targets = …)}. */
	static List<String> targetsOf(ClassNode node) {
		Set<String> targets = new LinkedHashSet<>();
		AnnotationNode mixin = null;
		if (node.visibleAnnotations != null) {
			for (AnnotationNode annotation : node.visibleAnnotations) {
				if (annotation != null && MIXIN.equals(annotation.desc)) mixin = annotation;
			}
		}
		if (mixin == null && node.invisibleAnnotations != null) {
			for (AnnotationNode annotation : node.invisibleAnnotations) {
				if (annotation != null && MIXIN.equals(annotation.desc)) mixin = annotation;
			}
		}
		if (mixin == null || mixin.values == null) return new ArrayList<>(targets);
		for (int i = 0; i + 1 < mixin.values.size(); i += 2) {
			String key = String.valueOf(mixin.values.get(i));
			Object value = mixin.values.get(i + 1);
			if ("value".equals(key) && value instanceof List<?> types) {
				for (Object type : types) {
					if (type instanceof Type declared) targets.add(declared.getInternalName());
				}
			} else if ("targets".equals(key) && value instanceof List<?> names) {
				for (Object name : names) targets.add(String.valueOf(name).replace('.', '/'));
			}
		}
		return new ArrayList<>(targets);
	}
}
