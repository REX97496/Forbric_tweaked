package net.forbric.compat.rendering;

/**
 * The 1.21.1 edition of vanilla 26.x's net.minecraft.client.renderer.block.FluidModel.
 *
 * <p>26.2's type is a record with six components -- a ChunkSectionLayer, three baked sprites (still, flowing,
 * overlay), a tint source and a custom renderer -- and three constructors. That is a rendering abstraction
 * introduced with the 26.x fluid renderer, and 1.21.1's merged base has ZERO entries named FluidModel: fluid
 * drawing there still goes through LiquidBlockRenderer.
 *
 * <p>WHY AN EMPTY CLASS IS ENOUGH, measured over all four error sites in KernelForgeFluids (100 lines):
 *
 * <pre>
 *   :58  public static FluidModel model(FluidModel model, FluidState state, BlockAndTintGetter level, BlockPos pos)
 *   :70  FluidModel own = extensions.getModel(state, level, pos, model);
 * </pre>
 *
 * <p>The kernel neither constructs a FluidModel nor calls a single method on one. It receives whatever a mod's
 * extension supplies, hands it to getModel, and returns the result. So the only thing it needs from this type
 * is its IDENTITY -- the same finding as ForbricPip.RenderState, and the same reason an empty declaration is
 * the correct answer rather than a stub. A stub would be a class pretending to have behaviour; this class
 * claims no behaviour at all, and nothing asks it for any.
 *
 * <p>WHAT IS NOT HERE. Nothing in this file draws a fluid. On 1.21.1 there is no 26.x fluid renderer to feed,
 * so a FluidModel produced by a mod's extension is carried through the kernel's signature and then has nowhere
 * to go. If a real fluid renderer is ever ported, this type has to grow the components the renderer reads --
 * and that is a rendering project, not a name.
 */
public final class ForbricFluidModel {

	private ForbricFluidModel() {
	}
}
