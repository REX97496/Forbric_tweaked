/*
 * Copyright 2026 The Forbric Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.forbric.installer.kernel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Reproduces {@code run/build-patched-forge.sh} in pure Java: produce the traditional-MinecraftForge-patched,
 * Mojmap-named Minecraft game jar Forbric loads under Knot. Pipeline:
 * <ol>
 *   <li>{@code installertools BUNDLER_EXTRACT} the Mojang server jar; client jar is the user's own install.</li>
 *   <li>{@code mergetool --merge ... --ann API} (Forge binpatches target the {@code @OnlyIn}-annotated merge).</li>
 *   <li>{@code binarypatcher --apply joined.lzma} (patches + ATs extracted from the Forge {@code -userdev} jar).</li>
 *   <li>overlay the patched classes onto the clean merge + strip the Mojang jar signature.</li>
 *   <li>apply Forge's access transformers via Forge's own {@code AccessTransformerEngine}.</li>
 * </ol>
 * The result embeds Mojang code, so it is built here and never redistributed.
 */
final class PatchedMcBuilder {

	// Tool coordinates the userdev config.json does NOT carry (the dev script hard-pins these versions).
	private static final String MERGETOOL = "net.minecraftforge:mergetool:1.2.5:fatjar";
	private static final String INSTALLERTOOLS = "net.minecraftforge:installertools:1.3.2:fatjar";
	private static final String LOG4J_API = "org.apache.logging.log4j:log4j-api:2.24.3";
	private static final String LOG4J_CORE = "org.apache.logging.log4j:log4j-core:2.24.3";
	private static final String[] ASM_ARTIFACTS = {"asm", "asm-tree", "asm-commons", "asm-util", "asm-analysis"};

	private final ForgeArtifacts fa;
	private final Http http;
	private final ForgeTool tool;
	private final Path mcDir;
	private final Path workDir;
	private final Path dlDir;
	private final Path outJar;
	private final Consumer<String> log;

	PatchedMcBuilder(ForgeArtifacts fa, Http http, Path mcDir, Path workDir, Path outJar, Consumer<String> log) {
		this.fa = fa;
		this.http = http;
		this.tool = new ForgeTool(log);
		this.mcDir = mcDir;
		this.workDir = workDir;
		this.dlDir = workDir.resolve("dl");
		this.outJar = outJar;
		this.log = log;
	}

