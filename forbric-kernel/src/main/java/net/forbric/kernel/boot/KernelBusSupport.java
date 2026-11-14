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

package net.forbric.kernel.boot;


import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;
import net.forbric.kernel.util.ForbricLog;

/** Shared reflective helpers for the kernel's native ecosystem-event plumbing. */
final class KernelBusSupport {
	private KernelBusSupport() {
	}

	/**
	 * Builds a NeoForge mod-event bus exactly as the genuine {@code FMLModContainer} does:
	 * {@code BusBuilder.builder().markerType(IModBusEvent).allowPerPhasePost().build()}. The
	 * {@code allowPerPhasePost()} is load-bearing on the client: phase-specific mod-bus events such as
	 * {@code RegisterKeyMappingsEvent} are dispatched via the {@code post(EventPriority, Event)} variant, which a bus
	 * without that option rejects with {@code IllegalStateException: This bus does not allow calling phase-specific
	 * post} (caught empirically in {@code Options.<init>} → {@code ClientHooks.onRegisterKeyMappings}).
	 */
	static Object makeModBus(ClassLoader cl) throws Exception {
		// The bus has to be built by the same loader that owns the events which will be posted on it. Resolving
		// BusBuilder through `cl` alone is not enough: measured on 1.21.1, that name came back from the application
		// class loader (the startup -cp) while the mod-bus marker and every NeoForge event class came from
		// ForbricClassLoader, so the bus's post(Event) took the application loader's Event and no event the kernel
		// or a mod constructs was ever an instance of it — a foreign mod's RegisterKeyMappingsEvent listener could
		// not be called with the event the kernel built (`[Forbric/ShimContext] RegisterKeyMappingsEvent cannot be
		// built in jdk.internal.loader.ClassLoaders$AppClassLoader; the bus offers net.neoforged.bus.api.Event from
		// …AppClassLoader; here Event is from …ForbricClassLoader`). Ask the marker's own loader first, and fall
		// back to `cl` only when that loader has no bus classes of its own.
		Class<?> modBusEvent = null;
		try {
			modBusEvent = Class.forName(ForeignType.MOD_BUS_EVENT.binary(Ecosystem.NEOFORGE), false, cl);
		} catch (Throwable ignored) {
			// No mod-bus marker on this base: the bus still has to be built, just without markerType.
		}
		ClassLoader busLoader = modBusEvent != null && modBusEvent.getClassLoader() != null
				? modBusEvent.getClassLoader() : cl;
		// …and the marker is not enough either: when a foreign mod asks for a container, the loader it hands over is
		// the application class loader, which has its own copy of every NeoForge class from the startup -cp. A bus
		// built there takes that copy of Event, while the kernel constructs events from the game loader's copy, so the
		// two never meet (measured with -Dforbric.probeKeys=true: 20 of 21 buses on ForbricClassLoader, the one the
		// shim minted on AppClassLoader — and that is the one whose key mappings could not be delivered). The thread's
		// context loader during mod loading is the game's, so it is preferred over a handed-over loader whose view of
		// net.neoforged.bus.api.Event disagrees with it.
		ClassLoader context = null;
		try {
			context = Thread.currentThread().getContextClassLoader();
		} catch (Throwable ignored) {
			// a security manager, in theory; the handed-over loader is the fallback
		}
		if (context != null && context != busLoader && load("net.neoforged.bus.api.Event", context) != null
				&& differsFrom(busLoader, context) && load("net.neoforged.bus.api.BusBuilder", context) != null) {
			if (SWAP_SAID.compareAndSet(false, true)) {
				ForbricLog.info("[Forbric/Bus] a bus is minted in %s instead of %s — the loader that was handed over "
						+ "resolves net.neoforged.bus.api.Event to a second copy, and a bus built there cannot accept "
						+ "an event this kernel constructs", name(context), name(busLoader));
			}
			busLoader = context;
		}
		Class<?> busBuilder;
		try {
			busBuilder = Class.forName("net.neoforged.bus.api.BusBuilder", false, busLoader);
		} catch (Throwable notThere) {
			busLoader = cl;
			busBuilder = Class.forName("net.neoforged.bus.api.BusBuilder", false, cl);
		}
		Object builder = busBuilder.getMethod("builder").invoke(null);
		if (modBusEvent != null) {
			try {
				builder = busBuilder.getMethod("markerType", Class.class).invoke(builder, modBusEvent);
			} catch (Throwable ignored) {
				// markerType is best-effort
			}
		}
		try {
			builder = busBuilder.getMethod("allowPerPhasePost").invoke(builder);
		} catch (Throwable ignored) {
			// allowPerPhasePost is best-effort (older bus APIs lack it)
		}
		Object built = builder.getClass().getMethod("build").invoke(builder);
		if (Boolean.getBoolean("forbric.probeKeys")) {
			try {
				keyCensus(cl, busLoader, modBusEvent, built);
			} catch (Throwable ignored) {
				// a diagnostic never fails a launch
			}
		}
		return built;
	}

