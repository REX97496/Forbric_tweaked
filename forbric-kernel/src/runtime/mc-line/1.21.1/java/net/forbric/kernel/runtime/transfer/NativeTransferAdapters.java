package net.forbric.kernel.runtime.transfer;

import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.SlottedStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.storage.base.SingleSlotStorage;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/**
 * Fabric's slotted storages against NeoForge's handler interfaces, the direction that DOES need the rollback layer.
 *
 * <p>Two asymmetries, both properties of the two APIs rather than choices:
 * <ul>
 * <li>A Fabric consumer hands a {@link TransactionContext} and expects an abort to undo what it did. The NeoForge
 * side has no transaction, so {@code FromNeo*} SNAPSHOTS the store before every mutating call
 * ({@link ForgeSnapshotAdapters}) and lets the Fabric transaction restore it. A store that cannot be snapshotted is
 * not exposed at all and is reported, exactly as the 26.2 edition refuses a non-journalled Forge store.
 * <li>A NeoForge consumer has only a {@code boolean simulate}, so {@code FromFabric*} turns {@code simulate=true}
 * into a real operation in a nested scope that is then ABORTED, and {@code simulate=false} into one that is
 * committed. If a Fabric transaction is already open on this thread it stays the owner: only a nested scope is
 * opened, and its commit joins the caller's.
 * </ul>
 *
 * <p>Amounts are never rounded after mutation: a sub-quantum Fabric result is aborted in its own scope and retried
 * once with an exactly representable maximum (one mB is 81 Fabric fluid units). An arbitrary unslotted Fabric
 * Storage cannot implement indexed insertion and is intentionally not accepted.
 *
 * <p>ONE REAL GAP OF THE 1.21.1 FLUID API, refused rather than faked: {@code IFluidHandler} can be READ per tank
 * ({@code getFluidInTank}) but not WRITTEN per tank -- {@code fill}/{@code drain} address the whole handler. So a
 * slotted Fabric write into a multi-tank NeoForge handler cannot be expressed: a single-tank handler (the common
 * case, and every {@code FluidTank}) delegates exactly, and a multi-tank one answers 0 with one reported issue. The
 * unslotted path ({@code storage.insert(...)}) still works for both, because it is the whole handler.
 */
public final class NativeTransferAdapters {
	private NativeTransferAdapters() { }

	public static SlottedStorage<ItemVariant> fabricItems(net.neoforged.neoforge.items.IItemHandler handler,
			TransferCodec<ItemVariant, ItemStack> codec) {
		Objects.requireNonNull(handler); check(codec);
		if (handler instanceof FromFabricItems own && own.codec == codec) return own.storage;
		return new FromNeoItems(handler, codec);
	}
	public static SlottedStorage<FluidVariant> fabricFluids(IFluidHandler handler, TransferCodec<FluidVariant, FluidStack> codec) {
		Objects.requireNonNull(handler); check(codec);
		if (handler instanceof FromFabricFluids own && own.codec == codec) return own.storage;
		return new FromNeoFluids(handler, codec);
	}
	public static net.neoforged.neoforge.items.IItemHandler neoItems(SlottedStorage<ItemVariant> storage,
			TransferCodec<ItemVariant, ItemStack> codec) {
		Objects.requireNonNull(storage); check(codec);
		if (storage instanceof FromNeoItems own && own.codec == codec) return own.handler;
		return new FromFabricItems(storage, codec);
	}
	public static IFluidHandler neoFluids(SlottedStorage<FluidVariant> storage, TransferCodec<FluidVariant, FluidStack> codec) {
		Objects.requireNonNull(storage); check(codec);
		if (storage instanceof FromNeoFluids own && own.codec == codec) return own.handler;
		return new FromFabricFluids(storage, codec);
	}

