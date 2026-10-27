/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import net.forbric.kernel.util.ForbricLog;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.world.item.Item;
import net.minecraftforge.event.GatherComponentsEvent;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.fml.loading.LoadingModList;

/**
 * The gate MinecraftForge's item-component hook passes through, so that a vanilla static initialiser cannot reach
 * FML before FML exists.
 *
 * <p>WHY THIS CLASS EXISTS. On 1.21.1 the chain
 *
 * <pre>
 *   Foods.&lt;clinit&gt; -&gt; FoodProperties$Builder.usingConvertsTo -&gt; ItemStack.&lt;init&gt;
 *     -&gt; Item.components -&gt; ForgeHooks.gatherItemComponents
 *       -&gt; ForgeEventFactory.&lt;clinit&gt; -&gt; ModLoader.get() -&gt; new ModLoader()
 *         -&gt; this.loadingModList.getErrors()   NPE: loadingModList is null
 * </pre>
 *
 * ends the boot. Both lines call the hook -- it is MinecraftForge's own patch, not something the merge added -- but
 * on 26.2 the first ItemStack that asks for components is built after FML is up, so the same code finds the state it
 * needs. Nothing is wrong with the hook; the first caller's timing differs.
 *
 * <p>WHY A RETARGET AND NOT A GUARD. The kernel never writes a StackMapTable: COMPUTE_FRAMES cannot run while a class
 * is being defined, so every writer uses COMPUTE_MAXS at most. A branch injected into ForgeHooks would therefore need
 * a hand-written frame. Redirecting the one invokestatic to this method instead keeps the instruction count identical,
 * and the branch lives here, in a .java file, where javac computes the frames.
 *
 * <p>WHAT THIS DOES NOT DO: it does not load mods, does not run discovery, and does not pretend an event fired. When
 * FML is not up the caller gets an event built from the UNMODIFIED map, which is exactly the composite it would have
 * produced if no listener existed -- and a line is logged once saying so, so an author whose component edit vanishes
 * during startup knows why.
 */
public final class ForbricItemComponentGate {

	private static boolean announced;

	private ForbricItemComponentGate() { }

	/**
	 * Same signature as {@code ForgeEventFactory.gatherItemComponentsEvent}, and named so a transformer can swap one
	 * invokestatic's owner for the other without touching its descriptor.
	 */
	public static GatherComponentsEvent.Item gatherItemComponentsEvent(Item item, DataComponentMap original) {
		if (!fmlIsUp()) {
			if (!announced) {
				announced = true;
				ForbricLog.warn("[Forbric/ItemComponents] an ItemStack asked for its components before FML was "
						+ "initialised, so MinecraftForge's GatherComponentsEvent did not fire for it — the vanilla "
						+ "component map is used. Boot-time items (Foods and its siblings) are constructed before mods "
						+ "load, so any component a mod adds is absent from those stacks only");
			}
			return new GatherComponentsEvent.Item(item, original);
		}
		return ForgeEventFactory.gatherItemComponentsEvent(item, original);
	}

	/**
	 * Whether FML's mod loading list exists yet. Asked through LoadingModList.get() rather than by touching ModLoader,
	 * because ModLoader's own initialiser is what throws.
	 */
	private static boolean fmlIsUp() {
		try {
			return LoadingModList.get() != null;
		} catch (Throwable notYet) {
			// LinkageError included on purpose: a class initialiser that fails surfaces as that, not as a checked
			// exception, and "FML is not up" is the only thing this question can mean.
			return false;
		}
	}
}