	ArtifactResult build(Path userdevJar, ForgeArtifacts.UserdevConfig cfg, Path forgeRuntimeJar) throws IOException {
		String coordinate = fa.patchedMcCoordinate();
		if (BuildStamp.isFresh(outJar)) {
			log.accept("[patched] up-to-date: " + outJar.getFileName());
			return new ArtifactResult(coordinate, outJar, Util.sha1(outJar), Files.size(outJar));
		}
		Files.createDirectories(dlDir);

		// 1) tools
		log.accept("[patched] fetching tools + Forge userdev");
		Path mergetool = dl(MERGETOOL);
		Path installertools = dl(INSTALLERTOOLS);
		Path binarypatcher = dl(cfg.binpatcherCoordinate);
		String atCoord = findLib(cfg, "net.minecraftforge", "accesstransformers", "net.minecraftforge:accesstransformers:8.2.2");
		String asmVersion = asmVersion(cfg);
		Path atJar = dl(atCoord);
		List<Path> engineCp = new ArrayList<>();
		engineCp.add(atJar);
		for (String a : ASM_ARTIFACTS) engineCp.add(dlCentral("org.ow2.asm:" + a + ":" + asmVersion));
		engineCp.add(dlCentral(LOG4J_API));
		engineCp.add(dlCentral(LOG4J_CORE));

		// 2) client jar from the user's install
		Path clientJar = mcDir.resolve("versions").resolve(fa.mcVersion).resolve(fa.mcVersion + ".jar");
		if (!Files.isRegularFile(clientJar)) {
			throw new IOException("client jar not found: " + clientJar + " — install/download vanilla "
					+ fa.mcVersion + " first");
		}

		// 3) server jar via <mc>.json downloads.server
		Path serverJar = dlDir.resolve("server.jar");
		downloadServer(serverJar);

		// 4) BUNDLER_EXTRACT + merge (--ann API)
		Path serverMain = workDir.resolve("server-main.jar");
		tool.runJar(installertools, List.of("--task", "BUNDLER_EXTRACT",
				"--input", serverJar.toString(), "--output", serverMain.toString(), "--jar-only"),
				"installertools BUNDLER_EXTRACT");
		Path clean = workDir.resolve("clean.jar");
		tool.runJar(mergetool, List.of("--merge", "--client", clientJar.toString(),
				"--server", serverMain.toString(), "--output", clean.toString(),
				"--keep-data", "--keep-meta", "--ann", "API"),
				"mergetool --merge --ann API");

		// 4b) deobfuscate. Forge's binpatches are built against the Mojmap-named jar, so the rename has to happen
		//     between the merge and binarypatcher. Skipped entirely for a version that publishes no obfuscation
		//     mappings — 26.x ships Mojmap-native, so there the jar already is the jar the patches expect and this
		//     step must not run at all (see MojmapMappings for why the version JSON is the gate).
		clean = deobfuscate(clean, engineCp);

		// 5) extract binpatches + ATs from the userdev jar
		Zips.extractEntries(userdevJar, workDir, cfg.binpatchesEntry, cfg.ats.get(0));
		Path joinedLzma = workDir.resolve(cfg.binpatchesEntry);
		Path atCfg = workDir.resolve(cfg.ats.get(0));

		// 6) binarypatcher --apply
		Path patchedSubset = workDir.resolve("patched-subset.jar");
		tool.runJar(binarypatcher, binpatcherArgs(cfg, clean, patchedSubset, joinedLzma),
				"binarypatcher --apply " + cfg.binpatchesEntry);

		// 7) overlay patched classes onto the clean merge + strip signatures
		log.accept("[patched] overlay patched classes onto clean + strip signatures");
		Path patchedFull = workDir.resolve("patched-full.jar");
		overlay(clean, patchedSubset, patchedFull);

		// 8) apply access transformers
		Path patchedAt = workDir.resolve("patched-at.jar");
		tool.applyAccessTransformers(engineCp, atCfg, patchedFull, patchedAt);

		// 9) inject a concrete covariant self() into MC classes implementing a public-self() Forge interface
		//    (IForgeLivingEntity/LivingEntity) — Forge's public interface default does not resolve on subclasses
		//    under Forbric's flat Knot classloader (ServerPlayer AbstractMethodError). Reuses the AT step's ASM.
		Files.createDirectories(outJar.getParent());
		int injected = tool.injectCovariantSelf(engineCp, forgeRuntimeJar, patchedAt, outJar);
		log.accept("[patched] injected concrete self() into " + injected + " class(es)");

		String sha1 = Util.sha1(outJar);
		long size = Files.size(outJar);
		log.accept("[patched] wrote " + outJar.getFileName() + " (" + (size / (1024 * 1024)) + " MB)");
		BuildStamp.write(outJar);
		return new ArtifactResult(coordinate, outJar, sha1, size);
	}

	// ---- steps ----

	/**
	 * Rewrite {@code clean.jar} from obfuscated names to Mojang's, returning the jar the rest of the pipeline must
	 * use. Returns the input unchanged — with no new file — in two cases, and both of them are what keeps 26.2 a
	 * byte-identical no-op:
	 *
	 * <ol>
	 *   <li><b>The version publishes no obfuscation mappings.</b> {@code downloads.client_mappings} is absent from
	 *       an unobfuscated version's JSON ({@code 26.2.json} carries only {@code client} and {@code server}), and its
	 *       presence is exactly the condition "this jar is obfuscated, so Forge's patches are against the other
	 *       names". This is a data gate, not a version string comparison: bumping {@link Pins#MINECRAFT} to a
	 *       different obfuscated version needs no code change, and 26.2 cannot enter the step by accident.</li>
	 *   <li><b>The mapping renames nothing in this jar.</b> A guard against a stale or wrong-version mapping file:
	 *       if no class entry name changes, the mapping does not apply and the original jar is kept, rather than
	 *       being re-serialized for no reason (ASM rebuilds the constant pool, so an "identity" pass would still
	 *       change bytes and could only ever make the binpatch checksums worse).</li>
	 * </ol>
	 */
	private Path deobfuscate(Path clean, List<Path> asmClasspath) throws IOException {
		Path mappingsFile = mappingsFile();
		if (mappingsFile == null) {
			log.accept("[patched] no obfuscation mappings published for " + fa.mcVersion
					+ " — the vanilla jar is already Mojmap-named, skipping deobfuscation");
			return clean;
		}
		MojmapMappings.Mappings mappings = MojmapMappings.parse(mappingsFile);
		log.accept("[patched] deobfuscating " + clean.getFileName() + " with " + mappings.summary()
				+ " from " + mappingsFile.getFileName());
		if (mappings.unmappedTypes > 0) {
			log.accept("[patched] " + mappings.unmappedTypes + " member descriptor type(s) are not declared by the"
					+ " mapping (the JDK, Guava, fastutil): those names are identical in both namespaces and are kept");
		}
		Path renamed = workDir.resolve("clean-mojmap.jar");
		MojmapRenamer.Result result = MojmapRenamer.rename(asmClasspath, clean, renamed, mappings, log);
		if (result.renamedEntries == 0) {
			log.accept("[patched] the mapping renames none of the " + result.classes + " class entries in "
					+ clean.getFileName() + " — not a jar this mapping applies to, keeping it as-is");
			Files.deleteIfExists(renamed);
			return clean;
		}
		log.accept("[patched] renamed " + result.renamedEntries + " of " + result.classes + " class entries, rewrote "
				+ result.changedBytes + ", set " + result.sourceFixed + " SourceFile attribute(s)");
		return renamed;
	}

