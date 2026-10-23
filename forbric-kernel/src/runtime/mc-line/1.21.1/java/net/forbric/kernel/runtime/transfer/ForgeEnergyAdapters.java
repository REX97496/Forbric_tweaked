package net.forbric.kernel.runtime.transfer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.energy.EnergyStorage;

/**
 * Energy on a line where the two ecosystems' energy APIs are the SAME SHAPE and neither has a transaction.
 *
 * <p>{@link #supports}/{@link #prepare}: the reversible part. Fabric's side of energy on this line is Team Reborn,
 * whose {@code insert/extract} DO take a {@code TransactionContext}, so when a Reborn consumer writes into a
 * handler {@code IEnergyStorage} the store has to be snapshotted before the write and restored if the scope aborts
 * -- exactly the rule the item and fluid stores follow, in {@link ForgeSnapshotAdapters}. Which store can be
 * snapshotted is proved the same way: it must be the standard {@code EnergyStorage} of one of the two ecosystems
 * (or a subclass that declares none of the six {@code IEnergyStorage} methods, so the standard code is what runs),
 * whose entire transferable state is the int {@code energy} field. Anything else gets no reversible view at all and
 * is reported once per class as FORGE_HANDLER_NOT_ROLLBACK_SAFE; there is no switch that grants it one.
 *
 * <p>{@link #neo}/{@link #forge}: the direction MOUNTS the other ecosystem. It is a type adapter, not a transaction:
 * both interfaces have the same six methods and bring their own {@code simulate} flag, and neither the Forge nor the
 * NeoForge API has anything to roll back, so there is nothing for this class to journal there. That is a real
 * difference from 26.2, where a NeoForge view of a Forge store had to journal because NeoForge consumers write
 * inside transactions.
 *
 * <p>The standard store can hold more than its capacity (or less than nothing): its {@code deserializeNBT} sets the
 * field unclamped, so a save made before a config lowered the capacity loads that way. Its own {@code receiveEnergy}
 * then answers a NEGATIVE amount and lowers the field. That is Forge's code on a state Forge allows, not a broken
 * provider, so the view refuses the operation instead of throwing into the consumer's tick: the field is put back as
 * it was and nothing moves.
 */
public final class ForgeEnergyAdapters {
	private ForgeEnergyAdapters() { }
	private static final Class<?> FORGE = net.minecraftforge.energy.EnergyStorage.class;
	private static final Class<?> NEO = net.neoforged.neoforge.energy.EnergyStorage.class;
	private static final Class<?> FORGE_EMPTY = net.minecraftforge.energy.EmptyEnergyStorage.class;
	private static final Class<?> NEO_EMPTY = net.neoforged.neoforge.energy.EmptyEnergyStorage.class;
	private static final Field FORGE_ENERGY = field(FORGE, "energy");
	private static final Field NEO_ENERGY = field(NEO, "energy");
	/** Every IEnergyStorage method. A subclass declaring any of them no longer runs the audited code. */
	private static final Set<String> CRITICAL = Set.of("receiveEnergy", "extractEnergy", "getEnergyStored", "getMaxEnergyStored", "canReceive", "canExtract");
	private static final ThreadLocal<Journal> CURRENT = new ThreadLocal<>();
	private static final ClassValue<Boolean> STANDARD_SHAPE = new ClassValue<>() {
		protected Boolean computeValue(Class<?> type) {
			boolean forge = FORGE.isAssignableFrom(type), neo = NEO.isAssignableFrom(type);
			if (!forge && !neo) return false;
			Class<?> base = forge ? FORGE : NEO;
			try {
				for (Class<?> at = type; at != base && at != null; at = at.getSuperclass())
					for (Method method : at.getDeclaredMethods()) if (CRITICAL.contains(method.getName())) return false;
				return true;
			} catch (LinkageError unreadable) { return false; }
		}
	};

