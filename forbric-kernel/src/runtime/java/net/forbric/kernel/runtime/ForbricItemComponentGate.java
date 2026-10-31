/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import net.forbric.kernel.util.ForbricLog;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.world.item.Item;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.event.GatherComponentsEvent;

/**
 * The gate MinecraftForge's item-component hook passes through, so that a vanilla static initialiser cannot reach FML
 * before FML exists.
 *
 * <p>On 1.21.1 this chain ends the boot:
 *
 * <pre>
 *   Foods.&lt;clinit&gt; -&gt; FoodProperties$Builder.usingConvertsTo -&gt; ItemStack.&lt;init&gt; -&gt; Item.components
 *     -&gt; ForgeHooks.gatherItemComponents -&gt; ForgeEventFactory.&lt;clinit&gt; -&gt; ModLoader.get() -&gt; new ModLoader()
 *       -&gt; this.loadingModList.getErrors()   NPE: loadingModList is null
 * </pre>
 *
 * Both lines call the hook -- it is MinecraftForge's own patch, not something the merge added -- but on 26.2 the first
 * ItemStack that asks for components is built after FML is up, so the same code finds the state it needs.
 *
 * <p>WHY A RETARGET AND NOT A GUARD: the kernel never writes a StackMapTable (COMPUTE_FRAMES cannot run while a class
 * is being defined), so a branch injected into ForgeHooks would need a hand-written frame. Redirecting the one
 * invokestatic here keeps the instruction count identical, and the branch lives in this .java file where javac
 * computes the frames.
 *
 * <p>WHAT THIS DOES NOT DO: it does not load mods or run discovery, and does not pretend an event fired. When FML is
 * not up the caller gets an event built from the UNMODIFIED map -- the same composite no listener would have produced
 * -- and one log line says so, so an author whose component edit is missing from a boot-time stack knows why.
 */
public final class ForbricItemComponentGate {

	private static boolean announced;

	private ForbricItemComponentGate() { }

	/** Same signature as {@code ForgeEventFactory.gatherItemComponentsEvent}, so a transformer can swap the owner. */
	public static GatherComponentsEvent.Item gatherItemComponentsEvent(Item item, DataComponentMap original) {
		if (!fmlIsUp()) {
			if (!announced) {
				announced = true;
				ForbricLog.warn("[Forbric/ItemComponents] an ItemStack asked for its components before FML was "
						+ "initialised, so MinecraftForge's GatherComponentsEvent did not fire for it — the vanilla "
						+ "component map is used. Boot-time items (Foods and its siblings) are constructed before "
						+ "mods load, so a component a mod adds is absent from those stacks only");
			}
			return new GatherComponentsEvent.Item(item, original);
		}
		return ForgeEventFactory.gatherItemComponentsEvent(item, original);
	}

	/**
	 * Whether FML's mod loading list exists yet. Asked reflectively because LoadingModList.get() is PUBLIC on 1.21.1
	 * and PRIVATE on 26.2 (both checked with javap), and this class is in the shared source set.
	 *
	 * <p>When the probe cannot be made at all this answers TRUE, i.e. delegate exactly as before: 26.2 never has the
	 * boot-time problem, so a probe that line cannot answer must not change its behaviour.
	 */
	private static boolean fmlIsUp() {
		java.lang.reflect.Method get;
		try {
			get = Class.forName("net.minecraftforge.fml.loading.LoadingModList").getMethod("get");
		} catch (ReflectiveOperationException | RuntimeException notPublicOrAbsent) {
			// Not public on this line (26.2's get() is private) -- try declared access before giving up.
			ForbricLog.debug("[Forbric/ItemComponents] LoadingModList.get() is not public here", notPublicOrAbsent);
			try {
				get = Class.forName("net.minecraftforge.fml.loading.LoadingModList").getDeclaredMethod("get");
				get.setAccessible(true);
			} catch (ReflectiveOperationException | RuntimeException absent) {
				ForbricLog.debug("[Forbric/ItemComponents] cannot probe LoadingModList at all; delegating", absent);
				return true;
			}
		}
		try {
			return get.invoke(null) != null;
		} catch (LinkageError notYet) {
			ForbricLog.debug("[Forbric/ItemComponents] FML's class initialiser failed; treating FML as not up", notYet);
			return false;
		} catch (ReflectiveOperationException | RuntimeException notYet) {
			ForbricLog.debug("[Forbric/ItemComponents] probe threw; treating FML as not up", notYet);
			return false;
		}
	}
}
