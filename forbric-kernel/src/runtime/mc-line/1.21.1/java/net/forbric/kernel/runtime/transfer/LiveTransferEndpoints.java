package net.forbric.kernel.runtime.transfer;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.SlottedStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.storage.base.SingleSlotStorage;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * Location-bound views never retain a resolved foreign handler. Every operation resolves it anew, and a removed
 * endpoint becomes empty. If removal happens inside a provider callback, throwing before the enclosing operation
 * settles lets the real engine (Fabric's transaction, or the handler's own simulate flag) refuse it instead of
 * silently writing into a dead object.
 *
 * <p>Identical in structure to the 26.2 edition: the 1.21.1 line's difference is only the type of the view it hands
 * out -- NeoForge handlers instead of {@code ResourceHandler} -- because that is what a NeoForge consumer asks for
 * here.
 */
public final class LiveTransferEndpoints {
	private LiveTransferEndpoints() { }
	static final class Unavailable extends IllegalStateException {
		Unavailable(String message) { super(message); }
	}
	/**
	 * A view that can name the store it would currently reach. A snapshot must be taken of THAT object, because the
	 * whole rollback is "capture the store, then write it": capturing a wrapper would capture nothing, and writing
	 * through a re-resolving wrapper would write a store the snapshot does not cover.
	 */
	interface LiveItems extends IItemHandler { IItemHandler resolvedItems(); }
	interface LiveFluids extends IFluidHandler { IFluidHandler resolvedFluids(); }
	interface LiveEnergy extends net.neoforged.neoforge.energy.IEnergyStorage { net.neoforged.neoforge.energy.IEnergyStorage resolvedEnergy(); }
	static void stillValid(BooleanSupplier valid) {
		if (!valid.getAsBoolean()) throw new Unavailable("Transfer endpoint invalidated during an operation");
	}
	static void unchanged(long before, LongSupplier generation) {
		if (before != generation.getAsLong()) throw new Unavailable("Transfer capability invalidated during an operation");
	}

