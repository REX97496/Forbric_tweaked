/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import java.util.Optional;
import net.forbric.kernel.util.ForbricLog;

/**
 * Answers Forge's module layer question so FMLLoader.getGameLayer() cannot throw.
 *
 * <p>1.21.1's FMLLoader has {@code private static IModuleLayerManager moduleLayerManager}, assigned in exactly one
 * place -- {@code public static List<ITransformationService.Resource> completeScan(IModuleLayerManager)}, the manager
 * arriving as a parameter. This process never calls completeScan, so the field stays null and getGameLayer() throws:
 *
 * <pre>
 *   getstatic moduleLayerManager; getstatic Layer.GAME;
 *   invokeinterface IModuleLayerManager.getLayer(Layer); Optional.orElseThrow(); checkcast ModuleLayer
 * </pre>
 *
 * <p>IModuleLayerManager is a single-method interface (javap: one abstract method) and Layer has four values --
 * BOOT, SERVICE, PLUGIN, GAME.
 *
 * <p>WHAT THIS ANSWERS AND WHY. Forbric runs no ModLauncher: the game is a classpath entry, not a set of named
 * modules, so no layer "contains the game" in the sense ModLauncher means. The only real layer this process has is
 * ModuleLayer.boot(), so every Layer answers boot and the first call logs that. It is a substitution and the log says
 * so rather than pretending four layers exist.
 *
 * <p>WHAT THIS DOES NOT DO: it does not build ModLauncher's real layer set. The one observed consumer hands the layer
 * to IModLanguageLoader.loadMod(IModInfo, ModFileScanData, ModuleLayer), and with zero mods nothing is loaded through
 * it -- a mod that inspects the game layer's module set gets boot's, which is not the same thing.
 */
public final class ForbricModuleLayerManager implements cpw.mods.modlauncher.api.IModuleLayerManager {

	private static boolean announced;

	@Override
	public Optional<ModuleLayer> getLayer(Layer layer) {
		if (!announced) {
			announced = true;
			ForbricLog.info("[Forbric/ModuleLayer] answering %s with ModuleLayer.boot() — this process runs no "
					+ "ModLauncher, so the game is a classpath entry and boot() is the only real layer there is. This "
					+ "keeps FMLLoader.getGameLayer() from throwing; it is a substitution, not ModLauncher's real layer "
					+ "set", layer);
		}
		return Optional.of(ModuleLayer.boot());
	}
}
