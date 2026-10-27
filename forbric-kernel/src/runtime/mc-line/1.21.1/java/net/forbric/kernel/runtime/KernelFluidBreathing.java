/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.fluids.FluidType;
import net.forbric.compat.fluids.ForbricFluidPredicate;

/** Fabric's non-drowning adapter defaults refill air; native types and the subsequent native event keep control. */
public final class KernelFluidBreathing {
	public static final String PROPERTY = "forbric.foreignFluidBreathing";
	public static final class Observation {
		private boolean queryPerformed;
		private boolean adapterObserved;
		private boolean nonDrowning;
		private Observation() { }
	}
	private KernelFluidBreathing() { }
	public static Observation observation() { return new Observation(); }
	public static net.forbric.compat.fluids.ForbricFluidPredicate<LivingEntity> remember(net.forbric.compat.fluids.ForbricFluidPredicate<LivingEntity> nativePredicate, Observation observation) {
		// The transformer proves that this factory feeds the native boolean consumer immediately.
		observation.queryPerformed=true;
		return (entity,type,height) -> {
			boolean drowns = nativePredicate.test(entity,type,height);
			if (KernelFabricFluidBehaviors.ownsNeoType(type)) { observation.adapterObserved=true; observation.nonDrowning|=!drowns; }
			return drowns;
		};
	}
	/** Called only where the native hook was about to discard the original refill amount, before its event exists. */
	public static int refillDefault(LivingEntity entity, int originalRefill, Observation observation) {
		if ("off".equalsIgnoreCase(System.getProperty(PROPERTY,"on")) || !KernelFabricFluidBehaviors.enabled() || originalRefill<=0) return 0;
		if (observation.queryPerformed) return observation.adapterObserved&&observation.nonDrowning?originalRefill:0;
		// Water breathing can skip the native drowning query. Identify only our adapter then;
		// native concrete/default drowning behavior is never queried again or admitted by a tag/name heuristic.
		// 1.21.1 has no LivingEntity#getFluidInteraction(): the 26.x FluidInteraction object carries
		// isEyeInFluidMatching(entity, net.forbric.compat.fluids.ForbricFluidPredicate), and neither it nor the three-argument predicate exists
		// here. LivingEntity does expose isInFluidType(BiPredicate), but it asks about the BODY rather than the
		// EYE, and eye-versus-body is not interchangeable for a drowning test -- answering with the body check
		// would grant air refills to entities whose eyes are clear. So when the native query was skipped this
		// answers "not non-drowning" and no refill is given.
		return 0;
	}
}