	public static IItemHandler items(Supplier<IItemHandler> lookup, BooleanSupplier valid) {
		return items(lookup, valid, () -> 0L);
	}
	public static IItemHandler items(Supplier<IItemHandler> lookup, BooleanSupplier valid, LongSupplier generation) {
		return new LiveItems() {
			private IItemHandler current() { return valid.getAsBoolean() ? lookup.get() : null; }
			public IItemHandler resolvedItems() { return current(); }
			public int getSlots() { IItemHandler h = current(); return h == null ? 0 : h.getSlots(); }
			public ItemStack getStackInSlot(int slot) {
				IItemHandler h = current();
				return h == null || slot < 0 || slot >= h.getSlots() ? ItemStack.EMPTY : h.getStackInSlot(slot);
			}
			public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
				IItemHandler h = current();
				if (h == null || slot < 0 || slot >= h.getSlots()) return stack;
				long before = generation.getAsLong();
				ItemStack remainder = h.insertItem(slot, stack, simulate);
				stillValid(valid); unchanged(before, generation); return remainder;
			}
			public ItemStack extractItem(int slot, int maximum, boolean simulate) {
				IItemHandler h = current();
				if (h == null || slot < 0 || slot >= h.getSlots()) return ItemStack.EMPTY;
				long before = generation.getAsLong();
				ItemStack extracted = h.extractItem(slot, maximum, simulate);
				stillValid(valid); unchanged(before, generation); return extracted;
			}
			public int getSlotLimit(int slot) {
				IItemHandler h = current();
				return h == null || slot < 0 || slot >= h.getSlots() ? 0 : h.getSlotLimit(slot);
			}
			public boolean isItemValid(int slot, ItemStack stack) {
				IItemHandler h = current();
				return h != null && slot >= 0 && slot < h.getSlots() && h.isItemValid(slot, stack);
			}
		};
	}

	public static IFluidHandler fluids(Supplier<IFluidHandler> lookup, BooleanSupplier valid) {
		return fluids(lookup, valid, () -> 0L);
	}
	public static IFluidHandler fluids(Supplier<IFluidHandler> lookup, BooleanSupplier valid, LongSupplier generation) {
		return new LiveFluids() {
			private IFluidHandler current() { return valid.getAsBoolean() ? lookup.get() : null; }
			public IFluidHandler resolvedFluids() { return current(); }
			public int getTanks() { IFluidHandler h = current(); return h == null ? 0 : h.getTanks(); }
			public FluidStack getFluidInTank(int tank) {
				IFluidHandler h = current();
				return h == null || tank < 0 || tank >= h.getTanks() ? FluidStack.EMPTY : h.getFluidInTank(tank);
			}
			public int getTankCapacity(int tank) {
				IFluidHandler h = current();
				return h == null || tank < 0 || tank >= h.getTanks() ? 0 : h.getTankCapacity(tank);
			}
			public boolean isFluidValid(int tank, FluidStack stack) {
				IFluidHandler h = current();
				return h != null && tank >= 0 && tank < h.getTanks() && h.isFluidValid(tank, stack);
			}
			public int fill(FluidStack stack, FluidAction action) {
				IFluidHandler h = current(); if (h == null) return 0;
				long before = generation.getAsLong();
				int moved = h.fill(stack, action);
				stillValid(valid); unchanged(before, generation); return moved;
			}
			public FluidStack drain(FluidStack stack, FluidAction action) {
				IFluidHandler h = current(); if (h == null) return FluidStack.EMPTY;
				long before = generation.getAsLong();
				FluidStack drained = h.drain(stack, action);
				stillValid(valid); unchanged(before, generation); return drained;
			}
			public FluidStack drain(int maximum, FluidAction action) {
				IFluidHandler h = current(); if (h == null) return FluidStack.EMPTY;
				long before = generation.getAsLong();
				FluidStack drained = h.drain(maximum, action);
				stillValid(valid); unchanged(before, generation); return drained;
			}
		};
	}

	/** The same rules for an energy store: resolved per operation, empty once its endpoint is gone. */
	public static net.neoforged.neoforge.energy.IEnergyStorage energy(Supplier<net.neoforged.neoforge.energy.IEnergyStorage> lookup,
			BooleanSupplier valid, LongSupplier generation) {
		return new LiveStore(lookup, valid, generation);
	}
	private record LiveStore(Supplier<net.neoforged.neoforge.energy.IEnergyStorage> lookup, BooleanSupplier valid,
			LongSupplier generation) implements LiveEnergy, EnergyAbilities {
		private net.neoforged.neoforge.energy.IEnergyStorage current() { return valid.getAsBoolean() ? lookup.get() : null; }
		public net.neoforged.neoforge.energy.IEnergyStorage resolvedEnergy() { return current(); }
		public int getEnergyStored() {
			var h = current(); return h == null ? 0 : EnergyUnits.saturated(h.getEnergyStored());
		}
		public int getMaxEnergyStored() {
			var h = current(); return h == null ? 0 : EnergyUnits.saturated(h.getMaxEnergyStored());
		}
		public int receiveEnergy(int maximum, boolean simulate) { return move(maximum, simulate, true); }
		public int extractEnergy(int maximum, boolean simulate) { return move(maximum, simulate, false); }
		private int move(int maximum, boolean simulate, boolean insert) {
			var h = current(); if (h == null) return 0;
			long before = generation.getAsLong();
			int moved = insert ? h.receiveEnergy(maximum, simulate) : h.extractEnergy(maximum, simulate);
			stillValid(valid); unchanged(before, generation); return moved;
		}
		public boolean canReceive() { var h = current(); return h != null && h.canReceive(); }
		public boolean canExtract() { var h = current(); return h != null && h.canExtract(); }
		public boolean canInsert() { var h = current(); return h != null && EnergyAbilities.forgeCanInsert(h); }
		// Identity, not the record's component equality: two live views of one place are two endpoints.
		@Override public boolean equals(Object other) { return this == other; }
		@Override public int hashCode() { return System.identityHashCode(this); }
	}

	public static SlottedStorage<ItemVariant> fabricItems(Supplier<SlottedStorage<ItemVariant>> lookup, BooleanSupplier valid, ItemVariant empty) {
		return fabricItems(lookup, valid, () -> 0L, empty);
	}
	public static SlottedStorage<ItemVariant> fabricItems(Supplier<SlottedStorage<ItemVariant>> lookup, BooleanSupplier valid,
			LongSupplier generation, ItemVariant empty) {
		return new SlottedStorage<>() {
			private SlottedStorage<ItemVariant> current() { return valid.getAsBoolean() ? lookup.get() : null; }
			public int getSlotCount() { var s = current(); return s == null ? 0 : s.getSlotCount(); }
			public long insert(ItemVariant resource, long maximum, TransactionContext transaction) {
				var s = current(); if (s == null) return 0;
				long before = generation.getAsLong();
				long moved = s.insert(resource, maximum, transaction);
				stillValid(valid); unchanged(before, generation); return moved;
			}
			public long extract(ItemVariant resource, long maximum, TransactionContext transaction) {
				var s = current(); if (s == null) return 0;
				long before = generation.getAsLong();
				long moved = s.extract(resource, maximum, transaction);
				stillValid(valid); unchanged(before, generation); return moved;
			}
			public SingleSlotStorage<ItemVariant> getSlot(int index) {
				if (index < 0) throw new IndexOutOfBoundsException(index);
				var s = current();
				SingleSlotStorage<ItemVariant> slot = s != null && index < s.getSlotCount() ? s.getSlot(index) : null;
				return new SingleSlotStorage<>() {
					public boolean isResourceBlank() { return slot == null || slot.isResourceBlank(); }
					public ItemVariant getResource() { return slot == null ? empty : slot.getResource(); }
					public long getAmount() { return slot == null ? 0 : slot.getAmount(); }
					public long getCapacity() { return slot == null ? 0 : slot.getCapacity(); }
					public boolean supportsInsertion() { return slot != null && slot.supportsInsertion(); }
					public boolean supportsExtraction() { return slot != null && slot.supportsExtraction(); }
					public long insert(ItemVariant resource, long maximum, TransactionContext transaction) {
						if (slot == null) return 0;
						long before = generation.getAsLong();
						long moved = slot.insert(resource, maximum, transaction);
						stillValid(valid); unchanged(before, generation); return moved;
					}
					public long extract(ItemVariant resource, long maximum, TransactionContext transaction) {
						if (slot == null) return 0;
						long before = generation.getAsLong();
						long moved = slot.extract(resource, maximum, transaction);
						stillValid(valid); unchanged(before, generation); return moved;
					}
				};
			}
			public Iterator<StorageView<ItemVariant>> iterator() {
				return new Iterator<>() {
					int next;
					public boolean hasNext() { return next < getSlotCount(); }
					public StorageView<ItemVariant> next() { if (!hasNext()) throw new NoSuchElementException(); return getSlot(next++); }
				};
			}
		};
	}

	public static SlottedStorage<FluidVariant> fabricFluids(Supplier<SlottedStorage<FluidVariant>> lookup, BooleanSupplier valid, FluidVariant empty) {
		return fabricFluids(lookup, valid, () -> 0L, empty);
	}
	public static SlottedStorage<FluidVariant> fabricFluids(Supplier<SlottedStorage<FluidVariant>> lookup, BooleanSupplier valid,
			LongSupplier generation, FluidVariant empty) {
		return new SlottedStorage<>() {
			private SlottedStorage<FluidVariant> current() { return valid.getAsBoolean() ? lookup.get() : null; }
			public int getSlotCount() { var s = current(); return s == null ? 0 : s.getSlotCount(); }
			public long insert(FluidVariant resource, long maximum, TransactionContext transaction) {
				var s = current(); if (s == null) return 0;
				long before = generation.getAsLong();
				long moved = s.insert(resource, maximum, transaction);
				stillValid(valid); unchanged(before, generation); return moved;
			}
			public long extract(FluidVariant resource, long maximum, TransactionContext transaction) {
				var s = current(); if (s == null) return 0;
				long before = generation.getAsLong();
				long moved = s.extract(resource, maximum, transaction);
				stillValid(valid); unchanged(before, generation); return moved;
			}
			public SingleSlotStorage<FluidVariant> getSlot(int index) {
				if (index < 0) throw new IndexOutOfBoundsException(index);
				var s = current();
				SingleSlotStorage<FluidVariant> slot = s != null && index < s.getSlotCount() ? s.getSlot(index) : null;
				return new SingleSlotStorage<>() {
					public boolean isResourceBlank() { return slot == null || slot.isResourceBlank(); }
					public FluidVariant getResource() { return slot == null ? empty : slot.getResource(); }
					public long getAmount() { return slot == null ? 0 : slot.getAmount(); }
					public long getCapacity() { return slot == null ? 0 : slot.getCapacity(); }
					public boolean supportsInsertion() { return slot != null && slot.supportsInsertion(); }
					public boolean supportsExtraction() { return slot != null && slot.supportsExtraction(); }
					public long insert(FluidVariant resource, long maximum, TransactionContext transaction) {
						if (slot == null) return 0;
						long before = generation.getAsLong();
						long moved = slot.insert(resource, maximum, transaction);
						stillValid(valid); unchanged(before, generation); return moved;
					}
					public long extract(FluidVariant resource, long maximum, TransactionContext transaction) {
						if (slot == null) return 0;
						long before = generation.getAsLong();
						long moved = slot.extract(resource, maximum, transaction);
						stillValid(valid); unchanged(before, generation); return moved;
					}
				};
			}
			public Iterator<StorageView<FluidVariant>> iterator() {
				return new Iterator<>() {
					int next;
					public boolean hasNext() { return next < getSlotCount(); }
					public StorageView<FluidVariant> next() { if (!hasNext()) throw new NoSuchElementException(); return getSlot(next++); }
				};
			}
		};
	}
}
