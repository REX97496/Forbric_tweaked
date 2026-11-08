package net.forbric.kernel.remap;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.TypePath;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import net.forbric.kernel.fabric.KernelMappingResolver;
import net.forbric.kernel.util.ForbricLog;

/**
 * Rewrites Fabric mod jars from the namespace they were compiled in (intermediary) to the one the game runs in
 * (Mojang), once, before they reach the class loader.
 *
 * <p>Why this has to exist. A Fabric mod on this line references {@code net/minecraft/class_310} because that is
 * what its compiler saw. The merged base is written in Mojang's names, where the same class is
 * {@code net/minecraft/client/Minecraft}. Handing the jar over unchanged is not a near miss: measured on a launch
 * with eight real mods, it produced 268 {@code [Mixin/mixin] Error loading class: net/minecraft/class_2591} lines,
 * every fabric-api entrypoint failing to construct, and the transfer bridge dying on
 * {@code NoClassDefFoundError: net/minecraft/class_1936}. Nothing at load time consulted a mapping table at all -
 * {@link KernelMappingResolver} was reachable only through {@code FabricLoader.getMappingResolver()}, for mods that
 * ask.
 *
 * <p>Why it rewrites the jar instead of aliasing names. A JVM decides binary identity by loader plus name. Defining
 * {@code class_310} as an alias of {@code Minecraft} produces two distinct classes, so a mod field of type
 * {@code class_310} cannot hold the player the game hands it - {@code IllegalArgumentException: argument type
 * mismatch} at the first call. Mixin compounds it: its target strings are resolved to classes, and an alias is not
 * the class Mixin was told to transform. Rewriting the references makes both sides say the same name, because it is
 * one name.
 *
 * <p>What is rewritten:
 * <ul>
 *   <li>every {@code .class} entry, through {@link ClassRemapper} with the resolver's class/member lookups;</li>
 *   <li>the entry's own name, when the class itself is a game class (a mixin never is, a mod's accessor sometimes
 *       is);</li>
 *   <li>mixin config json, for class names only - {@code "target": "net.minecraft.class_1087"} and the
 *       fully-qualified forms inside {@code @At(target=...)} strings.</li>
 *   <li>every refmap value, as a <b>member</b> selector: {@code "Lnet/minecraft/class_2248;method_9585(...)"}
 *       carries its owner, so its member name can be looked up rather than guessed. This is the layer that kept
 *       failing after the classes were right: measured on the cache, 50 modules still held 2910 intermediary
 *       tokens, all of them in {@code *-refmap.json} values, and mixin resolves those at run time - which is why
 *       {@code Error loading class: net/minecraft/class_2591} survived a jar whose annotations and descriptors
 *       had been rewritten. The keys stay in the named spelling because that is what the mod's own annotation
 *       says and what mixin looks the entry up by.</li>
 * </ul>
 *
 * <p>What is NOT rewritten, deliberately: the {@code data} block of a refmap (the tiny tables a mod may ship),
 * and member selectors that arrive without an owner - a bare {@code method_1234} string. Neither carries enough
 * to look a member up, and a guess there would rewrite the wrong method.
 */
public final class GuestJarRemap {

	/**
	 * The resolver answers in <b>binary</b> names ({@code net.minecraft.world.level...}), because that is what
	 * fabric-loader's own contract hands a mod. Bytecode is written in <b>internal</b> names, so every answer is
	 * brought back to that spelling here. Getting this wrong is silent: the dotted answer is accepted as an internal
	 * name and written into a descriptor, where nothing matches it.
	 */
	static String mapInternal(String internalName) {
		String mapped = KernelMappingResolver.INSTANCE.mapClassName("intermediary", internalName);
		if (mapped.equals(internalName)) return internalName;
		return mapped.indexOf('.') < 0 ? mapped : mapped.replace('.', '/');
	}

	/** Off switch, for a launch that needs to see the unwritten jar. */
	public static final String SWITCH = "forbric.remapGuestJars";

	/** Matches an intermediary class token in text: dotted or slashed, optionally package-qualified and nested. */
	private static final Pattern INTERMEDIARY = Pattern.compile(
			"(?<![A-Za-z0-9_$/.\\-])((?:[A-Za-z0-9_$]+[./])*class_\\d+(?:\\$[A-Za-z0-9_$]+)*)");

	/** A refmap method selector value: {@code "Lowner;name(args)ret"}. */
	private static final Pattern REFMAP_METHOD = Pattern.compile(
			"\"L([A-Za-z0-9_$/]+);([A-Za-z0-9_$<>]+)\\(([^)]*)\\)([A-Za-z0-9_$/;\\[\\$]+)\"");

