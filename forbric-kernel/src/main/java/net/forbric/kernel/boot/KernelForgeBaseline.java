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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import net.forbric.api.Ecosystem;
import net.forbric.api.ForeignType;
import net.forbric.kernel.util.ForbricLog;
import net.forbric.kernel.util.Reflect;

/**
 * Constructs the traditional-MinecraftForge baseline mod ({@code net.minecraftforge.common.ForgeMod}) and fires
 * its {@code RegisterEvent}s natively — the Forge-family twin of the NeoForge baseline in {@link KernelLifecycle}.
 *
 * <p>The merged base's Forge-patched vanilla code reads traditional-Forge {@code RegistryObject}s (e.g.
 * {@code ForgeMod.EMPTY_TYPE}, the empty {@code forge:fluid_type}, dereferenced by {@code EntityFluidInteraction}
 * when a chest minecart spawns). Those only bind if {@code ForgeMod}'s {@code DeferredRegister}s register and the
 * ForgeRegistries bake.
 *
 * <p>The context/bus/registry-event mechanics live in {@link KernelForgeModContext}, shared with the real
 * third-party Forge {@code @Mod}s that {@link KernelModLoader} constructs — the baseline is just its first client.
 */
public final class KernelForgeBaseline {
	private static final String FORGE_MOD = "net.minecraftforge.common.ForgeMod";

	private KernelForgeBaseline() {
	}