	private static void check(TransferCodec<?, ?> codec) {
		Objects.requireNonNull(codec);
		if (codec.fabricUnits() <= 0) throw new IllegalArgumentException("A transfer unit must be positive");
	}
	private static void amount(long amount) {
		if (amount < 0) throw new IllegalArgumentException("Negative transfer amount");
	}
	private static long validResult(long moved, long maximum) {
		if (moved < 0 || moved > maximum) throw new IllegalStateException("Provider returned an invalid transfer amount: " + moved + "/" + maximum);
		return moved;
	}
	static void requireSuccessfulRollback(LiveTransferEndpoints.Unavailable failure, Object provider) {
		// try-with-resources suppresses a close/rollback failure onto the original invalidation exception.
		// Returning zero in that case would assert a rollback the transaction failed to perform.
		if (failure.getSuppressed().length != 0) {
			TransferIssues.report("ROLLBACK_FAILED", provider, "Endpoint invalidated and its rollback failed; resource state is unknown");
			throw new IllegalStateException("Cross-API rollback failed; resource state is unknown", failure);
		}
	}
	private static long multiply(long nativeAmount, long units) {
		// Saturating the read-only advertised capacity is safe; a transfer itself is bounded to an int request.
		if (nativeAmount < 0) throw new IllegalStateException("Provider returned a negative amount/capacity");
		return nativeAmount > Long.MAX_VALUE / units ? Long.MAX_VALUE : nativeAmount * units;
	}
	/** Refuse to write a store this line cannot snapshot, with the 26.2 edition's own issue code. */
	private static boolean reversibleItems(TransactionContext context, Object store) {
		if (!ForgeSnapshotAdapters.supportsItems(store)) {
			TransferIssues.reportType("FORGE_HANDLER_NOT_ROLLBACK_SAFE", store.getClass().getName(),
					"The item provider is not an audited reversible implementation; transactional insertion and extraction were not exposed");
			return false;
		}
		ForgeSnapshotAdapters.prepare(context, store);
		return true;
	}
	private static boolean reversibleFluids(TransactionContext context, Object store) {
		if (!ForgeSnapshotAdapters.supportsFluids(store)) {
			TransferIssues.reportType("FORGE_HANDLER_NOT_ROLLBACK_SAFE", store.getClass().getName(),
					"The fluid provider is not an audited reversible implementation; transactional insertion and extraction were not exposed");
			return false;
		}
		ForgeSnapshotAdapters.prepare(context, store);
		return true;
	}

	/** The store this live view would write right now, or the handler itself when it is not a live view. */
	static Object resolveItems(Object handler) {
		return handler instanceof LiveTransferEndpoints.LiveItems live ? live.resolvedItems() : handler;
	}
	static Object resolveFluids(Object handler) {
		return handler instanceof LiveTransferEndpoints.LiveFluids live ? live.resolvedFluids() : handler;
	}
	/** The endpoint answers a different store than the one just written: that write is not snapshot-covered. */
	static void stillResolved(Object before, Object after) {
		if (before != after) throw new LiveTransferEndpoints.Unavailable("Transfer endpoint changed the answering store during an operation");
	}

