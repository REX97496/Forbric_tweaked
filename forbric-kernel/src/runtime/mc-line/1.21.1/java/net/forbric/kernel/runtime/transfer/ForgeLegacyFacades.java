package net.forbric.kernel.runtime.transfer;

import java.util.Objects;

import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.items.IItemHandler;

/**
 * Forge's handler interfaces against NeoForge's, the direction that needs no transaction on this line.
 *
 * <p>Both ecosystems declare the same six {@code IItemHandler} methods over the SAME vanilla {@link ItemStack}, so
 * an item facade is one delegation and no conversion -- and it is still needed, because the two are different Java
 * types and a Forge mod's caller cannot be handed a NeoForge one.
 *
 * <p>Fluids are the interesting half: the two {@code IFluidHandler}s exchange DIFFERENT stack classes. Forge's
 * carries a CompoundTag on this line and NeoForge's carries a DataComponentPatch (javap both), so every crossing
 * goes through {@link ForgeFluidMetadata}, which refuses rather than strips metadata it cannot round-trip. That is
 * the same rule 26.2 applies from a {@code ResourceHandler} to a Forge stack; here it applies in both directions.
 *
 * <p>A facade never wraps its own opposite indefinitely: both directions unwrap a facade of the other kind, so a
 * cable passing on a neighbour's handler never builds a bridge of a bridge.
 */
public final class ForgeLegacyFacades {
	private ForgeLegacyFacades() { }

	public static IItemHandler items(net.neoforged.neoforge.items.IItemHandler handler) {
		Objects.requireNonNull(handler);
		if (handler instanceof NeoItems own) return own.forge;
		return new ForgeItems(handler);
	}
	public static IFluidHandler fluids(net.neoforged.neoforge.fluids.capability.IFluidHandler handler) {
		Objects.requireNonNull(handler);
		if (handler instanceof NeoFluids own) return own.forge;
		return new ForgeFluids(handler);
	}
	public static net.neoforged.neoforge.items.IItemHandler neoItems(IItemHandler handler) {
		Objects.requireNonNull(handler);
		if (handler instanceof ForgeItems own) return own.neo;
		return new NeoItems(handler);
	}
	public static net.neoforged.neoforge.fluids.capability.IFluidHandler neoFluids(IFluidHandler handler) {
		Objects.requireNonNull(handler);
		if (handler instanceof ForgeFluids own) return own.neo;
		return new NeoFluids(handler);
	}

	/** The store behind one of our own facades, so an audit judges the STORE and not the wrapper; null when not one. */
	static Object unwrap(Object handler) {
		if (handler instanceof NeoItems own) return own.forge;
		if (handler instanceof NeoFluids own) return own.forge;
		if (handler instanceof ForgeItems own) return own.neo;
		if (handler instanceof ForgeFluids own) return own.neo;
		return null;
	}

	/** A Forge caller of a NeoForge item handler. */
	private record ForgeItems(net.neoforged.neoforge.items.IItemHandler neo) implements IItemHandler {
		public int getSlots() { return neo.getSlots(); }
		public ItemStack getStackInSlot(int slot) { return neo.getStackInSlot(slot); }
		public ItemStack insertItem(int slot, ItemStack input, boolean simulate) { return neo.insertItem(slot, input, simulate); }
		public ItemStack extractItem(int slot, int maximum, boolean simulate) { return neo.extractItem(slot, maximum, simulate); }
		public int getSlotLimit(int slot) { return neo.getSlotLimit(slot); }
		public boolean isItemValid(int slot, ItemStack stack) { return neo.isItemValid(slot, stack); }
	}
	private record NeoItems(IItemHandler forge) implements net.neoforged.neoforge.items.IItemHandler {
		public int getSlots() { return forge.getSlots(); }
		public ItemStack getStackInSlot(int slot) { return forge.getStackInSlot(slot); }
		public ItemStack insertItem(int slot, ItemStack input, boolean simulate) { return forge.insertItem(slot, input, simulate); }
		public ItemStack extractItem(int slot, int maximum, boolean simulate) { return forge.extractItem(slot, maximum, simulate); }
		public int getSlotLimit(int slot) { return forge.getSlotLimit(slot); }
		public boolean isItemValid(int slot, ItemStack stack) { return forge.isItemValid(slot, stack); }
	}

