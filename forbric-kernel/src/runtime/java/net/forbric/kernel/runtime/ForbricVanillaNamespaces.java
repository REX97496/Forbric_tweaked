/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.forbric.kernel.util.ForbricLog;

/**
 * The namespaces the merged base actually carries, so the client's own pack serves all of them.
 *
 * <p>Measured failure, from a real client launch that had already reached Rendering overlay:
 *
 * <pre>
 *   ModLoadingException: neoforge encountered an error dispatching RegisterShadersEvent
 *     ChainedJsonException: Invalid shaders/core/rendertype_entity_unlit_translucent.json: File not found
 * </pre>
 *
 * <p>and the file is there. The merged base jar contains four asset namespaces --
 * {@code c}, {@code forge}, {@code minecraft}, {@code neoforge} -- with those shader files under
 * {@code assets/neoforge/shaders/core/}. What is missing is the client pack's NAMESPACE SET:
 * VanillaPackResources stores one and returns it verbatim.
 *
 * <pre>
 *   public Set&lt;String&gt; getNamespaces(PackType);
 *     0: aload_0
 *     1: getfield namespaces
 *     4: areturn
 * </pre>
 *
 * <p>Two instructions and no branch, which is what makes this fixable at all: replacing the load and the field read
 * with one static call and one NOP keeps the instruction count and needs no StackMapTable -- the standing constraint
 * here, since this kernel can only use COMPUTE_MAXS.
 *
 * <p>WHY A LITERAL SET: the alternative is reading the jar, and this runs on the path that is already failing to read
 * it. Listing the four is a statement about what the merged base contains, and it is checked against the jar by eye
 * rather than at runtime -- so if the base ever gains a namespace, this must gain it too.
 *
 * <p>WHAT THIS DOES NOT DO: it does not add a namespace that has no assets in the base; a name here that the jar lacks
 * would produce a pack claiming a directory it cannot serve. And it does not affect the SERVER's pack namespaces --
 * this repair targets VanillaPackResources, which both sides use, so a side that has no neoforge assets simply never
 * asks for them.
 */
public final class ForbricVanillaNamespaces {

	/** The four asset namespaces the merged base carries, in a stable order. */
	private static final Set<String> NAMESPACES = namespaces();

	private ForbricVanillaNamespaces() {
	}

	private static Set<String> namespaces() {
		Set<String> set = new LinkedHashSet<>(List.of("minecraft", "neoforge", "forge", "c"));
		ForbricLog.info("[Forbric/Namespaces] the client pack serves %s — the merged base carries all four under "
				+ "assets/, and VanillaPackResources' own set named only the vanilla one, so NeoForge's own shader "
				+ "lookup could not resolve", set);
		return java.util.Collections.unmodifiableSet(set);
	}

	/** The namespace set the merged base can serve. */
	public static Set<String> all() {
		return NAMESPACES;
	}
}
