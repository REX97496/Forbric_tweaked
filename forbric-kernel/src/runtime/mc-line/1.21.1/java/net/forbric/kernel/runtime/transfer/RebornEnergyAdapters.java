package net.forbric.kernel.runtime.transfer;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.neoforged.neoforge.energy.IEnergyStorage;
import team.reborn.energy.api.EnergyStorage;

/**
 * Team Reborn Energy (the Fabric ecosystem's energy API) and the handler energy API, the one direction on this line
 * where a real transaction meets a real absence of one.
 *
 * <p>Reborn's {@code insert}/{@code extract} take a {@link TransactionContext}, so they can be asked to undo
 * themselves; {@code IEnergyStorage} can only be asked {@code simulate}. Therefore:
 * <ul>
 * <li>{@code FromFabric} (a handler consumer reading a Reborn store) opens a Fabric scope per call: {@code simulate}
 * runs the operation in a scope that is aborted, {@code execute} in one that is committed. Reborn's own store
 * restores itself, so nothing has to be snapshotted here.
 * <li>{@code FromNeo} (a Reborn consumer writing a handler store) SNAPSHOTS the store with
 * {@link ForgeEnergyAdapters#prepare} before the write, so an abort of the consumer's scope restores it. A store that
 * cannot be snapshotted is reported as FORGE_HANDLER_NOT_ROLLBACK_SAFE and not written at all.
 * </ul>
 *
 * <p>Units are 1:1 (see EnergyUnits). A request larger than an int is asked of the int side as Integer.MAX_VALUE;
 * the rest is never moved and stays in its source. A provider answer outside [0, request] is rejected before the
 * caller's scope can commit, so it is rolled back.
 *
 * <p>Only this class, RebornEnergyBridge and nothing else in the runtime names a Reborn type. They are loaded only
 * when KernelTransferInterop found Team Reborn Energy installed; without it no Reborn class is ever requested.
 */
public final class RebornEnergyAdapters {
	private RebornEnergyAdapters() { }

	/** A handler-side view of a Reborn store; our own Reborn view of a handler unwraps to that handler. */
	public static IEnergyStorage neo(EnergyStorage storage) {
		Objects.requireNonNull(storage);
		if (storage instanceof FromNeo own) return own.store();
		return new FromFabric(storage);
	}
	/**
	 * A Reborn view of a handler store; our own handler view of a Reborn store unwraps to that store. NULL when the
	 * store is not one whose writes can be taken back -- the 26.2 edition's rule, which refuses a provider rather
	 * than exposing it without rollback. {@code changed} runs once per root commit of a Reborn consumer's scope.
	 */
	public static EnergyStorage fabric(IEnergyStorage store, Object owner, Runnable changed) {
		Objects.requireNonNull(store);
		Objects.requireNonNull(changed);
		if (store instanceof FromFabric own) return own.storage();
		if (!ForgeEnergyAdapters.supports(store)) return null;
		return new FromNeo(store, owner, changed);
	}

	/** A Reborn store resolved again for every operation, under LiveTransferEndpoints' rules. */
	public static EnergyStorage live(Supplier<EnergyStorage> lookup, BooleanSupplier valid, LongSupplier generation) {
		return new Live(lookup, valid, generation);
	}

