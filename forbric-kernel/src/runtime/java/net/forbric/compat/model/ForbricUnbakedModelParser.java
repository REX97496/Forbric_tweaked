package net.forbric.compat.model;

import net.minecraft.resources.ResourceLocation;

/**
 * The 1.21.1 edition of NeoForge 26.x's net.neoforged.neoforge.client.model.UnbakedModelParser.
 *
 * <p>26.2's type, measured:
 *
 * <pre>
 *   private static ImmutableMap&lt;Identifier, UnbakedModelLoader&lt;?&gt;&gt; LOADERS
 *   public static UnbakedModelLoader&lt;?&gt; get(Identifier)
 *   public static void init()
 *   public static UnbakedModel parse(Reader)
 * </pre>
 *
 * <p>1.21.1's neoforge-runtime has ZERO entries matching UnbakedModel or ModelParser -- the whole NeoForge
 * model-loader system arrived with the 26.x model rework, and the kernel's 1.21.1 build has nothing to talk to.
 *
 * <p>WHY get() RETURNS NULL, AND WHY THAT IS THE RIGHT ANSWER RATHER THAN A STUB. The kernel uses exactly one
 * member of this class, at KernelModelFormats:246:
 *
 * <pre>
 *   return UnbakedModelParser.get(id) != null;
 * </pre>
 *
 * <p>That is the guard "is this model's loader id a NeoForge loader?". On 1.21.1 there are no NeoForge model
 * loaders, so no id is one, so null is the correct reply -- and the caller then takes its "treat it as a plain
 * vanilla model" path, which is what happens natively on this line. A stub would be a class returning something
 * that is not true; this returns what IS true.
 *
 * <p>WHAT IS NOT HERE. init() and parse() are absent on purpose: nothing on 1.21.1 calls them, and writing a
 * parse() that cannot parse would be exactly the kind of silent wrongness this port keeps refusing. If the
 * model-loader system is ever ported, this class has to become real or be deleted.
 */
public final class ForbricUnbakedModelParser {

	private ForbricUnbakedModelParser() {
	}

	/**
	 * Always null: 1.21.1 has no NeoForge model loaders registered. The argument is accepted so the kernel's
	 * call site keeps its spelling, and is not consulted because there is nothing to consult.
	 */
	public static Object get(ResourceLocation id) {
		return null;
	}
}
