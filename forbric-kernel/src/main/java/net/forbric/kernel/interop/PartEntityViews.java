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

package net.forbric.kernel.interop;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Narrows a mixed part collection to the one family a given loop can cast to.
 *
 * <p>Both multipart APIs are published on the merged base and neither class is the other's supertype:
 * {@code net.minecraftforge.entity.PartEntity} and {@code net.neoforged.neoforge.entity.PartEntity} each
 * extend only {@code Entity}. Level keeps ONE collection, {@code getPartEntities()}, holding parts of
 * both families. NeoForge's own {@code getEntities(Entity, AABB, Predicate)} walks it and casts every
 * element to the NeoForge type, so the first Forge part in it throws {@code ClassCastException} out of an
 * ordinary projectile query; MinecraftForge's loop, and ours, cast the other way and throw on a NeoForge
 * part just as fast.
 *
 * <p>Each loop therefore narrows the collection it walks. The common case — a collection whose every
 * element is already of the wanted type — returns the argument itself, so the hot path allocates nothing
 * and the identity of the collection is unchanged for anything else holding it.
 */
public final class PartEntityViews {
	private PartEntityViews() {
	}

	/**
	 * @param all  the collection as the level holds it, or {@code null}
	 * @param type the part class the caller's loop is about to cast every element to
	 * @return {@code all} when every element already satisfies {@code type}, otherwise a new list of just
	 *         those that do; empty (never {@code null}) when {@code all} is {@code null}
	 */
	public static Collection only(Collection all, Class<?> type) {
		if (all == null) return List.of();
		boolean mixed = false;
		for (Object part : all) {
			if (!type.isInstance(part)) {
				mixed = true;
				break;
			}
		}
		if (!mixed) return all;
		List<Object> kept = new ArrayList<>(all.size());
		for (Object part : all) {
			if (type.isInstance(part)) kept.add(part);
		}
		return kept;
	}
}
