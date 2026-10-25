package net.forbric.compat.network;

import java.util.List;
import java.util.Set;

import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;

/**
 * The 1.21.1 edition of NeoForge 26.x's RecipeContentPayload in net.neoforged.neoforge.network.payload.
 *
 * <p>26.2's version is a record that also implements CustomPacketPayload, carries a static TYPE and
 * STREAM_CODEC, and has a create(Collection, RecipeMap) factory. None of that is what the kernel uses:
 * KernelRecipeSync touches exactly three members -- the two-component constructor, recipes() and
 * recipeTypes() -- because its job is to hand a FILTERED recipe list back to the caller that built the
 * payload. Reproducing those three members as a record supplies the constructor and both accessors for
 * free, which is the whole class.
 *
 * <p>WHAT THIS DOES NOT DO, stated plainly: it does not make the payload sendable. There is no
 * CustomPacketPayload implementation here and no codec, so on 1.21.1 nothing transmits one of these. If a
 * caller ever needs that, this type is not enough and the class has to grow a codec -- which is a different
 * job from making the filter compile. The filter itself is real: KernelRecipeSync still walks the recipe
 * list, still tries each entry through RecipeHolder.STREAM_CODEC, and still drops the ones that throw.
 *
 * <p>NAMING. Taken from the InFluidPredicate compat layer: the class name must not let the rename table's
 * own simple-name row match inside its target. The row
 *
 * <pre>
 *   RecipeContentPayload  -&gt;  net.forbric.compat.network.ForbricRecipeContentPayload
 * </pre>
 *
 * <p>is safe because ForbricRecipeContentPayload is preceded by a letter wherever the source token appears
 * inside it, and normalizeRuntimeJava's lookbehind excludes letters. It does NOT exclude '.', which is why
 * a bare RecipeContentPayload in a compat package would have been rewritten twice.
 */
public record ForbricRecipeContentPayload(Set<RecipeType<?>> recipeTypes, List<RecipeHolder<?>> recipes) {
}