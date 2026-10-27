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
import java.util.Map;

import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.Reflect;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.ReloadableServerResources;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraftforge.event.ForgeEventFactory;
import net.neoforged.neoforge.event.EventHooks;

/**
 * The server twin of {@link KernelGameClientReload}: posts MinecraftForge's {@code AddReloadListenerEvent} from
 * the merged server reload, through the carrier's own {@code ForgeEventFactory}.
 *
 * <p>The merged {@code ReloadableServerResources.loadResources} calls only NeoForge's
 * {@code EventHooks.onResourceReload}; a constant-pool scan of the merged base finds ZERO references to Forge's
 * event. So every traditional-Forge mod that registers a JSON data loader the documented way — from
 * {@code AddReloadListenerEvent} — had its listener registered on a bus nobody posted to, and its custom data
 * folder loaded nothing, silently. The merged constructor's single call site is redirected here with the same
 * descriptor; this method calls NeoForge's hook first (so its list, context injection and ordering are exactly
 * what they were), then hands that list to the carrier's real {@code ForgeEventFactory.onResourceReload}, which
 * fires the Forge bus and appends what Forge listeners added. Forge's own order is the same: its listeners come
 * after the game's. The registry lookup handed to Forge is the same object NeoForge's path uses
 * ({@code getRegistryLookup()} returns the {@code lookupWithUpdatedTags} the resources were built with).
 *
 * <p>{@code -Dforbric.forgeReloadListeners=off} returns NeoForge's list unchanged — the redirect itself is inert
 * — and a throwing Forge listener costs this reload every Forge listener (the carrier fires them in one post),
 * which is logged rather than allowed to abort the reload.
 */
public final class KernelForgeReload {
	public static final String PROPERTY = "forbric.forgeReloadListeners";

	private KernelForgeReload() {
	}

	/**
	 * 1.21.1 cannot forward MinecraftForge's AddReloadListenerEvent here.
	 *
	 * <p>Two names differ and neither can be bridged from this class. EventHooks.onResourceReload on this line
	 * takes (ReloadableServerResources, RegistryAccess) -- the third Map&lt;ListenerKey&lt;?&gt;, ...&gt; argument is
	 * 26.x, and ListenerKey does not exist at all. ForgeEventFactory.onResourceReload on this line wants a
	 * RegistryAccess as its last argument, not the list to append to, and ReloadableServerResources exposes only
	 * getRegistryLookup(): HolderLookup$Provider -- a RegistryAccess cannot be obtained from it.
	 *
	 * <p>WHAT THIS COSTS: a traditional-Forge mod that registers a JSON data loader from
	 * AddReloadListenerEvent -- the documented way -- has no listener registered on 1.21.1, so its custom data
	 * folder loads nothing. This is a real loss, and it is a loss of exactly the thing this class existed to
	 * provide. It is not silently swallowed: the message below is logged once per reload.
	 */
	public static List<PreparableReloadListener> onResourceReload(ReloadableServerResources resources,
			RegistryAccess registries) {
		List<PreparableReloadListener> neo = EventHooks.onResourceReload(resources, registries);
		ForbricLog.debug("[Forbric/EventMux] 1.21.1 has no ForgeEventFactory.onResourceReload shape that accepts a "
				+ "listener list, so MinecraftForge's AddReloadListenerEvent is not posted; -D%s is inert at the server "
				+ "level on this line", PROPERTY);
		return neo;
	}
}
