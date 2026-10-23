package net.forbric.kernel.runtime.transfer;

import net.neoforged.neoforge.energy.IEnergyStorage;

/**
 * What a bridged energy view knows about its store's direction.
 *
 * <p>On 26.2 this interface existed because NeoForge's {@code EnergyHandler} had NO direction query while Forge's
 * {@code IEnergyStorage} and Reborn's {@code EnergyStorage} did -- so a view carrying a Forge or Reborn store
 * answered with the store's own flags. On 1.21.1 every {@code IEnergyStorage} has {@code canReceive/canExtract},
 * so the fallback is the store itself; the interface survives for the one case that still differs, a view whose
 * flags come from somewhere other than the NeoForge store it wraps (Team Reborn's store, and the live views that
 * answer nothing once their endpoint is gone). The flags are hints for consumers; every insert and extract is
 * still the store's to refuse.
 */
interface EnergyAbilities {
	boolean canInsert();
	boolean canExtract();

	/** Forge's canReceive for a bridged view: the view's own flag when it has one, else the store's. */
	static boolean forgeCanInsert(IEnergyStorage storage) {
		return storage instanceof EnergyAbilities known ? known.canInsert() : storage.canReceive();
	}
	static boolean forgeCanExtract(IEnergyStorage storage) {
		return storage instanceof EnergyAbilities known ? known.canExtract() : storage.canExtract();
	}
	/** Reborn's supportsInsertion for a bridged view: Reborn's default is true, so only a known false answers false. */
	static boolean fabricCanInsert(IEnergyStorage storage) {
		return !(storage instanceof EnergyAbilities known) || known.canInsert();
	}
	static boolean fabricCanExtract(IEnergyStorage storage) {
		return !(storage instanceof EnergyAbilities known) || known.canExtract();
	}
}