	/**
	 * Constructs ForgeMod, creates Forge's custom registries, then fires the Forge {@code RegisterEvent} stream for
	 * the baseline <em>and</em> for {@code modHandles} — the real traditional-Forge {@code @Mod}s
	 * {@link KernelModLoader} already constructed. They ride the same pass because {@code NewRegistryEvent} must have
	 * created the custom registries (forge:fluid_type et al.) before anything enumerates them, and every
	 * {@code DeferredRegister} — baseline or mod — flushes off the same event.
	 */
	public static void register(ClassLoader cl, List<KernelForgeModContext.Handle> modHandles) {
		try {
			Class.forName(FORGE_MOD, false, cl);
		} catch (ClassNotFoundException absent) {
			ForbricLog.debug("[Forbric/Forge] traditional-Forge ForgeMod not present — skipping");
			return;
		}
		// The baseline's own bring-up and the RegisterEvent pass are separated deliberately. They used to share one
		// try, so a ForgeMod that failed to construct took the whole pass with it — including every real mod's
		// RegisterEvent, which has nothing to do with ForgeMod. The mods can register without the baseline; what
		// they lose is forge:fluid_type and friends, which is a smaller loss than registering nothing at all.
		KernelForgeModContext.Handle baseline = null;
		try {
			baseline = KernelForgeModContext.create(cl, "forge");
			// create() no longer makes its container active — it is called for every mod long before any of them
			// constructs now — so the constructor's window is opened and closed here, the way the mod path does it.
			KernelForgeModContext.setActiveContainer(cl, baseline.container());
			Object mod;
			try {
				mod = KernelForgeModContext.constructMod(cl, FORGE_MOD, baseline);
			} finally {
				KernelForgeModContext.setActiveContainer(cl, null);
			}
			ForbricLog.info("[Forbric/Forge] constructed traditional-Forge baseline mod ForgeMod -> %s", mod);
			KernelForgeModContext.startup(cl, baseline.busGroup());
		} catch (Throwable t) {
			baseline = null;
			ForbricLog.warn("[Forbric/Forge] the traditional-Forge baseline mod (ForgeMod) did not come up — its own "
					+ "content (forge:fluid_type and the rest) will be missing, but every real MinecraftForge mod "
					+ "still gets its RegisterEvent", Reflect.unwrap(t));
		}

		try {
			// Forge's CUSTOM registries (forge:fluid_type, holder_set_type, biome/structure_modifier_serializers, …)
			// are created by ForgeMod's DeferredRegister subscribers to NewRegistryEvent — never by GameData.init(),
			// which only wraps the vanilla BuiltInRegistries. The kernel fires RegisterEvent but never NewRegistryEvent,
			// so those registries never exist and RegistryObjects like ForgeMod.EMPTY_TYPE (minecraft:empty fluid type,
			// read by EntityFluidInteraction when a chest minecart spawns during worldgen) stay unbound. Fire it FIRST
			// so the registries exist before the RegisterEvent pass enumerates + populates them.
			int created = fireNewRegistryEvent(cl, baseline, modHandles);

			List<KernelForgeModContext.Handle> all = new java.util.ArrayList<>();
			if (baseline != null) all.add(baseline);
			all.addAll(modHandles);
			int n = KernelForgeModContext.fireRegisterEvents(cl, all);
			ForbricLog.info("[Forbric/Forge] created %d custom registr(ies) via NewRegistryEvent + fired Forge "
					+ "RegisterEvent x%d on %d bus(es) [%s + %d mod(s)]", created, n, all.size(),
					baseline == null ? "NO baseline" : "baseline", modHandles.size());
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Forge] the traditional-Forge RegisterEvent pass failed as a whole — this is "
					+ "not one mod's listener throwing (those are isolated and named individually); nothing a "
					+ "MinecraftForge mod declares through DeferredRegister has registered", Reflect.unwrap(t));
		}
	}

	/**
	 * Post a {@code NewRegistryEvent} on its global bus (delivering to ForgeMod's {@code DeferredRegister} subscribers,
	 * which call {@code event.create(builder)}) then {@code fill()} it to build + register those registries into
	 * {@code RegistryManager.ACTIVE}. Returns the count of registries that came into being. Best-effort.
	 */
	private static int fireNewRegistryEvent(ClassLoader cl, KernelForgeModContext.Handle baseline,
			List<KernelForgeModContext.Handle> modHandles) {
		try {
			Class<?> newRegCls = Class.forName(ForeignType.NEW_REGISTRY_EVENT.binary(Ecosystem.FORGE), false, cl);
			Class<?> regManager = Class.forName(ForeignType.REGISTRY_MANAGER.binary(Ecosystem.FORGE), false, cl);
			Object active = regManager.getField("ACTIVE").get(null);
			Field rf = regManager.getDeclaredField("registries");
			rf.setAccessible(true);
			int before = ((java.util.Map<?, ?>) rf.get(active)).size();

			// How many DeferredRegisters are actually waiting for this event. Measured on the 1.21.1 instance:
			// "created 0 custom registr(ies) via NewRegistryEvent" while ForgeMod's own constructor demonstrably
			// calls ForgeDeferredRegistriesSetup.setup(bus) and DeferredRegister.register(bus) for each of them --
			// so either the listeners are on another bus than the one posted to, or the constructor never reached
			// that line. The count below is what tells those two apart on the next run.
			int waiting = 0;
			int withFactory = 0;
			try {
				Object regs = Class.forName("net.minecraftforge.registries.ForgeRegistries", false, cl)
						.getDeclaredField("registries");
				((Field) regs).setAccessible(true);
				if (((Field) regs).get(null) instanceof java.util.List<?> list) {
					waiting = list.size();
					Field factory = Class.forName("net.minecraftforge.registries.DeferredRegister", false, cl)
							.getDeclaredField("registryFactory");
					factory.setAccessible(true);
					for (Object registrar : list) if (factory.get(registrar) != null) withFactory++;
				}
			} catch (Throwable unmeasured) {
				ForbricLog.debug("[Forbric/Forge] could not count the DeferredRegisters waiting for "
						+ "NewRegistryEvent: " + unmeasured);
			}

			Object event = newRegCls.getDeclaredConstructor().newInstance();

			// The post and the fill are separated on purpose. This is traditional Forge's GLOBAL bus, so every
			// mod's NewRegistryEvent listener runs inside one call and there is no seam to isolate them at. What
			// there IS a seam for is the consequence: one listener throwing used to take fill() down with it, and
			// with it EVERY Forge custom registry in the instance -- forge:fluid_type, holder_set_type, the
			// modifier serializers -- including the ones listeners that already ran had created. Filling anyway
			// keeps those. This is the same shape as the deferred-work drain in KernelNeoSetup: the failure is
			// one mod's, and the cost should be too.
			//
			// The event no longer carries its own bus lookup: 1.21.1's NewRegistryEvent has no BUS field, so
			// getField("BUS") threw here and took every Forge custom registry with it. ForgeEventPost picks the
			// form this line actually has.
			boolean posted = true;
			try {
				java.util.List<Object> buses = new java.util.ArrayList<>();
				if (baseline != null) buses.add(baseline.busGroup());
				for (KernelForgeModContext.Handle handle : modHandles) buses.add(handle.busGroup());
				// …and the CONTAINER's bus, which on this line is a different IEventBus from the one the kernel
				// minted. Measured: ForgeDeferredRegistriesSetup says "setup has already been called" — ForgeMod's
				// constructor registered every ForgeRegistries.registries on the bus it was handed, and nothing it
				// created showed up after posting on the handle's bus. FMLJavaModLoadingContext.get().getModEventBus()
				// hands a mod its container's bus, so that is where the listeners are.
				for (KernelForgeModContext.Handle handle : allHandles(baseline, modHandles)) {
					Object containerBus = containerBus(handle);
					if (containerBus == null) continue;
					boolean seen = false;
					for (Object already : buses) if (already == containerBus) { seen = true; break; }
					if (!seen) buses.add(containerBus);
				}
				ForgeEventPost.post(cl, event, buses);
			} catch (Throwable t) {
				posted = false;
				ForbricLog.warn("[Forbric/Forge] a traditional-Forge mod's NewRegistryEvent listener failed — the "
						+ "registries collected before it are still created below, the ones after it are not",
						Reflect.unwrap(t));
			}

			Method fill = newRegCls.getDeclaredMethod("fill");
			fill.setAccessible(true);
			fill.invoke(event);

			int created = ((java.util.Map<?, ?>) rf.get(active)).size() - before;
			if (!posted) {
				ForbricLog.warn("[Forbric/Forge] %d Forge custom registr(ies) survived that failure", created);
			}
			if (created == 0 && waiting > 0) {
				ForbricLog.info("[Forbric/Forge] NewRegistryEvent created nothing although %d DeferredRegister(s) "
						+ "are waiting on it (%d of them with a registry factory) — retrying once after registering "
						+ "them on this bus directly, which is what ForgeMod's constructor is supposed to have done",
						waiting, withFactory);
				created = registerWaitersThenRefill(cl, newRegCls, rf, active, before, baseline);
			}
			return created;
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Forge] NewRegistryEvent failed — Forge custom registries (fluid_type etc.) "
					+ "will not exist", Reflect.unwrap(t));
			return 0;
		}
	}

	private static java.util.List<KernelForgeModContext.Handle> allHandles(KernelForgeModContext.Handle baseline,
			List<KernelForgeModContext.Handle> modHandles) {
		java.util.List<KernelForgeModContext.Handle> all = new java.util.ArrayList<>();
		if (baseline != null) all.add(baseline);
		all.addAll(modHandles);
		return all;
	}

	/**
	 * The {@code IEventBus} a mod container hands out, or {@code null}.
	 *
	 * <p>On this line {@code FMLJavaModLoadingContext.get().getModEventBus()} returns the container's bus, and that is
	 * the bus {@code ForgeMod}'s constructor registered every {@code ForgeRegistries.registries} entry on — measured:
	 * posting a {@code NewRegistryEvent} on the kernel's own bus created nothing, and Forge then reported its
	 * {@code DeferredRegister} setup as "already called".
	 */
	private static Object containerBus(KernelForgeModContext.Handle handle) {
		Object container = handle.container();
		if (container == null) return null;
		for (Method candidate : container.getClass().getMethods()) {
			if (!candidate.getName().equals("getEventBus") || candidate.getParameterCount() != 0) continue;
			try {
				return candidate.invoke(container);
			} catch (Throwable t) {
				ForbricLog.debug("[Forbric/Forge] a mod container's getEventBus() threw: " + t);
				return null;
			}
		}
		return null;
	}

	/**
	 * Posts a second {@code NewRegistryEvent} — on the container's bus this time — and fills it.
	 *
	 * <p>Why this exists: measured on the 1.21.1 instance, {@code NewRegistryEvent} posted on the kernel's own bus
	 * created nothing while nine {@code DeferredRegister}s sat waiting with a registry factory each, and Forge then
	 * answered {@code ForgeDeferredRegistriesSetup} with "Setup has already been called!". Both facts together say
	 * where the listeners are: {@code ForgeMod}'s constructor got its bus from
	 * {@code FMLJavaModLoadingContext.get().getModEventBus()}, which is the CONTAINER's bus — a different
	 * {@code IEventBus} from the one the kernel minted for the handle. So the retry posts where the registrars are.
	 *
	 * @return how many Forge custom registries the retry created
	 */
	private static int registerWaitersThenRefill(ClassLoader cl, Class<?> newRegCls, Field registriesField,
			Object active, int before, KernelForgeModContext.Handle baseline) {
		try {
			if (baseline == null) {
				ForbricLog.warn("[Forbric/Forge] there is no baseline bus to post the retry on, so the Forge custom "
						+ "registries (fluid_type, the ingredient serializers, …) do not exist on this base");
				return 0;
			}
			Object handleBus = baseline.busGroup();
			Object containerBus = containerBus(baseline);
			java.util.List<Object> buses = new java.util.ArrayList<>();
			if (containerBus != null) buses.add(containerBus);
			if (handleBus != null && handleBus != containerBus) buses.add(handleBus);
			if (buses.isEmpty()) {
				ForbricLog.warn("[Forbric/Forge] neither the baseline handle nor its container hands out a bus, so "
						+ "the waiting DeferredRegisters cannot be reached");
				return 0;
			}

			Object event = newRegCls.getDeclaredConstructor().newInstance();
			int posted = ForgeEventPost.post(cl, event, buses);
			Method fill = newRegCls.getDeclaredMethod("fill");
			fill.setAccessible(true);
			fill.invoke(event);

			java.util.Map<?, ?> registries = (java.util.Map<?, ?>) registriesField.get(active);
			int created = registries.size() - before;
			if (created > 0) {
				ForbricLog.info("[Forbric/Forge] the retry posted the event on %d bus(es) — the container's %s and "
						+ "the handle's %s — and created %d Forge custom registr(ies) that were missing: %s",
						posted, containerBus == null ? "none" : containerBus.getClass().getName(),
						handleBus == null ? "none" : handleBus.getClass().getName(), created, registries.keySet());
			} else {
				ForbricLog.warn("[Forbric/Forge] even posted on the container's bus, NewRegistryEvent created no "
						+ "Forge custom registry — Forge's registrars are not answering anywhere this kernel can see");
			}
			return created;
		} catch (Throwable t) {
			ForbricLog.warn("[Forbric/Forge] the retry that reaches the waiting DeferredRegisters failed — the "
					+ "Forge custom registries stay missing", Reflect.unwrap(t));
			return 0;
		}
	}
}