	/** The NeoForge item handler seen as Fabric slots; every write is snapshotted first. */
	private static final class FromNeoItems implements SlottedStorage<ItemVariant> {
		final net.neoforged.neoforge.items.IItemHandler handler;
		final TransferCodec<ItemVariant, ItemStack> codec;
		FromNeoItems(net.neoforged.neoforge.items.IItemHandler handler, TransferCodec<ItemVariant, ItemStack> codec) {
			this.handler = handler; this.codec = codec;
		}
		public int getSlotCount() { return handler.getSlots(); }
		public SingleSlotStorage<ItemVariant> getSlot(int slot) { Objects.checkIndex(slot, getSlotCount()); return new Slot(slot); }
		public int slotCount() { return handler.getSlots(); }
		public long insert(ItemVariant resource, long maximum, TransactionContext transaction) { return move(-1, resource, maximum, transaction, true); }
		public long extract(ItemVariant resource, long maximum, TransactionContext transaction) { return move(-1, resource, maximum, transaction, false); }
		long move(int slot, ItemVariant resource, long maximum, TransactionContext context, boolean insert) {
			amount(maximum);
			if (maximum == 0 || codec.isFabricBlank(resource)) return 0;
			ItemStack template = codec.template(resource);
			if (template == null) return 0;
			int request = (int) Math.min(Integer.MAX_VALUE, maximum / codec.fabricUnits());
			if (request == 0) return 0;
			Object store = resolveItems(handler);
			if (store == null || !reversibleItems(context, store)) return 0;
			try {
				ItemStack stack = template.copyWithCount(request);
				int moved = insert ? insert(store, slot, stack, request) : extract(store, slot, stack, request);
				stillResolved(store, resolveItems(handler));
				validResult(moved, request);
				return moved * codec.fabricUnits();
			} catch (LiveTransferEndpoints.Unavailable invalidated) {
				requireSuccessfulRollback(invalidated, handler);
				TransferIssues.report("ENDPOINT_INVALIDATED", handler, invalidated.getMessage() + "; the operation was rolled back");
				return 0;
			}
		}
		private static int insert(Object store, int slot, ItemStack stack, int request) {
			var items = (net.neoforged.neoforge.items.IItemHandler) store;
			if (slot >= 0) return request - items.insertItem(slot, stack, false).getCount();
			int moved = 0;
			for (int at = 0; at < items.getSlots() && moved < request; at++) {
				if (!items.isItemValid(at, stack)) continue;
				ItemStack attempt = stack.copyWithCount(request - moved);
				moved += attempt.getCount() - items.insertItem(at, attempt, false).getCount();
			}
			return moved;
		}
		private static int extract(Object store, int slot, ItemStack stack, int request) {
			var items = (net.neoforged.neoforge.items.IItemHandler) store;
			if (slot >= 0) return items.extractItem(slot, request, false).getCount();
			int moved = 0;
			for (int at = 0; at < items.getSlots() && moved < request; at++) {
				ItemStack found = items.getStackInSlot(at);
				if (found.isEmpty() || !ItemStack.isSameItemSameComponents(found, stack)) continue;
				moved += items.extractItem(at, request - moved, false).getCount();
			}
			return moved;
		}
		public Iterator<StorageView<ItemVariant>> iterator() {
			return new Iterator<>() {
				int next;
				public boolean hasNext() { return next < slotCount(); }
				public StorageView<ItemVariant> next() { if (!hasNext()) throw new NoSuchElementException(); return getSlot(next++); }
			};
		}
		private final class Slot implements SingleSlotStorage<ItemVariant> {
			final int index;
			Slot(int index) { this.index = index; }
			public boolean isResourceBlank() { return codec.isFabricBlank(getResource()); }
			public ItemVariant getResource() { return codec.toFabric(handler.getStackInSlot(index)); }
			public long getAmount() { return multiply(getResource().isBlank() ? 0 : handler.getStackInSlot(index).getCount(), codec.fabricUnits()); }
			public long getCapacity() { return multiply(handler.getSlotLimit(index), codec.fabricUnits()); }
			public boolean supportsInsertion() { return handler.getSlots() > 0; }
			public long insert(ItemVariant resource, long maximum, TransactionContext context) { return move(index, resource, maximum, context, true); }
			public long extract(ItemVariant resource, long maximum, TransactionContext context) { return move(index, resource, maximum, context, false); }
		}
	}

	/** The NeoForge fluid handler seen as Fabric slots; every write is snapshotted first. */
	private static final class FromNeoFluids implements SlottedStorage<FluidVariant> {
		final IFluidHandler handler;
		final TransferCodec<FluidVariant, FluidStack> codec;
		FromNeoFluids(IFluidHandler handler, TransferCodec<FluidVariant, FluidStack> codec) {
			this.handler = handler; this.codec = codec;
		}
		public int getSlotCount() { return handler.getTanks(); }
		public SingleSlotStorage<FluidVariant> getSlot(int slot) { Objects.checkIndex(slot, getSlotCount()); return new Slot(slot); }
		public long insert(FluidVariant resource, long maximum, TransactionContext transaction) { return move(-1, resource, maximum, transaction, true); }
		public long extract(FluidVariant resource, long maximum, TransactionContext transaction) { return move(-1, resource, maximum, transaction, false); }
		long move(int slot, FluidVariant resource, long maximum, TransactionContext context, boolean insert) {
			amount(maximum);
			if (maximum == 0 || codec.isFabricBlank(resource)) return 0;
			FluidStack template = codec.template(resource);
			if (template == null) return 0;
			int request = (int) Math.min(Integer.MAX_VALUE, maximum / codec.fabricUnits());
			if (request == 0) return 0;
			Object store = resolveFluids(handler);
			if (store == null) return 0;
			var fluids = (IFluidHandler) store;
			if (slot >= 0 && fluids.getTanks() != 1) {
				TransferIssues.reportType("FLUID_TANK_UNADDRESSABLE", store.getClass().getName(),
						"This line's IFluidHandler cannot be written per tank; only whole-handler Fabric transfers were exposed");
				return 0;
			}
			if (!reversibleFluids(context, store)) return 0;
			try {
				FluidStack stack = template.copyWithAmount(request);
				int moved = insert ? fluids.fill(stack, IFluidHandler.FluidAction.EXECUTE)
						: fluids.drain(stack, IFluidHandler.FluidAction.EXECUTE).getAmount();
				stillResolved(store, resolveFluids(handler));
				validResult(moved, request);
				return moved * codec.fabricUnits();
			} catch (LiveTransferEndpoints.Unavailable invalidated) {
				requireSuccessfulRollback(invalidated, handler);
				TransferIssues.report("ENDPOINT_INVALIDATED", handler, invalidated.getMessage() + "; the operation was rolled back");
				return 0;
			}
		}
		public Iterator<StorageView<FluidVariant>> iterator() {
			return new Iterator<>() {
				int next;
				public boolean hasNext() { return next < handler.getTanks(); }
				public StorageView<FluidVariant> next() { if (!hasNext()) throw new NoSuchElementException(); return getSlot(next++); }
			};
		}
		private final class Slot implements SingleSlotStorage<FluidVariant> {
			final int index;
			Slot(int index) { this.index = index; }
			public boolean isResourceBlank() { return codec.isFabricBlank(getResource()); }
			public FluidVariant getResource() { return codec.toFabric(handler.getFluidInTank(index)); }
			public long getAmount() { return multiply(handler.getFluidInTank(index).getAmount(), codec.fabricUnits()); }
			public long getCapacity() { return multiply(handler.getTankCapacity(index), codec.fabricUnits()); }
			public boolean supportsInsertion() { return handler.getTanks() > 0; }
			public long insert(FluidVariant resource, long maximum, TransactionContext context) { return move(index, resource, maximum, context, true); }
			public long extract(FluidVariant resource, long maximum, TransactionContext context) { return move(index, resource, maximum, context, false); }
		}
	}

