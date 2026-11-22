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

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.forbric.kernel.util.ForbricLog;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;

/**
 * Lets a Fabric fluid render handler supply the sprites and tint the merged {@code LiquidBlockRenderer.tesselate}
 * asks for.
 *
 * <p>fabric-rendering-fluids-v1 drives fluid rendering from {@code FluidRendererMixin}, and on 1.21.1 four of that
 * mixin's five handlers anchor on vanilla shapes this base does not have: it asks for
 * {@code LiquidBlockRenderer.isSameFluid(FluidState, FluidState)} (the only declaration, never called — measured
 * with javap on the merged base), reads {@code waterOverlaySprite} (the field is {@code waterOverlay} here and is
 * written by {@code setupSprites()} but read by nothing), and hooks {@code BlockState.getBlock()} in
 * {@code tesselate} (that call does not occur in the method at all). The five handlers therefore cannot attach, so
 * every Fabric mod's {@code FluidRenderHandler} was registered into a registry the renderer never asked. The
 * behaviour is not out of reach though: the NeoForge patch this base carries gets its sprites from
 * {@code FluidSpriteCache.getFluidSprites(level, pos, state)} and its tint from
 * {@code IClientFluidTypeExtensions.of(state).getTintColor(state, level, pos)}, and both of those are seams the
 * transformer can widen — it inserts a call here right after each.
 *
 * <p>Everything is reached reflectively, so the kernel compiles and links without fabric-api: the registry, the
 * {@code get(Fluid)} lookup and the two handler methods are resolved once, lazily, and any {@code Throwable} from
 * the walk is reported once and then answered by NeoForge's own value. The hot path is one reflective
 * {@code get(Fluid)} per fluid tesselation (the same order genuine Fabric pays through the mixin) plus a
 * contains-check; only the first sighting of a fluid logs. A fluid with no Fabric handler — every vanilla fluid,
 * and every fluid of a mod that does not register one — gets back exactly the array and tint NeoForge produced, so
 * vanilla rendering is byte-for-byte what it was. {@code -Dforbric.fabricFluids=off} returns both by identity.
 */
public final class KernelFabricFluids {
	public static final String PROPERTY = "forbric.fabricFluids";
	/** The interface, not the implementation: fabric is free to answer with its own registry as long as it has one. */
	private static final String REGISTRY = "net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderHandlerRegistry";
	private static final Set<Fluid> ASKED = ConcurrentHashMap.newKeySet();
	private static final Set<Fluid> ANSWERED = ConcurrentHashMap.newKeySet();
	private static volatile boolean failureReported;
	private static volatile boolean looked;

	private static MethodHandle registryInstance;
	private static MethodHandle handlerOf;
	private static MethodHandle spritesOf;
	private static MethodHandle colourOf;

	private KernelFabricFluids() {
	}

	/**
	 * Site A: NeoForge's sprite array, or the Fabric handler's sprites for this fluid.
	 *
	 * <p>Inserted immediately after {@code FluidSpriteCache.getFluidSprites} in {@code tesselate}, where the array
	 * is the only value on the stack: the three loads below push the method's own parameters and the call consumes
	 * them plus the array, so the stack shape after the seam is the same one instruction it was before.
	 */
	public static TextureAtlasSprite[] sprites(TextureAtlasSprite[] neo, BlockAndTintGetter level, BlockPos pos,
			FluidState state) {
		if (neo == null || level == null || state == null) return neo;
		if ("off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"))) return neo;
		Object handler = handlerFor(state);
		if (handler == null) return neo;
		try {
			TextureAtlasSprite[] own = (TextureAtlasSprite[]) spritesOf.invoke(handler, level, pos, state);
			if (own == null || own.length == 0) return neo;
			ANSWERED.add(state.getType());
			return own;
		} catch (Throwable t) {
			report(t);
			return neo;
		}
	}

	/**
	 * Site B: NeoForge's tint, or the Fabric handler's colour for this fluid.
	 *
	 * <p>Inserted immediately after {@code IClientFluidTypeExtensions.getTintColor} in {@code tesselate}, where an
	 * int is on the stack; the same three loads push this method's parameters, so one int goes in and one int comes
	 * out.
	 */
	public static int tintColor(int neo, BlockAndTintGetter level, BlockPos pos, FluidState state) {
		if (level == null || state == null) return neo;
		if ("off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"))) return neo;
		Object handler = handlerFor(state);
		if (handler == null) return neo;
		try {
			return (int) colourOf.invoke(handler, level, pos, state);
		} catch (Throwable t) {
			report(t);
			return neo;
		}
	}

	/** The Fabric handler for this fluid, or null — never throws out to the renderer. */
	private static Object handlerFor(FluidState state) {
		if (!looked) look();
		if (handlerOf == null) return null;
		Fluid fluid = state.getType();
		boolean first = !ASKED.contains(fluid) && ASKED.add(fluid);
		try {
			Object handler = handlerOf.invoke(fluid);
			if (first) report(fluid, handler != null);
			return handler;
		} catch (Throwable t) {
			report(t);
			return null;
		}
	}

	private static synchronized void look() {
		if (looked) return;
		looked = true;
		try {
			Class<?> registry = Class.forName(REGISTRY, true, KernelFabricFluids.class.getClassLoader());
			MethodHandles.Lookup lookup = MethodHandles.publicLookup();
			registryInstance = lookup.findStaticGetter(registry, "INSTANCE", registry);
			Object instance = (Object) registryInstance.invoke();
			if (instance == null) {
				report(new IllegalStateException(REGISTRY + ".INSTANCE is null"));
				return;
			}
			Class<?> handlerType = Class.forName(REGISTRY.replace("FluidRenderHandlerRegistry",
					"FluidRenderHandler"), true, KernelFabricFluids.class.getClassLoader());
			Class<?> fluidType = Class.forName("net.minecraft.world.level.material.Fluid", true,
					KernelFabricFluids.class.getClassLoader());
			handlerOf = lookup.findVirtual(registry, "get", MethodType.methodType(handlerType, fluidType))
					.bindTo(instance);
			spritesOf = lookup.findVirtual(handlerType, "getFluidSprites", MethodType.methodType(
					TextureAtlasSprite[].class, BlockAndTintGetter.class, BlockPos.class, FluidState.class));
			colourOf = lookup.findVirtual(handlerType, "getFluidColor", MethodType.methodType(int.class,
					BlockAndTintGetter.class, BlockPos.class, FluidState.class));
		} catch (Throwable t) {
			handlerOf = null;
			ForbricLog.debug("[Forbric/Fluids] no Fabric fluid render registry to consult (%s) — NeoForge's sprites "
					+ "and tint are used for every fluid", t.getClass().getName());
		}
	}

	private static void report(Fluid fluid, boolean answered) {
		ForbricLog.info("[Forbric/Fluids] Fabric fluid render handlers asked for %d fluid(s) so far, %d answered "
				+ "(%s)", ASKED.size(), ANSWERED.size() + (answered ? 1 : 0), fluid);
	}

	private static void report(Throwable t) {
		if (failureReported) return;
		failureReported = true;
		ForbricLog.warn("[Forbric/Fluids] a Fabric fluid render handler threw while being asked for sprites or a "
				+ "tint — NeoForge's own values are used", t);
	}
}
