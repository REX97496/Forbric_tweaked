package net.forbric.kernel.runtime.transfer;

import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * The two identity mappings, both of them exact on this line.
 *
 * <p>Items: vanilla {@link ItemStack} is the carrier for BOTH handler ecosystems on 1.21.1 (Forge and NeoForge
 * share the game's class), and Fabric's {@code ItemVariant} is component-bound here, so the mapping carries the
 * full DataComponentPatch in both directions.
 *
 * <p>Fluids: NeoForge's 1.21.1 {@code FluidStack} is ALREADY component-based -- javap shows
 * {@code getComponentsPatch()}, {@code isComponentsPatchEmpty()} and the
 * {@code FluidStack(Holder<Fluid>, int, DataComponentPatch)} constructor -- while MinecraftForge's is still
 * tag-based ({@code getTag()/setTag()}). That asymmetry is identical to 26.2's, so {@link ForgeFluidMetadata}
 * carries over unchanged in meaning; only a Fabic-side translation lives here.
 */
public final class TransferResources {
	private TransferResources() { }
	public static final TransferCodec<ItemVariant, ItemStack> ITEMS = new TransferCodec<>() {
		public ItemStack template(ItemVariant value) { return value.toStack(1); }
		public ItemVariant toFabric(ItemStack stack) { return stack.isEmpty() ? ItemVariant.blank() : ItemVariant.of(stack); }
		public boolean isFabricBlank(ItemVariant value) { return value.isBlank(); }
		public long fabricUnits() { return 1; }
	};
	public static final TransferCodec<FluidVariant, FluidStack> FLUIDS = new TransferCodec<>() {
		public FluidStack template(FluidVariant value) {
			return new FluidStack(value.getRegistryEntry(), 1, value.getComponents());
		}
		public FluidVariant toFabric(FluidStack stack) {
			return stack.isEmpty() ? FluidVariant.blank() : FluidVariant.of(stack.getFluid(), stack.getComponentsPatch());
		}
		public boolean isFabricBlank(FluidVariant value) { return value.isBlank(); }
		public long fabricUnits() { return 81; }
	};
}