	/** A Fabric item storage seen as a NeoForge item handler; simulate is an aborted real operation. */
	private static final class FromFabricItems implements net.neoforged.neoforge.items.IItemHandler {
		final SlottedStorage<ItemVariant> storage;
		final TransferCodec<ItemVariant, ItemStack> codec;
		FromFabricItems(SlottedStorage<ItemVariant> storage, TransferCodec<ItemVariant, ItemStack> codec) {
			this.storage = storage; this.codec = codec;
		}
		public int getSlots() { return storage.getSlotCount(); }
		public ItemStack getStackInSlot(int slot) {
			SingleSlotStorage<ItemVariant> view = storage.getSlot(slot);
			ItemVariant resource = view.getResource();
			if (codec.isFabricBlank(resource)) return ItemStack.EMPTY;
			ItemStack template = codec.template(resource);
			return template == null ? ItemStack.EMPTY : template.copyWithCount((int) Math.min(Integer.MAX_VALUE, view.getAmount() / codec.fabricUnits()));
		}
		public ItemStack insertItem(int slot, ItemStack input, boolean simulate) {
			if (input.isEmpty()) return ItemStack.EMPTY;
			int maximum = input.getCount();
			long moved = move(slot, codec.toFabric(input), maximum, simulate, true);
			return input.copyWithCount(maximum - (int) moved);
		}
		public ItemStack extractItem(int slot, int maximum, boolean simulate) {
			if (maximum < 0) throw new IllegalArgumentException("Negative item amount");
			if (maximum == 0) return ItemStack.EMPTY;
			ItemVariant resource = storage.getSlot(slot).getResource();
			if (codec.isFabricBlank(resource)) return ItemStack.EMPTY;
			long moved = move(slot, resource, maximum, simulate, false);
			ItemStack template = codec.template(resource);
			return template == null || moved == 0 ? ItemStack.EMPTY : template.copyWithCount((int) moved);
		}
		public int getSlotLimit(int slot) { return (int) Math.min(Integer.MAX_VALUE, storage.getSlot(slot).getCapacity() / codec.fabricUnits()); }
		public boolean isItemValid(int slot, ItemStack stack) { return !stack.isEmpty() && storage.getSlot(slot).supportsInsertion(); }
		private long move(int slot, ItemVariant resource, int maximum, boolean simulate, boolean insert) {
			if (maximum == 0 || codec.isFabricBlank(resource)) return 0;
			SingleSlotStorage<ItemVariant> view = storage.getSlot(slot);
			long request = Math.multiplyExact((long) maximum, codec.fabricUnits());
			// A provider may accept less than requested. Every unsuccessful trial is aborted in its OWN scope;
			// no rounded amount is ever returned for an unrounded mutation.
			for (int attempt = 0; attempt < 2 && request > 0; attempt++) {
				try (Transaction transaction = PairedTransactions.scope()) {
					long moved = validResult(insert ? view.insert(resource, request, transaction) : view.extract(resource, request, transaction), request);
					boolean exact = moved % codec.fabricUnits() == 0;
					if (exact && !simulate) transaction.commit();
					if (exact) return moved / codec.fabricUnits();
					request = moved / codec.fabricUnits() * codec.fabricUnits();
				} catch (LiveTransferEndpoints.Unavailable invalidated) {
					requireSuccessfulRollback(invalidated, storage);
					TransferIssues.report("ENDPOINT_INVALIDATED", storage, invalidated.getMessage() + "; the operation was rolled back");
					return 0;
				}
			}
			return 0;
		}
	}

