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
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
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
		if (mapped.equals(internalName)) {
			// Fabric API is published with its mixin annotations and refmaps still spelled in Yarn names, because the
			// loader is expected to translate them at runtime. The jar's own refmap says which intermediary class
			// each of its named classes is, and the table says which official class that is: two hops, one dictionary
			// that came with the guest instead of a second table this build would have to carry.
			String intermediary = yarn.classes.get(internalName);
			if (intermediary != null) {
				mapped = KernelMappingResolver.INSTANCE.mapClassName("intermediary", intermediary);
				if (!mapped.equals(intermediary)) yarnClasses++;
			}
		}
		if (mapped.equals(internalName)) return internalName;
		return mapped.indexOf('.') < 0 ? mapped : mapped.replace('.', '/');
	}

	/**
	 * Yarn names to intermediary ones, collected from one guest jar's refmaps.
	 *
	 * <p>A refmap's {@code data} section is exactly the set of named references that jar writes down, so the guest's
	 * own dictionary answers for its own names and no external Yarn table is needed. Rebuilt per jar; guest jars are
	 * rewritten one at a time.
	 */
	private static volatile YarnNames yarn = YarnNames.EMPTY;
	private static long yarnClasses;
	private static long yarnMembers;

	private static final class YarnNames {
		private static final YarnNames EMPTY = new YarnNames();
		private final Map<String, String> classes = new HashMap<>();
		private final Map<String, String> members = new HashMap<>();
		/** Mixin class (internal name) to its own refmap: the selector the author wrote, and the selector it means. */
		private final Map<String, Map<String, String>> selectors = new HashMap<>();
	}

	/** A named-to-intermediary pair inside a refmap's {@code data} section. */
	private static final Pattern YARN_PAIR = Pattern.compile(
			"\"([^\"]*)\"\\s*:\\s*\"L([A-Za-z0-9_$/]+);([A-Za-z0-9_$<>]+)([:(][^\"\\r\\n]*)?\"");

	/** A mixin class's own block inside a refmap's {@code mappings} section. */
	private static final Pattern MIXIN_BLOCK = Pattern.compile(
			"\"([A-Za-z0-9_$/]+)\"\\s*:\\s*\\{([^{}]*)\\}");
	/** One entry inside such a block: the authored selector, and the selector it means. */
	private static final Pattern SELECTOR_ENTRY = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"([^\"]+)\"");

	/**
	 * Reads what each mixin class in this jar wrote down about its own targets.
	 *
	 * <p>A refmap's {@code mappings} block is keyed by mixin class, and each entry is
	 * {@code "the selector the author wrote": "Lowner;name(desc)ret"}. That is the guest's own statement about what
	 * its {@code @Inject(method = …)} meant, and it is the only place that statement exists: {@code ItemGroupMixin}
	 * injects into {@code updateEntries}, which is Yarn for {@code CreativeModeTab.buildContents}, and nothing in the
	 * class file itself says so. Measured on 1.21.1: the anchors analysis resolved the name literally, reported
	 * "@Inject target CreativeModeTab.updateEntries" as missing, relaxed the injection away — and opening the
	 * creative inventory then died with {@code IllegalStateException: Item group has no page}, because the injection
	 * that would have set it had been switched off by us rather than failing on its own.
	 */
	private static Map<String, Map<String, String>> readMixinSelectors(String refmap) {
		int start = refmap.indexOf("\"mappings\"");
		if (start < 0) return new HashMap<>();
		int data = refmap.indexOf("\"data\"", start);
		String official = data > start ? refmap.substring(start, data) : refmap.substring(start);
		String intermediate = data > start ? refmap.substring(data) : "";
		Map<String, Map<String, String>> byOfficial = selectorBlocks(official);
		Map<String, Map<String, String>> byIntermediary = selectorBlocks(intermediate);
		Map<String, Map<String, String>> found = new HashMap<>();
		for (Map.Entry<String, Map<String, String>> mixin : byOfficial.entrySet()) {
			Map<String, String> answers = new HashMap<>(mixin.getValue());
			Map<String, String> intermediary = byIntermediary.get(mixin.getKey());
			if (intermediary != null) {
				// The annotation no longer carries the authored name by the time this pass sees it: the remapper has
				// already rewritten the string into its intermediary selector. Measured on ItemGroupMixin:
				// method = "updateEntries" arrives as "Lnet/minecraft/class_1761;method_47306(…)V".
				for (Map.Entry<String, String> entry : mixin.getValue().entrySet()) {
					String asIntermediary = intermediary.get(entry.getKey());
					if (asIntermediary != null) answers.put(asIntermediary, entry.getValue());
				}
			}
			found.put(mixin.getKey(), answers);
		}
		return found;
	}

	/** One refmap section, as mixin class to authored selector to the selector it stands for. */
	private static Map<String, Map<String, String>> selectorBlocks(String section) {
		Map<String, Map<String, String>> found = new HashMap<>();
		Matcher outer = MIXIN_BLOCK.matcher(section);
		while (outer.find()) {
			Map<String, String> entries = null;
			Matcher inner = SELECTOR_ENTRY.matcher(outer.group(2));
			while (inner.find()) {
				String from = inner.group(1);
				String to = inner.group(2);
				// A selector is the Lowner;member form. A bare number pair or a plain name is not one.
				if (!to.startsWith("L") || to.indexOf(';') < 0) continue;
				if (entries == null) entries = new HashMap<>();
				entries.put(from, to);
			}
			if (entries != null) found.put(outer.group(1), entries);
		}
		return found;
	}
	/** Off switch, for a launch that needs to see the unwritten jar. */
	public static final String SWITCH = "forbric.remapGuestJars";

	/** Matches an intermediary class token in text: dotted or slashed, optionally package-qualified and nested. */
	private static final Pattern INTERMEDIARY = Pattern.compile(
			"(?<![A-Za-z0-9_$/.\\-])((?:[A-Za-z0-9_$]+[./])*class_\\d+(?:\\$[A-Za-z0-9_$]+)*)");

	/** A class token inside a descriptor. */
	private static final Pattern DESCRIPTOR_CLASS = Pattern.compile("L([A-Za-z0-9_$/]+);");
	/** A refmap method selector value: {@code "Lowner;name(args)ret"}. */
	private static final Pattern REFMAP_METHOD = Pattern.compile(
			"\"L([A-Za-z0-9_$/]+);([A-Za-z0-9_$<>]+)\\(([^)]*)\\)([A-Za-z0-9_$/;\\[\\$]+)\"");

	/** A refmap field selector value: {@code "Lowner;name:desc"}. */
	private static final Pattern REFMAP_FIELD = Pattern.compile(
			"\"L([A-Za-z0-9_$/]+);([A-Za-z0-9_$<>]+):([A-Za-z0-9_$/;\\[\\$]+)\"");

	/** A refmap value that names a member and no owner: {@code "field_19315:Ljava/util/Set;"}. */
	private static final Pattern REFMAP_BARE = Pattern.compile(
			"\"((?:method|field)_\\d+)(?::([A-Za-z0-9_$/;\\[\\$]+)|\\(([^)]*)\\)([A-Za-z0-9_$/;\\[\\$]+))\"");

	/** A refmap entry: {@code "key": "Lowner;member(desc)ret"} or {@code "key": "Lowner;member:desc"}. */
	private static final Pattern REFMAP_PAIR = Pattern.compile(
			"\"([A-Za-z0-9_$<>()\\[\\];/]+)\"\\s*:\\s*\"L([A-Za-z0-9_$/]+);([A-Za-z0-9_$<>]+)([:(][^\"\\r\\n]*)\"");

	private static int jarsWritten;
	private static int jarsReused;
	private static long classesRewritten;
	private static long namesMapped;
	private static long classFailures;
	private static long refmapValues;
	private static long refmapMisses;
	private static long memberStrings;
	private static long memberStringMisses;
	private static long lambdasPicked;
	private static long lambdasAmbiguous;
	private static long tableNamesCorrected;
	private static long tableNamesUnresolved;
	private static volatile MethodIndex minecraftIndex;
	private static int lambdasUnindexed = 5;
	/** {@code @At} targets written because the injected method really makes that call, and those left alone. */
	private static final java.util.concurrent.atomic.AtomicInteger AT_TARGETS_WRITTEN =
			new java.util.concurrent.atomic.AtomicInteger();
	private static final java.util.concurrent.atomic.AtomicInteger AT_TARGETS_REFUSED =
			new java.util.concurrent.atomic.AtomicInteger();
	/**
	 * Why the call-site guard refused, by code: 1 no base index, 2 nothing to check against, 3 the mixed class cannot
	 * be read, 4 no method of that name and shape, 5 the method makes no such call.
	 */
	private static final java.util.concurrent.atomic.AtomicIntegerArray AT_REFUSALS =
			new java.util.concurrent.atomic.AtomicIntegerArray(6);

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
		long cachedBefore = regularFilesIn(cacheDir);
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
		// Always say what the cache did, warm boot included. A run that rewrites nothing used to print nothing at
		// all, so "this boot reused yesterday's jars" and "this boot never ran the rewriter" read the same from the
		// log - and two boots of one jar disagreed about which repairs landed because of exactly that.
		ForbricLog.info("[Forbric/Remap] guest jar cache %s: %d reused from an earlier boot, %d rewritten now,"
				+ " %d left as they came (of %d jar(s)); %d file(s) were on disk when this boot started, so the"
				+ " rewrite was %s", cacheDir, jarsReused, jarsWritten, failed, jars.size(), cachedBefore,
				cachedBefore < 0 ? "unknown" : (jarsWritten == 0 ? "warm" : (jarsReused == 0 ? "cold" : "partial")));
		if (classRenamesFromCached > 0) {
			ForbricLog.info("[Forbric/Remap] %d class rename(s) read back out of the cached refmaps, so this boot"
					+ " resolves a yarn-spelled mixin target the same way a cold boot does", classRenamesFromCached);
		}
		if (jarsWritten > 0) {
			ForbricLog.info("[Forbric/Remap] %d guest jar(s) rewritten into %s (%d class(es), %d name(s) mapped,"					+ " %d refmap selector(s) resolved%s)", jarsWritten, cacheDir, classesRewritten, namesMapped,
					refmapValues,
					(refmapMisses == 0 ? "" : ", " + refmapMisses + " refmap selector(s) not in the table")
							+ (failed == 0 ? "" : ", " + failed + " left as they came")
							+ (classFailures == 0 ? "" : ", " + classFailures + " class(es) unreadable")
							+ (lambdasPicked == 0 ? "" : ", " + lambdasPicked + " lambda name(s) replaced by the"
									+ " method the base actually declares")
							+ (lambdasAmbiguous == 0 ? "" : ", " + lambdasAmbiguous + " lambda name(s) left alone"
									+ " because more than one method there accepts the descriptor")
							+ (descriptorsPicked == 0 ? "" : ", " + descriptorsPicked + " member(s) named by their"
									+ " descriptor because the table had no row for them")
							+ (ownersReversed == 0 ? "" : ", " + ownersReversed + " owner(s) translated through the"
									+ " table's reverse class row because the jar's own dictionary did not carry them")
							+ (tableNamesCorrected == 0 ? "" : ", " + tableNamesCorrected + " table name(s) the base"
									+ " does not declare replaced by the method it does")
							+ (tableNamesUnresolved == 0 ? "" : ", " + tableNamesUnresolved + " table name(s) left"
									+ " unresolved because the base has no method with that shape")
							+ (LAMBDAS_POINTED.get() == 0 ? ""
									: ", " + LAMBDAS_POINTED.get() + " lambda selector(s) pointed at the copy this"
											+ " base actually compiled")
							+ (AT_CLASSES_MAPPED.get() == 0 ? ""
									: ", " + AT_CLASSES_MAPPED.get() + " @At selector(s) had a class name moved to"
											+ " the name this base declares (owner and descriptor types only, never a"
											+ " member) — Round 203 measured that as a loss, see mapAtSelectorClasses")
							+ (LEFTOVERS.isEmpty() ? "" : ", " + LEFTOVERS.size() + " class(es) still name a"
									+ " member in intermediary spelling because the table has no row for it ("
									+ leftoverMemberCount() + " member(s)); those calls fail at first use, and the"
									+ " cure is the table, not this rewrite"));
			if (Boolean.getBoolean("forbric.rewriteAtTargets")) {
				ForbricLog.info("[Forbric/Remap] %d @At target(s) rewritten because the method they inject into"
						+ " really makes that call, and %d left as the mod wrote them: %d with no base index,"
						+ " %d with nothing to check against, %d whose mixed class cannot be read, %d whose method"
						+ " of that shape is not there, %d whose method makes no such call",
						AT_TARGETS_WRITTEN.get(), AT_TARGETS_REFUSED.get(), AT_REFUSALS.get(1), AT_REFUSALS.get(2),
						AT_REFUSALS.get(3), AT_REFUSALS.get(4), AT_REFUSALS.get(5));
			}
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
		return remapUnder(jars, under, cacheDir, null);
	}

	/**
	 * Same, given the base jars. Without them the base index is null and every question that needs the base's own
	 * declarations — which name a {@code @Shadow} really has, which method a synthetic lambda id points at — is
	 * answered by the table alone or not at all. Measured with this missing: the candidates copy of
	 * {@code fabric-resource-loader-v0}'s {@code GameOptionsMixin} reached Mixin as
	 * {@code @Shadow field field_1887}, the run reported "was not located in the target class
	 * net.minecraft.client.Options", and the same module rewritten by hand with the index in hand came out as
	 * {@code resourcePacks}.
	 */
	public static List<Path> remapUnder(List<Path> jars, Path under, Path cacheDir, List<Path> minecraftJars) {
		if (minecraftJars != null && !minecraftJars.isEmpty()) minecraftIndex = new MethodIndex(minecraftJars);
		if (jars.isEmpty()) return jars;
		List<Path> out = new ArrayList<>(jars.size());
		for (Path jar : jars) {
			out.add(jar.startsWith(under) ? remapOneOrKeep(jar, cacheDir) : jar);
		}
		return out;
	}

	/**
	 * Reads a jar's refmaps into the two dictionaries the class pass needs.
	 *
	 * <p>A refmap's {@code data} section is exactly the set of named references that jar writes down, so the guest's
	 * own dictionary answers for its own names and no external Yarn table is needed. Rebuilt per jar; guest jars are
	 * rewritten one at a time.
	 */
	private static YarnNames readYarnNames(byte[] jarBytes) {
		YarnNames names = new YarnNames();
		try (ZipInputStream in = new ZipInputStream(new java.io.ByteArrayInputStream(jarBytes))) {
			for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
				if (entry.isDirectory() || !entry.getName().endsWith("-refmap.json")) continue;
				String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
				names.selectors.putAll(readMixinSelectors(text));
				int at = text.indexOf("\"data\"");
				if (at < 0) continue;
				Matcher matcher = YARN_PAIR.matcher(text.substring(at));
				while (matcher.find()) {
					String key = matcher.group(1);
					String intermediaryOwner = matcher.group(2);
					String intermediaryMember = matcher.group(3);
					if (!intermediaryOwner.contains("class_")) continue;
					int semicolon = key.indexOf(';');
					String namedOwner = key.startsWith("L") && semicolon > 0 ? key.substring(1, semicolon) : null;
					if (namedOwner != null && namedOwner.startsWith("net/minecraft/")
							&& !namedOwner.contains("class_")) {
						names.classes.putIfAbsent(namedOwner, intermediaryOwner);
					}
					String namedMember = namedOwner != null ? key.substring(semicolon + 1) : key;
					int cut = namedMember.indexOf('(') >= 0 ? namedMember.indexOf('(') : namedMember.indexOf(':');
					if (cut >= 0) namedMember = namedMember.substring(0, cut);
					if (namedMember.matches("[A-Za-z_$][A-Za-z0-9_$]*") && !namedMember.contains("class_")
							&& intermediaryMember.matches("(?:method|field)_\\d+")
							&& !namedMember.equals(intermediaryMember)) {
						names.members.putIfAbsent(namedMember, intermediaryMember);
					}
				}
			}
		} catch (IOException unreadable) {
			return names;
		}
		return names;
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

	/** How many files the cache directory held before this boot touched it; -1 when the answer cannot be read. */
	private static long regularFilesIn(Path dir) {
		if (dir == null || !java.nio.file.Files.isDirectory(dir)) return 0;
		try (java.util.stream.Stream<Path> entries = java.nio.file.Files.list(dir)) {
			return entries.filter(java.nio.file.Files::isRegularFile).count();
		} catch (IOException unreadable) {
			return -1;
		}
	}
	private static Path remapOne(Path jar, Path cacheDir) throws IOException {
		// Read the refmaps first: they are the only place a guest says what its Yarn-spelled names mean, and the class
		// pass below needs that dictionary while it is still rewriting.
		yarn = readYarnNames(Files.readAllBytes(jar));
		try {
			return remapWithYarn(jar, cacheDir);
		} finally {
			yarn = YarnNames.EMPTY;
		}
	}

	private static Path remapWithYarn(Path jar, Path cacheDir) throws IOException {
		byte[] bytes = Files.readAllBytes(jar);
		// The cache is keyed by the guest's content, so on its own it never notices that the rewriter changed: a jar
		// written by yesterday's remapper was handed back byte-for-byte today, and the fix under test could not be
		// seen at all. Measured while chasing @Shadow field field_1887: the runtime copy still said field_1887 while
		// the same jar rewritten by hand said resourcePacks, and the only difference was the cached file's mtime.
		// The salt makes a rewriter change invalidate everything; the table's own identity does the same for a
		// regenerated mappings file, since every answer here comes from that table.
		String key = digest(fingerprintOf(bytes));
		Path target = cacheDir.resolve(key + '-' + jar.getFileName());
		if (Files.isRegularFile(target) && Files.size(target) > 0) {
			jarsReused++;
			readClassRenamesFrom(target);
			return target;
		}
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
					// A refmap's values are selectors - "Lowner;member(args)ret" - and its keys are the same selectors
					// spelled exactly as the mod's annotation spells them. Mixin looks an annotation up in that map by
					// its literal text, so a refmap goes through rewriteRefmapSelectors, which rewrites the values and
					// copies the keys through untouched.
					//
					// MEASURED, AND OFF BY DEFAULT ON PURPOSE. Keeping the keys as written is what makes a guest
					// @At anchor resolvable at all: the previous two-pass rewrite renamed the keys to mojmap while the
					// annotation kept its yarn text, so Mixin's lookup could never hit and 172 handler(s) across 28
					// mod(s) stayed unattached. Turning this on took that to 88 and InvalidMixinException from 8 to 4 -
					// and then took the integrated server down, because handlers that had never attached started
					// running: fabric-networking-api-v1's login addon calls MinecraftServer.method_3773()I, the
					// shipped table has no row for that member, and the failure is a NoSuchMethodError in the server
					// tick loop. The fix for that is the table (its class_1132 row names IntegratedServer and carries
					// 11 of that class's members), not this flag; until the table is repaired the switch stays off so
					// the client keeps starting. -Dforbric.refmapKeys=true is the A/B lever.
					String mapped = name.endsWith("-refmap.json") && REFMAP_KEYS
							? rewriteRefmapSelectors(text, names)
							: (name.endsWith("-refmap.json")
									? mapClassNamesInText(mapRefmapValues(text, names), names)
									: mapClassNamesInText(text, names));
					if (!mapped.equals(text)) rewritten = mapped.getBytes(StandardCharsets.UTF_8);
				}
				ZipEntry copy = new ZipEntry(rewrittenName);
				copy.setTime(0L);
				out.putNextEntry(copy);
				out.write(rewritten);
				out.closeEntry();
			}
		}
		// Pass 2: renameOverrides learned (owner, name, descriptor) -> real name class by class, but a call site
		// that goes through an interface is not necessarily in a class rewritten after that interface. Re-read the
		// partial jar once and apply what was learned; measured, ModResourcePackCreator.loadPacks called
		// ModResourcePack.method_56926() and the world refused to open with NoSuchMethodError.
		if (!OVERRIDES.isEmpty()) {
			java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
			ZipOutputStream rewritten = new ZipOutputStream(buffer);
			try (ZipInputStream again = new ZipInputStream(Files.newInputStream(partial))) {
				for (ZipEntry entry; (entry = again.getNextEntry()) != null; ) {
					if (entry.isDirectory()) continue;
					byte[] content = again.readAllBytes();
					if (entry.getName().endsWith(".class")) content = applyOverrides(content);
					ZipEntry copy = new ZipEntry(entry.getName());
					copy.setTime(0L);
					rewritten.putNextEntry(copy);
					rewritten.write(content);
					rewritten.closeEntry();
				}
			}
			rewritten.finish();
			rewritten.close();
			Files.write(partial, buffer.toByteArray());
			OVERRIDES.clear();
		}
		Files.move(partial, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		jarsWritten++;
		classesRewritten += classes;
		namesMapped += names.size();
		return target;
	}

	private static final String MANIFEST = "META-INF/MANIFEST.MF";

	/** fabric-registry-sync's marker interface: the one Fabric asks a registry for by {@code instanceof}. */
	private static final String LISTENABLE = "net/fabricmc/fabric/impl/registry/sync/ListenableRegistry";

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

	/**
	 * Whether a refmap's keys keep the mod's own spelling while only its values are rewritten.
	 *
	 * <p>On by default since the namespace table was repaired and the handlers were given a guard. A refmap's
	 * {@code mappings} are keyed by the selector the mixin's own annotation carries, so rewriting the key while the
	 * annotation keeps its Yarn spelling makes Mixin look up a selector nobody wrote: the handler never resolves and
	 * never runs. Measured on cold boots, keys kept: {@code injection warning} 172 -> 87 and unattached handlers
	 * 169 -> 85 on this line.
	 *
	 * <p>Turning it on was refused twice before, for a measured reason and only that: the handlers that start
	 * resolving also start executing, and a guest jar whose member has no row in the shipped table keeps the
	 * intermediary name, so the JVM raised {@code NoSuchMethodError} at first use — once, from
	 * {@code ServerLoginNetworkAddon.sendCompressionPacket} through {@code ServerLoginNetworkHandlerMixin}, which took
	 * the integrated server down. Both halves of that are now answered: the table's 34 dropped classes were joined
	 * (Round 148, {@code MinecraftServer.method_3773 = getCompressionThreshold} among them), and
	 * {@code MixinUnmappedMemberHandlers} leaves out any handler whose own body still calls a member with no row,
	 * instead of letting it attach and fail in game time.
	 *
	 * <p>{@code -Dforbric.refmapKeys=false} restores the old behaviour for an A/B, and is what a boot that crashes
	 * inside a guest handler should be retried with.
	 */
	private static final boolean REFMAP_KEYS =
			Boolean.parseBoolean(System.getProperty("forbric.refmapKeys", "true"));

	/** One class through {@link ClassRemapper}, with every name the resolver knows. */
	/**
	 * Rewrites the selectors a guest wrote in its own injection annotations into the ones the base answers to.
	 *
	 * <p>A mixin's {@code @Inject(method = "updateEntries")} names a Yarn member; the base has no such member, and the
	 * only statement of what the guest meant is the jar's own refmap. Rewriting the annotation here means our anchor
	 * analysis and Mixin's own binding both read the same selector, so a mixin cannot be judged unfit by us and then be
	 * silently relaxed away — measured on 1.21.1 as {@code IllegalStateException: Item group has no page} when
	 * opening the creative inventory.
	 */
	private static byte[] rewriteInjectionSelectors(byte[] bytes, java.util.Set<String> seen) {
		if (yarn.selectors.isEmpty()) return bytes;
		ClassNode node = new ClassNode();
		// Frames are kept: renaming a string constant moves no instruction.
		new ClassReader(bytes).accept(node, 0);
		// A mixin whose own refmap carries no section still needs this pass: measured on fabric-registry-sync-v0's
		// SimpleRegistryMixin, whose selector keeps a Yarn class inside its descriptor and has no refmap row at all,
		// so the refmap cannot answer it and Mixin is left reading the string exactly as the author wrote it.
		Map<String, String> mine = yarn.selectors.getOrDefault(node.name, Map.of());
		List<String> targets = new ArrayList<>();
		for (List<org.objectweb.asm.tree.AnnotationNode> list : annotationLists(node)) {
			if (list == null) continue;
			for (org.objectweb.asm.tree.AnnotationNode annotation : list) {
				if (!"Lorg/spongepowered/asm/mixin/Mixin;".equals(annotation.desc)) continue;
				collectTargets(annotation, targets);
			}
		}
		boolean changed = false;
		for (org.objectweb.asm.tree.MethodNode method : node.methods) {
			for (List<org.objectweb.asm.tree.AnnotationNode> list : annotationLists(method)) {
				if (list == null) continue;
				for (org.objectweb.asm.tree.AnnotationNode annotation : list) {
					if (!isInjectionAnnotation(annotation.desc)) continue;
					if (annotation.values == null) continue;
					// The method selectors are settled first: the @At pass below asks whether the method it injects into
					// actually makes the call it is about to name, and that question only has an answer once the
					// method selector reads in the base's spelling.
					List<String> injected = new ArrayList<>(2);
					for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
						if (!"method".equals(annotation.values.get(i))) continue;
						Object written = annotation.values.get(i + 1);
						if (written instanceof String single) {
							String fixed = resolveSelector(mine, single, targets);
							if (fixed == null) fixed = single;
							String lambda = renumberLambda(fixed, mine, targets, annotation, seen);
							if (!lambda.equals(single)) {
								annotation.values.set(i + 1, lambda);
								changed = true;
							}
							injected.add(lambda);
						} else if (written instanceof List<?> many) {
							@SuppressWarnings("unchecked")
							List<String> selectors = (List<String>) many;
							for (int s = 0; s < selectors.size(); s++) {
								String fixed = resolveSelector(mine, selectors.get(s), targets);
								if (fixed == null) fixed = selectors.get(s);
								String lambda = renumberLambda(fixed, mine, targets, annotation, seen);
								if (!lambda.equals(selectors.get(s))) {
									selectors.set(s, lambda);
									changed = true;
								}
								injected.add(lambda);
							}
						}
					}
					// Moving even just the CLASS NAMES of an @At selector is behind a switch: Round 203 measured it on
					// the merged base and it cost 19 handlers. See mapAtSelectorClasses.
					if (Boolean.getBoolean("forbric.atTargetClasses")
							&& mapAtSelectorClasses(annotation)) {
						changed = true;
					}
					// The @At half stays behind a switch for the reason Round 200 measured: answering it from the
					// refmap alone turned 65 warnings into 87 and 63 unattached handlers into 85, because a target that
					// no longer matches a call site is a handler that stops running. Round 201's guard is the fix - see
					// atTarget - and it is measured against that same boot.
					if (Boolean.getBoolean("forbric.rewriteAtTargets")
							&& rewriteAtTargets(annotation, mine, targets, injected, seen)) {
						changed = true;
					}
				}
			}
		}
		if (!changed) return bytes;
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	/**
	 * Whether an annotation carries Mixin's {@code method} / {@code @At} grammar.
	 *
	 * <p>MixinExtras' injectors use exactly the same two fields as Mixin's own, and a jar full of them was being
	 * skipped whole: measured on fabric-entity-events-v1's {@code LivingEntityMixin}, {@code
	 * @WrapOperation(method = ["onDeath"], at = @At(target = "Lnet/minecraft/entity/Entity;onKilledOther(…)"))}
	 * came out of the rewrite byte-for-byte as authored — Yarn name, Yarn target — while the {@code @Inject} method
	 * beside it was rewritten correctly. That is one of the four sleep-event handlers this line reports as never
	 * attaching, and the annotation format says nothing about why it should be treated differently.
	 */
	private static boolean isInjectionAnnotation(String desc) {
		if (desc == null) return false;
		return desc.startsWith("Lorg/spongepowered/asm/mixin/injection/")
				|| desc.startsWith("Lcom/llamalad7/mixinextras/");
	}

	/**
	 * Rewrites the {@code target} of every {@code @At} nested in an injection annotation.
	 *
	 * <p>A guest writes INVOKE targets in intermediary form, e.g. {@code @Redirect(at = @At(value = "INVOKE", target =
	 * "Lnet/minecraft/class_1309;method_6043(Lnet/minecraft/world/damagesource/DamageSource;)V"))}, and Mixin refuses
	 * the qualified intermediary spelling outright: measured on fabric-entity-events-v1's {@code LivingEntityMixin},
	 * {@code @Redirect annotation on beforeEntityKilled specifies a target class 'net/minecraft/class_1309', which is
	 * not supported}. Sixty such failures in one boot came from this one hole.
	 *
	 * <p>An {@code @At} target is also answered by the guest's own refmap, which is the only statement of what a Yarn
	 * spelling meant: measured on the same class, {@code target = "net/minecraft/world/World.sendEntityStatus(…)"} has
	 * the refmap key of the same text and the value {@code Lnet/minecraft/world/level/Level;broadcastEntityEvent(…)}.
	 * Only that key is read here — nothing is computed and nothing is written that the jar did not say.
	 */
	private static boolean rewriteAtTargets(org.objectweb.asm.tree.AnnotationNode annotation,
			Map<String, String> mine, List<String> targets, List<String> injected, java.util.Set<String> seen) {
		if (annotation.values == null) return false;
		boolean changed = false;
		for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
			Object value = annotation.values.get(i + 1);
			if (value instanceof org.objectweb.asm.tree.AnnotationNode nested) {
				if (nested.desc != null && nested.desc.endsWith("/At;") && nested.values != null) {
					for (int n = 0; n + 1 < nested.values.size(); n += 2) {
						if (!"target".equals(nested.values.get(n))) continue;
						if (!(nested.values.get(n + 1) instanceof String text)) continue;
						String fixed = atTarget(text, mine, targets, injected, seen);
						if (fixed != null && !fixed.equals(text)) {
							nested.values.set(n + 1, fixed);
							changed = true;
						}
					}
				}
				if (rewriteAtTargets(nested, mine, targets, injected, seen)) changed = true;
			} else if (value instanceof List<?> many) {
				for (Object each : many) {
					if (each instanceof org.objectweb.asm.tree.AnnotationNode nested
							&& rewriteAtTargets(nested, mine, targets, injected, seen)) {
						changed = true;
					}
				}
			}
		}
		return changed;
	}

	/**
	 * Maps the CLASS NAMES an {@code @At} selector spells — the owner and the descriptor's types — and nothing else.
	 *
	 * <p><b>Measured, and it lost.</b> Round 203 ran this unconditionally on the merged base: 63 selectors had a class
	 * name moved, and the session went from 58 injection warnings to 77 while {@code required injection unavailable}
	 * went 44 to 60 — {@code fabric-entity-events-v1} alone doubled from 7 to 14. A class name is not a guess
	 * ({@link #mapInternal} answers 1:1 or not at all, and members are left byte for byte), but Mixin remaps an
	 * annotation's selectors through the mod's own refmap, and a selector already in the spelling Mixin expects is
	 * not improved by being rewritten to the same class under another name: the points that moved stopped matching.
	 * So it stays behind {@code -Dforbric.atTargetClasses=true}, off by default, with its numbers written down.
	 *
	 * <p>The one thing it did NOT fix is why it was written: fabric-networking-api-v1's
	 * {@code CustomPayloadPacketCodecMixin} still reads
	 * {@code @At target="Lnet/minecraft/network/packet/CustomPayload$1;getCodec(...)"} afterwards, because that Yarn
	 * spelling is in no refmap row and no table row, so there is nothing to map it through.
	 */
	private static boolean mapAtSelectorClasses(org.objectweb.asm.tree.AnnotationNode annotation) {
		if (annotation.values == null) return false;
		boolean changed = false;
		for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
			Object value = annotation.values.get(i + 1);
			if (value instanceof org.objectweb.asm.tree.AnnotationNode nested) {
				if (nested.desc != null && nested.desc.endsWith("/At;") && nested.values != null) {
					for (int n = 0; n + 1 < nested.values.size(); n += 2) {
						if (!"target".equals(nested.values.get(n))) continue;
						if (!(nested.values.get(n + 1) instanceof String text)) continue;
						String mapped = withMappedClasses(text);
						if (!mapped.equals(text)) {
							nested.values.set(n + 1, mapped);
							AT_CLASSES_MAPPED.incrementAndGet();
							changed = true;
						}
					}
				}
				if (mapAtSelectorClasses(nested)) changed = true;
			} else if (value instanceof List<?> many) {
				for (Object each : many) {
					if (each instanceof org.objectweb.asm.tree.AnnotationNode nested && mapAtSelectorClasses(nested)) {
						changed = true;
					}
				}
			}
		}
		return changed;
	}

	/** One selector with only its class names moved to the base's spelling. Members are left byte for byte. */
	private static String withMappedClasses(String selector) {
		String mapped = officialClassesInside(selector);
		if (mapped.startsWith("L")) return mapped;
		int paren = mapped.indexOf('(');
		int limit = paren < 0 ? mapped.length() : paren;
		int dot = mapped.lastIndexOf('.', limit - 1);
		if (dot <= 0) return mapped;
		String owner = mapped.substring(0, dot);
		if (owner.indexOf('/') < 0) return mapped;
		return mapInternal(owner) + mapped.substring(dot);
	}

	/**
	 * One {@code @At} target in the base's spelling, or null when the jar has nothing better than what it wrote.
	 *
	 * <p>The guest's refmap answers first, by exact text: the key is the selector as the annotation carries it and the
	 * value is the selector the jar means, so a Yarn target needs no external table. Unlike an {@code @Inject} method
	 * selector, an {@code @At} target keeps its owner — Mixin resolves the invocation against that owner — so the
	 * value is returned whole rather than stripped.
	 *
	 * <p><b>An answer is only written when the method being injected into really makes that call.</b> Round 200
	 * measured the unguarded version and it was worse than doing nothing: 65 injection warnings became 87 and 63
	 * unattached handlers became 85, because rewriting an {@code @At} that had been matching all along moved the
	 * target off its call site, and a handler whose target matches nothing is a handler that never runs. The guard
	 * asks the base's own bytecode the only question that separates the two cases: does the method named by the
	 * annotation's (already rewritten) {@code method} selector contain an invocation of exactly this owner, name and
	 * descriptor? Yarn-spelled targets can never match a base call site, so they answer "no" and are left alone;
	 * a target the jar genuinely meant answers "yes" and is written.
	 */
	private static String atTarget(String text, Map<String, String> mine, List<String> targets,
			List<String> injected, java.util.Set<String> seen) {
		// A refmap answers in intermediary spelling — its values are the author's names translated one step, not the
		// base's — so the answer goes through the same rewriting a refmap value gets before anything compares it with
		// the base's own bytecode. Measured without this step: every answer read
		// Lnet/minecraft/class_2680;method_26215()Z, matched no call site, and all 65 were refused.
		String declared = mine.get(text);
		String answer = null;
		if (declared != null && !declared.equals(text)) {
			String official = rewriteRefmapValue(text, declared, seen);
			if (official.startsWith("L") && official.indexOf(';') > 0) answer = official;
		}
		if (answer == null) {
			answer = officialTarget(text);
		}
		if (answer == null) return null;
		int code = whyItDoesNotCall(injected, targets, answer);
		if (code == 0) {
			AT_TARGETS_WRITTEN.incrementAndGet();
			return answer;
		}
		AT_TARGETS_REFUSED.incrementAndGet();
		AT_REFUSALS.incrementAndGet(code);
		if (code != 0 && AT_REFUSALS.get(code) <= 8 && Boolean.getBoolean("forbric.rewriteAtTargets.verbose")) {
			ForbricLog.info("[Forbric/Remap] @At left alone (code %d): answer=%s injected=%s mixed=%s", code, answer,
					injected, targets);
		}
		return null;
	}

	/** {@code lambda$enclosing$ordinal}, with or without a descriptor and with or without an {@code Lowner;} in front. */
	private static final java.util.regex.Pattern LAMBDA_SELECTOR = java.util.regex.Pattern
			.compile("(?:L([^;]+);)?(lambda\\$[A-Za-z0-9_$]+\\$)(\\d+)(\\(.*)?$");

	/** How many {@code @At} selectors had a class name — and only a class name — moved to the base's spelling. */
	private static final java.util.concurrent.atomic.AtomicInteger AT_CLASSES_MAPPED =
			new java.util.concurrent.atomic.AtomicInteger();

	private static final java.util.concurrent.atomic.AtomicInteger LAMBDAS_POINTED =
			new java.util.concurrent.atomic.AtomicInteger();

	/**
	 * Points a {@code lambda$<enclosing>$<ordinal>} selector at the copy this base actually compiled.
	 *
	 * <p>A lambda's own name carries the order javac emitted it in, and the merged base is built from two patched
	 * source trees at once: measured, {@code net.minecraft.world.entity.LivingEntity} on the 1.21.1 base carries
	 * {@code lambda$stopSleeping$11} and {@code lambda$stopSleeping$12} while the table (and every mod compiled
	 * against vanilla) says {@code lambda$stopSleeping$9}. Nothing is declared under the name the jar asks for, so
	 * Mixin reports the anchor missing and the handler never attaches - measured on fabric-entity-events-v1, four of
	 * its eight sleep handlers inject into exactly such a name.
	 *
	 * <p>What this does not do: guess blindly. One candidate of the same descriptor is taken as the answer; several
	 * candidates are separated by asking which of them contains the call the annotation's {@code @At} names; when that
	 * separates nothing the lowest ordinal is taken and the count is reported, because the remaining copies are the
	 * same source lambda compiled in two patch trees - the same code, not rival meanings.
	 */
	private static String renumberLambda(String selector, Map<String, String> mine, List<String> targets,
			org.objectweb.asm.tree.AnnotationNode annotation, java.util.Set<String> seen) {
		MethodIndex index = minecraftIndex;
		if (index == null || selector == null || selector.indexOf("lambda$") < 0) return selector;
		java.util.regex.Matcher m = LAMBDA_SELECTOR.matcher(selector);
		if (!m.matches()) return selector;
		String owner = m.group(1);
		String stem = m.group(2);
		String asked = stem + m.group(3);
		// A selector may name the lambda with no descriptor at all ("lambda$tick$32"); that form means "any shape",
		// so every question below has to read a null descriptor as a wildcard.
		String descriptor = m.group(4);
		List<String> owners = new ArrayList<>(2);
		if (owner != null) {
			owners.add(owner);
		} else {
			for (String mixed : targets) owners.add(mapInternal(mixed.replace('.', '/')));
		}
		for (String candidate : owners) if (index.hasMethod(candidate, asked, descriptor)) return selector;
		Map<String, List<String>> found = new java.util.LinkedHashMap<>();
		for (String candidate : owners) {
			List<String> names = index.lambdasNamed(candidate, stem, descriptor);
			if (!names.isEmpty()) found.put(candidate, names);
		}
		if (found.isEmpty()) return selector;
		List<String> answers = atAnswers(annotation, mine, seen);
		String chosenOwner = null;
		String chosen = null;
		for (Map.Entry<String, List<String>> e : found.entrySet()) {
			List<String> names = e.getValue();
			if (names.size() == 1) {
				chosenOwner = e.getKey();
				chosen = names.get(0);
				break;
			}
			List<String> matching = new ArrayList<>(2);
			for (String at : answers) {
				int semicolon = at.indexOf(';');
				if (semicolon <= 0) continue;
				String atOwner = at.substring(1, semicolon);
				String rest = at.substring(semicolon + 1);
				int paren = rest.indexOf('(');
				if (paren < 0) continue;
				String atName = rest.substring(0, paren);
				String atDescriptor = rest.substring(paren);
				for (String name : names) {
					if (index.methodCalls(e.getKey(), name, descriptor, atOwner, atName, atDescriptor) == 0
							&& !matching.contains(name)) {
						matching.add(name);
					}
				}
			}
			chosenOwner = e.getKey();
			chosen = matching.size() == 1 ? matching.get(0) : names.get(0);
			break;
		}
		if (chosen == null) return selector;
		LAMBDAS_POINTED.incrementAndGet();
		// A null descriptor is the "any shape" form, and it has to stay absent: concatenating it wrote the literal
		// word "null" into the selector, which is how nine renumbered hooks became "lambda$checkBedExists$10null" and
		// attached nothing on the boot that measured them.
		return (owner == null ? "" : "L" + chosenOwner + ';') + chosen + (descriptor == null ? "" : descriptor);
	}

	/** Every {@code @At} target of this annotation, in the base's spelling, for the tie-break above. */
	private static List<String> atAnswers(org.objectweb.asm.tree.AnnotationNode annotation, Map<String, String> mine,
			java.util.Set<String> seen) {
		List<String> answers = new ArrayList<>(2);
		if (annotation.values == null) return answers;
		for (int i = 0; i + 1 < annotation.values.size(); i += 2) {
			Object value = annotation.values.get(i + 1);
			if (value instanceof org.objectweb.asm.tree.AnnotationNode nested) {
				collectAtAnswer(nested, mine, seen, answers);
			} else if (value instanceof List<?> list) {
				for (Object element : list) {
					if (element instanceof org.objectweb.asm.tree.AnnotationNode nested) {
						collectAtAnswer(nested, mine, seen, answers);
					}
				}
			}
		}
		return answers;
	}

	private static void collectAtAnswer(org.objectweb.asm.tree.AnnotationNode nested, Map<String, String> mine,
			java.util.Set<String> seen, List<String> answers) {
		if (nested.desc == null || !nested.desc.endsWith("/At;") || nested.values == null) return;
		for (int n = 0; n + 1 < nested.values.size(); n += 2) {
			if (!"target".equals(nested.values.get(n))) continue;
			if (!(nested.values.get(n + 1) instanceof String text)) continue;
			String declared = mine.get(text);
			String answer = declared == null || declared.equals(text) ? officialTarget(text)
					: rewriteRefmapValue(text, declared, seen);
			if (answer != null && answer.startsWith("L") && answer.indexOf(';') > 0) answers.add(answer);
		}
	}

	/**
	 * Reads the base class with code included — the shared {@link MethodIndex} skips code on purpose, so this pass
	 * keeps its own cache. A method that cannot be read, or a target that is not an invocation (a field or a
	 * constructor pattern), answers "no": an answer nobody can check is not written.
	 *
	 * <p>Why the guard refused, by code: 0 when it says yes, 1 with no base index, 2 with nothing to check against, 3
	 * when the mixed class cannot be read, 4 when no method of that name and shape is there, 5 when the method does not
	 * make that call. The split exists because "the guard refused all 65" reads the same for five very different
	 * causes.
	 */
	private static int whyItDoesNotCall(List<String> injected, List<String> targets, String target) {
		MethodIndex index = minecraftIndex;
		if (index == null) return 1;
		if (injected.isEmpty() || targets.isEmpty() || target == null) return 2;
		if (!target.startsWith("L")) return 2;
		int close = target.indexOf(';');
		if (close <= 0) return 2;
		String owner = target.substring(1, close);
		String rest = target.substring(close + 1);
		int paren = rest.indexOf('(');
		if (paren < 0) return 2;
		int closeParen = rest.indexOf(')', paren);
		if (closeParen < 0) return 2;
		String name = rest.substring(0, paren);
		// The return type is part of the descriptor and sits past the closing paren: an @At target reads
		// {@code Lowner;name(parameters)return}, so cutting at ')' leaves {@code ()} and matches no call at all.
		String described = rest.substring(paren);
		// A readable target that says "no such call" is better evidence than a target this pass cannot read at all, so
		// the first answer that is not "could not read" is the one reported.
		int code = -1;
		for (String written : injected) {
			String bare = unqualified(written);
			int methodParen = bare.indexOf('(');
			if (methodParen < 0) continue;
			int methodClose = bare.indexOf(')', methodParen);
			if (methodClose < 0) continue;
			String methodName = bare.substring(0, methodParen);
			String methodDescriptor = bare.substring(methodParen, methodClose + 1);
			for (String mixed : targets) {
				String internal = mapInternal(mixed.replace('.', '/'));
				int answer = index.whyNot(internal, methodName, methodDescriptor, owner, name, described);
				if (answer == 0) return 0;
				if (code < 0 || (code == 3 && answer != 3)) code = answer;
			}
		}
		return code < 0 ? 3 : code;
	}

	/** An {@code @At} target rewritten into the base's spelling, or null when it is already there. */
	private static String officialTarget(String target) {
		if (target == null || target.isEmpty()) return null;
		if (!target.startsWith("L") || target.indexOf(';') <= 0) {
			if (!target.contains("class_")) return null;
			String slash = target.replace('.', '/');
			String mapped = mapInternal(slash);
			return mapped.equals(slash) ? null : mapped.replace('/', '.');
		}
		int close = target.indexOf(';');
		String owner = target.substring(1, close);
		String rest = target.substring(close + 1);
		int paren = rest.indexOf('(');
		int colon = rest.indexOf(':');
		String officialOwner = mapInternal(owner);
		if (paren >= 0) {
			String name = rest.substring(0, paren);
			String descriptor = rest.substring(paren);
			String official = KernelMappingResolver.INSTANCE.mapMethodName("intermediary", owner, name, descriptor);
			if (official.equals(name)) {
				String ownerless = KernelMappingResolver.INSTANCE.mapMethodNameWithoutOwner("intermediary", name);
				if (ownerless != null && !ownerless.isEmpty() && !ownerless.equals(name)) official = ownerless;
			}
			if (officialOwner.equals(owner) && official.equals(name)) return null;
			String officialParameters = officialDescriptor(descriptor);
			if (!baseDeclares(officialOwner, official, officialParameters)) return null;
			return "L" + officialOwner + ';' + official + officialParameters;
		}
		if (colon > 0) {
			String name = rest.substring(0, colon);
			String descriptor = rest.substring(colon);
			String official = KernelMappingResolver.INSTANCE.mapFieldName("intermediary", owner, name, descriptor);
			if (officialOwner.equals(owner) && official.equals(name)) return null;
			return "L" + officialOwner + ';' + official + officialDescriptor(descriptor);
		}
		return officialOwner.equals(owner) ? null : "L" + officialOwner + ';' + rest;
	}

	/** Whether the base really declares this member with this shape, so an answer can be written. */
	private static boolean baseDeclares(String owner, String name, String descriptor) {
		MethodIndex index = minecraftIndex;
		if (index == null || descriptor == null || descriptor.isEmpty()) return true;
		for (BaseMethod method : index.declaredByType(owner, descriptor)) {
			if (method.name().equals(name)) return true;
		}
		return false;
	}

	/**
	 * Drops the owner from a selector; {@code @Inject}'s {@code method} names a method of the target class.
	 *
	 * <p>Mixin reads an owner-qualified selector in {@code @Inject} as a request to target another class and refuses
	 * it outright: measured on {@code CreativeInventoryScreenMixin}, {@code method = "Lnet/minecraft/class_481;
	 * method_25426()V"} produced {@code InvalidInjectionException: @Inject annotation on init specifies a target class
	 * 'net/minecraft/class_481', which is not supported}. The bare form is what the annotation means.
	 *
	 * <p>The owner is dropped even when it does not match one of this mixin's targets. Measured on
	 * fabric-rendering-v1's {@code WorldRendererMixin}, whose refmap answered with {@code class_12098} for a method
	 * that lives on {@code LevelRenderer}: keeping the qualifier because it "belongs to some other class" made Mixin
	 * reject the whole mixin and drop every one of its nine injections, while the bare name leaves a single missing
	 * anchor that the caller can report and skip.
	 */
	private static String unqualified(String selector) {
		if (selector == null || !selector.startsWith("L")) return selector;
		int close = selector.indexOf(';');
		if (close < 0) return selector;
		String rest = selector.substring(close + 1);
		if (rest.indexOf('(') < 0) return selector;
		return rest;
	}

	/**
	 * The spelling to write for one authored selector, or null when it already reads that way.
	 *
	 * <p>Three sources answer in order, and the strip happens after all of them: the refmap (what the author meant),
	 * the table read straight off the selector (for a mixin whose refmap key the refmap itself does not carry), and
	 * the string as written. The last one matters: measured on fabric-rendering-v1's {@code WorldRendererMixin},
	 * {@code onReload} already held {@code Lnet/minecraft/client/render/WorldRenderer;reload()V} — official, but
	 * qualified — and because neither of the first two sources had anything to add, the qualifier survived and Mixin
	 * rejected the whole mixin with "specifies a target class 'net/minecraft/client/render/WorldRenderer', which is
	 * not supported", dropping all nine of its injections.
	 */
	private static String resolveSelector(Map<String, String> mine, String written) {
		return resolveSelector(mine, written, List.of());
	}

	/**
	 * The same, told which classes the mixin mixes into, so a selector whose member keeps its own name can still be
	 * checked against the class that has to declare it.
	 */
	private static String resolveSelector(Map<String, String> mine, String written, List<String> targets) {
		if (written == null || written.isEmpty()) return null;
		String answer = selectorFrom(mine, written, targets);
		if (answer == null) answer = officialSelector(written, targets);
		if (answer == null) answer = written;
		String bare = unqualified(answer);
		return bare.equals(written) ? null : bare;
	}

	/** One authored selector, or a semicolon-separated run of them, in the base's own spelling. */
	private static String selectorFrom(Map<String, String> mine, String written) {
		return selectorFrom(mine, written, List.of());
	}

	private static String selectorFrom(Map<String, String> mine, String written, List<String> targets) {
		if (written == null || written.isEmpty()) return null;
		List<String> parts = splitSelectors(written);
		if (parts.size() == 1) {
			return answerFor(mine, written, targets);
		}
		StringBuilder out = new StringBuilder();
		boolean any = false;
		for (String part : parts) {
			String answer = answerFor(mine, part, targets);
			if (answer == null) answer = part;
			else any = true;
			if (out.length() > 0) out.append(';');
			out.append(answer);
		}
		return any ? out.toString() : null;
	}

	/**
	 * The authored selectors in one annotation value, split on the semicolons that separate them.
	 *
	 * <p>Not {@code String.split(";")}: every descriptor that takes an object holds a semicolon of its own, so a
	 * selector like {@code <init>(Lnet/minecraft/registry/RegistryKey;…Z)V} was cut into three pieces, none of which
	 * the refmap had a row for, and the whole answer was "nothing changed". Measured on fabric-registry-sync-v0's
	 * {@code SimpleRegistryMixin}, whose refmap holds the exact row that was never looked up.
	 */
	private static List<String> splitSelectors(String written) {
		List<String> parts = new ArrayList<>(2);
		int depth = 0;
		int from = 0;
		for (int i = 0; i < written.length(); i++) {
			char c = written.charAt(i);
			if (c == '(') {
				depth++;
			} else if (c == ')') {
				depth--;
			} else if (c == ';' && depth == 0) {
				parts.add(written.substring(from, i));
				from = i + 1;
			}
		}
		parts.add(written.substring(from));
		return parts;
	}

	/**
	 * The table's answer for one selector, or null when it has none that differs.
	 *
	 * <p>A jar's refmap frequently records the intermediary selector on both sides of its own dictionary, so a hit
	 * is not yet an answer: measured on ItemGroupMixin, the entry is
	 * {@code "Lnet/minecraft/class_1761;method_47306(…)V": "Lnet/minecraft/class_1761;method_47306(…)V"}. The
	 * selector only becomes usable once the table has turned the member name into the official one.
	 */
	private static String answerFor(Map<String, String> mine, String written) {
		return answerFor(mine, written, List.of());
	}

	private static String answerFor(Map<String, String> mine, String written, List<String> targets) {
		String candidate = mine.get(written);
		if (candidate == null) candidate = mine.get(memberOf(written));
		if (candidate == null) return officialSelector(written, targets);
		String official = officialSelector(candidate, targets);
		if (official != null) return official;
		// Never hand back a selector the table could not finish. An intermediary owner in an @Inject selector is a
		// hard Mixin failure ("specifies a target class …, which is not supported"), while leaving the authored name
		// in place lets the refmap answer it exactly as it did before this pass existed.
		return looksIntermediary(candidate) ? null : candidate;
	}

	/** Whether a selector still spells a class or member in intermediary names. */
	private static boolean looksIntermediary(String selector) {
		if (selector.contains("class_")) return true;
		String head = selector;
		int paren = head.indexOf('(');
		if (paren >= 0) head = head.substring(0, paren);
		int semicolon = head.lastIndexOf(';');
		if (semicolon >= 0) head = head.substring(semicolon + 1);
		return INTERMEDIARY_MEMBER.matcher(head).matches();
	}

	/** An intermediary selector rewritten into the official spelling, or null when it is not one. */
	private static String officialSelector(String selector) {
		return officialSelector(selector, List.of());
	}

	/**
	 * The same, plus the one case an intermediary member name is not the whole story: a selector whose member is
	 * already official but whose descriptor still carries Yarn class names.
	 *
	 * <p>Measured on fabric-registry-sync-v0's {@code SimpleRegistryMixin}: the annotation reads
	 * {@code <init>(Lnet/minecraft/registry/RegistryKey;Lcom/mojang/serialization/Lifecycle;Z)V} while the base
	 * declares {@code MappedRegistry(ResourceKey, Lifecycle, boolean)}. The callback descriptor beside it had already
	 * been mapped — only the string constant had not — so the mixin reported {@code 14 of 15 anchors} and that last
	 * injection silently did nothing. Nothing here renames a member: when the name is not intermediary it is written
	 * back unchanged, and the rewrite is refused unless the class that has to declare it confirms the shape.
	 */
	private static String officialSelector(String selector, List<String> targets) {
		if (selector == null || selector.isEmpty()) return null;
		String owner = null;
		String rest = selector;
		if (selector.startsWith("L")) {
			int close = selector.indexOf(';');
			if (close < 0) return null;
			owner = selector.substring(1, close);
			rest = selector.substring(close + 1);
		}
		int paren = rest.indexOf('(');
		if (paren < 0) return null;
		String name = rest.substring(0, paren);
		String descriptor = rest.substring(paren);
		if (!INTERMEDIARY_MEMBER.matcher(name).matches()) {
			// The member keeps the spelling its author wrote; only the classes inside the descriptor were Yarn.
			String officialParameters = officialDescriptor(descriptor);
			if (officialParameters.equals(descriptor)) return null;
			if (owner != null) {
				String officialOwner = mapInternal(owner);
				if (!baseDeclares(officialOwner, name, officialParameters)) return null;
				return "L" + officialOwner + ';' + name + officialParameters;
			}
			for (String target : targets) {
				if (baseDeclares(mapInternal(target.replace('.', '/')), name, officialParameters)) {
					return name + officialParameters;
				}
			}
			return null;
		}
		// A bare intermediary member name names no owner, and Mixin's @Inject selector cannot carry one, so there is
		// nothing to check the answer against and nothing is written. Unchanged from before this overload existed.
		if (owner == null) return null;
		String officialName = KernelMappingResolver.INSTANCE.mapMethodName("intermediary", owner, name, descriptor);
		if (officialName.equals(name)) {
			// The selector may name a subclass of the class that declares the member: method_25426 (`init`) is
			// recorded under class_437 (Screen) alone, so an owner-aware lookup answers "unchanged" while the
			// owner-less table knows the name. Measured on CreativeInventoryScreenMixin, where writing the
			// intermediary result was a hard Mixin failure.
			String ownerless = KernelMappingResolver.INSTANCE.mapMethodNameWithoutOwner("intermediary", name);
			if (ownerless == null || ownerless.isEmpty() || ownerless.equals(name)) return null;
			officialName = ownerless;
		}
		String officialOwner = mapInternal(owner);
		String officialParameters = officialDescriptor(descriptor);
		// The table's keys carry no descriptor, so an overloaded name can map to a name the base does not declare with
		// this shape. Only an answer the base confirms may be written: measured on the 1.21.1 line, writing the
		// unconfirmed ones turned refmap-resolvable injections into hard InvalidInjectionException failures.
		if (!baseDeclares(officialOwner, officialName, officialParameters)) return null;
		return "L" + officialOwner + ';' + officialName + officialParameters;
	}

	/** Rewrites every intermediary class token inside a descriptor. */
	private static String officialDescriptor(String descriptor) {
		Matcher matcher = DESCRIPTOR_CLASS.matcher(descriptor);
		StringBuilder out = new StringBuilder();
		while (matcher.find()) {
			matcher.appendReplacement(out, Matcher.quoteReplacement("L" + mapInternal(matcher.group(1)) + ';'));
		}
		matcher.appendTail(out);
		return out.toString();
	}
	/** The bare member name of a selector: everything before its descriptor, minus any owner prefix. */
	private static String memberOf(String selector) {
		int cut = selector.indexOf('(');
		if (cut < 0) cut = selector.length();
		String head = selector.substring(0, cut);
		int semicolon = head.lastIndexOf(';');
		return semicolon >= 0 ? head.substring(semicolon + 1) : head;
	}

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
		currentClass = reader.getClassName();
		try {
			reader.accept(new StringConstants(remapper, seen, owners), 0);
		} finally {
			currentClass = null;
		}
		return rewriteInjectionSelectors(renameOverrides(patchListenableRegistry(renameShadows(writer.toByteArray()))), seen);
	}

	/**
	 * Replaces the body of fabric-registry-sync's {@code ListenableRegistry.get} with a call to the kernel's view.
	 *
	 * <p>Everything else in that class stays: the two event getters and the interface are untouched. The original
	 * {@code get} is an {@code instanceof} test followed by {@code throw new IllegalArgumentException("Unsupported
	 * registry: …")}; on a merged base the interface is missing from every Forge registry wrapper, so the throw fires
	 * during {@code Minecraft}'s constructor ({@code ItemModelShaper} asks for the item registry's event) and the client
	 * never reaches the main menu. The replacement answers a view backed by the kernel's own event instead of a throw.
	 */
	private static byte[] patchListenableRegistry(byte[] bytes) {
		if (new ClassReader(bytes).getClassName().equals(LISTENABLE)) {
			ClassNode node = new ClassNode();
			// Frames stay on every other method: only the replaced body has branches to lose.
			new ClassReader(bytes).accept(node, 0);
			for (org.objectweb.asm.tree.MethodNode method : node.methods) {
				if (!method.name.equals("get") || !method.desc.endsWith(")L" + LISTENABLE + ";")) continue;
				method.instructions.clear();
				method.tryCatchBlocks.clear();
				method.localVariables = null;
				method.visibleLocalVariableAnnotations = null;
				method.invisibleLocalVariableAnnotations = null;
				method.instructions.add(new org.objectweb.asm.tree.VarInsnNode(org.objectweb.asm.Opcodes.ALOAD, 0));
				method.instructions.add(new org.objectweb.asm.tree.MethodInsnNode(org.objectweb.asm.Opcodes.INVOKESTATIC,
						"net/forbric/kernel/runtime/KernelRegistryViews", "listenable",
						"(Ljava/lang/Object;)Ljava/lang/Object;", false));
				method.instructions.add(new org.objectweb.asm.tree.TypeInsnNode(org.objectweb.asm.Opcodes.CHECKCAST, LISTENABLE));
				method.instructions.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.ARETURN));
				method.maxStack = 1;
				method.maxLocals = 1;
			}
			ClassWriter writer = new ClassWriter(0);
			node.accept(writer);
			return writer.toByteArray();
		}
		return bytes;
	}

	/**
	 * Renames a mixin's own {@code @Shadow} members to the names the base declares.
	 *
	 * <p>A shadow carries no selector string: mixin finds the target member by the shadow's own name and descriptor,
	 * so an intermediary {@code method_29113(Ljava/lang/Object;)Ljava/util/Optional;} must become
	 * {@code getOptional(Ljava/lang/Object;)Ljava/util/Optional;} before the class is applied. Measured after the
	 * refmap rewrite: {@code SimpleRegistryMixin} still resolved only 6 of 15 anchors with
	 * "InvalidMixinException @Shadow method method_29113 … was not located in the target class MappedRegistry" —
	 * because refmaps answer selectors, never a member's own name. The descriptor is the evidence: only the name is
	 * translated, and only when the base declares exactly one method of that shape (or one of them is the hint).
	 */
	/** {@code owner|name|descriptor} -> the name the base actually declares, filled while pass 1 runs. */
	private static final Map<String, String> OVERRIDES = new HashMap<>();

	/** Pass 2 of a single jar: rewrite the call sites that name an override by its intermediary spelling. */
	private static byte[] applyOverrides(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		boolean changed = false;
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof MethodInsnNode call) {
					String fixed = OVERRIDES.get(call.owner + '|' + call.name + '|' + call.desc);
					if (fixed == null && INTERMEDIARY_MEMBER.matcher(call.name).matches()
							&& !call.owner.startsWith("net/minecraft/")) {
						// A reference through a guest type names a member the base declares on that type's
						// supertype (measured: ModResourcePack.method_56926 -> PackResources.location). The base
						// cannot be asked by the guest owner, so ask the table by name; it refuses ambiguity.
						String byName = KernelMappingResolver.INSTANCE.mapMethodNameWithoutOwner("intermediary", call.name);
						if (byName != null && !byName.equals(call.name)) {
							fixed = byName;
							OVERRIDES.put(call.owner + '|' + call.name + '|' + call.desc, byName);
						}
					}
					if (fixed != null) {
						call.name = fixed;
						changed = true;
					}
				} else if (insn instanceof org.objectweb.asm.tree.FieldInsnNode field) {
					String fixed = OVERRIDES.get(field.owner + '|' + field.name + '|' + field.desc);
					if (fixed != null) {
						field.name = fixed;
						changed = true;
					}
				}
			}
		}
		if (!changed) return bytes;
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}
	/**
	 * Renames the members a guest declares because an interface it implements declares them.
	 *
	 * <p>{@code ClassRemapper} renames method <em>references</em> through the table, but a declaration is keyed by
	 * the declaring class — and a guest class is not in the table, so its {@code MethodNode.name} stays
	 * intermediary. That is invisible until something calls the member through the interface: measured,
	 * {@code net.fabricmc.fabric.impl.resource.loader.ModResourcePackCreator} still declared
	 * {@code method_14453(Consumer)} after the remap, so {@code PackRepository.discoverAvailable} threw
	 * {@code AbstractMethodError: ... does not define or inherit an implementation of the resolved method
	 * 'abstract void loadPacks(java.util.function.Consumer)'} and the world would not open.
	 *
	 * <p>The base answers by descriptor across the whole hierarchy. One name is a fact; several are ambiguity and
	 * the member keeps its intermediary name so the failure stays visible instead of being guessed away.
	 */
	private static byte[] renameOverrides(byte[] remapped) {
		MethodIndex index = minecraftIndex;
		if (index == null) return remapped;

		ClassNode node = new ClassNode();
		new ClassReader(remapped).accept(node, 0);
		List<String> supers = new ArrayList<>();
		if (node.superName != null) supers.add(node.superName);
		supers.addAll(node.interfaces);

		Map<String, String> renamed = new HashMap<>();
		boolean changed = false;
		for (MethodNode method : node.methods) {
			if (!INTERMEDIARY_MEMBER.matcher(method.name).matches()) continue;
			Set<String> names = new LinkedHashSet<>();
			for (String owner : supers) {
				for (BaseMethod candidate : index.declaredByType(owner, method.desc)) {
					if (candidate.name().startsWith("lambda$") || candidate.name().startsWith("<")) continue;
					names.add(candidate.name());
				}
			}
			if (names.size() != 1) continue;
			String fixed = names.iterator().next();
			renamed.put(method.name + method.desc, fixed);
			method.name = fixed;
			// Call sites in OTHER classes still name this member by its intermediary spelling, and they were
			// rewritten before this class was: remember the rename so pass 2 can fix them.
			OVERRIDES.put(node.name + '|' + method.name + '|' + method.desc, fixed);
			changed = true;
		}
		if (!changed) return remapped;

		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn : method.instructions) {
				if (!(insn instanceof MethodInsnNode call) || !call.owner.equals(node.name)) continue;
				String fixed = renamed.get(call.name + call.desc);
				if (fixed != null) call.name = fixed;
			}
		}
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}
	private static byte[] renameShadows(byte[] remapped) {
		MethodIndex index = minecraftIndex;
		if (index == null) return remapped;
		ClassNode node = new ClassNode();
		new ClassReader(remapped).accept(node, 0);
		List<String> targets = new ArrayList<>();
		// @Mixin is @Retention(CLASS): it lands in the *invisible* annotation list, and reading only the visible one
		// left the target list empty — the first attempt renamed nothing.
		for (List<org.objectweb.asm.tree.AnnotationNode> list : annotationLists(node)) {
			for (org.objectweb.asm.tree.AnnotationNode annotation : list) {
				if (!"Lorg/spongepowered/asm/mixin/Mixin;".equals(annotation.desc)) continue;
				collectTargets(annotation, targets);
			}
		}
		if (targets.isEmpty()) return remapped;
		java.util.Map<String, String> byDescriptor = new java.util.HashMap<>();
		for (MethodNode method : node.methods) {
			if (!hasAnnotation(method, "Lorg/spongepowered/asm/mixin/Shadow;")
					&& !hasAnnotation(method, "Lorg/spongepowered/asm/mixin/Overwrite;")) {
				continue;
			}
			for (String target : targets) {
				// The name may already look official and still be wrong: the table mapped class_3288.method_56933
				// onto getChatLink, while the base declares location() and a getChatLink(boolean) of another shape.
				// The base is the only authority on "is this member here", so the check is by name *and* shape.
				if (declaresName(index, target, method.desc, method.name)) break;
				String picked = pickBaseName(index, target, method.desc, method.name);
				if (picked != null) {
					byDescriptor.put(method.name + method.desc, picked);
					break;
				}
			}
		}
		// Fields too. creative-inventory's mixin shadows the search field by its intermediary name and the whole
		// mixin was dropped with `@Shadow field field_2896 was not located in CreativeModeInventoryScreen`.
		java.util.Map<String, String> fieldByDescriptor = new java.util.HashMap<>();
		for (org.objectweb.asm.tree.FieldNode field : node.fields) {
			if (!hasAnnotation(field, "Lorg/spongepowered/asm/mixin/Shadow;")) continue;
			for (String target : targets) {
				if (declaresFieldName(index, target, field.desc, field.name)) break;
				String picked = pickBaseFieldName(index, target, field.desc, field.name);
				if (picked != null) {
					fieldByDescriptor.put(field.name + ' ' + field.desc, picked);
					break;
				}
			}
		}
		// The `@Shadow` passes answer only for members DECLARED on the mixin. A mixin that reads an inherited member
		// names it on itself with no declaration at all, so an empty map here does not mean there is nothing to do:
		// leaving early skipped those references entirely and the client died on the very next inherited field
		// (ServerGamePacketListenerImpl does not have member field ... field_45013).
		boolean changed = !byDescriptor.isEmpty() || !fieldByDescriptor.isEmpty();
		for (MethodNode method : node.methods) {
			String renamed = byDescriptor.get(method.name + method.desc);
			if (renamed != null) {
				method.name = renamed;
				changed = true;
			}
			for (org.objectweb.asm.tree.AbstractInsnNode insn : method.instructions) {
				if (insn instanceof org.objectweb.asm.tree.MethodInsnNode call
						&& node.name.equals(call.owner)) {
					String called = byDescriptor.get(call.name + call.desc);
					if (called == null) called = reachableName(index, node.superName, call.name, call.desc, true);
					if (called != null) {
						call.name = called;
						changed = true;
					}
				}
				if (insn instanceof org.objectweb.asm.tree.FieldInsnNode access
						&& node.name.equals(access.owner)) {
					String called = fieldByDescriptor.get(access.name + ' ' + access.desc);
					if (called == null) {
						called = reachableName(index, node.superName, access.name, access.desc, false);
					}
					if (called != null) {
						access.name = called;
						changed = true;
					}
				}
			}
		}
		for (org.objectweb.asm.tree.FieldNode field : node.fields) {
			String renamed = fieldByDescriptor.get(field.name + ' ' + field.desc);
			if (renamed != null) {
				field.name = renamed;
				changed = true;
			}
		}
		if (!changed) return remapped;
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	private static void collectTargets(org.objectweb.asm.tree.AnnotationNode annotation, List<String> into) {
		List<Object> values = new ArrayList<>();
		if (annotation.values != null) values.addAll(annotation.values);
		for (int i = 0; i + 1 < values.size(); i += 2) {
			Object value = values.get(i + 1);
			if (value instanceof List<?> list) {
				for (Object item : list) {
					if (item instanceof org.objectweb.asm.Type type) into.add(type.getInternalName());
				}
			} else if (value instanceof org.objectweb.asm.Type type) {
				into.add(type.getInternalName());
			}
		}
	}

	private static boolean hasAnnotation(MethodNode method, String descriptor) {
		for (List<org.objectweb.asm.tree.AnnotationNode> list : annotationLists(method)) {
			for (org.objectweb.asm.tree.AnnotationNode annotation : list) {
				if (descriptor.equals(annotation.desc)) return true;
			}
		}
		return false;
	}

	private static boolean hasAnnotation(org.objectweb.asm.tree.FieldNode field, String descriptor) {
		for (List<org.objectweb.asm.tree.AnnotationNode> list : annotationLists(field)) {
			for (org.objectweb.asm.tree.AnnotationNode annotation : list) {
				if (descriptor.equals(annotation.desc)) return true;
			}
		}
		return false;
	}

	private static List<List<org.objectweb.asm.tree.AnnotationNode>> annotationLists(Object holder) {
		List<List<org.objectweb.asm.tree.AnnotationNode>> lists = new ArrayList<>(2);
		if (holder instanceof ClassNode node) {
			if (node.visibleAnnotations != null) lists.add(node.visibleAnnotations);
			if (node.invisibleAnnotations != null) lists.add(node.invisibleAnnotations);
		} else if (holder instanceof MethodNode method) {
			if (method.visibleAnnotations != null) lists.add(method.visibleAnnotations);
			if (method.invisibleAnnotations != null) lists.add(method.invisibleAnnotations);
		} else if (holder instanceof org.objectweb.asm.tree.FieldNode field) {
			if (field.visibleAnnotations != null) lists.add(field.visibleAnnotations);
			if (field.invisibleAnnotations != null) lists.add(field.invisibleAnnotations);
		}
		return lists;
	}

	/** Whether the base declares exactly this name with exactly this shape. */
	private static boolean declaresName(MethodIndex index, String owner, String descriptor, String name) {
		for (BaseMethod method : index.declaredByType(owner, descriptor)) {
			if (method.name().equals(name)) return true;
		}
		return false;
	}

	/**
	 * Answers a member a mixin names on <em>itself</em> but inherits from the game class it is written against.
	 *
	 * <p>Measured: {@code net.fabricmc.fabric.mixin.networking.ServerConfigurationNetworkHandlerMixin} reads
	 * {@code getfield field_45012:Lnet/minecraft/server/MinecraftServer;} on its own type — Yarn for the
	 * {@code server} field of {@code ServerCommonPacketListenerImpl}, which is the mixin's superclass and not one of
	 * its {@code @Mixin} targets. No {@code @Shadow} declaration exists, so the passes above never saw it, the name
	 * stayed intermediary, and the player was kicked at the configuration handshake with
	 * {@code NoSuchFieldError: ServerConfigurationPacketListenerImpl does not have member field ... field_45012}.
	 *
	 * <p>The table's by-name answer is accepted only when a supertype of the mixin really declares that name with
	 * that shape; otherwise the name is left alone and the failure stays visible.
	 */
	private static String reachableName(MethodIndex index, String superName, String name, String descriptor,
			boolean method) {
		if (superName == null || !INTERMEDIARY_MEMBER.matcher(name).matches()) return null;
		String mapped = method
				? KernelMappingResolver.INSTANCE.mapMethodNameWithoutOwner("intermediary", name)
				: KernelMappingResolver.INSTANCE.mapFieldNameWithoutOwner("intermediary", name);
		if (mapped.equals(name)) return null;
		boolean declared = method
				? index.declares(superName, descriptor, mapped)
				: index.fieldsOfShape(superName, descriptor).contains(mapped);
		return declared ? mapped : null;
	}

	private static boolean declaresFieldName(MethodIndex index, String owner, String descriptor, String name) {
		return index.fieldsOfShape(owner, descriptor).contains(name);
	}

	/** The one field name of that shape on {@code owner}, or the hint when the base declares it. */
	private static String pickBaseFieldName(MethodIndex index, String owner, String descriptor, String hint) {
		java.util.Set<String> names = new java.util.LinkedHashSet<>(index.fieldsOfShape(owner, descriptor));
		if (names.contains(hint)) return hint;
		if (names.size() == 1) return names.iterator().next();
		// One shape is often shared by a dozen fields -- Options declares dozens of OptionInstance entries, all of
		// them private final and therefore indistinguishable by type alone -- so uniqueness says nothing here. The
		// name still does: field_1839 is an intermediary name the table answers on its own, and the answer is used
		// only when the base really declares it with this shape. Measured before this: "@Shadow field field_1839
		// was not located in the target class net.minecraft.client.Options" from fabric-key-binding-api-v1's
		// GameOptionsMixin, and field_1887 from fabric-resource-loader-v0's -- the table maps both
		// (class_315 field_1839 keyMappings, field_1887 resourcePacks) while this uniqueness search gave up.
		return fromTheTable(owner, names, hint, false);
	}

	/** The one name of that shape on {@code owner}, or the hint when the base declares it. */
	private static String pickBaseName(MethodIndex index, String owner, String descriptor, String hint) {
		java.util.Set<String> names = new java.util.LinkedHashSet<>();
		for (BaseMethod method : index.declaredByType(owner, descriptor)) {
			if (!method.name().startsWith("lambda$") && !method.name().startsWith("<")) names.add(method.name());
		}
		if (names.contains(hint)) return hint;
		if (names.size() == 1) return names.iterator().next();
		// Same argument as the field twin: a shape with several candidates still has a name, and an intermediary
		// name is one the table can translate without picking. Used only when the base declares the translated
		// name with this exact shape.
		return fromTheTable(owner, names, hint, true);
	}

	/**
	 * The candidate the mapping table names for {@code hint}, when {@code hint} is an intermediary member name and
	 * the base declares the translated name with the same shape. Null otherwise -- an unknown or ambiguous name
	 * makes the table answer nothing, which is the point of asking it instead of picking.
	 */
	private static String fromTheTable(String owner, java.util.Set<String> candidates, String hint, boolean method) {
		if (!INTERMEDIARY_MEMBER.matcher(hint).matches()) return null;
		String mapped = null;
		// Owner-aware first. The table is keyed by (intermediary owner, intermediary member), and the owner here is
		// already the official one, so it has to be turned back. Without this, field_1887 (Options.resourcePacks)
		// was still unlocated after the first version: the owner-less index refuses a name that appears under more
		// than one owner, and only field_1839 happened to be unique. Measured after this change: the same
		// GameOptionsMixin's second shadow resolves too, and the run's InvalidMixinException count is what the log
		// says rather than what this comment hopes.
		String intermediaryOwner = KernelMappingResolver.INSTANCE.unmapClassName("intermediary", owner);
		if (intermediaryOwner != null && !intermediaryOwner.equals(owner)) {
			mapped = method
					? KernelMappingResolver.INSTANCE.mapMethodName("intermediary", intermediaryOwner, hint, "")
					: KernelMappingResolver.INSTANCE.mapFieldName("intermediary", intermediaryOwner, hint, "");
		}
		if (mapped == null || mapped.isEmpty() || mapped.equals(hint)) {
			mapped = method
					? KernelMappingResolver.INSTANCE.mapMethodNameWithoutOwner("intermediary", hint)
					: KernelMappingResolver.INSTANCE.mapFieldNameWithoutOwner("intermediary", hint);
		}
		if (mapped == null || mapped.isEmpty() || mapped.equals(hint)) return null;
		return candidates.contains(mapped) ? mapped : null;
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
	/**
	 * A refmap leaf entry: {@code "key": "value"}, both sides captured whole.
	 */
	private static final Pattern REFMAP_LEAF = Pattern.compile(
			"\"((?:[^\"\\\\]|\\\\.)*)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

	/**
	 * Rewrites a refmap's <b>values</b> and leaves its keys exactly as the mod wrote them.
	 *
	 * <p>Measured need, and the reason a refmap cannot go through the plain document passes: the guest jar
	 * {@code fabric-entity-events-v1} ships one selector twice - inside its annotation as
	 * {@code Lnet/minecraft/world/World;spawnEntity(Lnet/minecraft/entity/Entity;)Z}, and as the key of its refmap
	 * entry spelled identically, with the intermediary selector {@code Lnet/minecraft/class_1937;method_8649(...)Z}
	 * as the value. Mixin resolves an annotation by looking its literal text up in that map, so a renamed key is a
	 * key nothing looks up. Measured on the written cache with every value correct: the key had become
	 * {@code Lnet/minecraft/world/level/Level;spawnEntity(...)} while the annotation stayed as authored, the selector
	 * never resolved, and 22 handlers of that one mod were counted unattached while their targets were all present.
	 *
	 * <p>So each leaf entry is passed to {@link #mapRefmapValues} on its own, which is where the tuned selector
	 * passes live, and only the value half of the answer is kept.
	 */
	private static String rewriteRefmapSelectors(String json, Set<String> seen) {
		int start = json.indexOf("\"mappings\"");
		if (start < 0) return json;
		int data = json.indexOf("\"data\"", start);
		int end = data > start ? data : json.length();
		String head = json.substring(0, start);
		String mappings = json.substring(start, end);
		String tail = json.substring(end);
		Matcher matcher = REFMAP_LEAF.matcher(mappings);
		StringBuilder out = new StringBuilder(mappings.length());
		int last = 0;
		boolean touched = false;
		while (matcher.find()) {
			String key = matcher.group(1);
			String value = matcher.group(2);
			String mapped = rewriteRefmapValue(key, value, seen);
			if (mapped.equals(value)) continue;
			touched = true;
			out.append(mappings, last, matcher.start(2)).append(mapped);
			last = matcher.end(2);
		}
		if (!touched) return json;
		out.append(mappings, last, mappings.length());
		return head + out + tail;
	}

	/**
	 * One refmap leaf: the selector passes see the pair, and only the value half of their answer is kept.
	 *
	 * <p>A bare class-name pair ({@code "net/minecraft/server/command/GameRuleCommand$1":
	 * "net/minecraft/server/commands/GameRuleCommand$1"}) is also the rename Mixin applies to a mixin's
	 * {@code @Mixin(targets = …)}, so it is remembered here: that is the only place in this process that holds the
	 * guest jar's own refmap in its hands, and a later pass (binding a shadow field) has to resolve a target the same
	 * way Mixin does. Reading the refmap again at mixin time was tried and answered nothing — the loader hands out no
	 * resource for a mixin class at that point.
	 */
	private static String rewriteRefmapValue(String key, String value, Set<String> seen) {
		String pair = "\"" + key + "\": \"" + value + "\"";
		String mapped = mapClassNamesInText(mapRefmapValues(pair, seen), seen);
		Matcher again = REFMAP_LEAF.matcher(mapped);
		String answered = again.find() ? again.group(2) : value;
		// A selector that is only a descriptor — "(" … ")" and nothing else — carries no member name for the passes
		// above to translate, so its class names are the whole answer and were left in intermediary spelling.
		// Measured in 51 rewritten guest jars: 4 refmap rows of that shape, and each one belongs to a handler Mixin
		// reported as never attached - fabric-item-api-v1's BrewingStandBlockEntityMixin, fabric-networking-api-v1's
		// ClientConfigurationNetworkHandlerMixin, and fabric-resource-loader-v0's SimpleResourceReloadMixin and
		// TestServerMixin. E.g. "(Lnet/minecraft/class_1935;)Lnet/minecraft/class_1799;" became the statement that a
		// handler wants a method taking Intermediary types, which no base class declares.
		String described = officialClassesInside(answered);
		if (!described.equals(answered)) answered = described;
		if (!answered.equals(value) && key.indexOf('(') < 0 && key.indexOf(':') < 0
				&& key.startsWith("net/") && answered.startsWith("net/") && answered.indexOf('(') < 0
				&& answered.indexOf(':') < 0) {
			rememberClassRename(key, answered);
		}
		return answered;
	}

	/**
	 * Every {@code L…;} token in a selector, mapped to the class this base declares.
	 *
	 * <p>Idempotent: {@link #mapInternal} answers with its input when the name is already official or unknown, so a
	 * value the tuned passes already finished is returned unchanged.
	 */
	private static String officialClassesInside(String selector) {
		if (selector == null || selector.indexOf("Lnet/") < 0 && selector.indexOf("Lnet.") < 0) return selector;
		Matcher matcher = java.util.regex.Pattern.compile("L([A-Za-z0-9_$/]+);").matcher(selector);
		StringBuilder out = new StringBuilder(selector.length());
		while (matcher.find()) {
			String mapped = mapInternal(matcher.group(1));
			matcher.appendReplacement(out, Matcher.quoteReplacement("L" + mapped + ";"));
		}
		matcher.appendTail(out);
		return out.toString();
	}

	/**
	 * Records one refmap class rename, and says whether it was new.
	 *
	 * <p>Both sides must be bare internal class names, because a refmap entry that names a method or a field is not a
	 * rename and must never be consulted as one.
	 */
	private static boolean rememberClassRename(String key, String answered) {
		if (key == null || answered == null || key.equals(answered)) return false;
		if (!key.startsWith("net/") || !answered.startsWith("net/")) return false;
		if (key.indexOf('(') >= 0 || key.indexOf(':') >= 0
				|| answered.indexOf('(') >= 0 || answered.indexOf(':') >= 0) return false;
		return CLASS_RENAMES.putIfAbsent(key, answered) == null;
	}

	/**
	 * Reads the class renames back out of a jar that was rewritten on an earlier boot.
	 *
	 * <p>Why this exists. The rename table used to be a by-product of rewriting: {@link #rewriteRefmapValue} filled it
	 * while it answered selectors. A warm boot hands back the cached jar without rewriting anything, so the table came
	 * up empty, {@code MixinShadowFieldBind} could not resolve a mixin's yarn-spelled target, and the two
	 * {@code @Shadow} repairs that cleared eight {@code InvalidMixinException} came back on the second launch.
	 * Measured: cold boot {@code invalid=0 bound=4}, warm boot {@code invalid=8 bound=0}, same jar and same cache
	 * contents. The cached refmap still holds both spellings, so reading it costs one pass over a few hundred bytes
	 * per jar and makes the two boots agree.
	 */
	private static void readClassRenamesFrom(Path cached) {
		try (java.util.zip.ZipInputStream in = new java.util.zip.ZipInputStream(Files.newInputStream(cached))) {
			for (java.util.zip.ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
				if (entry.isDirectory() || !entry.getName().endsWith("-refmap.json")) continue;
				String json = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
				Matcher names = REFMAP_LEAF.matcher(json);
				while (names.find()) {
					if (rememberClassRename(names.group(1), names.group(2))) classRenamesFromCached++;
				}
			}
		} catch (IOException | RuntimeException unreadable) {
			ForbricLog.debug("[Forbric/Remap] the cached %s has no readable refmap, so the class renames it carries"
					+ " stay unknown on this boot: %s", cached.getFileName(), String.valueOf(unreadable));
		}
	}

	/** Class renames read back out of cached refmaps on a warm boot. */
	private static int classRenamesFromCached;

	/** Class renames this pass read out of the guest refmaps, key and value in internal form. */
	private static final java.util.Map<String, String> CLASS_RENAMES = new java.util.concurrent.ConcurrentHashMap<>();

	/** What a guest refmap renames this class to, or the name itself when no refmap mentioned it. */
	public static String classRenamedTo(String internalName) {
		if (internalName == null) return null;
		String renamed = CLASS_RENAMES.get(internalName);
		if (renamed != null) return renamed;
		// A refmap may spell the nested class with dots; the loader and the base both use slashes.
		String slashed = internalName.replace('.', '/');
		renamed = CLASS_RENAMES.get(slashed);
		return renamed != null ? renamed : slashed;
	}

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
		String fields = replaceSelectors(methods, REFMAP_FIELD, false, seen);
		return useTheKeyWhenTheValueDoesNotResolve(fields);
	}

	/**
	 * Answers the last few refmap values from the key beside them.
	 *
	 * <p>A refmap is a table of "the member my source wrote" to "the member the runtime has". The key is the source
	 * name (mixin looks it up by it); the value carries the owner and shape, and its member half is an intermediary
	 * name — sometimes a lambda the table collapsed several methods onto. When the value's member cannot be resolved
	 * (measured: {@code MappedRegistry.method_29113} and two lambda rows), the key already says which member the mod
	 * meant, and the value already says which owner and which descriptor — so accepting the key only when the base
	 * declares that name with that shape is a verification, not a guess. Without it {@code SimpleRegistryMixin}
	 * cannot shadow {@code getHolder}/{@code get}, the mixin applies only partially, no registry becomes a
	 * {@code ListenableRegistry}, and every lookup throws "Unsupported registry".
	 */
	private static String useTheKeyWhenTheValueDoesNotResolve(String json) {
		MethodIndex index = minecraftIndex;
		if (index == null) return json;
		Matcher matcher = REFMAP_PAIR.matcher(json);
		StringBuilder out = new StringBuilder(json.length());
		int last = 0;
		while (matcher.find()) {
			int cut = matcher.group(1).indexOf('(');
			String named = cut < 0 ? matcher.group(1) : matcher.group(1).substring(0, cut);
			String member = matcher.group(3);
			String rest = matcher.group(4);
			if (member.equals(named) || !rest.startsWith("(")) continue;
			if (!INTERMEDIARY_MEMBER.matcher(member).matches() && !member.startsWith("lambda$")) continue;
			if (!named.matches("[A-Za-z_$][A-Za-z0-9_$]*")) continue;
			String target = named;
			if (!index.declares(matcher.group(2), rest, target)) {
				// The value's member is a name the base never declares: the table row behind it is one of the
				// rows that records a lambda the base no longer has (`CompoundTag.contains` at `()Set` is
				// `getKeys`). The key is what the mixin source wrote, so translate it through the jar's own
				// yarn -> intermediary dictionary and then the table, and keep the answer only if the base
				// declares it at this shape.
				String intermediary = yarn.members.get(named);
				if (intermediary == null) continue;
				String official = KernelMappingResolver.INSTANCE.mapMethodNameWithoutOwner("intermediary", intermediary);
				if (official.equals(intermediary) || !index.declares(matcher.group(2), rest, official)) continue;
				target = official;
			}
			refmapValues++;
			out.append(json, last, matcher.start(3)).append(target);
			last = matcher.end(3);
		}
		if (last == 0) return json;
		out.append(json, last, json.length());
		return out.toString();
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
			if (method && officialName.equals(name)) {
				String unqualified = KernelMappingResolver.INSTANCE.mapMethodNameWithoutOwner("intermediary", name);
				if (!unqualified.equals(name)) officialName = unqualified;
			}
			if (!method && officialName.equals(name)) {
				String unqualified = KernelMappingResolver.INSTANCE.mapFieldNameWithoutOwner("intermediary", name);
				if (!unqualified.equals(name)) officialName = unqualified;
			}
			String officialDescriptor = mapDescriptor(descriptor, seen);
			if (method) {
				// The refmap of a fabric module is written with official class names but intermediary member names
				// (Lnet/minecraft/core/MappedRegistry;method_10250(…)), so the table's owner#name key never matches.
				// The exact shape does, and answering it here is what lets SimpleRegistryMixin's @Shadow members
				// resolve — without it fabric-registry-sync cannot wrap any registry and every lookup throws
				// "Unsupported registry". A table row that lands on a name the base does not declare (one row maps
				// class_5321.method_29177 onto lambda$create$2) is corrected here too.
				String picked = nameInTheBase(officialOwner, officialDescriptor, officialName);
				if (picked != null && !picked.equals(officialName)) officialName = picked;
			}
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

	/**
	 * The base's own name for a member the guest names differently but with exactly this shape, or {@code null}.
	 *
	 * <p>Only the unambiguous tier of the lambda tie-breaks is used here, because a refmap selector is a claim about
	 * one member: a public method sharing the name with a field of the class (a getter), a single public method of
	 * that shape, or a single static lambda sibling. Anything still tied stays as written.
	 */
	private static String nameInTheBase(String officialOwner, String officialDescriptor, String mapped) {
		MethodIndex index = minecraftIndex;
		if (index == null) return null;
		List<BaseMethod> byDescriptor = index.declaredByType(officialOwner, officialDescriptor);
		if (debugShapes-- > 0 && Boolean.getBoolean("forbric.debugShapes")) {
			ForbricLog.info("[Forbric/Shape] ref " + mapped + " " + officialOwner + officialDescriptor + " -> "
					+ byDescriptor);
		}
		if (byDescriptor.isEmpty()) return null;
		for (BaseMethod method : byDescriptor) if (method.name().equals(mapped)) return mapped;
		List<String> named = new ArrayList<>(byDescriptor.size());
		List<String> siblings = new ArrayList<>(byDescriptor.size());
		for (BaseMethod method : byDescriptor) {
			String candidate = method.name();
			if (candidate.startsWith("<")) continue;
			if (candidate.startsWith("lambda$")) {
				if ((method.access() & 0x0008) != 0 && !siblings.contains(candidate)) siblings.add(candidate);
			} else if (method.callable() && !named.contains(candidate)) {
				// A name declared by both the class and its super counts once: MappedRegistry and BaseMappedRegistry
				// both declare register(…), and a list would report that as a tie between two candidates.
				named.add(candidate);
			}
		}
		if (named.size() == 1) return named.get(0);
		java.util.Set<String> fields = index.fields(officialOwner);
		List<String> getters = new ArrayList<>(1);
		for (String candidate : named) if (fields.contains(candidate)) getters.add(candidate);
		if (getters.size() == 1) return getters.get(0);
		if (named.isEmpty() && siblings.size() == 1) return siblings.get(0);
		return null;
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
		return replaceYarnClasses(out.toString(), seen);
	}

	/** A path-shaped token, used to find Yarn class names inside selectors and annotation values. */
	private static final Pattern TOKEN = Pattern.compile(
			"(?<![A-Za-z0-9_$/.\\-])[A-Za-z_$][A-Za-z0-9_$]*(?:[./][A-Za-z0-9_$]+)+");

	/**
	 * Replaces the class names a guest still spells the Yarn way, using the dictionary its own refmap supplied.
	 *
	 * <p>Measured need: {@code @Mixin(targets = "net/minecraft/item/ItemStack")} and refmap keys written
	 * {@code net/minecraft/block/BlockState} both name classes no intermediary entry can answer for, so the mixin
	 * service looked for a class that does not exist and the whole mixin was dropped.
	 */
	private static String replaceYarnClasses(String text, Set<String> seen) {
		if (yarn.classes.isEmpty()) return text;
		Matcher matcher = TOKEN.matcher(text);
		StringBuilder out = new StringBuilder(text.length());
		while (matcher.find()) {
			String token = matcher.group();
			String internal = token.replace('.', '/');
			if (!yarn.classes.containsKey(internal)) {
				matcher.appendReplacement(out, Matcher.quoteReplacement(token));
				continue;
			}
			String mapped = mapInternal(internal);
			seen.add(internal);
			matcher.appendReplacement(out,
					Matcher.quoteReplacement(token.indexOf('.') >= 0 ? mapped.replace('/', '.') : mapped));
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
		/** A member a guest call site may name: public, whether the base declares it instance or static. */
		private boolean callable() {
			return (access & 0x0001) != 0;
		}

		/** A member a lambda-shaped call can be: a lambda is always private static synthetic, so its target is static. */
		private boolean staticMethod() {
			return (access & 0x0001) != 0 && (access & 0x0008) != 0;
		}
	}

	/**
	 * Rows for guests compiled against a vanilla surface this line no longer has, keyed by
	 * {@code owner|descriptor|the name the table gave}.
	 *
	 * <p>Only add a row after reading both sides with {@code javap} and naming the failure it fixes. A row here is a
	 * claim that the base declares the replacement and the guest's meaning is that replacement, which a tie-break
	 * between same-shaped candidates cannot establish.
	 */
	private static final java.util.regex.Pattern INTERMEDIARY_MEMBER = java.util.regex.Pattern.compile("(?:method|field)_\\d+");
	private static long descriptorsPicked;
	/**
	 * Owners translated to intermediary by asking the joined table rather than a guest jar's own dictionary.
	 *
	 * <p>Counted because it is a whole class of earlier misses: the dictionary is built from the refmap's
	 * {@code data} section, which only lists what that one jar mentions, so a call site into a class the jar never
	 * shadowed kept its yarn spelling and every qualified lookup under it missed. Measured consequence:
	 * {@code fabric-networking-api-v1} called {@code MinecraftServer.method_3773()I}, the qualified and unqualified
	 * lookups both missed, the descriptor index found two {@code ()I} methods there and refused to guess, and the
	 * first login tick threw {@code NoSuchMethodError}; the class row was in the table all along.
	 */
	private static long ownersReversed;
	/**
	 * Guest classes whose own bytecode still names a member in intermediary spelling after every lookup missed.
	 *
	 * <p>Such a class is broken on this base: the call it makes names a method the game does not declare, so it
	 * throws {@code NoSuchMethodError} the first time it runs. That is not theoretical - fabric-networking-api-v1's
	 * {@code ServerLoginNetworkAddon.sendCompressionPacket} calls {@code MinecraftServer.method_3773()I}, the table
	 * has no row for that member, and the first login tick after the mixin that calls it started attaching took the
	 * integrated server down. Recording it lets the mixin service refuse a mixin that reaches one of these classes
	 * instead of attaching bytecode that is guaranteed to fail later, far from the cause.
	 */
	private static final Map<String, java.util.Set<String>> LEFTOVERS = new java.util.concurrent.ConcurrentHashMap<>();
	/** The class being rewritten right now, so a miss is filed under the class that holds it. */
	private static volatile String currentClass;

	/** Guest classes (internal names) that still speak intermediary, for the mixin service's fit judgement. */
	public static java.util.Set<String> classesStillSpeakingIntermediary() {
		return java.util.Set.copyOf(LEFTOVERS.keySet());
	}

	/**
	 * What a class wanted that the table could not name, for a caller that has to decide whether it can run it.
	 *
	 * <p>Answers empty for a class that was fully rewritten, which is the normal case.
	 */
	public static java.util.Set<String> leftoverMembersOf(String internalName) {
		java.util.Set<String> members = LEFTOVERS.get(internalName);
		return members == null ? java.util.Set.of() : java.util.Set.copyOf(members);
	}

	private static void recordLeftover(String member) {
		String owner = currentClass;
		if (owner == null) return;
		LEFTOVERS.computeIfAbsent(owner, key -> java.util.concurrent.ConcurrentHashMap.newKeySet()).add(member);
	}

	/** How many distinct member names across all classes were left in intermediary spelling. */
	private static int leftoverMemberCount() {
		int total = 0;
		for (java.util.Set<String> members : LEFTOVERS.values()) total += members.size();
		return total;
	}

	private static int shapeProbes = 0;
	private static int debugShapes = 6;
	private static final Map<String, String> KNOWN_LAMBDA_TARGETS = Map.of(
			// fabric-transfer-api-v1 builds its item packet codec as
			// StreamCodec.cast(ByteBufCodecs.<lambda>(Registries.ITEM), ItemVariant::getItem, …): the lambda answers
			// the registry's element, which is what registry(ResourceKey) returns — holderRegistry and holderSet
			// return Holder and HolderSet and would fail that cast. Measured failure: NoSuchMethodError for
			// lambda$fromCodecWithRegistries$9(ResourceKey) during Bootstrap, VariantCodecs.<clinit>.
			"net/minecraft/network/codec/ByteBufCodecs|(Lnet/minecraft/resources/ResourceKey;)"
					+ "Lnet/minecraft/network/codec/StreamCodec;|lambda$fromCodecWithRegistries$9", "registry",
			// fabric-model-loading-api-v1's ModelLoadingPluginManager.preparePlugins returns Util.combine(futures)
			// (yarn), which this line's table records as class_156.method_652 -> lambda$allOf$8. That name belongs
			// to lambda$allOf$8(int)Predicate[] here, not to any (List)CompletableFuture, so the guest got a
			// NoSuchMethodError while Minecraft was still constructing its ModelManager. Util.sequence is the
			// (List)CompletableFuture the reload path itself uses (ModelManager.reload, offset 68); sequenceFailFast
			// and sequenceFailFastAndCancel share that erasure but belong to SimpleReloadInstance.of instead.
			"net/minecraft/Util|(Ljava/util/List;)Ljava/util/concurrent/CompletableFuture;|lambda$allOf$8", "sequence");

	private static final class MethodIndex {
		private final List<Path> jars;
		private final Map<String, Map<String, List<BaseMethod>>> cache = new HashMap<>();
		private final Map<String, List<String>> parents = new HashMap<>();
		private final Map<String, java.util.Set<String>> declaredFields = new HashMap<>();
		/** field descriptor -> the names the type declares with it, for the @Shadow field pass. */
		private final Map<String, Map<String, List<String>>> fieldShapes = new HashMap<>();
		/** Classes read with code included, for the one question that needs the body and not just the signature. */
		private final Map<String, Object> codeCache = new HashMap<>();
		private final java.util.Set<String> codeTried = new java.util.HashSet<>();

		private MethodIndex(List<Path> jars) {
			this.jars = jars;
		}

		/** Whether the type declares this method; a null descriptor matches any shape. */
		private boolean hasMethod(String internalOwner, String name, String descriptor) {
			ClassNode node = codeOf(internalOwner);
			if (node == null) return false;
			for (MethodNode method : node.methods)
				if (method.name.equals(name) && (descriptor == null || method.desc.equals(descriptor))) return true;
			return false;
		}

		/** Whether the body of a method with that name (any shape when the descriptor is null) makes that call. */
		private int methodCalls(String internalOwner, String name, String descriptor,
				String targetOwner, String targetName, String targetDescriptor) {
			ClassNode node = codeOf(internalOwner);
			if (node == null) return 3;
			boolean any = false;
			for (MethodNode method : node.methods) {
				if (!method.name.equals(name)) continue;
				if (descriptor != null && !method.desc.startsWith(descriptor)) continue;
				any = true;
				if (method.instructions == null) continue;
				for (org.objectweb.asm.tree.AbstractInsnNode instruction : method.instructions) {
					if (instruction instanceof org.objectweb.asm.tree.MethodInsnNode call
							&& call.owner.equals(targetOwner) && call.name.equals(targetName)
							&& call.desc.equals(targetDescriptor)) {
						return 0;
					}
				}
			}
			return any ? 5 : 4;
		}

		/** The {@code lambda$<stem>$<digits>} methods of this type, optionally of one shape, sorted. */
		private List<String> lambdasNamed(String internalOwner, String stem, String descriptor) {
			ClassNode node = codeOf(internalOwner);
			if (node == null) return List.of();
			List<String> found = new ArrayList<>(2);
			for (MethodNode method : node.methods) {
				if (!method.name.startsWith(stem)) continue;
				if (descriptor != null && !method.desc.equals(descriptor)) continue;
				String rest = method.name.substring(stem.length());
				if (rest.isEmpty()) continue;
				boolean digits = true;
				for (int i = 0; i < rest.length(); i++) if (!Character.isDigit(rest.charAt(i))) { digits = false; break; }
				if (digits) found.add(method.name);
			}
			java.util.Collections.sort(found);
			return found;
		}

		/**
		 * Why {@code methodName+methodDescriptor} of {@code internalOwner} does not invoke that target: 0 when it
		 * does, 3 when the class cannot be read, 4 when no method of that shape is there, 5 when the method makes no
		 * such call.
		 *
		 * <p>The answer is a bytecode fact: an {@code @At} target only matches where the method really makes that call,
		 * so a target nothing calls is a handler that would never run. Read with code, because {@link #read} skips it.
		 */
		private int whyNot(String internalOwner, String methodName, String methodDescriptor,
				String targetOwner, String targetName, String targetDescriptor) {
			ClassNode node = codeOf(internalOwner);
			if (node == null) return 3;
			boolean anyMethod = false;
			for (MethodNode method : node.methods) {
				if (!method.name.equals(methodName) || !method.desc.startsWith(methodDescriptor)) continue;
				anyMethod = true;
				if (method.instructions == null) continue;
				for (org.objectweb.asm.tree.AbstractInsnNode instruction : method.instructions) {
					if (instruction instanceof org.objectweb.asm.tree.MethodInsnNode call
							&& call.owner.equals(targetOwner)
							&& call.name.equals(targetName)
							&& call.desc.equals(targetDescriptor)) {
						return 0;
					}
				}
			}
			return anyMethod ? 5 : 4;
		}

		private ClassNode codeOf(String internalOwner) {
			if (!codeTried.add(internalOwner)) {
				Object cached = codeCache.get(internalOwner);
				return cached instanceof ClassNode node ? node : null;
			}
			String entryName = internalOwner + ".class";
			for (Path jar : jars) {
				try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar.toFile())) {
					java.util.zip.ZipEntry found = zip.getEntry(entryName);
					if (found == null) continue;
					ClassNode node = new ClassNode();
					try (java.io.InputStream in = zip.getInputStream(found)) {
						new ClassReader(in).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
					}
					codeCache.put(internalOwner, node);
					return node;
				} catch (IOException | RuntimeException unreadable) {
					// A jar that cannot be opened simply does not answer; the next one may carry the class.
				}
			}
			return null;
		}

		/** The field names the type declares, used to tell a getter from its same-shaped sibling. */
		private java.util.Set<String> fields(String internalOwner) {
			read(internalOwner);
			return declaredFields.getOrDefault(internalOwner, java.util.Set.of());
		}

		/** Every field name of that shape declared by the type or by what it extends or implements. */
		private List<String> fieldsOfShape(String internalOwner, String descriptor) {
			List<String> found = new ArrayList<>(2);
			java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>();
			java.util.Set<String> visited = new java.util.HashSet<>();
			queue.add(internalOwner);
			while (!queue.isEmpty() && visited.size() < 24) {
				String type = queue.poll();
				if (!visited.add(type)) continue;
				read(type);
				found.addAll(fieldShapes.getOrDefault(type, Map.of()).getOrDefault(descriptor, List.of()));
				queue.addAll(parents.getOrDefault(type, List.of()));
			}
			return found;
		}

		private List<BaseMethod> methods(String internalOwner, String descriptor) {
			return cache.computeIfAbsent(internalOwner, this::read).getOrDefault(descriptor, List.of());
		}

		/**
		 * Every method of that shape declared by {@code internalOwner} or by anything it extends or implements.
		 *
		 * <p>A call site names the static receiver: {@code Registry.method_30517()} is declared on a superinterface,
		 * so asking only the named class answered "nothing" and left the intermediary name in the guest's bytecode —
		 * the call then died with NoSuchMethodError at registry-sync time.
		 */
		private List<BaseMethod> declaredByType(String internalOwner, String descriptor) {
			List<BaseMethod> found = new ArrayList<>(2);
			java.util.ArrayDeque<String> pending = new java.util.ArrayDeque<>();
			java.util.Set<String> seen = new java.util.HashSet<>();
			pending.add(internalOwner);
			while (!pending.isEmpty() && seen.size() < 24) {
				String current = pending.poll();
				if (current == null || !seen.add(current)) continue;
				found.addAll(methods(current, descriptor));
				read(current);
				pending.addAll(parents.getOrDefault(current, List.of()));
			}
			return found;
		}

		/** Whether {@code internalOwner} (or a supertype) declares a method of that exact name and shape. */
		private boolean declares(String internalOwner, String descriptor, String name) {
			for (BaseMethod method : declaredByType(internalOwner, descriptor)) {
				if (method.name().equals(name)) return true;
			}
			return false;
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
					List<String> supers = new ArrayList<>(1 + node.interfaces.size());
					if (node.superName != null) supers.add(node.superName);
					supers.addAll(node.interfaces);
					parents.put(internalOwner, List.copyOf(supers));
					Map<String, List<BaseMethod>> byDescriptor = new HashMap<>();
					for (MethodNode method : node.methods) {
						byDescriptor.computeIfAbsent(method.desc, key -> new ArrayList<>(2))
								.add(new BaseMethod(method.name, method.access));
					}
					if (!node.fields.isEmpty()) {
						java.util.Set<String> names = new java.util.HashSet<>(node.fields.size());
						Map<String, List<String>> shapes = new HashMap<>();
						for (org.objectweb.asm.tree.FieldNode field : node.fields) {
							names.add(field.name);
							shapes.computeIfAbsent(field.desc, key -> new ArrayList<>(2)).add(field.name);
						}
						declaredFields.put(internalOwner, names);
						fieldShapes.put(internalOwner, shapes);
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
			// A Fabric mixin names its target the way its source did: net/minecraft/block/Block, not class_2248. The
			// refmap data section supplied those pairs, so translate the owner and the member before the table sees
			// them, and everything downstream (qualified lookup, inheritance fallback, descriptor answer) then works
			// on the intermediary spelling it was written for.
			String intermediaryOwner = yarn.classes.get(owner);
			if (intermediaryOwner == null) {
				// The dictionary only knows the classes this one jar's refmap data section mentions. A call site into
				// anything else arrives in its yarn spelling, and the table is keyed by the intermediary one, so the
				// qualified lookup missed whenever yarn and official disagree about a member's name. Ask the table for
				// the reverse class row instead of giving up: it is the same authority, read in the other direction.
				// Measured: fabric-networking-api-v1 calls MinecraftServer.method_3773()I; without this the name stayed
				// intermediary and the first login tick threw NoSuchMethodError.
				String reversed = KernelMappingResolver.INSTANCE.unmapClassName("intermediary", owner);
				if (reversed != null && !reversed.equals(owner) && reversed.indexOf("class_") >= 0) {
					intermediaryOwner = reversed;
					ownersReversed++;
				}
			}
			if (intermediaryOwner == null) intermediaryOwner = owner;
			// The dictionary only speaks for Minecraft members. Applying it to a JDK owner renamed
			// java/util/stream/Stream.toList into Stream.lambda$fromCodec$4 (yarn's table has that spelling for
			// StreamCodec's own lambda), and the client died in ModResourcePackUtil with a NoSuchMethodError.
			boolean minecraftOwner = mapInternal(intermediaryOwner).startsWith("net/minecraft/");
			String intermediaryName = name;
			String translated = minecraftOwner ? yarn.members.get(name) : null;
			if (translated != null && !name.contains("_")) {
				intermediaryName = translated;
				yarnMembers++;
			}
			owner = intermediaryOwner;
			name = intermediaryName;
			String mapped = KernelMappingResolver.INSTANCE.mapMethodName("intermediary", owner, name, descriptor);
			// A call site names the static receiver type, not the class that declares the method: fabric-api calls
			// Registry.holderByNameCodec through DefaultedRegistry, and the table files that row under Registry — so a
			// qualified miss is usually an inherited member, not an unknown one. Of the 39655 method rows in the table
			// only 10 names map to two different official names, and the resolver refuses those, so the unqualified
			// lookup is safe; it is what turns method_40294 into holderByNameCodec instead of leaving the call to fail
			// with NoSuchMethodError at Bootstrap.
			String resolved = mapped.equals(name) && minecraftOwner
					? KernelMappingResolver.INSTANCE.mapMethodNameWithoutOwner("intermediary", name)
					: mapped;
			if (resolved.equals(name) && INTERMEDIARY_MEMBER.matcher(name).matches()) {
				// The table is not a complete join: Registry alone has 47 method rows, so a member the guest calls
				// through it can still be named method_NNNN after both lookups. The descriptor is not lossy, so ask
				// the base which method it declares with exactly this shape and take it when the answer is one name.
				String byShape = byDescriptor(owner, name, descriptor, true);
				if (byShape != null) {
					descriptorsPicked++;
					return byShape;
				}
			}
			String answered = verifyTheTableAgainstTheBase(owner, name, descriptor,
					lambdaShape(owner, resolved, descriptor));
			if (INTERMEDIARY_MEMBER.matcher(answered).matches()) recordLeftover(answered);
			return answered;
		}

		/**
		 * Checks a name the table just handed out against what the base actually declares, and asks the base instead.
		 *
		 * <p>The table is a join between two namespaces, not a promise: measured rows name
		 * {@code ResourceKey.location()} as {@code lambda$create$2}, {@code Util.sequence} as {@code lambda$allOf$8},
		 * and a {@code Minecraft} getter as {@code getModelManager} with a {@code PackRepository} return type the base
		 * does not have. A row that survives the lambda pass can therefore still be a name nothing accepts, and the
		 * failure only shows up much later as a NoSuchMethodError from inside Bootstrap or resource loading. The
		 * descriptor cannot be mis-joined, so when the mapped name is not declared anywhere the owner hierarchy with
		 * that shape, and exactly one method is, take that one; otherwise leave the name and count it, because a
		 * wrong guess would be worse than the error it hides.
		 */
		private String verifyTheTableAgainstTheBase(String owner, String name, String descriptor, String resolved) {
			String officialOwner = mapInternal(owner);
			if (resolved.equals(name) || !officialOwner.startsWith("net/minecraft/")) return resolved;
			MethodIndex index = minecraftIndex;
			if (index == null) return resolved;
			String officialDescriptor = mapDesc(descriptor);
			if (index.declares(officialOwner, officialDescriptor, resolved)) return resolved;
			String byShape = byDescriptor(owner, name, descriptor, true);
			if (byShape != null) {
				tableNamesCorrected++;
				return byShape;
			}
			tableNamesUnresolved++;
			if (tableNamesUnresolved <= 5) {
				ForbricLog.debug("[Forbric/Remap] the table names %s%s %s, which the base does not declare, and no"
						+ " single method there takes its shape", officialOwner, officialDescriptor, resolved);
			}
			return resolved;
		}

		/**
		 * The name the base declares for a member whose table name is still intermediary, found by its descriptor.
		 *
		 * <p>Returns {@code null} when the index cannot read the owner, when nothing matches, or when more than one
		 * name takes the shape — an ambiguous answer would be a guess.
		 */
		private String byDescriptor(String owner, String name, String descriptor, boolean method) {
			MethodIndex index = minecraftIndex;
			if (index == null) {
				if (shapeProbes-- > 0 && Boolean.getBoolean("forbric.debugShapes")) {
					ForbricLog.info("[Forbric/Shape] no index for " + owner);
				}
				return null;
			}
			List<BaseMethod> candidates = index.declaredByType(mapInternal(owner), mapDesc(descriptor));
			if (shapeProbes-- > 0 && Boolean.getBoolean("forbric.debugShapes")) {
				ForbricLog.info("[Forbric/Shape] " + owner + " " + name + " " + descriptor + " -> "
						+ mapInternal(owner) + mapDesc(descriptor) + " : " + candidates);
			}
			if (candidates.size() != 1) return null;
			BaseMethod only = candidates.get(0);
			if (!only.callable() || only.name().startsWith("lambda$") || only.name().startsWith("<")) return null;
			return only.name();
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
			// Where a guest was compiled against a vanilla method that has since been replaced outright, no amount of
			// index reading can recover the target: the base has several methods with the descriptor and none is
			// named after the old one. These are measured renames, each with the failure they came from.
			String known = KNOWN_LAMBDA_TARGETS.get(officialOwner + '|' + officialDescriptor + '|' + mapped);
			if (known != null) {
				lambdasPicked++;
				return known;
			}
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
			List<String> siblings = new ArrayList<>(byDescriptor.size());
			for (BaseMethod method : byDescriptor) {
				String candidate = method.name();
				if (candidate.startsWith("<")) continue;
				if (candidate.startsWith("lambda$")) {
					// The base keeps its own lambdas under private static synthetic names. A guest built against a
					// different build of the same line can carry the enclosing method's next number, so the shape
					// still matches: one such sibling with exactly this descriptor is the lambda the guest meant.
					if ((method.access() & 0x0008) != 0 && !siblings.contains(candidate)) siblings.add(candidate);
				} else if (method.callable()) {
					// Instance members count: a mis-rolled table row can name a plain getter as a lambda, and the
					// guest's call is invokevirtual ResourceKey.location(), not a static invocation.
					if (!named.contains(candidate)) named.add(candidate);
				}
			}
			if (named.size() == 1) {
				lambdasPicked++;
				return named.get(0);
			}
			// A getter is named after the field it returns. Measured: the table rows class_5321.method_29177 onto
			// lambda$create$2, so ResourceKey location() arrives here as a lambda name; location() and registry() both
			// answer ()Lnet/minecraft/resources/ResourceLocation;, and only the first shares its name with a field the
			// class declares. Picking on that basis leaves every non-getter tie alone.
			java.util.Set<String> fields = index.fields(officialOwner);
			List<String> getters = new ArrayList<>(1);
			for (String candidate : named) if (fields.contains(candidate)) getters.add(candidate);
			if (getters.size() == 1) {
				lambdasPicked++;
				return getters.get(0);
			}
			if (named.isEmpty() && siblings.size() == 1) {
				// Measured: fabric-registry-sync calls ResourceKey.lambda$create$2(); this line numbers that lambda
				// differently, and it is the only static member of ResourceKey with the shape.
				lambdasPicked++;
				return siblings.get(0);
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
			// Second tie-break, same idea one level up: vanilla names the "narrowed" siblings after the plain one —
			// holderRegistry/holderSet wrap what registry returns, optionalX and listX wrap what X returns. Measured
			// case: ByteBufCodecs.registry / holderRegistry / holderSet all take (ResourceKey) and answer a
			// StreamCodec, and fabric's transfer module wants the Item the registry yields (it casts the result with
			// ItemVariant::getItem). A lone candidate that is none of those wrappers is the plain reading.
			List<String> plain = new ArrayList<>(readers.size());
			for (String candidate : readers) {
				if (!wrapsAnother(candidate, readers)) plain.add(candidate);
			}
			if (plain.size() == 1 && plain.size() < readers.size()) {
				lambdasPicked++;
				ForbricLog.debug("[Forbric/Remap] %s%s is ambiguous, and %s is the plain reading among %s",
						officialOwner, officialDescriptor, plain.get(0), named);
				return plain.get(0);
			}
			lambdasAmbiguous++;
			return mapped;
		}

		/** True when {@code candidate} is named after another candidate here, as holderX wraps what X returns. */
		private static boolean wrapsAnother(String candidate, List<String> candidates) {
			for (String other : candidates) {
				if (other.equals(candidate) || other.length() <= 2) continue;
				if (!candidate.endsWith(Character.toUpperCase(other.charAt(0)) + other.substring(1))) continue;
				String stem = candidate.substring(0, candidate.length() - other.length());
				if (stem.equals("holder") || stem.equals("optional") || stem.equals("list") || stem.equals("set")
						|| stem.equals("map") || stem.equals("raw") || stem.equals("boxed")) {
					return true;
				}
			}
			return false;
		}

		@Override
		public String mapFieldName(String owner, String name, String descriptor) {
			// Same two-hop translation as mapMethodName: a refmap entry can name a field in Yarn spelling, and the
			// table only knows the intermediary one.
			String intermediaryOwner = yarn.classes.get(owner);
			if (intermediaryOwner != null) owner = intermediaryOwner;
			boolean minecraftOwner = mapInternal(owner).startsWith("net/minecraft/");
			String translated = minecraftOwner ? yarn.members.get(name) : null;
			if (translated != null && !translated.startsWith("method_") && !name.contains("_")) {
				name = translated;
				yarnMembers++;
			}
			String mapped = KernelMappingResolver.INSTANCE.mapFieldName("intermediary", owner, name, descriptor);
			return mapped.equals(name) && minecraftOwner
					? KernelMappingResolver.INSTANCE.mapFieldNameWithoutOwner("intermediary", name)
					: mapped;
		}

		@Override
		public String mapRecordComponentName(String owner, String name, String descriptor) {
			return mapFieldName(owner, name, descriptor);
		}
	}

	/** The cache salt: bump {@code CACHE_VERSION} whenever the rewriting itself changes. */
	private static final String CACHE_VERSION = "8";

	private static byte[] fingerprintOf(byte[] jarBytes) {
		String table = System.getProperty(KernelMappingResolver.TABLE_PROPERTY, "");
		String tableIdentity = "none";
		if (!table.isEmpty()) {
			java.nio.file.Path path = java.nio.file.Path.of(table);
			if (java.nio.file.Files.isRegularFile(path)) {
				try {
					tableIdentity = java.nio.file.Files.size(path) + "@" + java.nio.file.Files.getLastModifiedTime(path).toMillis();
				} catch (java.io.IOException unreadable) {
					tableIdentity = "unreadable";
				}
			}
		}
		byte[] prefix = (CACHE_VERSION + '|' + tableIdentity + '|').getBytes(java.nio.charset.StandardCharsets.UTF_8);
		byte[] combined = new byte[prefix.length + jarBytes.length];
		System.arraycopy(prefix, 0, combined, 0, prefix.length);
		System.arraycopy(jarBytes, 0, combined, prefix.length, jarBytes.length);
		return combined;
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
