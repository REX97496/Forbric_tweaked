/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import java.util.List;
import java.util.function.Function;
import net.forbric.api.AncestorComposition;
import net.forbric.kernel.util.ForbricLog;

/**
 * One registration for several ancestor-composition proofs.
 *
 * <p>WHY THIS EXISTS. {@code TransformerRegistrationOrderTest} requires that
 * {@code ForbricClassLoader.registerAncestorComposition} is called EXACTLY ONCE, and that nothing branches between
 * constructing the proof and registering it -- because a proof registered late is a proof that something can skip,
 * and skipping it means a class is defined with an unproven ancestor. Adding a second call for a second root would
 * violate that, and loosening the test would remove the guarantee rather than extend it.
 *
 * <p>So the proofs are composed here and registered as one. {@link #proves} asks each in turn and stops at the first
 * that answers true, which is the same contract {@code RequiredAncestorCompositions} already applies across separate
 * registrations -- just held in one object.
 *
 * <p>WHAT THIS DOES NOT DO: it does not make the proofs interchangeable. Each still answers only for the requirement
 * it was written for, and an unproven root still fails to define.
 */
public final class ForbricAncestorProofs implements AncestorComposition {

	private final List<AncestorComposition> proofs;

	public ForbricAncestorProofs(AncestorComposition... proofs) {
		this.proofs = List.of(proofs);
	}

	@Override
	public boolean proves(Requirement requirement, byte[] finalDefinition, Function<String, byte[]> resources) {
		for (AncestorComposition proof : proofs) {
			try {
				if (proof.proves(requirement, finalDefinition, resources)) return true;
			} catch (RuntimeException unproven) {
				// A proof that throws has not proven anything; say so and let the next one answer rather than
				// turning an unproven root into a crash inside the proof mechanism itself.
				ForbricLog.warn("[Forbric/Composition] " + proof.getClass().getSimpleName()
						+ " threw while proving " + requirement.owner() + " — treated as unproven", unproven);
			}
		}
		return false;
	}
}
