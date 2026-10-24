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

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import net.forbric.kernel.util.ForbricLog;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForgeEventHandler;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.registries.datamaps.DataMapsUpdatedEvent;

/**
 * Watches NeoForge's OWN data-map reload path and counts what it does, so {@link KernelNeoWorldgen}'s
 * about-to-start fallback can stand down when the genuine path already applied. No static game state: this class
 * can be initialised and its watch installed on a recording bus outside the game.
 *
 * <p>THE SAME JOB AS THE SHARED FILE, and this is the 1.21.1 edition of it. It is an overlay rather than a table
 * row because what moved is where NeoForge posts the two signals, not how either one is spelled: the keyed,
 * ordered reload-listener registry 26.2 registers into does not exist on this line, and the per-cause subevent it
 * listens for was folded into the base event.
 *
 * <p>WHY THIS CLASS AND NOT ITS SERVER TWIN. {@code KernelForgeReload} is in the same cluster and cannot be
 * ported here: its installer in the boot half is descriptor-bound to 26.2's three-argument
 * {@code EventHooks.onResourceReload}, so an edition of it would compile and be installed nowhere. This class is
 * reached by REFLECTION on {@code installDataMapWatch(Object)} — {@code GameEventMultiplexer.installDataMapWatch}
 * resolves it with {@code getMethod("installDataMapWatch", Object.class)}, called unconditionally from
 * {@code KernelLifecycle.startGameBuses} — so keeping that signature is the whole of the install contract, and
 * the signature is line-neutral by construction. Its CONSUMER is on this line too:
 * {@code KernelNeoWorldgen.beforeServerStart} calls {@link #appliedTotal()} first and only runs its fallback when
 * that answers 0, and this line's {@code KernelNeoWorldgen} overlay already does.
 *
 * <h2>The three differences, each read out of the carriers with javap</h2>
 *
 * <table>
 *   <tr><th></th><th>26.2 (the shared file)</th><th>1.21.1 (here)</th></tr>
 *   <tr><td>registration signal</td>
 *       <td>{@code AddServerReloadListenersEvent extends SortedReloadListenerEvent}, with
 *           {@code getServerResources()} and {@code addRetainedListener(ListenerKey,T)}</td>
 *       <td>{@code AddReloadListenerEvent extends net.neoforged.bus.api.Event}: {@code
 *           addListener(PreparableReloadListener)}, {@code getListeners()}, {@code getServerResources()},
 *           {@code getConditionContext()}, {@code getRegistryAccess()}</td></tr>
 *   <tr><td>"is the loader registered"</td>
 *       <td>{@code event.getServerResources().getListener(NeoForgeReloadListeners.DATA_MAPS_KEY)}</td>
 *       <td>{@code NeoForgeEventHandler.DATA_MAPS}, by identity against the previous reload</td></tr>
 *   <tr><td>the data-load signal</td>
 *       <td>{@code TagsUpdatedEvent.ServerDataLoad}, a nested {@code final} subclass carrying
 *           {@code getServerResources()}</td>
 *       <td>{@code TagsUpdatedEvent}, filtered on {@code getUpdateCause() == UpdateCause.SERVER_DATA_LOAD}</td></tr>
 * </table>
 *
 * <p>Both post on {@code NeoForge.EVENT_BUS}, which is the bus {@code installDataMapWatch(Object)} is handed:
 * {@code EventHooks.onResourceReload(ReloadableServerResources, RegistryAccess)} builds the event and calls
 * {@code NeoForge.EVENT_BUS.post} on it, then returns {@code event.getListeners()} (javap {@code -c}). And the
 * per-cause constants are the SAME ENUM on both lines — {@code TagsUpdatedEvent$UpdateCause} declares
 * {@code SERVER_DATA_LOAD} and {@code CLIENT_PACKET_RECEIVED} in both jars, {@code DataMapsUpdatedEvent$UpdateCause}
 * declares {@code CLIENT_SYNC} and {@code SERVER_RELOAD} in both — so the second listener below is byte-for-byte
 * the shared file's and needs no edition of its own.
 *
 * <h2>The registration probe, and why identity is the right question on this line</h2>
 *
 * <p>26.2 asks the resource manager for the listener under a key. There is no key here, and the faithful
 * question therefore changes from "is something registered under that name" to "did NeoForge register a loader
 * for THIS reload". {@code NeoForgeEventHandler.onResourceReload} answers it in its own bytecode:
 *
 * <pre>
 *   new DataMapLoader ; dup ; aload_1 ; AddReloadListenerEvent.getRegistryAccess ; invokespecial &lt;init&gt;(RegistryAccess)
 *   dup ; putstatic DATA_MAPS ; invokevirtual AddReloadListenerEvent.addListener(PreparableReloadListener)
 * </pre>
 *
 * <p>— it constructs a NEW loader per reload, stores it, and adds it to that same event's list. So a changed
 * identity is the same statement as 26.2's keyed lookup, and a static one means the handler did not run for this
 * reload. Two things about it are stated rather than glossed:
 *
 * <ul>
 *   <li>{@code DATA_MAPS} is a PRIVATE field ({@code private static DataMapLoader DATA_MAPS}), so this read is
 *       reflection on a carrier implementation detail, the same class of dependency as
 *       {@code KernelForgeOverlayLayers}' {@code ForgeLayeredDraw.namedLayers}. If the field cannot be read, this
 *       says so instead of claiming the loader is missing — a probe that cannot answer is not an answer.</li>
 *   <li>It is STRONGER than the 26.2 probe in one respect worth naming: {@code DATA_MAPS} is the exact object this
 *       line's {@code NeoForgeEventHandler.tagsUpdated} applies, so "registered" and "will be applied" are the same
 *       object here, where 26.2's key only promised a listener under a name.</li>
 * </ul>
 *
 * <p>The handler runs at the default priority ({@code @SubscribeEvent} with none, javap {@code -v}) and these
 * listeners are added at {@link EventPriority#LOWEST}, so a reload always reaches NeoForge's registration first;
 * {@code LOWEST} through the four-argument overload for the same reason the shared file gives — the shorter ones
 * promote to NORMAL, which would run the count line before {@code NeoForgeEventHandler.tagsUpdated} at NORMAL has
 * applied anything.
 *
 * <p>What is NOT verified, and cannot be from here: no reload has ever run through this class. Both listeners are
 * arguments from the bytecode quoted above — that the event is posted, that the loader is in it, and that
 * {@code DataMapLoader.apply()} posts {@code DataMapsUpdatedEvent(..., SERVER_RELOAD)} (javap {@code -c}:
 * {@code getstatic NeoForge.EVENT_BUS}, {@code new DataMapsUpdatedEvent}, {@code getstatic
 * UpdateCause.SERVER_RELOAD}) — not from an executed reload.
 */
