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

import java.util.Collection;

import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.Reflect;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;

/**
 * Delivers {@code RegisterKeyMappingsEvent} to the buses of mods that are not NeoForge mods.
 *
 * <p>The event's normal fan-out happens once, from {@code Options.<init>} via
 * {@code ClientHooks.onRegisterKeyMappings}, over the mods published in {@code ModList}. A Fabric mod holding the
 * NeoForge build of a multi-loader library is in neither: it gets its container from
 * {@code KernelForeignShimContext} while its own entrypoints run, which is inside {@code Minecraft.<init>} but
 * AFTER {@code Options} was built. So EntityCulling's keybind was added to a bus whose only fan-out had already
 * happened — registered, never delivered, and a keybind that is in no {@code Options.keyMappings} is a keybind the
 * player cannot see in Controls or press in game.
 *
 * <p>{@code register} writes straight into the live {@code Options.keyMappings} array, so re-posting the event here
 * puts the mappings where the game reads them. The two calls after it are what make them USABLE rather than merely
 * present: {@code Options.load(true)} is the late pass that applies a binding saved in {@code options.txt} for a key
 * that did not exist during the first load (the kernel already keeps those in {@code unknownKeys} — see
 * {@link net.forbric.kernel.runtime.KernelForgeOptions}), without which the player's own rebind is silently
 * reverted to the mod's default; and {@code KeyMapping.resetMapping()} rebuilds the input→mapping index that
 * dispatch reads, without which the key is listed in Controls and does nothing when pressed.
 */
public final class KernelForeignShimKeys {
	private KernelForeignShimKeys() {
	}