	/** A Fabric fluid storage seen as a NeoForge fluid handler; simulate is an aborted real operation. */
	private static final class FromFabricFluids implements IFluidHandler {
		final SlottedStorage<FluidVariant> storage;
		final TransferCodec<FluidVariant, FluidStack> codec;
		FromFabricFluids(SlottedStorage<FluidVariant> storage, TransferCodec<FluidVariant, FluidStack> codec) {
			this.storage = storage; this.codec = codec;
		}
		public int getTanks() { return storage.getSlotCount(); }
		public FluidStack getFluidInTank(int tank) {
			SingleSlotStorage<FluidVariant> view = storage.getSlot(tank);
			FluidVariant resource = view.getResource();
			if (codec.isFabricBlank(resource)) return FluidStack.EMPTY;
			FluidStack template = codec.template(resource);
			return template == null ? FluidStack.EMPTY : template.copyWithAmount((int) Math.min(Integer.MAX_VALUE, view.getAmount() / codec.fabricUnits()));
		}
		public int getTankCapacity(int tank) { return (int) Math.min(Integer.MAX_VALUE, storage.getSlot(tank).getCapacity() / codec.fabricUnits()); }
		public boolean isFluidValid(int tank, FluidStack stack) { return !stack.isEmpty() && storage.getSlot(tank).supportsInsertion(); }
		public int fill(FluidStack stack, FluidAction action) {
			Objects.requireNonNull(action);
			if (stack.isEmpty()) return 0;
			return (int) move(codec.toFabric(stack), stack.getAmount(), action.execute(), true);
		}
		public FluidStack drain(FluidStack stack, FluidAction action) {
			Objects.requireNonNull(action);
			if (stack.isEmpty()) return FluidStack.EMPTY;
			for (int tank = 0; tank < getTanks(); tank++) {
				FluidStack held = getFluidInTank(tank);
				if (held.isEmpty() || !FluidStack.isSameFluidSameComponents(held, stack)) continue;
				long moved = move(codec.toFabric(stack), Math.min(stack.getAmount(), held.getAmount()), action.execute(), false);
				return moved == 0 ? FluidStack.EMPTY : stack.copyWithAmount((int) moved);
			}
			return FluidStack.EMPTY;
		}
		public FluidStack drain(int maximum, FluidAction action) {
			Objects.requireNonNull(action);
			if (maximum <= 0) return FluidStack.EMPTY;
			for (int tank = 0; tank < getTanks(); tank++) {
				FluidStack held = getFluidInTank(tank);
				if (held.isEmpty()) continue;
				long moved = move(codec.toFabric(held), Math.min(maximum, held.getAmount()), action.execute(), false);
				if (moved > 0) return held.copyWithAmount((int) moved);
			}
			return FluidStack.EMPTY;
		}
		private long move(FluidVariant resource, int maximum, boolean execute, boolean insert) {
			if (maximum == 0 || codec.isFabricBlank(resource)) return 0;
			long request = Math.multiplyExact((long) maximum, codec.fabricUnits());
			for (int attempt = 0; attempt < 2 && request > 0; attempt++) {
				try (Transaction transaction = PairedTransactions.scope()) {
					long moved = validResult(insert ? storage.insert(resource, request, transaction) : storage.extract(resource, request, transaction), request);
					boolean exact = moved % codec.fabricUnits() == 0;
					if (exact && execute) transaction.commit();
					if (exact) return moved / codec.fabricUnits();
					request = moved / codec.fabricUnits() * codec.fabricUnits();
				} catch (LiveTransferEndpoints.Unavailable invalidated) {
					requireSuccessfulRollback(invalidated, storage);
					TransferIssues.report("ENDPOINT_INVALIDATED", storage, invalidated.getMessage() + "; the operation was rolled back");
					return 0;
				}
			}
			return 0;
		}
	}
}