	/**
	 * The {@code client.txt} to deobfuscate with, or {@code null} when this Minecraft version is not obfuscated.
	 *
	 * <p>{@code -Dforbric.mojang.mappings=<file>} overrides the download, which is what makes the step reproducible
	 * on a machine with no network. Otherwise the file is fetched from the URL the version JSON itself carries and
	 * checked against that JSON's sha1 — the same provenance rule the server jar already follows.
	 */
	private Path mappingsFile() throws IOException {
		String override = System.getProperty("forbric.mojang.mappings");
		if (override != null && !override.isBlank()) {
			Path file = Path.of(override);
			if (!Files.isRegularFile(file)) {
				throw new IOException("-Dforbric.mojang.mappings points at a missing file: " + file);
			}
			return file;
		}
		Object mappings = downloads(versionJson(), "client_mappings");
		if (mappings == null) return null; // unobfuscated version: nothing to rename
		Map<String, Object> entry = asMap(mappings);
		String url = (String) entry.get("url");
		String sha1 = (String) entry.get("sha1");
		if (url == null) return null;
		Path dest = dlDir.resolve("client-mappings-" + fa.mcVersion + ".txt");
		if (Files.isRegularFile(dest) && Files.size(dest) > 0
				&& (sha1 == null || sha1.equalsIgnoreCase(Util.sha1(dest)))) {
			return dest;
		}
		log.accept("[patched] downloading " + fa.mcVersion + " client mappings …");
		http.downloadToFile(url, dest);
		ForgeTool.verifySha1(dest, sha1, fa.mcVersion + " client mappings");
		return dest;
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> versionJson() throws IOException {
		Path versionJson = mcDir.resolve("versions").resolve(fa.mcVersion).resolve(fa.mcVersion + ".json");
		if (!Files.isRegularFile(versionJson)) {
			throw new IOException("version json not found: " + versionJson + " — download vanilla " + fa.mcVersion + " first");
		}
		try {
			return (Map<String, Object>) Json.parse(Files.readString(versionJson));
		} catch (RuntimeException e) {
			throw new IOException("malformed " + versionJson.getFileName() + ": " + e.getMessage(), e);
		}
	}

	@SuppressWarnings("unchecked")
	private static Object downloads(Map<String, Object> root, String key) {
		Object downloads = root.get("downloads");
		return downloads instanceof Map ? ((Map<String, Object>) downloads).get(key) : null;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> asMap(Object o) {
		return o instanceof Map ? (Map<String, Object>) o : Map.of();
	}

	private List<String> binpatcherArgs(ForgeArtifacts.UserdevConfig cfg, Path clean, Path output, Path patch) {
		List<String> out = new ArrayList<>();
		for (String tok : cfg.binpatcherArgs) {
			switch (tok) {
				case "{clean}":  out.add(clean.toString()); break;
				case "{output}": out.add(output.toString()); break;
				case "{patch}":  out.add(patch.toString()); break;
				default:         out.add(tok);
			}
		}
		return out;
	}

	@SuppressWarnings("unchecked")
	private void downloadServer(Path dest) throws IOException {
		Path versionJson = mcDir.resolve("versions").resolve(fa.mcVersion).resolve(fa.mcVersion + ".json");
		if (!Files.isRegularFile(versionJson)) {
			throw new IOException("version json not found: " + versionJson + " — download vanilla " + fa.mcVersion + " first");
		}
		Map<String, Object> root;
		try {
			root = (Map<String, Object>) Json.parse(Files.readString(versionJson));
		} catch (RuntimeException e) {
			throw new IOException("malformed " + versionJson.getFileName() + ": " + e.getMessage(), e);
		}
		Object downloads = root.get("downloads");
		Map<String, Object> server = downloads instanceof Map ? (Map<String, Object>) ((Map<String, Object>) downloads).get("server") : null;
		if (server == null || server.get("url") == null) {
			throw new IOException(fa.mcVersion + ".json has no downloads.server.url (a client-only version can't be Forge-patched)");
		}
		String url = (String) server.get("url");
		String sha1 = (String) server.get("sha1");
		if (Files.isRegularFile(dest) && Files.size(dest) > 0) {
			// trust a cached server jar only if its sha1 still matches
			if (sha1 == null || sha1.equalsIgnoreCase(Util.sha1(dest))) return;
		}
		log.accept("[patched] downloading " + fa.mcVersion + " server jar …");
		http.downloadToFile(url, dest);
		ForgeTool.verifySha1(dest, sha1, fa.mcVersion + " server.jar");
	}

	/** Overlay patched classes onto the clean merge, strip signatures, keep only the manifest main section. */
	static void overlay(Path clean, Path patchedSubset, Path out) throws IOException {
		LinkedHashMap<String, byte[]> pe = Zips.readAll(patchedSubset);
		LinkedHashMap<String, byte[]> cleanAll = Zips.readAll(clean);
		LinkedHashMap<String, byte[]> result = new LinkedHashMap<>();
		for (Map.Entry<String, byte[]> e : cleanAll.entrySet()) {
			String n = e.getKey();
			if (Zips.isSignatureFile(n)) continue;
			if (n.equals("META-INF/MANIFEST.MF")) {
				result.put(n, manifestMainSection(e.getValue()));
				continue;
			}
			result.put(n, pe.containsKey(n) ? pe.get(n) : e.getValue());
		}
		for (Map.Entry<String, byte[]> e : pe.entrySet()) {
			if (!result.containsKey(e.getKey())) result.put(e.getKey(), e.getValue());
		}
		Zips.writeJar(out, result);
	}

	/** Keep only the manifest's main section (re-serialized classes fail the original per-file SHA digests). */
	private static byte[] manifestMainSection(byte[] raw) {
		String text = new String(raw, StandardCharsets.UTF_8);
		String main = text.split("\r\n\r\n", 2)[0];
		main = main.split("\n\n", 2)[0];
		main = rstrip(main) + "\r\n";
		return main.getBytes(StandardCharsets.UTF_8);
	}

	// ---- download helpers ----

	private Path dl(String coordinate) throws IOException {
		String file = fileName(coordinate);
		Path dest = dlDir.resolve(file);
		http.ensureWithFallback(fa.forgeUrl(coordinate), fa.centralUrl(coordinate), dest);
		return dest;
	}

	private Path dlCentral(String coordinate) throws IOException {
		String file = fileName(coordinate);
		Path dest = dlDir.resolve(file);
		http.ensureWithFallback(fa.centralUrl(coordinate), fa.forgeUrl(coordinate), dest);
		return dest;
	}

	private static String fileName(String coordinate) {
		String rel = Util.coordinateToPath(ForgeArtifacts.stripExtension(coordinate));
		return rel.substring(rel.lastIndexOf('/') + 1);
	}

	private static String findLib(ForgeArtifacts.UserdevConfig cfg, String group, String artifact, String dflt) {
		for (String lib : cfg.libraries) {
			String[] p = lib.split(":");
			if (p.length >= 2 && p[0].equals(group) && p[1].equals(artifact)) return lib;
		}
		return dflt;
	}

	private static String asmVersion(ForgeArtifacts.UserdevConfig cfg) {
		for (String lib : cfg.libraries) {
			String[] p = lib.split(":");
			if (p.length >= 3 && p[0].equals("org.ow2.asm") && p[1].equals("asm")) return p[2];
		}
		return "9.9.1";
	}

	private static String rstrip(String s) {
		int end = s.length();
		while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) end--;
		return s.substring(0, end);
	}
}