	private static Field field(Class<?> type, String name) {
		try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field; }
		catch (ReflectiveOperationException drift) { throw new ExceptionInInitializerError(drift); }
	}
	private static Field energyField(Object storage) {
		return storage.getClass() == FORGE || FORGE.isAssignableFrom(storage.getClass()) ? FORGE_ENERGY : NEO_ENERGY;
	}
	static int read(Object storage) {
		try { return energyField(storage).getInt(storage); }
		catch (IllegalAccessException impossible) { throw new IllegalStateException(impossible); }
	}
	private static void write(Object storage, int value) {
		try { energyField(storage).setInt(storage, value); }
		catch (IllegalAccessException impossible) { throw new IllegalStateException(impossible); }
	}

	/** Whether {@code storage} may be written inside a Fabric transaction: the standard class, or a subclass of its exact shape. */
	/** The store behind one of our own facades: an audit judges the store, not the wrapper. */
	private static Object target(Object store) {
		if (store instanceof LiveTransferEndpoints.LiveEnergy live) return target(live.resolvedEnergy());
		if (store instanceof NeoView own) return own.store();
		if (store instanceof ForgeView own) return own.store();
		return store;
	}
	/** Forge's and NeoForge's EmptyEnergyStorage both mean "nothing here"; neither is a store to audit. */
	private static boolean empty(Object storage) {
		Class<?> type = storage.getClass();
		return type == FORGE_EMPTY || type == NEO_EMPTY;
	}
	public static boolean supports(Object candidate) {
		Object storage = target(candidate);
		if (storage == null) return false;
		if (empty(storage)) return true; // holds nothing, accepts nothing: no finding
		boolean supported = (FORGE.isAssignableFrom(storage.getClass()) || NEO.isAssignableFrom(storage.getClass()))
				&& STANDARD_SHAPE.get(storage.getClass());
		if (!supported) refused(storage);
		return supported;
	}
	private static void refused(Object storage) {
		TransferIssues.report("FORGE_HANDLER_NOT_ROLLBACK_SAFE", storage,
				"The energy provider is not an audited reversible implementation; transactional insertion and extraction were not exposed");
	}

	/** Snapshot {@code storage} and register the undo with the consumer's transaction. Call before EVERY mutation. */
	static void prepare(TransactionContext context, Object candidate) {
		Object storage = target(candidate);
		if (!supports(storage)) throw new IllegalStateException("A non-reversible energy store cannot be written transactionally");
		if (empty(storage)) return; // nothing to restore
		current().prepare(storage, context);
	}

	/** Arm the "this block changed" notification, which runs once after the ROOT commit. */
	static void changed(Object candidate, Object owner, Runnable changed) {
		Object storage = target(candidate);
		if (empty(storage)) return;
		current().notifications.put(owner, changed);
	}

	private static Journal current() {
		Journal journal = CURRENT.get();
		if (journal == null) { journal = new Journal(); CURRENT.set(journal); }
		return journal;
	}

	/** A NeoForge view of a Forge store. Both interfaces carry their own simulate flag, so this is a type adapter. */
	public static net.neoforged.neoforge.energy.IEnergyStorage neo(net.minecraftforge.energy.IEnergyStorage forge, Object owner, Runnable changed) {
		Objects.requireNonNull(forge);
		Objects.requireNonNull(changed);
		if (forge.getClass() == net.minecraftforge.energy.EmptyEnergyStorage.class) return net.neoforged.neoforge.energy.EmptyEnergyStorage.INSTANCE;
		if (forge instanceof ForgeView own) return own.store;
		return new NeoView(forge, owner, changed);
	}
	/** A Forge view of a NeoForge store, the opposite direction. */
	public static net.minecraftforge.energy.IEnergyStorage forge(net.neoforged.neoforge.energy.IEnergyStorage neo, Object owner, Runnable changed) {
		Objects.requireNonNull(neo);
		Objects.requireNonNull(changed);
		if (neo.getClass() == net.neoforged.neoforge.energy.EmptyEnergyStorage.class) return net.minecraftforge.energy.EmptyEnergyStorage.INSTANCE;
		if (neo instanceof NeoView own) return own.store;
		return new ForgeView(neo, owner, changed);
	}

	private static final class Snapshot {
		final IdentityHashMap<Object, Integer> values = new IdentityHashMap<>();
		final IdentityHashMap<Object, Runnable> notifications;
		Snapshot(IdentityHashMap<Object, Runnable> notifications) { this.notifications = new IdentityHashMap<>(notifications); }
	}
	private static final class Journal extends PairedTransactions.Journal<Snapshot> {
		final Set<Object> tracked = Collections.newSetFromMap(new IdentityHashMap<>());
		final List<Snapshot> snapshots = new ArrayList<>();
		IdentityHashMap<Object, Runnable> notifications = new IdentityHashMap<>();
		void prepare(Object storage, TransactionContext context) {
			tracked.add(storage);
			PairedTransactions.begin(this, storage, context);
		}
		void include(Object storage) {
			// A store first touched at depth n must still be restored if a shallower scope aborts: every live
			// snapshot learns its value now, before this operation changes it.
			for (Snapshot snapshot : snapshots) snapshot.values.putIfAbsent(storage, read(storage));
			tracked.add(storage);
		}
		protected Snapshot createSnapshot() {
			Snapshot snapshot = new Snapshot(notifications);
			for (Object storage : tracked) snapshot.values.put(storage, read(storage));
			snapshots.add(snapshot);
			return tracked(snapshot);
		}
		@Override protected void releaseSnapshot(Snapshot snapshot) { snapshots.remove(snapshot); super.releaseSnapshot(snapshot); }
		protected void readSnapshot(Snapshot snapshot) {
			for (var entry : snapshot.values.entrySet()) write(entry.getKey(), entry.getValue());
			notifications = new IdentityHashMap<>(snapshot.notifications);
		}
		protected void onFinalCommit() {
			List<Runnable> callbacks = new ArrayList<>(notifications.values()); clear();
			RuntimeException failure = null;
			for (Runnable callback : callbacks) try { callback.run(); }
			catch (RuntimeException thrown) { if (failure == null) failure = thrown; else failure.addSuppressed(thrown); }
			if (failure != null) throw failure;
		}
		void clear() {
			tracked.clear(); snapshots.clear(); notifications.clear();
			if (CURRENT.get() == this) CURRENT.remove();
		}
	}

	private record NeoView(net.minecraftforge.energy.IEnergyStorage store, Object owner, Runnable changed)
			implements net.neoforged.neoforge.energy.IEnergyStorage, EnergyAbilities {
		public int receiveEnergy(int maximum, boolean simulate) { return move(maximum, simulate, true); }
		public int extractEnergy(int maximum, boolean simulate) { return move(maximum, simulate, false); }
		private int move(int maximum, boolean simulate, boolean insert) {
			if (maximum < 0) throw new IllegalArgumentException("Negative energy amount: " + maximum);
			if (maximum == 0) return 0;
			int moved = insert ? store.receiveEnergy(maximum, simulate) : store.extractEnergy(maximum, simulate);
			if (moved < 0 || moved > maximum) return 0;
			if (moved > 0 && !simulate) changed.run();
			return moved;
		}
		public int getEnergyStored() { return Math.max(0, store.getEnergyStored()); }
		public int getMaxEnergyStored() { return Math.max(0, store.getMaxEnergyStored()); }
		public boolean canExtract() { return store.canExtract(); }
		public boolean canReceive() { return store.canReceive(); }
		// The view's own flags are the store's, so EnergyAbilities and the interface agree by construction.
		public boolean canInsert() { return store.canReceive(); }
	}

	private record ForgeView(net.neoforged.neoforge.energy.IEnergyStorage store, Object owner, Runnable changed)
			implements net.minecraftforge.energy.IEnergyStorage, EnergyAbilities {
		public int receiveEnergy(int maximum, boolean simulate) { return move(maximum, simulate, true); }
		public int extractEnergy(int maximum, boolean simulate) { return move(maximum, simulate, false); }
		private int move(int maximum, boolean simulate, boolean insert) {
			if (maximum < 0) throw new IllegalArgumentException("Negative energy amount: " + maximum);
			if (maximum == 0) return 0;
			int moved = insert ? store.receiveEnergy(maximum, simulate) : store.extractEnergy(maximum, simulate);
			if (moved < 0 || moved > maximum) return 0;
			if (moved > 0 && !simulate) changed.run();
			return moved;
		}
		public int getEnergyStored() { return Math.max(0, store.getEnergyStored()); }
		public int getMaxEnergyStored() { return Math.max(0, store.getMaxEnergyStored()); }
		public boolean canExtract() { return store.canExtract(); }
		public boolean canReceive() { return store.canReceive(); }
		public boolean canInsert() { return store.canReceive(); }
	}
}
