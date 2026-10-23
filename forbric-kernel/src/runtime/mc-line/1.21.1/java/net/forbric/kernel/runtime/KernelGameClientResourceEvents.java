/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import net.minecraftforge.client.ForgeHooksClient;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.TextureAtlasStitchedEvent;

/**
 * An atlas stitched and the models baked, for MinecraftForge mods — on NeoForge's mod bus, where the merged client
 * posts them ({@code TextureAtlas.upload}, {@code ModelManager.apply}). Nothing calls MinecraftForge's two hooks, so
 * Xaero's block colours and entity icons, built from these, went stale after every resource reload. Both hooks are
 * pure emitters.
 *
 * <p>NeoForge skips mod-bus posts entirely once a mod has failed to load, so these arrive only on a clean load — as
 * NeoForge's own do.
 *
 * <p><b>1.21.1 line.</b> {@code ModelEvent.BakingCompleted} carries what was baked in a different shape on each
 * line, and {@code ForgeHooksClient.onModelBake} takes it in that shape (javap, both carriers):
 *
 * <pre>
 *   26.2    BakingCompleted.getBakingResult() : ModelBakery$BakingResult
 *           ForgeHooksClient.onModelBake(ModelManager, ModelBakery)
 *   1.21.1  BakingCompleted.getModels()      : Map&lt;ModelResourceLocation, BakedModel&gt;
 *           ForgeHooksClient.onModelBake(ModelManager, Map&lt;ModelResourceLocation, BakedModel&gt;, ModelBakery)
 * </pre>
 *
 * <p>So this is the one accessor that changes, and both sides of it change together: the map the 1.21.1 event
 * carries is exactly the argument the 1.21.1 hook wants. No value is converted.
 */
public final class KernelGameClientResourceEvents {
	private KernelGameClientResourceEvents() {
	}

	public static void install(Object modBus) {
		KernelGameClientNetworkEvents.forward((IEventBus) modBus, TextureAtlasStitchedEvent.class, "TextureAtlasStitchedEvent",
				event -> ForgeHooksClient.onTextureStitchedPost(event.getAtlas()));
		KernelGameClientNetworkEvents.forward((IEventBus) modBus, ModelEvent.BakingCompleted.class, "ModelEvent.BakingCompleted",
				event -> ForgeHooksClient.onModelBake(event.getModelManager(), event.getModels(), event.getModelBakery()));
	}
}