	private record FromFabric(EnergyStorage storage) implements IEnergyStorage, EnergyAbilities {
		public int receiveEnergy(int maximum, boolean simulate) { return move(maximum, simulate, true); }
		public int extractEnergy(int maximum, boolean simulate) { return move(maximum, simulate, false); }
		private int move(int maximum, boolean simulate, boolean insert) {
			if (maximum < 0) throw new IllegalArgumentException("Negative energy amount: " + maximum);
			if (maximum == 0) return 0;
			try (var scope = PairedTransactions.scope()) {
				long moved = EnergyUnits.moved(insert ? storage.insert(maximum, scope) : storage.extract(maximum, scope), maximum);
				if (!simulate) scope.commit();
				return (int) moved;
			} catch (LiveTransferEndpoints.Unavailable invalidated) {
				NativeTransferAdapters.requireSuccessfulRollback(invalidated, storage);
				TransferIssues.report("ENDPOINT_INVALIDATED", storage, invalidated.getMessage() + "; the operation was rolled back");
				return 0;
			}
		}
		// Never moves energy: a malformed negative Reborn amount reads as empty rather than failing a render.
		public int getEnergyStored() { return EnergyUnits.saturated(EnergyUnits.reported(storage.getAmount())); }
		public int getMaxEnergyStored() { return EnergyUnits.saturated(EnergyUnits.reported(storage.getCapacity())); }
		public boolean canExtract() { return storage.supportsExtraction(); }
		public boolean canReceive() { return storage.supportsInsertion(); }
		public boolean canInsert() { return storage.supportsInsertion(); }
		// Identity, not the record's component equality: two views of one store are two endpoints.
		@Override public boolean equals(Object other) { return this == other; }
		@Override public int hashCode() { return System.identityHashCode(this); }
	}

	private record FromNeo(IEnergyStorage store, Object owner, Runnable changed) implements EnergyStorage {
		public boolean supportsInsertion() { return EnergyAbilities.fabricCanInsert(store); }
		public boolean supportsExtraction() { return EnergyAbilities.fabricCanExtract(store); }
		public long insert(long maximum, TransactionContext context) { return move(maximum, context, true); }
		public long extract(long maximum, TransactionContext context) { return move(maximum, context, false); }
		private long move(long maximum, TransactionContext context, boolean insert) {
			int request = EnergyUnits.request(maximum);
			if (request == 0) return 0;
			try {
				IEnergyStorage target = resolved(store);
				if (target == null) return 0;
				ForgeEnergyAdapters.prepare(context, target);
				long moved = EnergyUnits.moved(insert ? target.receiveEnergy(request, false) : target.extractEnergy(request, false), request);
				NativeTransferAdapters.stillResolved(target, resolved(store));
				if (moved > 0) ForgeEnergyAdapters.changed(target, owner, changed);
				return moved;
			} catch (LiveTransferEndpoints.Unavailable invalidated) {
				NativeTransferAdapters.requireSuccessfulRollback(invalidated, store);
				TransferIssues.report("ENDPOINT_INVALIDATED", store, invalidated.getMessage() + "; the operation was rolled back");
				return 0;
			}
		}
		private static IEnergyStorage resolved(IEnergyStorage store) {
			return store instanceof LiveTransferEndpoints.LiveEnergy live ? live.resolvedEnergy() : store;
		}
		public long getAmount() { return EnergyUnits.reported(store.getEnergyStored()); }
		public long getCapacity() { return EnergyUnits.reported(store.getMaxEnergyStored()); }
		@Override public boolean equals(Object other) { return this == other; }
		@Override public int hashCode() { return System.identityHashCode(this); }
	}

	private record Live(Supplier<EnergyStorage> lookup, BooleanSupplier valid, LongSupplier generation) implements EnergyStorage {
		private EnergyStorage current() { return valid.getAsBoolean() ? lookup.get() : null; }
		public boolean supportsInsertion() { var s = current(); return s != null && s.supportsInsertion(); }
		public boolean supportsExtraction() { var s = current(); return s != null && s.supportsExtraction(); }
		public long insert(long maximum, TransactionContext context) {
			var s = current(); if (s == null) return 0;
			long before = generation.getAsLong();
			long moved = s.insert(maximum, context); LiveTransferEndpoints.stillValid(valid); LiveTransferEndpoints.unchanged(before, generation); return moved;
		}
		public long extract(long maximum, TransactionContext context) {
			var s = current(); if (s == null) return 0;
			long before = generation.getAsLong();
			long moved = s.extract(maximum, context); LiveTransferEndpoints.stillValid(valid); LiveTransferEndpoints.unchanged(before, generation); return moved;
		}
		public long getAmount() { var s = current(); return s == null ? 0 : s.getAmount(); }
		public long getCapacity() { var s = current(); return s == null ? 0 : s.getCapacity(); }
		@Override public boolean equals(Object other) { return this == other; }
		@Override public int hashCode() { return System.identityHashCode(this); }
	}
}