	/** A Forge caller of a NeoForge fluid handler; every stack crosses through the metadata codec. */
	private record ForgeFluids(net.neoforged.neoforge.fluids.capability.IFluidHandler neo) implements IFluidHandler {
		public int getTanks() { return neo.getTanks(); }
		public FluidStack getFluidInTank(int tank) {
			var stack = neo.getFluidInTank(tank);
			FluidStack forge = ForgeFluidMetadata.toForge(stack, stack.getAmount());
			return forge == null ? FluidStack.EMPTY : forge;
		}
		public int getTankCapacity(int tank) { return neo.getTankCapacity(tank); }
		public boolean isFluidValid(int tank, FluidStack stack) {
			var converted = ForgeFluidMetadata.toNeo(stack);
			return converted != null && !converted.isEmpty() && neo.isFluidValid(tank, converted);
		}
		public int fill(FluidStack stack, FluidAction action) {
			Objects.requireNonNull(action);
			if (stack.isEmpty()) return 0;
			var converted = ForgeFluidMetadata.toNeo(stack);
			if (converted == null) return 0;
			converted.setAmount(stack.getAmount());
			int moved = neo.fill(converted, action.execute()
					? net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE
					: net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.SIMULATE);
			ForgeSnapshotAdapters.checkAmount(moved, stack.getAmount());
			return moved;
		}
		public FluidStack drain(FluidStack stack, FluidAction action) {
			Objects.requireNonNull(action);
			if (stack.isEmpty()) return FluidStack.EMPTY;
			var converted = ForgeFluidMetadata.toNeo(stack);
			if (converted == null) return FluidStack.EMPTY;
			converted.setAmount(stack.getAmount());
			return drained(neo.drain(converted, action.execute()
					? net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE
					: net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.SIMULATE));
		}
		public FluidStack drain(int maximum, FluidAction action) {
			Objects.requireNonNull(action);
			if (maximum < 0) throw new IllegalArgumentException("Negative fluid amount");
			if (maximum == 0) return FluidStack.EMPTY;
			return drained(neo.drain(maximum, action.execute()
					? net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE
					: net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.SIMULATE));
		}
		private static FluidStack drained(net.neoforged.neoforge.fluids.FluidStack stack) {
			if (stack.isEmpty()) return FluidStack.EMPTY;
			FluidStack forge = ForgeFluidMetadata.toForge(stack, stack.getAmount());
			return forge == null ? FluidStack.EMPTY : forge;
		}
	}

	/** A NeoForge caller of a Forge fluid handler; the same crossing, the other way. */
	private record NeoFluids(IFluidHandler forge) implements net.neoforged.neoforge.fluids.capability.IFluidHandler {
		public int getTanks() { return forge.getTanks(); }
		public net.neoforged.neoforge.fluids.FluidStack getFluidInTank(int tank) {
			var stack = forge.getFluidInTank(tank);
			var neo = ForgeFluidMetadata.toNeo(stack);
			if (neo == null) return net.neoforged.neoforge.fluids.FluidStack.EMPTY;
			neo.setAmount(stack.getAmount());
			return neo;
		}
		public int getTankCapacity(int tank) { return forge.getTankCapacity(tank); }
		public boolean isFluidValid(int tank, net.neoforged.neoforge.fluids.FluidStack stack) {
			FluidStack converted = ForgeFluidMetadata.toForge(stack, stack.getAmount());
			return converted != null && !converted.isEmpty() && forge.isFluidValid(tank, converted);
		}
		public int fill(net.neoforged.neoforge.fluids.FluidStack stack, FluidAction action) {
			Objects.requireNonNull(action);
			if (stack.isEmpty()) return 0;
			FluidStack converted = ForgeFluidMetadata.toForge(stack, stack.getAmount());
			if (converted == null) return 0;
			int moved = forge.fill(converted, action.execute() ? IFluidHandler.FluidAction.EXECUTE : IFluidHandler.FluidAction.SIMULATE);
			ForgeSnapshotAdapters.checkAmount(moved, stack.getAmount());
			return moved;
		}
		public net.neoforged.neoforge.fluids.FluidStack drain(net.neoforged.neoforge.fluids.FluidStack stack, FluidAction action) {
			Objects.requireNonNull(action);
			if (stack.isEmpty()) return net.neoforged.neoforge.fluids.FluidStack.EMPTY;
			FluidStack converted = ForgeFluidMetadata.toForge(stack, stack.getAmount());
			if (converted == null) return net.neoforged.neoforge.fluids.FluidStack.EMPTY;
			return drained(forge.drain(converted, action.execute() ? IFluidHandler.FluidAction.EXECUTE : IFluidHandler.FluidAction.SIMULATE));
		}
		public net.neoforged.neoforge.fluids.FluidStack drain(int maximum, FluidAction action) {
			Objects.requireNonNull(action);
			if (maximum < 0) throw new IllegalArgumentException("Negative fluid amount");
			if (maximum == 0) return net.neoforged.neoforge.fluids.FluidStack.EMPTY;
			return drained(forge.drain(maximum, action.execute() ? IFluidHandler.FluidAction.EXECUTE : IFluidHandler.FluidAction.SIMULATE));
		}
		private static net.neoforged.neoforge.fluids.FluidStack drained(FluidStack stack) {
			if (stack.isEmpty()) return net.neoforged.neoforge.fluids.FluidStack.EMPTY;
			var neo = ForgeFluidMetadata.toNeo(stack);
			if (neo == null) return net.neoforged.neoforge.fluids.FluidStack.EMPTY;
			neo.setAmount(stack.getAmount());
			return neo;
		}
	}
}
