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
 * Proves that dropping net.minecraftforge.entity.PartEntity as an ancestor of EnderDragonPart loses no state, because
 * the retained ancestor carries the same state protocol.
 *
 * <p>The merged base declares that requirement inside its own jar
 * (META-INF/forbric/required-ancestor-compositions.tsv), so it cannot be withdrawn without rebuilding the base, and a
 * 1.21.1 launch stops on it:
 *
 * <pre>
 *   LinkageError: Unresolved stateful ancestor composition for EnderDragonPart:
 *     retained net/neoforged/neoforge/entity/PartEntity,
 *     removed net/minecraftforge/entity/PartEntity
 * </pre>
 *
 * <p>MEASURED WITH javap, both ancestors on 1.21.1: {@code private final T parent}, {@code public PartEntity(T)},
 * {@code public T getParent()}, {@code public Packet&lt;ClientGamePacketListener&gt; getAddEntityPacket(ServerEntity)}
 * -- identical member for member. So this is a comparison, not an assertion: both definitions are read through the
 * resolver the kernel uses, every state-bearing member is extracted, and the proof answers true only when the
 * RETAINED set CONTAINS the REMOVED set. A member on the removed side and missing from the retained side fails it,
 * which is the loss this check exists to catch.
 *
 * <p>WHAT THIS DOES NOT DO: it proves nothing about any class other than EnderDragonPart, and does not claim the two
 * ancestors are interchangeable in general.
 */
public final class PartEntityPartComposition implements AncestorComposition {

	static final String ROOT = "net/minecraft/world/entity/boss/EnderDragonPart";
	// ForeignType.PART_ENTITY, not two inline literals: ForeignTypeTest requires that a concept written out under
	// BOTH Forge families goes through one row, so that a change cannot update one half and miss the other.
	static final String RETAINED = net.forbric.api.ForeignType.PART_ENTITY.internal(net.forbric.api.Ecosystem.NEOFORGE);
	static final String REMOVED = net.forbric.api.ForeignType.PART_ENTITY.internal(net.forbric.api.Ecosystem.FORGE);

	@Override
	public boolean proves(Requirement requirement, byte[] finalDefinition, Function<String, byte[]> resources) {
		if (requirement == null || finalDefinition == null) return false;
		if (!ROOT.equals(requirement.owner())) return false;
		if (!RETAINED.equals(requirement.retainedSuperclass())) return false;
		if (!REMOVED.equals(requirement.sourceSuperclass())) return false;
		if (!RETAINED.equals(superNameOf(finalDefinition))) return false;

		Set<String> retained = stateMembers(resources.apply(RETAINED + ".class"));
		Set<String> removed = stateMembers(resources.apply(REMOVED + ".class"));
		if (retained == null || removed == null) return false;
		if (!retained.containsAll(removed)) return false;

		ForbricLog.info("[Forbric/Composition] EnderDragonPart keeps every state member of the PartEntity the merge "
				+ "dropped (" + removed.size() + " member(s)) — the retained neoforged ancestor carries the same "
				+ "parent field, (T) constructor, getParent and getAddEntityPacket, so dropping the forge parent "
				+ "loses no state");
		return true;
	}

	private static String superNameOf(byte[] bytes) {
		if (bytes == null) return null;
		try {
			return new ClassReader(bytes).getSuperName();
		} catch (RuntimeException unreadable) {
			ForbricLog.warn("[Forbric/Composition] could not read a class definition while proving the PartEntity "
					+ "composition — reported as unproven", unreadable);
			return null;
		}
	}

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
