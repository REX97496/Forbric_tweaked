package net.forbric.compat.fluids;

import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.fluids.FluidType;

/**
 * The 1.21.1 edition of NeoForge 26.x's InFluidPredicate in net.neoforged.neoforge.fluids.
 *
 * <p>A single-abstract-method type, reproduced rather than emulated. On 26.2 the interface is
 *
 * <pre>
 *   public interface InFluidPredicate&lt;E extends Entity&gt; {
 *       boolean test(E entity, FluidType type, double height);
 *   }
 * </pre>
 *
 * <p>1.21.1 has no type by that name in any carrier -- measured: zero entries matching it across
 * forge-runtime, neoforge-runtime and the merged base. FluidType itself exists on both lines, so only the
 * name is missing and only the name has to be supplied.
 *
 * <p>WHY THIS LIVES IN src/runtime/java. It has to: the 1.21.1 measurement compiles that source set with a
 * hand-built classpath that does not include src/main's output, so a support class placed under src/main
 * is invisible to it and every reference to it fails with "package ... does not exist". That was this
 * class's first version and it was wrong.
 *
 * <p>WHY IT IS NOT CALLED InFluidPredicate. normalizeRuntimeJava applies the rename table to every file in
 * this source set, and two rows point at this class. If the class were named InFluidPredicate, the
 * simple-name row would match its own declaration and its own references, because the pattern's lookbehind
 * excludes only letters, digits, underscore and dollar -- not '.'. That includes the '.' in this file's own
 * package path. So the name carries a Forbric prefix and the table's simple-name row cannot reach it: the
 * target does not contain the source token, which is the invariant checked across the whole table.
 *
 * <p>The javadoc above deliberately paraphrases the absent type's fully-qualified name rather than spelling
 * it, because spelling it would make the table rewrite this comment too. That rewrite is harmless -- it is
 * a comment, and the file compiles either way -- but a comment rewritten into nonsense is still a comment
 * someone has to read later.
 */
public interface ForbricFluidPredicate<E extends Entity> {

	boolean test(E entity, FluidType type, double height);
}