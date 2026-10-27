/*
 * Copyright 2026 The Forbric Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.forbric.kernel.runtime;

import java.util.List;

import net.forbric.api.CompatibilityFinding;
import net.forbric.api.CompatibilityFindings;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.level.BaseSpawner;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.event.entity.living.MobSpawnEvent;
import net.neoforged.neoforge.common.extensions.IOwnedSpawner;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;

/**
 * Both event families run BEFORE the one possible Mob.finalizeSpawn call. The caller supplies the same
 * ValueInput that created this entity. Forge's updated SpawnGroupData enters the one finalization call;
 * its returned data is discarded, as in both native BaseSpawner callers.
 *
 * <p>Native cancellation has two distinct meanings: cancelling the event skips initialization, while
 * setSpawnCancelled prevents the later world insertion. The previous adapter conflated these and posted Forge
 * only after NeoForge had already initialized the mob. Calling Neo's native hook with initialize=false posts
 * its event without initializing, leaving a single finalization point after both families have decided.
 */
public final class KernelSpawnerFinalize {

	private KernelSpawnerFinalize() {
	}

	/** Old or unrecognized call sites retain Neo's native path and explicitly report the missing Forge input. */
	public static FinalizeSpawnEvent finalizeMobSpawnSpawner(Mob mob, ServerLevelAccessor level,
			DifficultyInstance difficulty, MobSpawnType reason, SpawnGroupData data, IOwnedSpawner spawner,
			boolean flag) {
		finding("spawner-finalize-input", true, "The spawner call site did not supply its ValueInput; Forge finalization was not dispatched.");
		return EventHooks.finalizeMobSpawnSpawner(mob, level, difficulty, reason, data, spawner, flag);
	}

	/*
	 * 1.21.1 has no net.minecraft.world.level.storage.ValueInput: the ValueInput/ValueOutput pair replaced
	 * CompoundTag only in 26.x, and ForgeEventFactory.onFinalizeSpawnSpawner on this line still takes the
	 * CompoundTag the entity was hydrated from. The eight-argument overload existed solely to accept that
	 * ValueInput, so it does not exist here. The transformer that would have redirected the merged spawner
	 * call into it looks for the 26.x descriptor and therefore never fires on this line.
	 *
	 * WHAT THIS COSTS: a MinecraftForge listener to MobSpawnEvent.FinalizeSpawn is not dispatched from the
	 * BaseSpawner path on 1.21.1, and a Forge listener cannot veto or rewrite a spawner's spawn data.
	 */

	private static void finding(String id, boolean required, String detail) {
		finding(id, CompatibilityFinding.Confidence.CONFIRMED, required, detail);
	}

	private static void finding(String id, CompatibilityFinding.Confidence confidence, boolean required, String detail) {
		CompatibilityFindings.record(new CompatibilityFinding(id, "forbric", "Spawner finalization",
				"KernelSpawnerFinalize", confidence, required, detail,
				List.of("BaseSpawner.serverTick", detail)));
	}
}