	/** A refmap field selector value: {@code "Lowner;name:desc"}. */
	private static final Pattern REFMAP_FIELD = Pattern.compile(
			"\"L([A-Za-z0-9_$/]+);([A-Za-z0-9_$<>]+):([A-Za-z0-9_$/;\\[\\$]+)\"");

	/** A refmap value that names a member and no owner: {@code "field_19315:Ljava/util/Set;"}. */
	private static final Pattern REFMAP_BARE = Pattern.compile(
			"\"((?:method|field)_\\d+)(?::([A-Za-z0-9_$/;\\[\\$]+)|\\(([^)]*)\\)([A-Za-z0-9_$/;\\[\\$]+))\"");

	private static int jarsWritten;
	private static long classesRewritten;
	private static long namesMapped;
	private static long classFailures;
	private static long refmapValues;
	private static long refmapMisses;
	private static long memberStrings;
	private static long memberStringMisses;
	private static long lambdasPicked;
	private static long lambdasAmbiguous;
	private static volatile MethodIndex minecraftIndex;
	private static int lambdasUnindexed = 5;

	private GuestJarRemap() {
	}

	/**
	 * Returns jars to put on the classpath in place of the ones given: each is rewritten once into {@code cacheDir}
	 * and reused on a later launch.
	 *
	 * <p>A jar that cannot be rewritten is returned as it came, because a mod that loads with unrewritten names is a
	 * mod whose failures are already reported one per mixin; refusing the launch would trade a partial load for
	 * none.
	 */
	public static List<Path> remapAll(List<Path> jars, Path cacheDir) {
		return remapAll(jars, cacheDir, null);
	}

	/**
	 * Same, given the base jars the guests will run against. When the table maps an intermediary id onto a synthetic
	 * lambda name and the base class has no such method, the base's own methods pick the target by descriptor — the
	 * table alone cannot: nine intermediary ids of {@code ResourceLocation} all read {@code lambda$read$0}, and
	 * {@code ByteBufCodecs} repeats the shape one lambda along.
	 */
	public static List<Path> remapAll(List<Path> jars, Path cacheDir, List<Path> minecraftJars) {
		minecraftIndex = minecraftJars == null || minecraftJars.isEmpty() ? null : new MethodIndex(minecraftJars);
		if (Boolean.getBoolean("forbric." + SWITCH + ".off")) return jars;
		if (jars.isEmpty()) return jars;
		if (KernelMappingResolver.INSTANCE.getNamespaces().size() < 2) {
			// No table: the runtime namespace IS the one mods were compiled against. Saying so once is better than
			// rewriting 49 jars into byte-identical copies.
			ForbricLog.debug("[Forbric/Remap] no mapping table, so the guest jars already speak the game's"
					+ " namespace; nothing was rewritten");
			return jars;
		}
		List<Path> out = new ArrayList<>(jars.size());
		int failed = 0;
		for (Path jar : jars) {
			try {
				out.add(remapOne(jar, cacheDir));
			} catch (IOException | RuntimeException unreadable) {
				failed++;
				ForbricLog.warn("[Forbric/Remap] %s could not be rewritten and is loaded as it came: %s",
						jar.getFileName(), String.valueOf(unreadable));
				out.add(jar);
			}
		}
		if (jarsWritten > 0) {
			ForbricLog.info("[Forbric/Remap] %d guest jar(s) rewritten into %s (%d class(es), %d name(s) mapped,"
					+ " %d refmap selector(s) resolved%s)", jarsWritten, cacheDir, classesRewritten, namesMapped,
					refmapValues,
					(refmapMisses == 0 ? "" : ", " + refmapMisses + " refmap selector(s) not in the table")
							+ (failed == 0 ? "" : ", " + failed + " left as they came")
							+ (classFailures == 0 ? "" : ", " + classFailures + " class(es) unreadable")
							+ (lambdasPicked == 0 ? "" : ", " + lambdasPicked + " lambda name(s) replaced by the"
									+ " method the base actually declares")
							+ (lambdasAmbiguous == 0 ? "" : ", " + lambdasAmbiguous + " lambda name(s) left alone"
									+ " because more than one method there accepts the descriptor"));
		}
		return out;
	}

