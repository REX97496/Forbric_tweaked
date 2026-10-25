package net.forbric.compat.fml;

/**
 * The 1.21.1 editions of the seven FML types KernelFmlTransformerView builds its view out of.
 *
 * <p>WHAT THE KERNEL DOES WITH THEM, read line by line from its 180 lines. It does not call a single method
 * on any of these types. It builds an object graph with Unsafe.allocateInstance and then writes named
 * PRIVATE FIELDS by reflection:
 *
 * <pre>
 *   :110  allocate(ForbricFmlMixinClassProcessor.class)
 *   :111  ForbricFmlMixinClassProcessor.class.getDeclaredField("transformer")      -&gt; the mixin weaver
 *   :115  SequencedMap&lt;ForbricProcessorName, ForbricClassProcessor&gt; processors
 *   :116  processors.put(ForbricClassProcessorIds.MIXIN, processor)
 *   :117  allocate(ForbricClassProcessorSet.class)
 *   :118  write(ForbricClassProcessorSet.class, "processors", set, ...)
 *   :120  allocate(ForbricTransformingClassLoader.class)
 *   :121  write(ForbricTransformingClassLoader.class, "classTransformer", loader, new ForbricClassTransformer(set, null))
 * </pre>
 *
 * <p>SO THE FIELD NAMES ARE THE CONTRACT, and nothing else is. That is why these types are shells: a caller
 * reaches into them by name, and a caller that finds the names gets the graph it was looking for. Giving them
 * methods would be inventing behaviour nothing asks for.
 *
 * <p>TWO LAYERS OF FAIL-SOFT SIT ABOVE THIS, both measured, and neither is mine to claim.
 *
 * <p>1. THE KERNEL. build() wraps everything in catch (Throwable unbuildable) at :144, sets gaveUp, logs
 * "could not build FML's ForbricTransformingClassLoader view ... starts without its class patches, as before", and
 * returns null -- after which contextLoader() hands the real loader back. The kernel wrote that path for
 * exactly this situation.
 *
 * <p>2. LIBJF. Its MixinPlugin.onLoad wraps its whole reflection walk in catch (Throwable) and logs
 * "Could not initialize LibJF ASM". Read at
 * https://git.jfronny.dev/JfMods/LibJF/src/commit/44f844afe2cba23ebf38787d78e09c271a4de66d/
 * libjf-unsafe-v0/src/main/java/io/gitlab/jfronny/libjf/unsafe/MixinPlugin.java
 *
 * <p>WHAT IS NOT HERE, stated plainly. This is a NAME-AND-FIELD reproduction, not an FML. Nothing in this
 * file transforms a class, and a mod that walks this graph and writes a new weaver into `transformer` changes
 * a field on a shell whose only other reference is the kernel's own bookkeeping. Whether that has any effect
 * on 1.21.1 depends on what the kernel's MixinWeaverSlot does with the value afterwards, which is outside this
 * file. If it has no effect, LibJF's ASM layer does nothing on this line -- the same outcome its own
 * catch(Throwable) would have produced, reached by a different route.
 */
public final class ForbricFmlTransformation {

	private ForbricFmlTransformation() {
	}

	/** Holds the transformer. Field name is the contract; see the class note. */
	public static final class ForbricTransformingClassLoader {

		private Object classTransformer;

		public Object classTransformer() {
			return classTransformer;
		}
	}

	/** The only type the kernel CONSTRUCTS, at :121, as new ForbricClassTransformer(set, null). */
	public static final class ForbricClassTransformer {

		private final Object processors;
		private final Object unused;

		public ForbricClassTransformer(ForbricClassProcessorSet processors, Object unused) {
			this.processors = processors;
			this.unused = unused;
		}

		public ForbricClassProcessorSet processors() {
			return (ForbricClassProcessorSet) processors;
		}
	}

	/** Holds the processor map. Field name is the contract. */
	public static final class ForbricClassProcessorSet {

		private Object processors;

		public Object processors() {
			return processors;
		}
	}

	/** Holds the mixin weaver. Field name is the contract. */
	public static final class ForbricFmlMixinClassProcessor {

		private Object transformer;

		public Object transformer() {
			return transformer;
		}
	}

	/** Map value type only. Zero members are used. */
	public interface ForbricClassProcessor {
	}

	/** Map key type only. Zero members are used. */
	public static final class ForbricProcessorName {

		private final String name;

		public ForbricProcessorName() {
			this("unnamed");
		}

		public ForbricProcessorName(String name) {
			this.name = name;
		}

		@Override
		public String toString() {
			return "ForbricProcessorName[" + name + "]";
		}
	}

	/** Supplies the one constant the kernel names: MIXIN. */
	public static final class ForbricClassProcessorIds {

		public static final ForbricProcessorName MIXIN = new ForbricProcessorName("mixin");
		private static final ForbricProcessorName[] ALL = { MIXIN };

		private ForbricClassProcessorIds() {
		}

		public static ForbricProcessorName[] all() {
			return ALL.clone();
		}
	}
}
