/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import net.minecraft.resources.Identifier;

/**
 * Finishes the stage id a guest mixin already turned into a full id.
 *
 * <h2>Measured failure</h2>
 *
 * <pre>
 *   Exception caught during firing event: Invalid shaders/core/rendertype_entity_unlit_translucent.json:
 *     Non [a-z0-9/._-] character in path of location:
 *     neoforge:neoforge:shaders/core/rendertype_entity_unlit_translucent.vsh
 * </pre>
 *
 * <p>The loader then never finishes its first resource reload, so the client sits under the loading overlay
 * forever — no crash report, a live render loop, and a title screen that never arrives.
 *
 * <p>{@code fabric-rendering-v1}'s {@code ShaderProgramMixin.modifyStageId} is written against Yarn's shape of
 * {@code ShaderProgram.getOrCreate}: Yarn ends that method with a ONE-argument {@code Identifier.of(String)}, and the
 * mixin hands that call a string it has already rebuilt as a full id ({@code neoforge:shaders/core/x.vsh}) so a mod
 * can put its shaders in its own namespace. The merged 1.21.1 base is Mojmap plus NeoForge's patches, where the same
 * step reads {@code Identifier.fromNamespaceAndPath(location.getNamespace(), stageId)} — two arguments. The
 * mixin applies either way (it modifies a local, not the call), so the full id arrives where a path belongs, and the
 * base glues the namespace onto the front of it.
 *
 * <h2>What this does</h2>
 *
 * <p>Reads the same two arguments and returns the id the string already names when it carries a namespace, and the
 * base's own answer when it does not — which is exactly the branch the Yarn-shaped call would have taken. The repair
 * in {@code ForbricMergedBaseCompatTransformer} points that one call at this method; nothing else changes, and the
 * namespace-less case (every vanilla shader) still goes through the base's own code.
 */
public final class KernelShaders {

	private KernelShaders() {
	}

	/** The id a shader stage lives at, given the namespace its shader was registered under and the stage string. */
	public static Identifier stageId(String namespace, String stage) {
		if (stage == null) {
			return Identifier.fromNamespaceAndPath(namespace, "null");
		}

		// A colon means a guest mixin already rebuilt this stage as a full id (see the class comment). Parsing is
		// what Yarn's one-argument Identifier.of(String) would have done with the same string.
		return stage.indexOf(':') >= 0 ? Identifier.parse(stage) : Identifier.fromNamespaceAndPath(namespace, stage);
	}
}