	/**
	 * Rewrites only the jars that live under {@code under}, leaving the rest of the list as it came.
	 *
	 * <p>Why this exists. The duplicate-mod arbiter extracts a multi-loader jar's children into
	 * {@code .forbric-kernel/candidates/}, and those paths reach the mod-jar list, which precedes the Fabric list
	 * on the classpath. A guest module can therefore be read by Mixin from the candidates copy - the untouched
	 * original. Measured: {@code BlockEntityTypeAccessor} arrived at Mixin as 492 bytes from
	 * {@code candidates/…/fabric-api-lookup-api-v1-0.116.17.jar} while the rewritten copy in the cache is 612
	 * bytes, and that one difference is the whole of {@code @Mixin target net.minecraft.class_2591 was not
	 * found}. The cache is keyed by the source content, so this costs a hit, not a second rewrite.
	 */
	public static List<Path> remapUnder(List<Path> jars, Path under, Path cacheDir) {
		if (jars.isEmpty()) return jars;
		List<Path> out = new ArrayList<>(jars.size());
		for (Path jar : jars) {
			out.add(jar.startsWith(under) ? remapOneOrKeep(jar, cacheDir) : jar);
		}
		return out;
	}

	private static Path remapOneOrKeep(Path jar, Path cacheDir) {
		try {
			return remapOne(jar, cacheDir);
		} catch (IOException | RuntimeException failed) {
			ForbricLog.warn("[Forbric/Remap] %s could not be rewritten and stays as it came: %s", jar,
					String.valueOf(failed));
			return jar;
		}
	}

