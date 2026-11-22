/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import net.forbric.kernel.remap.GuestJarRemap;
import net.forbric.kernel.util.ForbricLog;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Rebinds an {@code @Shadow} field whose TYPE a Forge or NeoForge patch re-typed, and rewrites the one read pattern
 * that retype makes stale.
 *
 * <p>Measured on this line, from the merged base's own provenance copies: vanilla 1.21.1's
 * {@code net/minecraft/client/color/block/BlockColors} keeps its providers in a
 * {@code net/minecraft/core/IdMapper} ({@code META-INF/forbric/native-reference/FABRIC/…/BlockColors.class.bin}
 * mentions {@code IdMapper} three times), and both patched copies replace it with a map —
 * {@code native-reference/FORGE/…} and {@code native-reference/NEOFORGE/…} mention it zero times, and the merged
 * source says why in one line: {@code // Neo: Use the block instance directly as non-Vanilla block ids are not
 * constant}, over {@code private final java.util.Map<Block, BlockColor> blockColors = new
 * java.util.IdentityHashMap<>();}. {@code ItemColors} was re-typed the same way ({@code Map<Item, ItemColor>
 * itemColors}).
 *
 * <p>fabric-rendering-v1's {@code BlockColorsMixin}/{@code ItemColorsMixin} shadow those two fields as
 * {@code IdMapper} and read them through {@code IdMapper.byId(BuiltInRegistries.BLOCK.getId(block))}. The name is
 * not the problem — {@code field_1995 -> blockColors} is in the namespace table — the descriptor is: Mixin refuses
 * the whole mixin with {@code @Shadow field field_1995 are not declared by
 * net/minecraft/client/color/block/BlockColors}, and the pair is then taken out by the cast closure as well, because
 * each one is the only implementer of the other's {@code ColorProviderRegistryImpl$ColorMapperHolder}. The cost is
 * the whole fabric colour API: nothing ever calls {@code ColorProviderRegistryImpl.initialize(...)}, so every
 * {@code ColorProviderRegistry.BLOCK.register(...)} a fabric mod makes sits in a queue that is never drained.
 *
 * <p>So the field is re-typed to what the base actually declares, and the read pattern is rewritten onto the new
 * type: the {@code GETSTATIC <registry>} + {@code Registry.getId(Object)} + {@code IdMapper.byId(int)} triple becomes
 * a single {@code java.util.Map.get(Object)}. The choice of field is by generic value type (the {@code BlockColor} or
 * {@code ItemColor} inside the {@code IdMapper<>} vs the same type inside the base's {@code Map<…, …>}), never by
 * name, because the point of this repair is that the two sides disagree about the type. Two candidates, or a use
 * that is not the read pattern above, and nothing is changed: half a retype would define a class that fails
 * verification.
 *
 * <p>Unlike {@link MixinShadowFieldBind}, the field is renamed to the name the base carries, so Mixin binds it
 * directly and this repair does not depend on a refmap row existing for the field.
 */
final class MixinRetypedShadowBind {
	private static final String SHADOW = "Lorg/spongepowered/asm/mixin/Shadow;";
	private static final String ID_MAPPER = "Lnet/minecraft/core/IdMapper;";
	private static final String MAP = "Ljava/util/Map;";
	/**
	 * The same two types as they appear in a Signature attribute. A descriptor says {@code Ljava/util/Map;} with the
	 * semicolon right after the name; a generic signature says {@code Ljava/util/Map<…>;} with the semicolon last, so
	 * the descriptor constant cannot be used to recognise one. Round 206's first run asked
	 * {@code signature.startsWith("Ljava/util/Map;")}, never matched, read the whole {@code Map<Key, Value>} as a
	 * single {@code IdMapper<>} argument and matched no field at all.
	 */
	private static final String MAP_SIGNATURE = "Ljava/util/Map<";
	private static final String ID_MAPPER_SIGNATURE = "Lnet/minecraft/core/IdMapper<";
	private static final String ID_MAPPER_OWNER = "net/minecraft/core/IdMapper";
	/**
	 * The two calls are spelled twice. A guest jar ships intermediary member names —
	 * {@code javap -c} on {@code BlockColorsMixin} out of {@code fabric-rendering-v1-0.116.17.jar} shows
	 * {@code invokeinterface net/minecraft/class_7922.method_10206:(Ljava/lang/Object;)I} and
	 * {@code invokevirtual net/minecraft/class_2361.method_10200:(I)Ljava/lang/Object;} — while a class that has
	 * already been through {@code GuestJarRemap} carries the official {@code getId}/{@code byId}. Round 206's third
	 * run only knew the official spelling, so the read pattern never matched and the field was left alone with no
	 * line in the log.
	 */
	private static final String BY_ID = "byId";
	private static final String BY_ID_INTERMEDIARY = "method_10200";
	private static final String GET_ID = "getId";
	private static final String GET_ID_INTERMEDIARY = "method_10206";
	private static final String MAP_OWNER = "java/util/Map";
	private static final String MAP_GET = "get";

	private MixinRetypedShadowBind() {
	}

	static void bind(ClassNode node, Function<String, ClassNode> base) {
		apply(node, base);
	}

	/**
	 * The same repair on the bytes the guest-mixin adapter judges. It has to run there too: the adapter rejects a
	 * mixin whose {@code @Shadow} field the base does not declare, and it rejects it before Mixin ever asks for the
	 * class, so a repair that only ran on the weave path never got the chance. Round 206's first two runs logged the
	 * two colour mixins as suppressed with no repair line at all for exactly this reason.
	 */
	static byte[] asLoaded(byte[] bytes, Function<String, byte[]> resource) {
		if (bytes == null) return null;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		boolean changed = apply(node, name -> {
			byte[] base = resource.apply(name);
			if (base == null) return null;
			ClassNode read = new ClassNode();
			new ClassReader(base).accept(read, ClassReader.SKIP_CODE);
			return read;
		});
		if (!changed) return bytes;
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	static boolean apply(ClassNode node, Function<String, ClassNode> base) {
		if (node == null || node.fields == null || node.fields.isEmpty()) return false;
		List<FieldNode> shadows = new ArrayList<>();
		for (FieldNode field : node.fields) {
			if (!isIdMapper(field.desc)) continue;
			if (!has(field.visibleAnnotations, SHADOW) && !has(field.invisibleAnnotations, SHADOW)) continue;
			shadows.add(field);
		}
		if (shadows.isEmpty()) return false;
		List<String> resolved = new ArrayList<>();
		for (String target : MixinShadowFieldBind.targetsOf(node)) {
			resolved.add(target);
			// A class read straight out of a guest jar names its target in intermediary spelling
			// (net/minecraft/class_324), and the loader only resolves the official one, so the base lookup below
			// answers nothing without this hop. Measured on the source gate, which reads the jar bytes.
			String official = GuestJarRemap.officialClass(target);
			if (official != null && !official.equals(target)) resolved.add(official);
			String renamed = GuestJarRemap.classRenamedTo(target);
			if (renamed != null && !renamed.equals(target)) resolved.add(renamed);
		}
		List<ClassNode> readable = new ArrayList<>();
		for (String target : resolved) {
			ClassNode targetNode;
			try {
				targetNode = base.apply(target);
			} catch (RuntimeException unreadable) {
				targetNode = null;
			}
			if (targetNode != null) readable.add(targetNode);
		}		if (readable.isEmpty()) return false;
		List<String> bound = new ArrayList<>();
		for (FieldNode shadow : shadows) {
			String wanted = valueTypeOf(shadow.signature);
			if (wanted == null) continue;
			List<FieldNode> candidates = new ArrayList<>();
			for (ClassNode targetNode : readable) {
				for (FieldNode candidate : targetNode.fields) {
					if (!isMap(candidate.desc)) continue;
					if ((candidate.access & Opcodes.ACC_STATIC) != (shadow.access & Opcodes.ACC_STATIC)) continue;
					if (!wanted.equals(valueTypeOf(candidate.signature))) continue;
					candidates.add(candidate);
				}
			}
			if (candidates.size() != 1) {
				ForbricLog.info("[Forbric/Mixin] %s shadows %s as an IdMapper; %d field(s) of a map shape carry the"
						+ " same value type in the target, so which one it means is %s", node.name.replace('/', '.'),
						shadow.name, candidates.size(), candidates.isEmpty() ? "not here" : "a guess");
				continue;
			}
			FieldNode answer = candidates.get(0);
			if (!retype(node, shadow, answer)) {
				ForbricLog.info("[Forbric/Mixin] %s reads its IdMapper %s in a way this repair does not rewrite, so"
						+ " the mixin is left as the author wrote it", node.name.replace('/', '.'), shadow.name);
				continue;
			}
			bound.add(shadow.name + " -> " + answer.name + " " + answer.desc);
		}
		if (bound.isEmpty()) return false;
		ForbricLog.info("[Forbric/Mixin] re-typed %d @Shadow field(s) of %s to the map its target declares, and moved"
				+ " their one read to Map.get: %s", bound.size(), node.name.replace('/', '.'), bound);
		return true;
	}

	/**
	 * Re-types the shadow and moves every read of it onto {@link java.util.Map#get(Object)}. All or nothing: one use
	 * that is not the read pattern leaves the field exactly as it was.
	 */
	private static boolean retype(ClassNode node, FieldNode shadow, FieldNode answer) {
		// Collect first, change nothing: one use that is not the read pattern and the field stays as the author
		// wrote it, because a half-re-typed field is a class that fails verification at first use.
		List<MethodNode> owners = new ArrayList<>();
		List<MethodInsnNode> reads = new ArrayList<>();
		List<AbstractInsnNode[]> patterns = new ArrayList<>();
		for (MethodNode method : node.methods) {
			if (method.instructions == null) continue;
			for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (!(insn instanceof MethodInsnNode call)) continue;
				if (!isIdMapperOwner(call.owner)) continue;
				if (!BY_ID.equals(call.name) && !BY_ID_INTERMEDIARY.equals(call.name)) return false;
				AbstractInsnNode getId = previousCode(call);
				if (!(getId instanceof MethodInsnNode) || (!GET_ID.equals(((MethodInsnNode) getId).name) && !GET_ID_INTERMEDIARY.equals(((MethodInsnNode) getId).name))) return false;
				FieldInsnNode registry = registryBehind(getId, shadow);
				if (registry == null) return false;
				owners.add(method);
				reads.add(call);
				patterns.add(new AbstractInsnNode[] {registry, getId});
			}
		}
		if (reads.isEmpty()) return false;
		// Every use is the same read: rename the field, re-type it, and drop the id round-trip that only existed to
		// turn a Block into the int an IdMapper is addressed with.
		shadow.desc = answer.desc;
		shadow.signature = answer.signature;
		String was = shadow.name;
		shadow.name = answer.name;
		for (MethodNode method : node.methods) {
			if (method.instructions == null) continue;
			for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (!(insn instanceof FieldInsnNode access)) continue;
				if (!was.equals(access.name) || !isIdMapper(access.desc)) continue;
				if (access.owner != null && !access.owner.equals(node.name)) continue;
				access.name = answer.name;
				access.desc = answer.desc;
			}
		}
		for (int i = 0; i < reads.size(); i++) {
			MethodInsnNode read = reads.get(i);
			MethodNode method = owners.get(i);
			AbstractInsnNode[] pattern = patterns.get(i);
			// An interface call is a different opcode, not only a different pool entry. Editing the node in place
			// writes INVOKEVIRTUAL against a CONSTANT_InterfaceMethodref, and the JVM refuses the whole class:
			// "Illegal type at constant pool entry 267 in class net.minecraft.client.color.block.BlockColors",
			// thrown from Minecraft's constructor on the first run that got this far.
			method.instructions.set(read, new MethodInsnNode(Opcodes.INVOKEINTERFACE, MAP_OWNER, MAP_GET,
					"(Ljava/lang/Object;)Ljava/lang/Object;", true));
			method.instructions.remove(pattern[1]);
			method.instructions.remove(pattern[0]);
		}
		return true;
	}

	/**
	 * The {@code GETSTATIC <registry>} an {@code IdMapper.byId} read is addressed through, or null when the shape is
	 * not that read. The key expression sits between the registry read and the id call and its length differs per
	 * mixin: {@code BlockColorsMixin#get(Block)} pushes the argument itself, while
	 * {@code ItemColorsMixin#get(ItemConvertible)} asks {@code asItem()} first ({@code javap -c} on both classes as
	 * the guest jars ship them). Both keep {@code getfield <shadow>} immediately before the {@code getstatic}, which
	 * is what this walks back to.
	 */
	private static FieldInsnNode registryBehind(AbstractInsnNode getId, FieldNode shadow) {
		AbstractInsnNode cursor = previousCode(getId);
		for (int hops = 0; cursor != null && hops < 6; hops++) {
			if (cursor instanceof FieldInsnNode field) {
				if (field.getOpcode() != Opcodes.GETSTATIC) return null;
				AbstractInsnNode before = previousCode(field);
				if (before instanceof FieldInsnNode read && read.getOpcode() == Opcodes.GETFIELD
						&& shadow.name.equals(read.name) && isIdMapper(read.desc)) {
					return field;
				}
				return null;
			}
			if (cursor instanceof org.objectweb.asm.tree.VarInsnNode || cursor instanceof MethodInsnNode
					|| cursor instanceof org.objectweb.asm.tree.TypeInsnNode
					|| cursor instanceof org.objectweb.asm.tree.InsnNode) {
				cursor = previousCode(cursor);
				continue;
			}
			return null;
		}
		return null;
	}

	/** The previous instruction that is not a label, a line number or a frame. */
	private static AbstractInsnNode previousCode(AbstractInsnNode insn) {
		AbstractInsnNode cursor = insn == null ? null : insn.getPrevious();
		while (cursor != null && cursor.getOpcode() < 0) cursor = cursor.getPrevious();
		return cursor;
	}

	/**
	 * The one type argument inside {@code IdMapper<X>} or the VALUE argument of {@code Map<K, V>}, in internal form.
	 * Anything else (no signature, a raw type, a nested generic) answers null, which stops the repair.
	 */
	static String valueTypeOf(String signature) {
		if (signature == null || !signature.startsWith("L")) return null;
		int open = signature.indexOf('<');
		int close = signature.lastIndexOf('>');
		if (open < 0 || close <= open) return null;
		// Which container this is has to be read after mapping: a guest jar spells IdMapper as class_2361, and a
		// literal comparison with either descriptor constant finds neither shape.
		String owner = GuestJarRemap.officialClass(signature.substring(1, open));
		boolean map = MAP_OWNER.equals(owner);
		if (!map && !ID_MAPPER_OWNER.equals(owner)) return null;
		String inside = signature.substring(open + 1, close);
		if (map) {
			// Map<K, V>: the value type starts after the key's closing semicolon.
			int split = inside.indexOf(';');
			if (split < 0 || split + 1 >= inside.length()) return null;
			inside = inside.substring(split + 1);
		}
		if (inside.indexOf('<') >= 0) return null;
		if (inside.length() > 2 && inside.startsWith("L") && inside.endsWith(";")) {
			return GuestJarRemap.officialClass(inside.substring(1, inside.length() - 1));
		}
		return null;
	}

	/**
	 * Whether this descriptor names an {@code IdMapper}, in whatever spelling it was written. The guest-mixin
	 * adapter's source gate reads the class straight out of the mod jar, where the field is
	 * {@code Lnet/minecraft/class_2361;} — the intermediary name of {@code IdMapper} — while the base class it is
	 * compared against is official. Comparing those two as text is why this repair found no shadow at all in its
	 * first three runs.
	 */
	private static boolean isIdMapper(String desc) {
		return ID_MAPPER_OWNER.equals(ownerOf(desc));
	}

	private static boolean isMap(String desc) {
		return MAP_OWNER.equals(ownerOf(desc));
	}

	private static boolean isIdMapperOwner(String owner) {
		return owner != null && ID_MAPPER_OWNER.equals(GuestJarRemap.officialClass(owner));
	}

	/** The internal name inside a plain class descriptor, mapped to the name the base declares. */
	private static String ownerOf(String desc) {
		if (desc == null || desc.length() < 3 || !desc.startsWith("L") || !desc.endsWith(";")) return null;
		return GuestJarRemap.officialClass(desc.substring(1, desc.length() - 1));
	}

	private static boolean has(List<AnnotationNode> annotations, String desc) {
		if (annotations == null) return false;
		for (AnnotationNode annotation : annotations) {
			if (annotation != null && desc.equals(annotation.desc)) return true;
		}
		return false;
	}
}
