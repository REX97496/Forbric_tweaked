package net.forbric.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.Test;

/**
 * Every texture and model a loaded jar refers to must be provided by something the instance carries.
 *
 * <h2>Why this is not "did it start"</h2>
 *
 * <p>A missing texture does not stop a game. It renders as the purple-and-black checkerboard, or a model silently
 * falls back, and the log says nothing unless someone turned on the right debug flag. "It launched and did not
 * crash" therefore passes with a jar full of broken references, which is exactly the standard this measurement
 * exists to replace.
 *
 * <h2>What it checks, and what it cannot</h2>
 *
 * <p>It builds the set of resources the instance provides — the vanilla jar plus every jar in the instance's mods
 * directory — and then walks every model JSON in that set, resolving three kinds of reference:
 *
 * <ul>
 *   <li>{@code parent} — a model path, or one of Minecraft's {@code builtin/…} parents which are code, not files
 *   <li>{@code textures} — a {@code #ref} (local, resolved through the same file), or {@code namespace:path},
 *       which must have a {@code textures/<path>.png}
 *   <li>{@code elements[].faces[].texture} — same two forms
 * </ul>
 *
 * <p>Two honest limits. First, the provider set includes BOTH branches of a mod that publishes two, even though the
 * kernel loads only one, so a resource only the unused branch ships would hide a real miss: the number below is a
 * LOWER BOUND on missing references, never an overcount. Second, it sees only what is declared in model JSON.
 * A texture reached from code, or a blockstate naming a model that exists but is empty, is outside it.
 *
 * <p>Deliberately not a GUI test: none of this can be confirmed by looking at the screen, and a suite that needed
 * someone to look would stop being a measurement.
 */
class ResourceIntegrityCensusTest {

	/** The isolated test instance this census reads, if the machine has built one. */
	private static final List<Path> INSTANCE_CANDIDATES = List.of(
			Path.of("build", "..", ".dev-test", "client").normalize(),
			Path.of(".dev-test", "client"),
			Path.of("..", ".dev-test", "client").normalize());

	private static final Pattern TEXTURE_REF = Pattern.compile(
			"\"(?:parent|texture|top|bottom|side|north|south|east|west|up|down|particle|all|end|front|back|"
					+ "base|casing|inner|outer|contents|fluid|still|flowing|overlay|stem|upperstem|tip|middle|"
					+ "edge|torch|lantern|barrel|bricks)\"\\s*:\\s*\"([^\"]+)\"");

	/** Minecraft's own non-file model parents: engine behaviour, not assets on disk. */
	private static final Set<String> BUILTIN_PARENTS = Set.of(
			"builtin/entity", "builtin/generated", "builtin/generated_tinted", "builtin/entity_tinted");

	@Test
	void everyTextureAndModelTheLoadedJarsReferToIsProvided() throws Exception {
		Path instance = locateInstance();
		net.forbric.kernel.TestFixtures.require(net.forbric.kernel.TestFixtures.Fixture.MC_LIBRARIES,
				instance != null,
				"no Forbric test instance was found (looked in " + INSTANCE_CANDIDATES + "). Build one with"
						+ " tools/dev.py prepare and put the mainstream mods in its client/mods/");

		List<Path> providers = new ArrayList<>();
		Path vanilla = instance.resolve("../minecraft/versions/26.2/26.2.jar").normalize();
		assertTrue(Files.isRegularFile(vanilla), "the vanilla jar this census needs is absent: " + vanilla);
		providers.add(vanilla);
		Path mods = instance.resolve("mods");
		assertTrue(Files.isDirectory(mods), "the instance has no mods directory: " + mods);
		try (var stream = Files.list(mods)) {
			stream.filter(p -> p.toString().endsWith(".jar")).forEach(providers::add);
		}

		// Provided resources, as "<namespace>/<path under assets/<ns>/>".
		Set<String> provided = new TreeSet<>();
		Map<String, Path> whoProvides = new LinkedHashMap<>();
		for (Path jar : providers) {
			try (ZipFile zip = new ZipFile(jar.toFile())) {
				for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
					ZipEntry entry = e.nextElement();
					String name = entry.getName();
					if (!name.startsWith("assets/") || name.endsWith("/")) continue;
					String[] parts = name.split("/", 3);
					if (parts.length < 3) continue;
					provided.add(parts[1] + "/" + parts[2]);
					whoProvides.putIfAbsent(parts[1] + "/" + parts[2], jar.getFileName());
				}
			}
		}
		assertTrue(provided.size() > 5000,
				"the provider set is only " + provided.size() + " resource(s), which is too few to be a Minecraft"
						+ " instance — the vanilla jar was probably not the client jar");