	private static Path remapOne(Path jar, Path cacheDir) throws IOException {
		byte[] bytes = Files.readAllBytes(jar);
		String key = digest(bytes);
		Path target = cacheDir.resolve(key + '-' + jar.getFileName());
		if (Files.isRegularFile(target) && Files.size(target) > 0) return target;
		Files.createDirectories(cacheDir);

		Set<String> names = new LinkedHashSet<>();
		long classes = 0;
		Path partial = Files.createTempFile(cacheDir, "remap", ".jar.part");
		try (ZipInputStream in = new ZipInputStream(new java.io.ByteArrayInputStream(bytes));
				ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(partial))) {
			for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
				if (entry.isDirectory()) continue;
				byte[] content = in.readAllBytes();
				String name = entry.getName();
				if (isSignature(name)) continue;
				byte[] rewritten = content;
				String rewrittenName = name;
				if (MANIFEST.equals(name)) {
					rewritten = stripDigests(content);
				} else if (name.endsWith(".class")) {
					try {
						byte[] mapped = mapClass(content, names);
						rewritten = mapped;
						classes++;
						String internal = name.substring(0, name.length() - ".class".length());
						String renamed = mapInternal(internal);
						if (!renamed.equals(internal)) rewrittenName = renamed + ".class";
					} catch (RuntimeException unreadableClass) {
						// One unreadable class does not cost the whole jar: it is copied through, and the failure
						// still names itself when the class is first used. The first three also name themselves
						// here, because a jar that silently kept half its classes is exactly the shape that reads as
						// "the remap ran and the errors did not move".
						classFailures++;
						if (classFailures <= 3) {
							ForbricLog.warn("[Forbric/Remap] %s inside %s kept as it came: %s", name,
									jar.getFileName(), String.valueOf(unreadableClass));
						}
					}
				} else if (name.endsWith(".json")) {
					String text = new String(content, StandardCharsets.UTF_8);
					// A refmap's values are selectors - "Lowner;member(args)ret" - and they are read BEFORE the
					// plain text pass: that pass maps the owner half, while the member lookup needs the owner
					// exactly as the mod wrote it (intermediary). Both passes are idempotent, so the order only
					// decides which of them gets to see a selector whole.
					String mapped = name.endsWith("-refmap.json") ? mapRefmapValues(text, names) : text;
					mapped = mapClassNamesInText(mapped, names);
					if (!mapped.equals(text)) rewritten = mapped.getBytes(StandardCharsets.UTF_8);
				}
				ZipEntry copy = new ZipEntry(rewrittenName);
				copy.setTime(0L);
				out.putNextEntry(copy);
				out.write(rewritten);
				out.closeEntry();
			}
		}
		Files.move(partial, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		jarsWritten++;
		classesRewritten += classes;
		namesMapped += names.size();
		return target;
	}

	private static final String MANIFEST = "META-INF/MANIFEST.MF";

	/**
	 * Signature files of a signed jar. A rewritten jar keeps the manifest's per-entry digests and the .SF/.RSA
	 * pair, so the first class the verifier reads fails with
	 * {@code SecurityException: SHA-256 digest error for …/BlockEntityTypeAccessor.class} and the whole mixin
	 * config is abandoned - measured the first time the candidates copy was rewritten. Renaming classes cannot
	 * keep a signature valid, so the signature goes with it, which is what every remapping tool does.
	 */
	private static boolean isSignature(String name) {
		if (!name.startsWith("META-INF/")) return false;
		String file = name.substring("META-INF/".length());
		if (file.indexOf('/') >= 0) return false;
		String upper = file.toUpperCase(java.util.Locale.ROOT);
		return upper.endsWith(".SF") || upper.endsWith(".RSA") || upper.endsWith(".DSA") || upper.endsWith(".EC");
	}

	/**
	 * The manifest without any digest, so the verifier has nothing to check. Main attributes survive (Multi-Release
	 * and Main-Class still mean what they meant); per-entry sections exist only to carry digests, so they go.
	 */
	private static byte[] stripDigests(byte[] content) {
		java.util.jar.Manifest manifest = new java.util.jar.Manifest();
		try {
			manifest.read(new java.io.ByteArrayInputStream(content));
		} catch (IOException unreadable) {
			// An unreadable manifest is worse than a rewritten one: a jar with no manifest at all still loads.
			return new byte[0];
		}
		manifest.getEntries().clear();
		java.util.jar.Attributes main = manifest.getMainAttributes();
		main.remove(new java.util.jar.Attributes.Name("Signature-Version"));
		java.util.List<String> keys = new ArrayList<>();
		for (Object key : main.keySet()) keys.add(String.valueOf(key));
		for (String key : keys) {
			if (key.toLowerCase(java.util.Locale.ROOT).contains("digest")) {
				main.remove(new java.util.jar.Attributes.Name(key));
			}
		}
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
		try {
			manifest.write(out);
		} catch (IOException impossible) {
			return new byte[0];
		}
		return out.toByteArray();
	}

	/** One class through {@link ClassRemapper}, with every name the resolver knows. */
	private static byte[] mapClass(byte[] content, Set<String> seen) {
		ClassReader reader = new ClassReader(content);
		// COMPUTE_MAXS, never COMPUTE_FRAMES: renaming does not change a single instruction, and recomputing frames
		// would need a common-superclass answer from a loader that is still being assembled.
		ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
		ClassRemapper remapper = new ClassRemapper(writer, new ResolverRemapper(seen));
		List<String> owners = mixinTargets(content);
		// The strings go FIRST, and they have to exist. ClassRemapper maps types, not the text of a string
		// constant - and fabric-api chooses its targets in exactly that form: @Mixin(targets = "net.minecraft.
		// class_2591"). Measured after the first pass, which only handled descriptors: 251 "Error loading class:
		// net/minecraft/class_2591" lines survived a jar that had been rewritten top to bottom.
		reader.accept(new StringConstants(remapper, seen, owners), 0);
		return writer.toByteArray();
	}

	/**
	 * The classes a mixin declares as its target, as internal names.
	 *
	 * <p>Needed because a bare member selector carries no owner: {@code @Accessor("field_19315")} on a mixin whose
	 * {@code @Mixin} names {@code BlockEntityType} can only be translated if the owner is known. Measured with this
	 * missing: {@code InvalidAccessorException: No candidates were found matching field_19315:Ljava/util/Set; in
	 * net/minecraft/world/level/block/entity/BlockEntityType} — the class half was already right, only the member
	 * name was still intermediary.
	 */
	private static List<String> mixinTargets(byte[] content) {
		List<String> owners = new ArrayList<>();
		try {
			new ClassReader(content).accept(new ClassVisitor(Opcodes.ASM9) {
				@Override
				public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
					if (!"Lorg/spongepowered/asm/mixin/Mixin;".equals(descriptor)) return null;
					return new AnnotationVisitor(Opcodes.ASM9) {
						@Override
						public AnnotationVisitor visitArray(String name) {
							return this;
						}

						@Override
						public void visit(String name, Object value) {
							if (value instanceof org.objectweb.asm.Type type) owners.add(type.getInternalName());
							else if (value instanceof String text) owners.add(text.replace('.', '/'));
						}
					};
				}
			}, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		} catch (RuntimeException unreadable) {
			return List.of();
		}
		return owners;
	}

	/**
	 * A bare member selector in an annotation, mapped against the mixin's own targets.
	 *
	 * <p>Both spellings are tried because the string does not say which it is, and the table is keyed by owner and
	 * name only. Counted, not warned: one module can hold dozens, and the count is the signal.
	 */
	private static String mapMemberText(String text, List<String> owners) {
		boolean method = text.startsWith("method_");
		boolean field = text.startsWith("field_");
		if (!method && !field) return text;
		for (int i = method ? 7 : 6; i < text.length(); i++) {
			if (!Character.isDigit(text.charAt(i))) return text;
		}
		for (String owner : owners) {
			String mapped = method
					? KernelMappingResolver.INSTANCE.mapMethodName("intermediary", owner, text, "")
					: KernelMappingResolver.INSTANCE.mapFieldName("intermediary", owner, text, "");
			if (!mapped.equals(text)) {
				memberStrings++;
				return mapped;
			}
		}
		memberStringMisses++;
		return text;
	}

	/**
	 * Rewrites the <b>values</b> of a refmap: {@code "Lnet/minecraft/class_2248;method_9585(...)V"} becomes
	 * {@code "Lnet/minecraft/world/level/block/Block;<its official name>(...)V"}.
	 *
	 * <p>This is the layer that kept failing after every class name was already right. Mixin reads a refmap at run
	 * time to translate the selector written in the mod's annotation into the target it should transform, so an
	 * unmapped value is a target that cannot be loaded - which reads in the log as
	 * {@code Error loading class: net/minecraft/class_2591}, one per mixin, and looks exactly like the class pass
	 * never ran. Measured on the written cache before this pass: 50 modules, 2910 intermediary tokens, all of them
	 * in refmap values, while the same modules' mixin configs held none.
	 *
	 * <p>The keys are left alone on purpose: {@code "Lnet/minecraft/block/Block;onBroken(...)V"} is the spelling
	 * the mod's own annotation uses, and mixin looks the entry up by it.
	 */
	private static String mapRefmapValues(String json, Set<String> seen) {
		if (KernelMappingResolver.INSTANCE.getNamespaces().size() < 2) return json;
		// An ownerless value first: "field_19315:Ljava/util/Set;" carries its owner in no part of the document, and
		// the owner-carrying passes below cannot see it. Measured after the first launch that had every class name
		// right: BlockEntityTypeAccessor still failed with "No candidates were found matching
		// field_19315:Ljava/util/Set;", because mixin substituted the refmap value for the annotation and the value
		// was the one token nothing had translated.
		String bare = replaceBareSelectors(json, seen);
		// Then methods before fields: a field pattern would also match a method selector's owner half and stop there.
		String methods = replaceSelectors(bare, REFMAP_METHOD, true, seen);
		return replaceSelectors(methods, REFMAP_FIELD, false, seen);
	}

	/**
	 * Rewrites a refmap value that names a member but no owner: {@code "field_19315:Ljava/util/Set;"}.
	 *
	 * <p>The name is translated through the table indexed by member name alone, which is only unambiguous because
	 * intermediary member names are unique per member; a name that is not gets left as written (see
	 * {@link KernelMappingResolver#mapFieldNameWithoutOwner}).
	 */
	private static String replaceBareSelectors(String json, Set<String> seen) {
		Matcher matcher = REFMAP_BARE.matcher(json);
		StringBuilder out = new StringBuilder(json.length());
		int last = 0;
		while (matcher.find()) {
			String name = matcher.group(1);
			boolean field = matcher.group(2) != null;
			String mapped = field
					? KernelMappingResolver.INSTANCE.mapFieldNameWithoutOwner("intermediary", name)
					: KernelMappingResolver.INSTANCE.mapMethodNameWithoutOwner("intermediary", name);
			if (mapped.equals(name)) {
				refmapMisses++;
				continue;
			}
			refmapValues++;
			out.append(json, last, matcher.start());
			out.append('"').append(mapped);
			if (field) {
				out.append(':').append(mapDescriptor(matcher.group(2), seen));
			} else {
				out.append('(').append(mapDescriptor(matcher.group(3), seen)).append(')')
						.append(mapDescriptor(matcher.group(4), seen));
			}
			out.append('"');
			last = matcher.end();
		}
		if (last == 0) return json;
		out.append(json, last, json.length());
		return out.toString();
	}

	private static String replaceSelectors(String json, Pattern pattern, boolean method, Set<String> seen) {
		Matcher matcher = pattern.matcher(json);
		StringBuilder out = new StringBuilder(json.length());
		int last = 0;
		while (matcher.find()) {
			String owner = matcher.group(1);
			String name = matcher.group(2);
			String descriptor = method ? "(" + matcher.group(3) + ')' + matcher.group(4) : matcher.group(3);
			String officialOwner = mapInternal(owner);
			String officialName = method
					? KernelMappingResolver.INSTANCE.mapMethodName("intermediary", owner, name, descriptor)
					: KernelMappingResolver.INSTANCE.mapFieldName("intermediary", owner, name, descriptor);
			String officialDescriptor = mapDescriptor(descriptor, seen);
			if (officialName.equals(name) && officialOwner.equals(owner)
					&& officialDescriptor.equals(descriptor)) {
				// A selector whose member is not in the table. Counted rather than warned one per hit: a single
				// module can hold dozens, and the number is what says whether the table or the jar is wrong.
				refmapMisses++;
				continue;
			}
			refmapValues++;
			out.append(json, last, matcher.start());
			out.append('"').append('L').append(officialOwner).append(';').append(officialName);
			// A FIELD selector keeps the ':' between the name and the descriptor; a METHOD selector has none, since its
			// descriptor already starts with '('. Dropping it produced "Lnet/minecraft/client/Minecraft;gameThreadLjava/
			// lang/Thread;", which mixin then read as one name ("Invalid name: gameThreadLjava/lang/Thread;") and
			// crashed the client on fabric-lifecycle-events-v1's MinecraftClientMixin.
			if (!method) out.append(':');
			out.append(officialDescriptor).append('"');
			last = matcher.end();
		}
		if (last == 0) return json;
		out.append(json, last, json.length());
		return out.toString();
	}

	/** Every {@code L...;} type inside a descriptor, mapped; the rest of the descriptor is copied as written. */
	private static String mapDescriptor(String descriptor, Set<String> seen) {
		StringBuilder out = new StringBuilder(descriptor.length());
		for (int i = 0; i < descriptor.length(); i++) {
			char c = descriptor.charAt(i);
			if (c != 'L') {
				out.append(c);
				continue;
			}
			int end = descriptor.indexOf(';', i);
			if (end < 0) {
				out.append(descriptor, i, descriptor.length());
				break;
			}
			String internal = descriptor.substring(i + 1, end);
			String mapped = mapInternal(internal);
			if (!mapped.equals(internal)) seen.add(internal);
			out.append('L').append(mapped).append(';');
			i = end;
		}
		return out.toString();
	}

	/**
	 * Rewrites intermediary class names that are written as <b>text</b> rather than as descriptors: string
	 * constants, and every annotation value reachable from the class (a mixin's {@code targets} lives there, on the
	 * mixin class, on its methods, and on its parameters).
	 */
	private static final class StringConstants extends ClassVisitor {
		private final Set<String> seen;
		private final List<String> owners;

		private StringConstants(ClassVisitor next, Set<String> seen, List<String> owners) {
			super(Opcodes.ASM9, next);
			this.seen = seen;
			this.owners = owners;
		}

		private String rewrite(Object value) {
			if (!(value instanceof String text)) return null;
			String byClass = mapClassNamesInText(text, seen);
			// A member selector carries no owner of its own, so it is translated against the mixin's declared
			// targets. Only when the class pass left it alone, because a mapped text is no longer a selector.
			return byClass.equals(text) ? mapMemberText(text, owners) : byClass;
		}

		private AnnotationVisitor wrap(AnnotationVisitor next) {
			return next == null ? null : new StringAnnotation(Opcodes.ASM9, next, seen, owners);
		}

		@Override
		public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
			return wrap(super.visitAnnotation(descriptor, visible));
		}

		@Override
		public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath path, String descriptor, boolean visible) {
			return wrap(super.visitTypeAnnotation(typeRef, path, descriptor, visible));
		}

		@Override
		public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
			FieldVisitor next = super.visitField(access, name, descriptor, signature, rewrite(value));
			return next == null ? null : new FieldVisitor(Opcodes.ASM9, next) {
				@Override
				public AnnotationVisitor visitAnnotation(String descriptor2, boolean visible) {
					return wrap(super.visitAnnotation(descriptor2, visible));
				}
			};
		}

		@Override
		public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
				String[] exceptions) {
			MethodVisitor next = super.visitMethod(access, name, descriptor, signature, exceptions);
			return next == null ? null : new MethodVisitor(Opcodes.ASM9, next) {
				@Override
				public void visitLdcInsn(Object value) {
					String rewritten = rewrite(value);
					super.visitLdcInsn(rewritten == null ? value : rewritten);
				}

				@Override
				public AnnotationVisitor visitAnnotation(String descriptor2, boolean visible) {
					return wrap(super.visitAnnotation(descriptor2, visible));
				}

				@Override
				public AnnotationVisitor visitParameterAnnotation(int parameter, String descriptor2, boolean visible) {
					return wrap(super.visitParameterAnnotation(parameter, descriptor2, visible));
				}

				@Override
				public AnnotationVisitor visitInsnAnnotation(int typeRef, TypePath path, String descriptor2,
						boolean visible) {
					return wrap(super.visitInsnAnnotation(typeRef, path, descriptor2, visible));
				}

				@Override
				public AnnotationVisitor visitTryCatchAnnotation(int typeRef, TypePath path, String descriptor2,
						boolean visible) {
					return wrap(super.visitTryCatchAnnotation(typeRef, path, descriptor2, visible));
				}

				@Override
				public AnnotationVisitor visitLocalVariableAnnotation(int typeRef, TypePath path, Label[] start,
						Label[] end, int[] index, String descriptor2, boolean visible) {
					return wrap(super.visitLocalVariableAnnotation(typeRef, path, start, end, index, descriptor2,
							visible));
				}
			};
		}
	}

	/** The annotation half of {@link StringConstants}: a value that is a string is a name, and is rewritten. */
	private static final class StringAnnotation extends AnnotationVisitor {
		private final Set<String> seen;
		private final List<String> owners;

		private StringAnnotation(int api, AnnotationVisitor next, Set<String> seen, List<String> owners) {
			super(api, next);
			this.seen = seen;
			this.owners = owners;
		}

		@Override
		public void visit(String name, Object value) {
			if (!(value instanceof String text)) {
				super.visit(name, value);
				return;
			}
			String byClass = mapClassNamesInText(text, seen);
			super.visit(name, byClass.equals(text) ? mapMemberText(text, owners) : byClass);
		}

		@Override
		public AnnotationVisitor visitAnnotation(String name, String descriptor) {
			AnnotationVisitor next = super.visitAnnotation(name, descriptor);
			return next == null ? null : new StringAnnotation(api, next, seen, owners);
		}

		@Override
		public AnnotationVisitor visitArray(String name) {
			AnnotationVisitor next = super.visitArray(name);
			return next == null ? null : new StringAnnotation(api, next, seen, owners);
		}
	}

	/**
	 * Rewrites the intermediary class tokens inside a text resource - a mixin config's {@code target}, its
	 * {@code @At(target=...)} selectors, and the class half of a refmap value.
	 *
	 * <p>Only tokens that look like {@code class_NNNN} are touched, so a mod's own class names in the same file are
	 * left exactly as written.
	 */
	static String mapClassNamesInText(String text, Set<String> seen) {
		Matcher matcher = INTERMEDIARY.matcher(text);
		StringBuilder out = new StringBuilder(text.length());
		while (matcher.find()) {
			String token = matcher.group(1);
			boolean dotted = token.indexOf('.') >= 0;
			String internal = dotted ? token.replace('.', '/') : token;
			String mapped = mapInternal(internal);
			String replacement;
			if (mapped.equals(internal)) {
				replacement = token;
			} else {
				seen.add(internal);
				replacement = dotted ? mapped.replace('/', '.') : mapped;
			}
			matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
		}
		matcher.appendTail(out);
		return out.toString();
	}

	/**
	 * The base jars, asked what a class actually declares.
	 *
	 * <p>Read lazily and cached per owner: only classes the guests call lambda-named methods on are ever opened, so
	 * the cost is a handful of entries out of a fifty-thousand-entry jar rather than an index of the whole base.
	 */
	/** One method of a base class: its name and the access flags the base declares it with. */
	private record BaseMethod(String name, int access) {
		private boolean callable() {
			return (access & 0x0001) != 0 && (access & 0x0008) != 0;
		}
	}

	private static final class MethodIndex {
		private final List<Path> jars;
		private final Map<String, Map<String, List<BaseMethod>>> cache = new HashMap<>();

		private MethodIndex(List<Path> jars) {
			this.jars = jars;
		}

		private List<BaseMethod> methods(String internalOwner, String descriptor) {
			return cache.computeIfAbsent(internalOwner, this::read).getOrDefault(descriptor, List.of());
		}

		private Map<String, List<BaseMethod>> read(String internalOwner) {
			String entryName = internalOwner + ".class";
			for (Path jar : jars) {
				try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar.toFile())) {
					java.util.zip.ZipEntry found = zip.getEntry(entryName);
					if (found == null) continue;
					ClassNode node = new ClassNode();
					try (java.io.InputStream in = zip.getInputStream(found)) {
						new ClassReader(in).accept(node,
								ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
					}
					Map<String, List<BaseMethod>> byDescriptor = new HashMap<>();
					for (MethodNode method : node.methods) {
						byDescriptor.computeIfAbsent(method.desc, key -> new ArrayList<>(2))
								.add(new BaseMethod(method.name, method.access));
					}
					return byDescriptor;
				} catch (IOException | RuntimeException unreadable) {
					// A jar that cannot be opened simply does not answer; the next one may carry the class.
				}
			}
			return Map.of();
		}
	}

	/** The resolver, as the ASM visitor shape that asks for it. */
	private static final class ResolverRemapper extends Remapper {
		private final Set<String> seen;

		private ResolverRemapper(Set<String> seen) {
			this.seen = seen;
		}

		@Override
		public String map(String internalName) {
			String mapped = mapInternal(internalName);
			if (!mapped.equals(internalName)) seen.add(internalName);
			return mapped;
		}

		@Override
		public String mapMethodName(String owner, String name, String descriptor) {
			String mapped = KernelMappingResolver.INSTANCE.mapMethodName("intermediary", owner, name, descriptor);
			// A call site names the static receiver type, not the class that declares the method: fabric-api calls
			// Registry.holderByNameCodec through DefaultedRegistry, and the table files that row under Registry — so a
			// qualified miss is usually an inherited member, not an unknown one. Of the 39655 method rows in the table
			// only 10 names map to two different official names, and the resolver refuses those, so the unqualified
			// lookup is safe; it is what turns method_40294 into holderByNameCodec instead of leaving the call to fail
			// with NoSuchMethodError at Bootstrap.
			String resolved = mapped.equals(name)
					? KernelMappingResolver.INSTANCE.mapMethodNameWithoutOwner("intermediary", name)
					: mapped;
			return lambdaShape(owner, resolved, descriptor);
		}

		/**
		 * Picks the method the base actually declares when the table named a synthetic lambda.
		 *
		 * <p>The table maps one official name onto every lambda of an enclosing method — nine intermediary ids of
		 * {@code ResourceLocation} read {@code lambda$read$0}, and {@code ByteBufCodecs} repeats the shape — so a
		 * name-only answer is right for at most one of them. The descriptor is not ambiguous, though: when the base
		 * class has no method under the mapped name but exactly one non-lambda method accepts the same descriptor, that
		 * method is the one the guest was compiled against. The owner and descriptor still hold their <em>source</em>
		 * spelling here, because ASM asks for the name before it maps the owner, so both are mapped before comparing.
		 */
		private String lambdaShape(String owner, String mapped, String descriptor) {
			if (!mapped.startsWith("lambda$")) return mapped;
			String officialOwner = mapInternal(owner);
			String officialDescriptor = mapDesc(descriptor);
			MethodIndex index = minecraftIndex;
			if (index == null) return mapped;
			List<BaseMethod> byDescriptor = index.methods(officialOwner, officialDescriptor);
			if (byDescriptor.isEmpty() && lambdasUnindexed-- > 0) {
				ForbricLog.debug("[Forbric/Remap] %s%s is not declared on the base, and no method there takes it",
						officialOwner, officialDescriptor);
			}
			boolean present = false;
			for (BaseMethod method : byDescriptor) if (method.name().equals(mapped)) present = true;
			if (byDescriptor.isEmpty() || present) {
				// Either the base could not be read, or the name really does exist with this descriptor: leave it.
				if (!byDescriptor.isEmpty()) lambdasPicked++;
				return mapped;
			}
			List<String> named = new ArrayList<>(byDescriptor.size());
			for (BaseMethod method : byDescriptor) {
				String candidate = method.name();
				if (!candidate.startsWith("lambda$") && !candidate.startsWith("<") && method.callable()) {
					named.add(candidate);
				}
			}
			if (named.size() == 1) {
				lambdasPicked++;
				return named.get(0);
			}
			// More than one method takes the same descriptor, and the table cannot say which one the guest meant
			// because it collapsed every lambda of the enclosing method onto one name. Measured case:
			// ResourceLocation declares fromNamespaceAndPath(String, String) and tryBuild(String, String) publicly,
			// and a private createUntrusted(String, String) beside them; fabric-api-base's read() was the public
			// throwing one. Restricting to callable members drops the private helper, and a "try*" sibling is by
			// naming convention the variant that answers instead of throwing — so a lone non-try candidate is the
			// reader the lambda came from. Anything still tied is left alone rather than guessed.
			List<String> readers = new ArrayList<>(named.size());
			for (String candidate : named) {
				if (!candidate.startsWith("try")) readers.add(candidate);
			}
			if (readers.size() == 1) {
				lambdasPicked++;
				ForbricLog.debug("[Forbric/Remap] %s%s is ambiguous, and %s is the one reader among %s", officialOwner,
						officialDescriptor, readers.get(0), named);
				return readers.get(0);
			}
			lambdasAmbiguous++;
			return mapped;
		}

		@Override
		public String mapFieldName(String owner, String name, String descriptor) {
			String mapped = KernelMappingResolver.INSTANCE.mapFieldName("intermediary", owner, name, descriptor);
			return mapped.equals(name)
					? KernelMappingResolver.INSTANCE.mapFieldNameWithoutOwner("intermediary", name)
					: mapped;
		}

		@Override
		public String mapRecordComponentName(String owner, String name, String descriptor) {
			return mapFieldName(owner, name, descriptor);
		}
	}

	private static String digest(byte[] bytes) {
		try {
			MessageDigest sha = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(sha.digest(bytes)).substring(0, 16);
		} catch (NoSuchAlgorithmException everyJvmHasSha256) {
			throw new IllegalStateException("SHA-256 is required of every JVM", everyJvmHasSha256);
		}
	}

	/** Copies a stream through, used only by tests that need a jar's bytes without our rewriting. */
	static void copy(InputStream in, OutputStream out) throws IOException {
		in.transferTo(out);
	}
}
