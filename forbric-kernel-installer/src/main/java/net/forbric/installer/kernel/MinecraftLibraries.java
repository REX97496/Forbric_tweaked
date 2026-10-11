/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.installer.kernel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The base version's libraries, and getting them onto disk before anything needs to read them.
 *
 * <h2>Why the merge needs this and nothing provided it</h2>
 *
 * <p>{@link ArtifactBuilder} hands {@code mcDir/libraries} to the merge, and the merge's proof engine reads that
 * directory for the classes it has to reason about — Guava among them, because proving
 * {@code ImmutableList.Builder.build()} non-null means reading {@code ImmutableList.builder()}. Nothing ever
 * downloaded those files: {@link Installer} only LISTS them, as launcher-substituted paths, and a launcher
 * populates the real ones on its first launch.
 *
 * <p>That works when installing into a {@code .minecraft} the user has already played. It does not work for
 * {@code tools/dev.py prepare}, which builds an isolated {@code .dev/minecraft} that no launcher has ever
 * touched, and it does not work for an install onto a machine that has never launched this version. The merge
 * then fails on the FIRST library it needs, with a message about the bytecode rather than about the missing
 * file, and — because the pipeline cannot get past the merge — the step that would have fetched them never runs.
 * A clean checkout could not complete {@code prepare} at all. See
 * {@link net.forbric.tools.NonNullReturnProof} for the other half of that: the proof now names the classes it
 * could not read, so the cause is visible even when this has not run.
 *
 * <p>So they are fetched before the merge. The platform rule filter lives here rather than being written twice:
 * {@link Installer} uses it for the profile's paths and this class uses it for the download list, and those two
 * must agree about which libraries this machine wants.
 */
final class MinecraftLibraries {

	/** One library the base version declares for this platform, with where to get it. */
	record Library(String name, String path, String url, String sha1, long size) {
	}

	private MinecraftLibraries() {
	}

	/**
	 * Every library {@code baseJson} declares that this platform needs and that carries a downloadable artifact,
	 * in the version's own order.
	 *
	 * <p>Entries without {@code downloads.artifact} are skipped. Those are the natives-only declarations, which
	 * a launcher extracts rather than putting on a classpath, and the merge reads classes.
	 */
	@SuppressWarnings("unchecked")
	static List<Library> declared(Map<String, Object> baseJson) {
		List<Library> out = new ArrayList<>();
		Object libraries = baseJson.get("libraries");
		if (!(libraries instanceof List<?> list)) return out;
		for (Object entry : list) {
			if (!(entry instanceof Map<?, ?> raw)) continue;
			Map<String, Object> library = (Map<String, Object>) raw;
			if (!onThisPlatform(library)) continue;
			Object downloads = library.get("downloads");
			if (!(downloads instanceof Map<?, ?> d)) continue;
			Object artifactRaw = ((Map<String, Object>) d).get("artifact");
			if (!(artifactRaw instanceof Map<?, ?> a)) continue;
			Map<String, Object> artifact = (Map<String, Object>) a;
			Object path = artifact.get("path");
			Object url = artifact.get("url");
			if (!(path instanceof String relative) || relative.isBlank()) continue;
			if (!(url instanceof String source) || source.isBlank()) continue;
			Object sha1 = artifact.get("sha1");
			Object size = artifact.get("size");
			out.add(new Library(String.valueOf(library.get("name")), relative, source,
					sha1 instanceof String declared ? declared : null,
					size instanceof Number n ? n.longValue() : -1L));
		}
		return out;
	}

	/**
	 * Downloads whatever of {@code libraries} is not already in {@code mcDir/libraries}, and says how many it
	 * fetched.
	 *
	 * <p>Only what this fetches is hashed. A file that is already there came from a launcher that verified it, and
	 * re-hashing a few hundred of them on every install would cost seconds to catch something those launchers
	 * already check; a truncated one is caught by the size comparison instead. What this downloaded is hashed,
	 * because a body that stopped early is the failure this whole class exists to prevent — it would become a
	 * class the merge cannot read, which looks exactly like unsafe code.
	 *
	 * @return how many were fetched, so a caller can say something only when it did work
	 */
	static int ensure(Path mcDir, List<Library> libraries, Http http, Consumer<String> log) throws IOException {
		Path root = mcDir.resolve("libraries");
		int fetched = 0;
		int present = 0;
		for (Library library : libraries) {
			Path dest = root.resolve(library.path());
			if (alreadyThere(dest, library)) {
				present++;
				continue;
			}
			http.ensure(library.url(), dest);
			if (library.sha1() != null && !library.sha1().equalsIgnoreCase(Util.sha1(dest))) {
				throw new IOException("the downloaded " + library.name() + " does not match the sha1 the version "
						+ "declares, so it would be a class the merge could not read: " + dest);
			}
			fetched++;
		}
		log.accept("[libraries] " + (fetched == 0
				? present + " Minecraft library jar(s) already present under " + root
				: "fetched " + fetched + " Minecraft library jar(s) the merge has to read (" + present
						+ " already present) into " + root));
		return fetched;
	}

	/** Reads {@code versions/<mc>/<mc>.json}, the manifest both the merge and the profile are derived from. */
	static Map<String, Object> versionJson(Path mcDir, String mcVersion) throws IOException {
		Path json = mcDir.resolve("versions").resolve(mcVersion).resolve(mcVersion + ".json");
		if (!Files.isRegularFile(json)) {
			throw new IOException("the base version manifest is missing, so the libraries the merge reads cannot "
					+ "be resolved: " + json);
		}
		Object parsed = Json.parse(Files.readString(json, StandardCharsets.UTF_8));
		if (!(parsed instanceof Map<?, ?> map)) {
			throw new IOException("the base version manifest is not a JSON object: " + json);
		}
		@SuppressWarnings("unchecked")
		Map<String, Object> result = (Map<String, Object>) map;
		return result;
	}

	private static boolean alreadyThere(Path file, Library library) {
		try {
			if (!Files.isRegularFile(file)) return false;
			long size = Files.size(file);
			if (size == 0) return false;
			return library.size() <= 0 || size == library.size();
		} catch (IOException | RuntimeException unreadable) {
			return false;
		}
	}

	/**
	 * Mojang's rule list, read the way a launcher reads it: last matching rule wins, default allow.
	 *
	 * <p>Package-private rather than private because {@link Installer} needs the same answer for the profile it
	 * writes. Two copies of this would eventually disagree, and the disagreement would be a library on the
	 * classpath that nothing downloaded.
	 */
	@SuppressWarnings("unchecked")
	static boolean onThisPlatform(Map<String, Object> library) {
		Object rules = library.get("rules");
		if (!(rules instanceof List<?> list) || list.isEmpty()) return true;
		String os = osName();
		boolean allowed = false;
		for (Object entry : list) {
			if (!(entry instanceof Map<?, ?> rule)) continue;
			Object specRaw = ((Map<String, Object>) rule).get("os");
			if (specRaw instanceof Map<?, ?> spec) {
				Object name = ((Map<String, Object>) spec).get("name");
				if (name != null && !os.equals(name)) continue;
			}
			allowed = "allow".equals(rule.get("action"));
		}
		return allowed;
	}

	static String osName() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		if (os.contains("win")) return "windows";
		if (os.contains("mac")) return "osx";
		return "linux";
	}
}
