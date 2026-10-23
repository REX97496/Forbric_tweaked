package net.forbric.kernel.runtime.transfer;

/**
 * Resource identity conversion between a Fabric variant and the game stack that carries ONE unit of it.
 *
 * <p>26.2's twin of this interface was parameterised by NeoForge's {@code transfer.resource.Resource} and converted
 * to {@code ItemResource} / {@code FluidResource}, types that describe a resource WITHOUT an amount. 1.21.1 has no
 * resource type at all: the handler API's unit IS the stack. So the conversion target here is an ItemStack or a
 * FluidStack of exactly one unit, and every caller scales it ({@code copyWithCount} / {@code copyWithAmount}).
 * Keeping the amount out of the codec is what keeps NativeTransferAdapters' "no rounding after mutation" rule
 * meaningful -- a codec that also carried the amount could round it silently.
 *
 * <p>A null conversion means unrepresentable, never "discard its metadata".
 */
public interface TransferCodec<F, S> {
	/** The stack that carries one unit of this variant, with its components/tag; null when unrepresentable. */
	S template(F resource);

	F toFabric(S stack);

	boolean isFabricBlank(F resource);

	/** Fabric units per one native unit: one for items, 81 for fluids. */
	long fabricUnits();
}