	/**
	 * One-shot census of the class-loader views that decide whether a mod-bus event built by the kernel is an instance
	 * of the event a bus will accept. Gated by {@code -Dforbric.probeKeys=true}: it is a diagnostic, not behaviour.
	 */
	private static void keyCensus(ClassLoader cl, ClassLoader busLoader, Class<?> modBusEvent, Object builder) {
		StringBuilder out = new StringBuilder("[Forbric/Bus] key census: cl=").append(name(cl))
				.append(", busLoader=").append(name(busLoader))
				.append(", modBusEvent=").append(modBusEvent == null ? "none" : name(modBusEvent.getClassLoader()));
		for (String type : new String[]{"net.neoforged.bus.api.Event", "net.neoforged.fml.event.IModBusEvent",
				"net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent",
				"net.minecraft.client.Options", "net.neoforged.bus.api.BusBuilder"}) {
			Class<?> viaCl = load(type, cl);
			Class<?> viaBus = load(type, busLoader);
			out.append("; ").append(type).append(": cl=").append(viaCl == null ? "missing" : name(viaCl.getClassLoader()))
					.append('/').append(System.identityHashCode(viaCl))
					.append(", bus=").append(viaBus == null ? "missing" : name(viaBus.getClassLoader()))
					.append('/').append(System.identityHashCode(viaBus));
		}
		out.append("; sameEventClass=").append(load("net.neoforged.bus.api.Event", cl)
				== load("net.neoforged.bus.api.Event", busLoader));
		ForbricLog.info(out.toString());
	}

	private static Class<?> load(String type, ClassLoader loader) {
		try {
			return Class.forName(type, false, loader);
		} catch (Throwable absent) {
			return null;
		}
	}

	private static String name(ClassLoader loader) {
		return loader == null ? "bootstrap" : loader.getClass().getSimpleName() + '@'
				+ Integer.toHexString(System.identityHashCode(loader));
	}

	/** True when {@code a} and {@code b} do not agree on which class {@code net.neoforged.bus.api.Event} is. */
	private static boolean differsFrom(ClassLoader a, ClassLoader b) {
		Class<?> viaA = load("net.neoforged.bus.api.Event", a);
		Class<?> viaB = load("net.neoforged.bus.api.Event", b);
		return viaA != null && viaB != null && viaA != viaB;
	}

	private static final java.util.concurrent.atomic.AtomicBoolean SWAP_SAID =
			new java.util.concurrent.atomic.AtomicBoolean();

	/**
	 * The single-argument method of that name on {@code owner}, found by shape rather than by signature: an event
	 * bus's {@code post} erases to its own event bound, and naming that bound here would silently disable a bridge
	 * the day it changes.
	 */
	static java.lang.reflect.Method singleArgMethod(Class<?> owner, String name) throws NoSuchMethodException {
		for (java.lang.reflect.Method m : owner.getMethods()) {
			if (m.getName().equals(name) && m.getParameterCount() == 1) return m;
		}
		throw new NoSuchMethodException(owner.getName() + "." + name + "(<one argument>)");
	}
}
