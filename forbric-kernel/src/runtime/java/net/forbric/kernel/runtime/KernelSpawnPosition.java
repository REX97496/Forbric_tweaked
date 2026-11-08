/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.BaseSpawner;
import net.minecraft.world.level.ServerLevelAccessor;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.MobSpawnEvent;

/**
 * The event half of NeoForge's {@code EventHooks.checkSpawnPositionSpawner} and {@code checkSpawnPosition}, for a
 * caller whose vanilla checks the kernel has put back (SpawnPositionCallsInjector).
 *
 * <p>NeoForge's hooks post {@code MobSpawnEvent.PositionCheck} (with the spawner, or null outside one), and only
 * when its listeners leave the result at {@code DEFAULT} do they run the vanilla checks — {@code Mob.checkSpawnRules}
 * (for a spawner, unless the spawn data carries custom rules), then {@code Mob.checkSpawnObstruction}. Those calls
 * lived inside the hooks, where a Fabric mixin aimed at the caller cannot reach them. This posts the same event the
 * same way and answers what it decided; the calls themselves now run in the caller, exactly where vanilla made them.
 */
public final class KernelSpawnPosition {
	private KernelSpawnPosition() {
	}

	/** -1 when the listeners left it to the vanilla checks, 1 when they allowed the spawn, 0 when they denied it. */
	public static int decide(Mob mob, ServerLevelAccessor level, EntitySpawnReason reason, BaseSpawner spawner) {
		MobSpawnEvent.PositionCheck event = new MobSpawnEvent.PositionCheck(mob, level, reason, spawner);
		NeoForge.EVENT_BUS.post(event);
		MobSpawnEvent.PositionCheck.Result result = event.getResult();
		if (result == MobSpawnEvent.PositionCheck.Result.DEFAULT) return -1;
		return result == MobSpawnEvent.PositionCheck.Result.SUCCEED ? 1 : 0;
	}

	/**
	 * The same decision, with vanilla's two checks run here instead of in the caller. It exists for the classes whose
	 * stack map frames cannot be recomputed after the inlined form is spliced in: there the hook is retargeted to this
	 * method in place, which changes no instruction count and so needs no new frames, and the behaviour is the one
	 * {@code EventHooks.checkSpawnPosition} had — the event first, vanilla's checks only when the listeners said
	 * DEFAULT. A Fabric mixin aimed at the caller's {@code checkSpawnRules} cannot bind on that path; that is a
	 * narrower gap than leaving NeoForge's hook in place, which had neither the calls nor a way to reach them.
	 */
	public static boolean decidePosition(Mob mob, ServerLevelAccessor level, EntitySpawnReason reason) {
		int answer = decide(mob, level, reason, null);
		if (answer >= 0) return answer != 0;
		return mob.checkSpawnRules(level, reason) && mob.checkSpawnObstruction(level);
	}
}