	/**
	 * Posts the event at {@code buses} and returns how many mappings that added, or -1 if it could not run.
	 *
	 * @param buses {@code net.neoforged.bus.api.IEventBus} instances, handed over untyped from the boot side
	 */
	public static int deliver(Collection<Object> buses) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null || minecraft.options == null || buses.isEmpty()) return -1;
		Options options = minecraft.options;
		int before = options.keyMappings.length;
		try {
			for (Object bus : buses) {
				try {
					// Never cast to IEventBus here: measured, the cast threw
					// "class net.neoforged.bus.EventBus cannot be cast to class net.neoforged.bus.api.IEventBus"
					// while the bus itself was healthy — the two names resolve to different copies in this class.
					java.lang.reflect.Method post = postOn(bus.getClass(), null);
					if (post == null) {
						ForbricLog.warn("[Forbric/ShimContext] %s has no single-argument post method, so what a foreign "
								+ "mod registered on it cannot be delivered", bus.getClass().getName());
						continue;
					}
					// And the event has to be built in the loader the bus's own parameter type came from: measured,
					// this bus takes net.neoforged.bus.api.Event from the app class loader while the class below is
					// loaded with the game's, so the kernel's own RegisterKeyMappingsEvent is not an Event for this bus.
					Object event = eventFor(post.getParameterTypes()[0].getClassLoader(), options);
					if (event == null) {
						ForbricLog.warn("[Forbric/ShimContext] RegisterKeyMappingsEvent cannot be built in %s, the loader "
								+ "the bus's post(Event) belongs to; the bus offers %s; here Event is from %s and "
								+ "RegisterKeyMappingsEvent is from %s",
								String.valueOf(post.getParameterTypes()[0].getClassLoader()),
								describePosts(bus.getClass()), loaderOfNamed("net.neoforged.bus.api.Event"),
								loaderOfNamed("net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent"));
						continue;
					}
					post.invoke(bus, event);
				} catch (Throwable perBus) {
					ForbricLog.warn("[Forbric/ShimContext] a foreign mod threw while registering key mappings",
							Reflect.unwrap(perBus));
				}
			}
			int added = options.keyMappings.length - before;
			if (java.lang.Boolean.getBoolean("forbric.probeKeys")) {
				// Says WHY nothing landed, not just that nothing did: a bus with no listeners means the mod
				// registered somewhere else, a bus with listeners means the event shape is still not the one its
				// handler takes.
				StringBuilder out = new StringBuilder("[Forbric/ShimKeys] delivered to ")
						.append(buses.size()).append(" bus(es), ").append(before).append(" -> ")
						.append(options.keyMappings.length).append(" key mapping(s)");
				for (Object bus : buses) out.append("; ").append(describe(bus));
				ForbricLog.info(out.toString());
			}
			if (added <= 0) return 0;

			// Order matters: the saved binding has to be applied BEFORE the index is rebuilt, or the index holds
			// the default and the player's own key does nothing until the next restart.
			options.load(true);
			KeyMapping.resetMapping();
			ForbricLog.info("[Forbric/ShimContext] delivered %d key mapping(s) registered by non-NeoForge mod(s) "
					+ "— their bus is not in ModList, so the one fan-out from Options.<init> had already run",
					added);
			return added;
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/ShimContext] could not deliver foreign key mappings", Reflect.unwrap(t));
			return -1;
		}
	}

	/**
	 * Builds {@code RegisterKeyMappingsEvent} in {@code loader} and returns null when that loader cannot build it.
	 *
	 * <p>Measured on this base: the bus hands a {@code post(net.neoforged.bus.api.Event)} whose {@code Event} comes
	 * from the app class loader, while this class is loaded with the game's class loader. The kernel's own
	 * {@code RegisterKeyMappingsEvent} is therefore not an {@code Event} for that bus, and the event has to be built
	 * by the same loader the parameter type belongs to — that is the class a listener on that bus can accept.
	 */
	private static Object eventFor(ClassLoader loader, Object options) {
		try {
			Class<?> type = Class.forName("net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent", true, loader);
			for (java.lang.reflect.Constructor<?> constructor : type.getConstructors()) {
				if (constructor.getParameterCount() != 1) continue;
				if (!constructor.getParameterTypes()[0].isInstance(options)) continue;
				return constructor.newInstance(options);
			}
		} catch (Throwable cannotBuild) {
			ForbricLog.debug("[Forbric/ShimContext] no RegisterKeyMappingsEvent in %s (%s)", String.valueOf(loader),
					cannotBuild.toString());
		}
		return null;
	}

	/**
	 * Names the bus and counts what a listener registry looks like on it: every {@code Map} field, by name and size.
	 * Diagnostic only ({@code -Dforbric.probeKeys=true}); says whether nothing landed because no listener registered
	 * on this bus at all, or because the listeners are there and the event did not match them.
	 */
	private static String describe(Object bus) {
		StringBuilder out = new StringBuilder(bus.getClass().getName()).append('@')
				.append(Integer.toHexString(System.identityHashCode(bus)));
		for (Class<?> type = bus.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
			for (java.lang.reflect.Field field : type.getDeclaredFields()) {
				if (!java.util.Map.class.isAssignableFrom(field.getType())) continue;
				try {
					field.setAccessible(true);
					Object value = field.get(bus);
					out.append(' ').append(field.getName()).append('=')
							.append(value instanceof java.util.Map<?, ?> map ? map.size() : "null");
				} catch (Throwable ignored) {
					out.append(' ').append(field.getName()).append("=?");
				}
			}
		}
		return out.toString();
	}

	private static String loaderOf(Class<?> type) {		if (type == null) return "none";
		ClassLoader loader = type.getClassLoader();
		return loader == null ? "bootstrap" : loader.getClass().getName();
	}

	/** The loader this class resolves {@code name} to, or {@code "not here"} when it cannot. */
	private static String loaderOfNamed(String name) {
		try {
			return loaderOf(Class.forName(name, false, KernelForeignShimKeys.class.getClassLoader()));
		} catch (Throwable absent) {
			return "not here";
		}
	}

	/** Every single-argument {@code post} the bus declares, with the type and loader of its parameter. */
	private static String describePosts(Class<?> bus) {
		StringBuilder out = new StringBuilder();
		for (Class<?> type = bus; type != null; type = type.getSuperclass()) {
			for (java.lang.reflect.Method method : type.getMethods()) {
				if (!"post".equals(method.getName()) || method.getParameterCount() != 1) continue;
				Class<?> parameter = method.getParameterTypes()[0];
				if (out.length() > 0) out.append(", ");
				out.append(parameter.getName()).append(" from ").append(loaderOf(parameter));
			}
		}
		return out.length() == 0 ? "none at all" : out.toString();
	}

	/**
	 * The bus's own single-argument {@code post}, looked up on the object instead of through the interface.
	 *
	 * <p>Same reason {@code ForgeEventPost.postOn} exists on the other side of the merge: the event-bus name survives
	 * a spelling change and a loader split, the interface identity does not.
	 */
	private static java.lang.reflect.Method postOn(Class<?> bus, Object event) {
		for (Class<?> type = bus; type != null; type = type.getSuperclass()) {
			for (java.lang.reflect.Method method : type.getMethods()) {
				if (!"post".equals(method.getName()) || method.getParameterCount() != 1) continue;
				if (event != null && !method.getParameterTypes()[0].isInstance(event)) continue;
				try {
					method.setAccessible(true);
					return method;
				} catch (RuntimeException refused) {
					return null;
				}
			}
		}
		return null;
	}
}
