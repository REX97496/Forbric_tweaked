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
import java.util.function.Consumer;

import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.Reflect;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;

/**
 * Collects traditional MinecraftForge's client reload listeners and hands them to NeoForge's registration event.
 *
 * <p><b>This is a 1.21.1 EDITION of the 26.2 bridge, not a copy of it.</b> The two lines run the same idea through
 * an API that changed shape, and the three differences are the whole of the port:
 *
 * <ol>
 *   <li><b>NeoForge renamed its event.</b> 26.2 registers on {@code AddClientReloadListenersEvent}; this line
 *       registers on {@code RegisterClientReloadListenersEvent}, which is also the simple name MinecraftForge's
 *       own event has here. Only one of the two can be imported, so NeoForge's is imported and MinecraftForge's
 *       is written out in full at its one use.</li>
 *   <li><b>This line's event takes no listener id.</b> 26.2's {@code SortedReloadListenerEvent} keys listeners by
 *       {@code Identifier} and sorts a dependency graph over them, so the 26.2 bridge SYNTHESISES an id per
 *       listener. This line's event has {@code registerReloadListener(PreparableReloadListener)} and no key, no
 *       ordering and no graph (javap, both lines). The id derivation is therefore <em>absent rather than
 *       skipped</em>: there is nothing on this line to derive it for, and an id nobody reads is not a port of the
 *       code that needed one.</li>
 *   <li><b>There is no per-event bus field, and the NeoForge mod bus cannot carry a Forge event.</b> 26.2's
 *       MinecraftForge event carries a static {@code EventBus<...> BUS} — EventBus 7's per-event bus — and the
 *       fallback below posts the Forge event through it. On this line the event is a plain {@code IModBusEvent}
 *       with no {@code BUS} field, and the two event hierarchies are separate types: measured with javap on this
 *       line, {@code net.neoforged.bus.EventBus implements net.neoforged.bus.api.IEventBus} only, and only
 *       {@code net.minecraftforge.eventbus.api.IEventBus.post} accepts a {@code net.minecraftforge.eventbus.api.Event}.
 *       The mod bus this class is handed is a {@code net.neoforged.bus.api.IEventBus}, so it cannot post it — that
 *       is not a preference, it is what javac says: {@code no suitable method found for post(...)}, which the first
 *       cut of this file hit. This line's global mod-bus dispatcher is the Forge loader's
 *       {@code ModLoader.get().postEvent(Event & IModBusEvent)}, the call the loader itself uses to fire mod-bus
 *       events, so the fallback calls that.</li>
 * </ol>
 *
 * <p>The structure around those three differences is unchanged, because it is a statement about the CARRIER
 * rather than about a name: the merged base posts only NeoForge's event, so a Forge mod's listeners registered
 * through MinecraftForge's event reach nothing unless something reposts them here.
 *
 * <h2>The scratch manager</h2>
 *
 * <p>MinecraftForge's event has no accessor for what was registered to it — the listeners go straight into the
 * {@code ReloadableResourceManager} it was constructed with. So one is constructed purely as a capture buffer,
 * the Forge event is posted against it, and whatever landed inside is read out and re-registered on NeoForge's
 * event. A captured empty list still means the event has already been posted. The manager is never used to reload
 * anything; its constructor seeds an empty list, which is why it is safe to read immediately.
 */
public final class KernelGameClientReload {
	private KernelGameClientReload() {
	}

	/**
	 * @param modBus the NeoForge MOD bus, handed over untyped from the boot side. This event is a mod-bus event,
	 *               not a game-bus one, which is why it is a separate pass from the other four bridges.
	 */
	public static void install(Object modBus) {
		Consumer<RegisterClientReloadListenersEvent> bridge = event -> {
			List<PreparableReloadListener> captured = ForgeClientReloadCapture.drain();
			if (captured != null) {
				// ForgeHooksClient already posted its self-destructing event. Reposting it would return an
				// empty list while its handler count still read "one", losing all real reload listeners.
				// Let registration failures reach NeoForge's normal event error handling.
				register(event, captured);
				return;
			}
			try {
				// The capture buffer. 1.21.1 has no `getListeners()` on the manager, so the private field is read
				// instead -- the same read the 26.2 accessor performs (`getfield listeners; areturn`). The helper
				// lives on KernelForgeClientInit because that class needs the identical read; both files are this
				// line's own, so there is one implementation rather than two that can drift.
				try (ReloadableResourceManager scratch = new ReloadableResourceManager(PackType.CLIENT_RESOURCES)) {
					// Fully qualified: this line's NeoForge event has the same simple name, and it is the one imported.
					// Fired through the Forge LOADER's global mod-bus dispatch, not the NeoForge bus this method is
					// handed: on this line that bus cannot accept a net.minecraftforge.eventbus.api.Event (javap,
					// both interfaces). Whether the merged base leaves the Forge loader with any mods registered
					// there is a property of the merge and is NOT verified here -- no 1.21.1 launch has happened.
					net.minecraftforge.fml.ModLoader.get().postEvent(
							new net.minecraftforge.client.event.RegisterClientReloadListenersEvent(scratch));
					register(event, KernelForgeClientInit.listenersOf(scratch));
				}
			} catch (Throwable t) {
				ForbricLog.warn("[forbric/EventMux] could not bridge Forge client reload listeners",
						Reflect.unwrap(t));
			}
		};

		// Four-argument overload with LOWEST, as everywhere in this package.
		((IEventBus) modBus).addListener(EventPriority.LOWEST, false, RegisterClientReloadListenersEvent.class, bridge);
	}

	private static void register(RegisterClientReloadListenersEvent event, List<PreparableReloadListener> listeners) {
		int n = 0;
		for (PreparableReloadListener listener : listeners) {
			// No id: this line's registration has none (see the class comment, difference 2).
			event.registerReloadListener(listener);
			n++;
		}
		if (n > 0) {
			ForbricLog.info("[forbric/EventMux] bridged %d Forge client reload listener(s) into NeoForge's "
					+ "reload registration", n);
		}
	}
}