public final class KernelNeoDataMapWatch {
	private KernelNeoDataMapWatch() {
	}

	private static final AtomicInteger GENERATION = new AtomicInteger();
	private static final AtomicInteger APPLIED_THIS_GENERATION = new AtomicInteger();
	private static final AtomicInteger APPLIED_TOTAL = new AtomicInteger();

	/** The loader the previous reload was given, so a repeat means NeoForge did not register one for this one. */
	private static final AtomicReference<Object> LAST_LOADER = new AtomicReference<>();

	/** The probe could not answer. Distinct from null, which is the probe answering "no loader". */
	private static final Object UNREADABLE = new Object();

	/** {@code NeoForgeEventHandler.DATA_MAPS}, or null when this line's carrier does not have that shape. */
	private static final Field DATA_MAPS = dataMapsField();

	/**
	 * Watches NeoForge's OWN data-map path, which the merged base carries whole: the carrier's
	 * {@code NeoForgeEventHandler.onResourceReload} registers a {@code DataMapLoader} on
	 * {@code AddReloadListenerEvent} (the merged {@code ReloadableServerResources} posts it through
	 * {@code EventHooks.onResourceReload} and the event carries the live condition context), and
	 * {@code NeoForgeEventHandler.tagsUpdated} applies it when {@code TagsUpdatedEvent}'s cause is
	 * {@code SERVER_DATA_LOAD} — posted on the initial load and on every {@code /reload}.
	 *
	 * <p>Three LOWEST-priority listeners count what that path does per reload generation and say so, and
	 * {@link #beforeServerStart}'s counterpart ({@code KernelNeoWorldgen}) stands its fallback down when the
	 * genuine path already applied.
	 */
	public static void installDataMapWatch(Object neoBus) {
		IEventBus bus = (IEventBus) neoBus;
		bus.addListener(EventPriority.LOWEST, false, AddReloadListenerEvent.class, event -> {
			int generation = GENERATION.incrementAndGet();
			APPLIED_THIS_GENERATION.set(0);
			Object loader = nativeLoader();
			if (loader == UNREADABLE) {
				ForbricLog.warn("[Forbric/Worldgen] reload #%d: could not read NeoForge's DataMapLoader field (%s) — "
						+ "whether NeoForge's own reload path registered one is NOT KNOWN here; the counts below are "
						+ "still NeoForge's own", generation, DATA_MAPS == null ? "no such field" : "inaccessible");
				return;
			}
			Object previous = LAST_LOADER.get();
			boolean registered = loader != null && loader != previous;
			if (registered) {
				LAST_LOADER.set(loader);
			} else {
				ForbricLog.warn("[Forbric/Worldgen] reload #%d: NeoForge's DataMapLoader is NOT registered — "
						+ "NeoForgeEventHandler.onResourceReload did not run on this bus; the kernel's about-to-start "
						+ "fallback will load the data maps", generation);
			}
		});
		bus.addListener(EventPriority.LOWEST, false, DataMapsUpdatedEvent.class, event -> {
			if (event.getCause() == DataMapsUpdatedEvent.UpdateCause.SERVER_RELOAD) {
				APPLIED_THIS_GENERATION.incrementAndGet();
				APPLIED_TOTAL.incrementAndGet();
			}
		});
		// One TagsUpdatedEvent on this line, not the per-cause ServerDataLoad subclass: same enum, same constants,
		// so the filter is the whole of the difference. The same condition NeoForgeEventHandler.tagsUpdated uses
		// to decide whether to apply at all (javap -c: getstatic UpdateCause.SERVER_DATA_LOAD, if_acmpne).
		bus.addListener(EventPriority.LOWEST, false, TagsUpdatedEvent.class, event -> {
			if (event.getUpdateCause() != TagsUpdatedEvent.UpdateCause.SERVER_DATA_LOAD) return;
			int applied = APPLIED_THIS_GENERATION.get();
			if (applied > 0) {
				ForbricLog.info("[Forbric/Worldgen] reload #%d: NeoForge's own reload path applied data maps for %d "
						+ "registr%s", GENERATION.get(), applied, applied == 1 ? "y" : "ies");
			} else {
				ForbricLog.warn("[Forbric/Worldgen] reload #%d: NeoForge's own reload path applied data maps for 0 "
						+ "registries — the kernel's about-to-start fallback will load them (with the live condition "
						+ "context), and /reload will not rebuild them", GENERATION.get());
			}
		});
	}

	/** Registries NeoForge's own path applied data maps for, across every reload so far. */
	public static int appliedTotal() {
		return APPLIED_TOTAL.get();
	}

	private static Field dataMapsField() {
		try {
			Field field = NeoForgeEventHandler.class.getDeclaredField("DATA_MAPS");
			field.setAccessible(true);
			return field;
		} catch (Throwable absent) {
			// A static initialiser that threw would poison this class, and this class is loaded by reflection at
			// boot — so a carrier that moved the field must cost one warning line, never the boot.
			return null;
		}
	}

	/** NeoForge's current {@code DataMapLoader}: null when there is none, {@link #UNREADABLE} when unknown. */
	private static Object nativeLoader() {
		Field field = DATA_MAPS;
		if (field == null) return UNREADABLE;
		try {
			return field.get(null);
		} catch (Throwable unreadable) {
			return UNREADABLE;
		}
	}
}