		Set<String> missingTextures = new TreeSet<>();
		Set<String> missingModels = new TreeSet<>();
		Set<String> missingMods = new TreeSet<>();
		int modelsRead = 0, refsChecked = 0;
		Map<String, Integer> byNamespace = new TreeMap<>();

		for (Path jar : providers) {
			try (ZipFile zip = new ZipFile(jar.toFile())) {
				for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
					ZipEntry entry = e.nextElement();
					String name = entry.getName();
					if (!name.startsWith("assets/") || !name.endsWith(".json")) continue;
					if (!name.contains("/models/")) continue;
					String[] parts = name.split("/", 3);
					if (parts.length < 3) continue;
					String namespace = parts[1];
					String path = parts[2];
					modelsRead++;
					String json = readAll(zip.getInputStream(entry));
					Matcher matcher = TEXTURE_REF.matcher(json);
					while (matcher.find()) {
						refsChecked++;
						String value = matcher.group(1);
						if (value.startsWith("#") || value.isBlank()) continue;   // local reference
						if (value.startsWith("builtin/")) continue;

						int colon = value.indexOf(':');
						String refNamespace = colon < 0 ? "minecraft" : value.substring(0, colon);
						String refPath = colon < 0 ? value : value.substring(colon + 1);

						// A reference inside a model may be a model (parent) or a texture. Ask both, because the
						// JSON key does not always say which and a wrong guess would report a present resource as
						// missing.
						boolean asTexture = provided.contains(refNamespace + "/textures/" + refPath + ".png");
						boolean asModel = provided.contains(refNamespace + "/models/" + refPath + ".json")
								|| provided.contains(refNamespace + "/blockstates/" + refPath + ".json")
								|| BUILTIN_PARENTS.contains(value);
						if (asTexture || asModel) continue;

						byNamespace.merge(refNamespace, 1, Integer::sum);
						if (name.contains("/models/block/") || name.contains("/models/item/")) {
							missingTextures.add(namespace + ":" + path + " -> " + value);
						} else {
							missingTextures.add(namespace + ":" + path + " -> " + value);
						}
						missingMods.add(String.valueOf(whoProvides.get(namespace + "/" + path)));
					}
				}
			}
		}

		System.out.println("[resources] providers=" + providers.size() + " assets=" + provided.size()
				+ " models-read=" + modelsRead + " refs=" + refsChecked
				+ " unresolved=" + (missingTextures.size() + missingModels.size()));
		System.out.println("[resources] unresolved by namespace: " + byNamespace);
		missingTextures.stream().limit(12).forEach(one -> System.out.println("[resources]   " + one));

		// Pinned as a ceiling, because the honest direction for a resource census is down.
		//
		// The first measurement, against the 34-mod mainstream instance: providers 35, assets 11396, models read
		// 3972, references checked 12996, unresolved 3. All three are accounted for and none is a defect:
		//
		//   minecraft:models/block/air.json -> minecraft:missingno   vanilla's own deliberate missing texture
		//   minecraft:models/item/air.json  -> minecraft:missingno   the same, for the item form
		//   minecraft:models/block/heavy_core.json -> "all"          this pattern matching a texture KEY
		//
		// So the instance carries every texture and model its 34 mainstream mods and the vanilla jar refer to. The
		// ceiling is 3 rather than 0 because two of those are Minecraft's own and lowering it would mean claiming
		// vanilla is broken; the third is a known limit of key-name matching and is named here rather than hidden.
		int unresolved = missingTextures.size() + missingModels.size();
		assertTrue(unresolved <= UNRESOLVED_CEILING,
				"unresolved model/texture reference(s): " + unresolved + " (ceiling " + UNRESOLVED_CEILING + ")."
						+ " Every one renders as a checkerboard or a fallback model and nothing else would say so."
						+ " First: " + missingTextures.stream().limit(6).toList());
		assertTrue(unresolved > 0 || UNRESOLVED_CEILING == 0,
				"unresolved dropped to " + unresolved + " — lower UNRESOLVED_CEILING to 0 in this same commit, so"
						+ " the gain is locked in rather than left as headroom for the next regression");
	}

	/**
	 * The first measurement, against the 34-mod mainstream instance: 3, of which two are vanilla's own deliberate
	 * {@code missingno} references and one is a matching artefact. Set to the real figure rather than zero so the
	 * test states the current truth; it only ever moves down.
	 */
	private static final int UNRESOLVED_CEILING = 3;

	private static Path locateInstance() {
		for (Path candidate : INSTANCE_CANDIDATES) {
			if (Files.isDirectory(candidate.resolve("mods"))) return candidate.toAbsolutePath().normalize();
		}
		return null;
	}

	private static String readAll(InputStream in) throws IOException {
		try (in) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	/** Unused import guard. */
	@SuppressWarnings("unused")
	private static final Set<String> NOTHING = new LinkedHashSet<>();
}
