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

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.Reflect;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.GameData;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import net.neoforged.neoforge.registries.NewRegistryEvent;
import net.neoforged.neoforge.registries.ModifyRegistriesEvent;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * The game side of NeoForge's registry phase: {@code NewRegistryEvent}, then {@code RegisterEvent} for every
 * registry, in NeoForge's own order.
 *
 * <p>The MinecraftForge twin is {@link KernelForgeRegistries}, and as there the boot side keeps what is policy —
 * which buses, whether to honour NeoForge's order, and the unfreeze/freeze window all of this runs inside.
 *
 * <h2>What is still reflective</h2>
 *
 * <p>Both event constructors are package-private, which is reflection on either side. Their parameter types are
 * written out and checked now, so a re-signatured event is a build failure rather than a caught
 * {@code Throwable} at the moment no mod registers anything. {@code NewRegistryEvent.fill} is likewise
 * package-private.
 */
public final class KernelNeoRegistries {
	private KernelNeoRegistries() {
	}

	/**
	 * Every registry {@code RegisterEvent} should be posted for, in the order it should be posted in.
	 *
	 * <p>Three sources, and the third is the one that was missing. Vanilla's holder and NeoForge's holder are
	 * static fields the kernel can name; a MOD's custom registry is a static field on the mod, in no holder at
	 * all. So the kernel posted {@code RegisterEvent} for every registry EXCEPT the ones
	 * {@code NewRegistryEvent} had made one line earlier, and a {@code DeferredRegister} aimed at one never
	 * flushed — silently, because nothing fails when an event is simply not posted. Lithostitched's modifier types
	 * are registered exactly that way, so its {@code lithostitched:modifier_type} registry existed and was EMPTY,
	 * and the first world load died on "Unknown registry key … lithostitched:add_features" with the blame landing
	 * on Tectonic, which merely referenced it.
	 *
	 * <p>The ROOT registry is the authority for that third source: {@code NewRegistryEvent.fill()} has just
	 * registered each new registry into it, which is where genuine NeoForge reads its registration order from.
	 * Appended AFTER the two holders rather than replacing them, so the order the gates have proven is untouched.
	 *
	 * @param inNeoForgeOrder false restores field-declaration order (the {@code -Dforbric.neoRegistrationOrder=off}
	 *                       escape hatch), which is a worse order and is only there to be compared against
	 */
	public static List<Object> collect(boolean inNeoForgeOrder) {
		List<Object> registries = new ArrayList<>();
		collectHolder(BuiltInRegistries.class, registries);
		collectHolder(NeoForgeRegistries.class, registries);
		collectRoot(registries);
		return inNeoForgeOrder ? inRegistrationOrder(registries) : registries;
	}

