package net.forbric.kernel.runtime.transfer;

import java.util.IdentityHashMap;
import java.util.Map;

import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.fluids.FluidStack;

/**
 * MinecraftForge's tag-carrying {@code FluidStack} against NeoForge's component-carrying one, both on 1.21.1.
 *
 * <p>This class is the one place where the two lines of the port agree for a measured reason rather than by
 * inheritance: 26.2's Forge still uses CompoundTag for fluid metadata while 26.2's NeoForge uses
 * DataComponentPatch, and javap on the 1.21.1 carriers shows the same split
 * ({@code net.minecraftforge.fluids.FluidStack.hasTag/getTag} against
 * {@code net.neoforged.neoforge.fluids.FluidStack.getComponentsPatch/isComponentsPatchEmpty}). So the rule is
 * unchanged and so is the reasoning below.
 *
 * <p>Empty metadata converts directly. A non-empty value requires an explicit fluid-specific codec AND an exact
 * round trip on every conversion. A missing/throwing/lossy codec declines transfer instead of stripping data.
 */
public final class ForgeFluidMetadata {
	private ForgeFluidMetadata() { }
	public interface Codec {
		DataComponentPatch toComponents(CompoundTag tag);
		CompoundTag toTag(DataComponentPatch components);
	}
	private static final Map<Fluid, Codec> CODECS = new IdentityHashMap<>();
	public static synchronized void register(Fluid fluid, Codec codec) {
		java.util.Objects.requireNonNull(fluid); java.util.Objects.requireNonNull(codec);
		if (CODECS.putIfAbsent(fluid, codec) != null) throw new IllegalArgumentException("A fluid metadata codec is already registered");
	}
	private static synchronized Codec codec(Fluid fluid) { return CODECS.get(fluid); }
	private static boolean empty(CompoundTag tag) { return tag == null || tag.isEmpty(); }
	private static boolean same(CompoundTag a, CompoundTag b) { return empty(a) ? empty(b) : a.equals(b); }

	/** Forge's stack to NeoForge's; null when its tag has no verified lossless component mapping. */
	public static net.neoforged.neoforge.fluids.FluidStack toNeo(FluidStack stack) {
		if (stack.isEmpty()) return net.neoforged.neoforge.fluids.FluidStack.EMPTY;
		Fluid fluid = stack.getFluid();
		if (!stack.hasTag() || empty(stack.getTag())) return new net.neoforged.neoforge.fluids.FluidStack(fluid, 1);
		Codec codec = codec(fluid);
		try {
			if (codec != null) {
				CompoundTag original = stack.getTag().copy();
				DataComponentPatch patch = codec.toComponents(original.copy());
				if (patch != null && !patch.isEmpty() && same(original, codec.toTag(patch))) {
					return new net.neoforged.neoforge.fluids.FluidStack(fluid.builtInRegistryHolder(), 1, patch);
				}
			}
		} catch (RuntimeException invalid) { /* A codec that cannot round-trip this value grants no write capability. */ }
		TransferIssues.report("FLUID_METADATA_UNREPRESENTABLE", stack,
				"Forge fluid tag has no verified lossless component mapping; the fluid was not transferred");
		return null;
	}

	/** NeoForge's stack to Forge's, at {@code amount}; null when its components have no verified lossless tag mapping. */
	public static FluidStack toForge(net.neoforged.neoforge.fluids.FluidStack resource, int amount) {
		if (resource.isEmpty() || amount == 0) return FluidStack.EMPTY;
		if (amount < 0) throw new IllegalArgumentException("Negative fluid amount");
		Fluid fluid = resource.getFluid();
		if (resource.isComponentsPatchEmpty()) return new FluidStack(fluid, amount);
		Codec codec = codec(fluid);
		try {
			if (codec != null) {
				DataComponentPatch original = resource.getComponentsPatch();
				CompoundTag tag = codec.toTag(original);
				if (!empty(tag) && original.equals(codec.toComponents(tag.copy()))) {
					return new FluidStack(fluid, amount, tag.copy());
				}
			}
		} catch (RuntimeException invalid) { /* Reject, never silently erase a component. */ }
		TransferIssues.report("FLUID_METADATA_UNREPRESENTABLE", resource,
				"Fluid components have no verified lossless Forge tag mapping; the fluid was not transferred");
		return null;
	}
}
