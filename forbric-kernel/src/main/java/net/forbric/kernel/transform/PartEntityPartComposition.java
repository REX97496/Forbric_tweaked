/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Function;
import net.forbric.api.AncestorComposition;
import net.forbric.kernel.util.ForbricLog;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

/**
 * Proves that dropping {@code net.minecraftforge.entity.PartEntity} as an ancestor of
 * {@code net.minecraft.world.entity.boss.EnderDragonPart} loses no state, because the retained ancestor carries the
 * same state protocol.
 *
 * <p>The merged base declares that requirement itself, inside the jar
 * ({@code META-INF/forbric/required-ancestor-compositions.tsv}), so it cannot be withdrawn without rebuilding the
 * base. So a 1.21.1 launch stops on:
 *
 * <pre>
 *   LinkageError: Unresolved stateful ancestor composition for EnderDragonPart: retained
 *   net/neoforged/neoforge/entity/PartEntity, removed net/minecraftforge/entity/PartEntity.
 *   No protocol proved the final definition
 * </pre>
 *
 * <p>WHY THE PROOF IS LEGITIMATE RATHER THAN A RUBBER STAMP. Measured with javap against the 1.21.1 staged
 * runtimes, the two ancestors are identical member for member:
 *
 * <pre>
 *   net.neoforged.neoforge.entity.PartEntity&lt;T extends Entity&gt;   and
 *   net.minecraftforge.entity.PartEntity&lt;T extends Entity&gt;        both have
 *       private final T parent;   public PartEntity(T);   public T getParent();
 *       public Packet&lt;ClientGamePacketListener&gt; getAddEntityPacket(ServerEntity);
 * </pre>
 *
 * <p>and on 1.21.1 {@code EnderDragonPart} extends the neoforged one and nothing else. So this does not assert "trust
 * me": it reads both definitions through the same resolver the kernel uses, extracts every member that carries state
 * or moves it, and returns true only when the RETAINED ancestor's set CONTAINS the REMOVED one's. A member present
 * on the removed side and absent from the retained side fails the proof, which is exactly the loss this check exists
 * to catch.
 *
 * <p>WHAT THIS DOES NOT DO: it proves nothing about any class other than EnderDragonPart, and it does not claim the
 * two ancestors are interchangeable in general -- only that this root keeps every state member of the parent the
 * merge dropped.
 */
public final class PartEntityPartComposition implements AncestorComposition {

	/** The root the merged base names, and the two ancestors it names for it. */
	static final String ROOT = "net/minecraft/world/entity/boss/EnderDragonPart";
	static final String RETAINED = "net/neoforged/neoforge/entity/PartEntity";
	static final String REMOVED = "net/minecraftforge/entity/PartEntity";

	@Override
	public boolean proves(Requirement requirement, byte[] finalDefinition, Function<String, byte[]> resources) {
		if (requirement == null || finalDefinition == null) return false;
		if (!ROOT.equals(requirement.owner())) return false;
		if (!RETAINED.equals(requirement.retainedSuperclass())) return false;
		if (!REMOVED.equals(requirement.sourceSuperclass())) return false;

		// The root must actually extend the retained ancestor. If the merge chose the other one there is nothing for
		// this proof to say, and another one has to answer.
		if (!RETAINED.equals(superNameOf(finalDefinition))) return false;

		Set<String> retained = stateMembers(resources.apply(RETAINED + ".class"));
		Set<String> removed = stateMembers(resources.apply(REMOVED + ".class"));
		if (retained == null || removed == null) return false;

		// Containment, not equality: a retained ancestor that carries MORE is fine; one that carries LESS is the
		// failure this whole check exists to catch.
		if (!retained.containsAll(removed)) return false;

		ForbricLog.info("[Forbric/Composition] EnderDragonPart keeps every state member of the PartEntity the merge "
				+ "dropped (%d member(s)) — the retained neoforged ancestor carries the same parent field, (T) "
				+ "constructor, getParent and getAddEntityPacket, so dropping the forge parent loses no state",
				removed.size());
		return true;
	}

	/** The internal name of the declared superclass, or null when the bytes cannot be read. */
	private static String superNameOf(byte[] bytes) {
		if (bytes == null) return null;
		try {
			return new ClassReader(bytes).getSuperName();
		} catch (RuntimeException unreadable) {
			ForbricLog.warn("[Forbric/Composition] could not read a class definition while proving the PartEntity "
					+ "composition — reported as unproven, so the root is left uncomposed", unreadable);
			return null;
		}
	}

	/**
	 * Every member that carries state or moves it: fields with descriptors, and methods with full descriptors
	 * except the static initialiser (which carries no state across the boundary).
	 */
	private static Set<String> stateMembers(byte[] bytes) {
		if (bytes == null) return null;
		try {
			ClassNode node = new ClassNode();
			new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);
			Set<String> out = new LinkedHashSet<>();
			node.fields.forEach(f -> out.add("field " + f.name + " " + f.desc));
			node.methods.forEach(m -> {
				if (!"<clinit>".equals(m.name)) out.add("method " + m.name + " " + m.desc);
			});
			return out;
		} catch (RuntimeException unreadable) {
			ForbricLog.warn("[Forbric/Composition] could not parse a PartEntity definition — reported as unproven",
					unreadable);
			return null;
		}
	}
}