	/** Every {@code Registry} declared as a public static field on {@code holder}, identity-deduped. */
	private static void collectHolder(Class<?> holder, List<Object> out) {
		try {
			for (Field f : holder.getFields()) {
				if (!Registry.class.isAssignableFrom(f.getType())) continue;
				// A mirrored field and the field it was mirrored from hold the same registry, and the view answers
				// as a Registry too; without unwrapping the sweep sees two objects and posts RegisterEvent for one
				// registry twice, after which the second pass dies on "Adding duplicate key" in a mod's
				// DeferredRegister (measured: curios' loot function type on the 8-mod launch).
				Object reg = KernelRegistryViews.unwrap(f.get(null));
				if (reg != null && !containsIdentity(out, reg)) out.add(reg);
			}
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Lifecycle] could not read " + holder.getSimpleName()
					+ " — RegisterEvent will not be posted for its registries", Reflect.unwrap(t));
		}
	}

	/**
	 * Adds every registry inside the ROOT registry the holder sweeps did not already name.
	 *
	 * <p>The root holds itself as {@code minecraft:root} and the holder sweep already picked that up, so the
	 * identity dedupe leaves the existing list untouched and only genuinely new registries are appended.
	 */
	private static void collectRoot(List<Object> out) {
		try {
			int added = 0;
			for (Object entry : BuiltInRegistries.REGISTRY) {
				if (!(entry instanceof Registry<?>) || containsIdentity(out, entry)) continue;
				out.add(entry);
				added++;
			}
			if (added > 0) {
				ForbricLog.debug("[Forbric/Lifecycle] %d mod-created registr(ies) joined the RegisterEvent sweep",
						added);
			}
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Lifecycle] could not enumerate the root registry — a mod's own registry will "
					+ "stay empty and its datapack entries will fail to parse", Reflect.unwrap(t));
		}
	}

	/** Identity, as everywhere else here: {@code Registry} does not override {@code equals}. */
	private static boolean containsIdentity(List<Object> out, Object candidate) {
		for (Object seen : out) {
			if (seen == candidate) return true;
		}
		return false;
	}

	/**
	 * Sorts into NEOFORGE's registration order rather than the order the fields happen to be declared in.
	 *
	 * <p>{@code BuiltInRegistries} declares ITEM 57 fields before DATA_COMPONENT_TYPE, and a mod whose items name
	 * their own data components dies on that gap. Stable, so everything NeoForge does not rank keeps the
	 * collection order the gates have proven.
	 */
	private static List<Object> inRegistrationOrder(List<Object> registries) {
		try {
			Set<Identifier> ids = GameData.getRegistrationOrder();
			if (ids == null || ids.isEmpty()) return registries;

			Map<String, Integer> rank = new HashMap<>();
			int next = 0;
			for (Identifier id : ids) rank.putIfAbsent(String.valueOf(id), next++);
			int unranked = rank.size();

			Map<Object, Integer> ranked = new IdentityHashMap<>();
			for (Object registry : registries) {
				ResourceKey<? extends Registry<?>> key = ((Registry<?>) registry).key();
				ranked.put(registry, rank.getOrDefault(String.valueOf(key.identifier()), unranked));
			}

			List<Object> sorted = new ArrayList<>(registries);
			sorted.sort(Comparator.comparingInt(r -> ranked.getOrDefault(r, unranked)));

			int moved = 0;
			for (int i = 0; i < sorted.size(); i++) {
				if (sorted.get(i) != registries.get(i)) moved++;
			}
			if (moved > 0) {
				ForbricLog.info("[Forbric/Lifecycle] fired RegisterEvent in NeoForge's registration order, not "
						+ "BuiltInRegistries' field order — %d registr(ies) moved. attribute, data_component_type "
						+ "and particle_type go first because mods' block and item builders read them", moved);
			}
			return sorted;
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Lifecycle] no NeoForge registration order available — firing RegisterEvent in "
					+ "collection order; a mod whose items read their own data components may lose them",
					Reflect.unwrap(t));
			return registries;
		}
	}

	/**
	 * Posts {@code RegisterEvent} for every registry at every bus, REGISTRY-major / mod-minor.
	 *
	 * <p>That order matches genuine NeoForge ({@code RegistryManager} walks registries, firing each mod's bus per
	 * registry). A {@code DeferredRegister}'s {@code DeferredHolder}s resolve during their OWN registry's event,
	 * so a mod registering {@code minecraft:item} must see every mod's blocks already registered — which only
	 * holds if all buses fire for BLOCK before any fires for ITEM. NeoForge content is explicitly namespaced by
	 * {@code DeferredRegister}, so unlike the MinecraftForge twin there is no active container to track here.
	 *
	 * <p>One mod's listener must not take the window down with it. Genuine NeoForge wraps each container's
	 * dispatch and collects the failure as a {@code ModLoadingIssue}, so the other mods AND the baseline still
	 * register; the kernel let the exception propagate, so a single mod throwing (Mob Champions reading a
	 * not-yet-loaded config) aborted the whole window — the NeoForge baseline never registered its own content and
	 * the client died much later, and misleadingly, on an unbound {@code neoforge:fluid_type/water}. Reported once
	 * per bus, then carried on from.
	 *
	 * @return how many registries were swept
	 */
	public static int fireRegisterEvents(List<Object> buses, List<Object> registries) throws Exception {
		Constructor<RegisterEvent> ctor = RegisterEvent.class.getDeclaredConstructor(
				ResourceKey.class, Registry.class);
		ctor.setAccessible(true);

		Set<Object> broken = Collections.newSetFromMap(new IdentityHashMap<>());
		for (Object registry : registries) {
			ResourceKey<? extends Registry<?>> key = ((Registry<?>) registry).key();
			for (Object bus : buses) {
				try {
					((IEventBus) bus).post(ctor.newInstance(key, registry));
				} catch (Throwable t) {
					if (broken.add(bus)) {
						ForbricLog.warn("[Forbric/Lifecycle] a NeoForge mod's RegisterEvent listener failed on %s — "
								+ "that mod's remaining content is skipped, every other mod and the baseline still "
								+ "register", key, Reflect.unwrap(t));
					}
				}
			}
		}
		return registries.size();
	}

	/**
	 * Posts {@code NewRegistryEvent} to every bus, then fills it — the phase before {@code RegisterEvent} where
	 * mods create their own registries.
	 *
	 * <p>The kernel fired this only on traditional Forge's global bus and drove NeoForge's own handler directly,
	 * so no NeoForge MOD ever received it. Two things break without it: a mod that declares a custom registry
	 * never gets one, and — less obviously — mods use this earliest mod-bus phase for setup that later phases
	 * depend on. WhiteNoise loads its config here (its own spec, outside NeoForge's {@code ConfigTracker}, so no
	 * amount of {@code loadConfigs} substitutes), which is why Mob Champions could read a config value from its
	 * {@code RegisterEvent} listener on genuine NeoForge but threw "Cannot get config value before config is
	 * loaded" here.
	 *
	 * <p>One event instance is posted to every bus and filled once, matching NeoForge, which collects each mod's
	 * registries into a single event and registers them together.
	 *
	 * @return how many buses received it
	 */
	public static int postNewRegistryEvent(List<Object> buses) throws Exception {
		Constructor<NewRegistryEvent> ctor = NewRegistryEvent.class.getDeclaredConstructor();
		ctor.setAccessible(true);
		NewRegistryEvent event = ctor.newInstance();

		int delivered = 0;
		// Who created what, per bus. A duplicate inside one NewRegistryEvent can only come from two listeners
		// calling create() for the same key, and fill() names only the key it died on — not the bus that added it.
		// This used to be unanswerable from the log: "Attempted duplicate registration of registry
		// neoforge:entity_data_serializers" arrived with no statement of which buses had contributed.
		java.util.List<Object> createdKeys = createdRegistries(event);
		for (Object bus : buses) {
			int before = createdKeys.size();
			try {
				((IEventBus) bus).post(event);
				delivered++;
			} catch (Throwable perBus) {
				ForbricLog.warn("[Forbric/Lifecycle] a NeoForge mod's NewRegistryEvent listener failed — that "
						+ "mod's custom registries are skipped, the rest still register", Reflect.unwrap(perBus));
			}
			int addedHere = createdKeys.size() - before;
			if (addedHere > 0) {
				ForbricLog.info("[Forbric/Lifecycle] bus #%d created %d registry(ies): %s", delivered,
						addedHere, createdKeys.subList(before, createdKeys.size()));
			}
		}

		// Measured cause of "Attempted duplicate registration of registry neoforge:entity_data_serializers":
		// [Forbric/Seed] already registered the NeoForge baseline registries into the root natively, and the
		// baseline mod's own NewRegistryEvent listener then hands the same registries back here. fill() throws on
		// the FIRST key the root already holds and abandons every registry after it — so one repeat costs the
		// whole remaining list, mods' own included. Dropping the repeats is the same set fill() would have
		// registered anyway: the registry object is identical, and it is already in the root.
		dropRegistriesTheRootAlreadyHas(event);

		// Separate from the delivery above: mods have already been notified by this point, so a fill() failure must
		// not be reported as "could not post" — the earliest-phase setup mods hang off this event has happened
		// either way.
		try {
			Method fill = NewRegistryEvent.class.getDeclaredMethod("fill");
			fill.setAccessible(true);
			fill.invoke(event);
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Lifecycle] NewRegistryEvent.fill failed — mod-declared custom registries "
					+ "may be missing (delivery to the mod buses itself succeeded)", Reflect.unwrap(t));
		}
		if (!"off".equalsIgnoreCase(System.getProperty("forbric.modifyRegistriesEvent", "on"))) {
			Constructor<ModifyRegistriesEvent> modifyCtor = ModifyRegistriesEvent.class.getDeclaredConstructor();
			modifyCtor.setAccessible(true);
			ModifyRegistriesEvent modify = modifyCtor.newInstance();
			for (Object bus : buses) {
				try { ((IEventBus) bus).post(modify); }
				catch (Throwable failed) {
					ForbricLog.warn("[Forbric/Lifecycle] a NeoForge ModifyRegistriesEvent listener failed", Reflect.unwrap(failed));
				}
			}
			ForbricLog.info("[Forbric/Lifecycle] posted ModifyRegistriesEvent after registry creation, before content registration");
		}
		return delivered;
	}

	/**
	 * Removes the registries {@code event} collected whose key the root registry already answers for, so
	 * {@code fill()} cannot die on its first repeat and lose everything queued behind it.
	 *
	 * <p>Best-effort by design: if the private list cannot be read or the root cannot be consulted, the event is
	 * left exactly as it was and {@code fill()} decides — this must never be the reason a boot stops.
	 */
	private static void dropRegistriesTheRootAlreadyHas(NewRegistryEvent event) {
		try {
			java.lang.reflect.Field field = NewRegistryEvent.class.getDeclaredField("registries");
			field.setAccessible(true);
			@SuppressWarnings("unchecked")
			java.util.List<Object> registries = (java.util.List<Object>) field.get(event);
			java.util.List<Object> dropped = new java.util.ArrayList<>();
			// Two distinct ways one key reaches fill() twice, and both have to go:
			//   1. the root already holds it (the kernel seeded the NeoForge baseline before this event), and
			//   2. the SAME event collected it twice — the merged base declares ForgeRegistries' serializer key and
			//      NeoForgeRegistries' under one id, so both families' listeners create an entry for it and the
			//      root does not hold either one yet. fill() dies on the second with exactly the message this is
			//      here to prevent, so the list has to be deduplicated by key, first occurrence winning.
			java.util.Set<String> seenIds = new java.util.HashSet<>();
			java.util.List<String> held = new java.util.ArrayList<>();
			java.util.List<String> loose = new java.util.ArrayList<>();
			java.util.List<String> unreadable = new java.util.ArrayList<>();
			for (java.util.Iterator<Object> it = registries.iterator(); it.hasNext(); ) {
				Object registry = it.next();
				Object key;
				try {
					key = registry.getClass().getMethod("key").invoke(registry);
					Object location = key.getClass().getMethod("location").invoke(key);
					// Deduplicate by the ID TEXT, which is what fill() collides on, and only then ask the root.
					// Two measurements shaped this:
					//   * by key TEXT it looked clean ("11 collected, 11 distinct keys") while fill() still died on a
					//     duplicate — the two entries are two distinct ResourceKeys naming one id;
					//   * by id OBJECT equality it also looked clean, because the merged base can hand one registry an
					//     id of each spelling (ResourceLocation here, Identifier there) and each class's equals checks
					//     its own type while both print "ns:path".
					// Comparing the id's text is the only form that sees both.
					if (!seenIds.add(String.valueOf(location))) {
						dropped.add(key);
						it.remove();
						continue;
					}
					// ...and separately, anything the root already holds. NOT through java.util.Map: the root is a
					// net.minecraft.core.MappedRegistry on this line and it does not implement Map at all — casting it
					// threw ClassCastException for all 11 entries, the surrounding catch swallowed that, and the
					// duplicate came straight back. Ask whichever one-argument lookup the root actually declares for
					// this id type (containsKey / getOptional / getValue / get) and read its answer.
					if (!rootHolds(location)) {
						loose.add(String.valueOf(location));
						continue;
					}
					held.add(String.valueOf(location));
				} catch (Throwable unknown) {
					// Named, not swallowed: an unreadable entry leaves fill() to decide, and if that is what makes
					// fill() throw then this line is the only place the reason exists.
					unreadable.add(String.valueOf(registry) + " -> " + unknown);
					continue; // not readable as a registry: leave fill() to decide
				}
				dropped.add(key);
				it.remove();
			}
			if (!dropped.isEmpty()) {
				ForbricLog.info("[Forbric/Lifecycle] %d registry(ies) were already in the root, or were the same key "
						+ "collected a second time by another family's listener, and are left out of this fill: %s",
						dropped.size(), dropped);
			} else {
				// Said out loud when nothing was dropped, with all four numbers that decide the next move: how many
				// entries were collected, how many distinct ids they name, which of them the root already holds, and
				// which could not be read at all. The first version of this method reported "nothing to drop" and
				// then fill() threw on a duplicate anyway, and the log could not say whether the private list was
				// empty, unreadable, or read and genuinely clean.
				ForbricLog.info("[Forbric/Lifecycle] nothing was dropped from this fill: %d registry(ies) collected, "
						+ "%d distinct id(s), the root already holds %d %s, %d unknown to it %s, and %d unreadable %s",
						registries.size(), seenIds.size(), held.size(), held, loose.size(), loose,
						unreadable.size(), unreadable);
			}
		} catch (Throwable unreadableEvent) {
			ForbricLog.debug("[Forbric/Lifecycle] could not inspect NewRegistryEvent's collected registries: %s",
					unreadableEvent);
		}
	}

	/**
	 * Whether the root registry already holds {@code location}, asked the way that registry can actually answer.
	 *
	 * <p>The root is a {@code MappedRegistry}, which is <em>not</em> a {@code java.util.Map}: casting it makes every
	 * lookup throw {@code ClassCastException}, and because the caller treats an unreadable entry as "leave it to
	 * fill()", that failure is invisible except as a duplicate registration later on. Its one-argument lookups are
	 * also spelled differently on each line ({@code containsKey(ResourceLocation)} here, keyed by {@code Identifier}
	 * on 26.x), so this walks the methods it declares and takes the first whose parameter accepts this id.
	 */
	private static boolean rootHolds(Object location) {
		Object root = net.minecraft.core.registries.BuiltInRegistries.REGISTRY;
		for (java.lang.reflect.Method method : root.getClass().getMethods()) {
			if (method.getParameterCount() != 1) continue;
			String name = method.getName();
			if (!name.equals("containsKey") && !name.equals("getOptional") && !name.equals("getValue")
					&& !name.equals("get")) continue;
			if (!method.getParameterTypes()[0].isInstance(location)) continue;
			try {
				Object answer = method.invoke(root, location);
				if (answer instanceof Boolean found) return found;
				if (answer instanceof java.util.Optional<?> optional) return optional.isPresent();
				if (answer != null) return true;
			} catch (Throwable ignored) {
				// try the next spelling rather than claim the root is empty
			}
		}
		return false;
	}

	/**
	 * The registry keys collected into {@code event} so far, newest last, read through its private list.
	 *
	 * <p>Only for reporting: {@code fill()} is the thing that registers. Each entry is asked for its own
	 * {@code key()} so the log names something a reader can grep for, and an entry that cannot answer is described
	 * by its class instead of being dropped — a diagnostic that hides the one case it cannot format is worse than
	 * no diagnostic.
	 */
	private static java.util.List<Object> createdRegistries(NewRegistryEvent event) {
		java.util.List<Object> keys = new java.util.ArrayList<>();
		try {
			java.lang.reflect.Field field = NewRegistryEvent.class.getDeclaredField("registries");
			field.setAccessible(true);
			for (Object registry : (java.util.List<?>) field.get(event)) {
				Object described;
				try {
					described = registry.getClass().getMethod("key").invoke(registry);
				} catch (Throwable unknown) {
					described = registry.getClass().getName();
				}
				keys.add(described);
			}
		} catch (Throwable unreadable) {
			keys.add("unreadable: " + unreadable.getClass().getSimpleName());
		}
		return keys;
	}
}
