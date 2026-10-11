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

package net.forbric.kernel.boot;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.util.ForbricLog;

/**
 * Mods that do the same job this kernel already does natively, and therefore must not be installed.
 *
 * <h2>What they are</h2>
 *
 * <p>All four are the Sinytra Connector stack — the "run Fabric mods on a Forge-family loader" bridge:
 *
 * <ul>
 *   <li>{@code connector} — Sinytra Connector itself (信雅互联)
 *   <li>{@code kilt} — Kilt, the same idea from the other direction
 *   <li>{@code fabric_api} — Forgified Fabric API, the NeoForge translation of Fabric API
 *   <li>{@code connectorextras} — Connector Extras
 * </ul>
 *
 * <p>Forbric runs Fabric and both Forge families in ONE instance natively, so installing this stack asks the
 * kernel to bridge itself to something it already is. It does not merely fail to help: Connector and a merged
 * base fight over the same classes, and the failure looks like a broken mod rather than a redundant one, which is
 * the hardest kind of report for a player to act on.
 *
 * <h2>The ids are read from the jars, not from the project names — two of the four differ</h2>
 *
 * <p>Resolved from the real metadata of published builds, and the two obvious-looking ones are the two that would
 * have been wrong:
 *
 * <pre>
 *   Sinytra Connector      slug connector           -&gt; id "connector"        (matches)
 *   Kilt                   slug kilt                -&gt; id "kilt"             (matches)
 *   Forgified Fabric API   slug forgified-fabric-api -&gt; id "fabric_api"      (DIFFERENT)
 *   Connector Extras       slug connector-extras    -&gt; id "connectorextras"  (DIFFERENT)
 * </pre>
 *
 * <p>A list keyed on the slugs would have been dead code for Forgified Fabric API and Connector Extras — the two
 * mods whose names look most self-evident. That is why the ids were read out of the jars before this was written,
 * and why the note is here rather than in a commit message: the next person tempted to "tidy" an id back to its
 * project name needs to see this first.
 *
 * <h2>Why {@code fabric_api} is special-cased, and what would happen if it were not</h2>
 *
 * <p>{@code fabric_api} is ALSO the id of the real Fabric API. Disabling that id outright would switch off the
 * Fabric API on every Fabric-side install — turning a helpful notice into a broken game. Forgified Fabric API is
 * the same id declared by a <b>Forge-family</b> jar, so the shared id is only redundant when the declaring jar is
 * not Fabric. The real Fabric API survives; the translation is switched off. There is a test for exactly this,
 * because it is the one rule here whose mistake is worse than not having the feature.
 *
 * <h2>What it does</h2>
 *
 * <p>{@link #suppress} returns the jars to take off the classpath. It is unioned into the set
 * {@link DuplicateModArbiter} already builds from {@link DisabledMods}, so a redundant jar never becomes a claim
 * and never reaches the classpath — the same path the player's own {@code forbric-disabled.txt} takes, which keeps
 * one mechanism rather than two. It also records what it suppressed for the load report and logs it at WARN, with
 * the mod's readable name, so a player who installed the stack is told rather than left to infer it.
 *
 * <p>Switches: {@code -Dforbric.redundantMods=off} stops the whole thing; {@code -Dforbric.redundantMods=keep}
 * reports and does not suppress, for someone who wants to reproduce what happens WITH the stack installed.
 */
public final class RedundantMods {

	/** {@code off} disables it, {@code keep} reports without suppressing. */
	public static final String SWITCH = "forbric.redundantMods";

	/**
	 * One mod that the kernel makes redundant.
	 *
	 * @param id          the mod id as the jar's OWN metadata declares it
	 * @param display     the name a player recognises, including the Chinese name where one is in common use
	 * @param because     one sentence: what the kernel already does, so the notice says why and not just what
	 * @param forgedOnly  whether the id is shared with the real mod of another ecosystem and may only be
	 *                    suppressed when a Forge-family jar declares it. True for {@code fabric_api}, which the real
	 *                    Fabric API also uses; false for the ids only these mods claim.
	 */
	record Redundant(String id, String display, String because, boolean forgedOnly) {
	}

	/**
	 * Read from the published metadata of real builds. See the class comment for why two of these ids do not look
	 * like their project names.
	 */
	static final List<Redundant> BUILT_IN = List.of(
			new Redundant("connector", "Sinytra Connector (信雅互联)",
					"the kernel already runs Fabric mods beside Forge mods natively, so it has nothing to bridge",
					false),
			new Redundant("kilt", "Kilt",
					"the kernel already runs each ecosystem on its own terms, so a cross-loader shim only adds a"
							+ " second copy of the same classes",
					false),
			new Redundant("fabric_api", "Forgified Fabric API",
					"the kernel serves Fabric API to Fabric mods directly, so the NeoForge translation of it would"
							+ " put a second 'fabric_api' on the classpath",
					true),
			new Redundant("connectorextras", "Connector Extras",
					"it extends Connector, which is itself unnecessary here",
					false));

	/** Suppressed this boot, for the load report. Set once by {@link #suppress}. */
	private static volatile List<String> suppressedThisBoot = List.of();

	private RedundantMods() {
	}

	/** The jars to take off the classpath because a redundant mod declares them, or an empty set. */
	static Set<Path> suppress(Path modsDir, Set<Path> alreadyDisabled) {
		if ("off".equalsIgnoreCase(System.getProperty(SWITCH, "on"))) return Set.of();
		if (modsDir == null || !Files.isDirectory(modsDir)) return Set.of();

		Map<String, RedundantWant> wanted = new LinkedHashMap<>();
		for (Redundant redundant : BUILT_IN) wanted.put(redundant.id(), new RedundantWant(redundant));

		Set<Path> found = new LinkedHashSet<>();
		List<String> notices = new ArrayList<>();

		try (DirectoryStream<Path> stream = Files.newDirectoryStream(modsDir, "*.jar")) {
			for (Path jar : stream) {
				if (alreadyDisabled != null && alreadyDisabled.contains(jar)) continue;
				for (Declaration declaration : declaredBy(jar)) {
					RedundantWant want = wanted.get(declaration.id());
					if (want == null) continue;
					// The shared-id rule: `fabric_api` is the real Fabric API's id too, so a Fabric jar declaring it
					// is the real thing and must NOT be touched. Only the Forge-family translation is redundant.
					if (want.rule.forgedOnly() && declaration.ecosystem() == Ecosystem.FABRIC) continue;
					if (!found.add(jar)) continue;
					want.hits.add(jar);
					notices.add(String.format(Locale.ROOT, "%s [%s, declared by %s] — %s",
							want.rule.display(), want.rule.id(), declaration.ecosystem(), want.rule.because()));
				}
			}
		} catch (IOException unreadable) {
			ForbricLog.warn("[Forbric/Redundant] could not read %s to look for redundant mods: %s — nothing was"
					+ " suppressed on this boot", modsDir, unreadable);
			return Set.of();
		}

		suppressedThisBoot = List.copyOf(notices);
		if (found.isEmpty()) return Set.of();

		boolean keep = "keep".equalsIgnoreCase(System.getProperty(SWITCH, "on"));
		ForbricLog.warn("[Forbric/Redundant] %d mod jar(s) do the same job the kernel already does and %s:",
				found.size(), keep ? "are REPORTED ONLY (-D" + SWITCH + "=keep)" : "have been switched off");
		for (String notice : notices) ForbricLog.warn("[Forbric/Redundant]   %s", notice);
		ForbricLog.warn("[Forbric/Redundant] These do not need to be installed. Remove the jar(s) from mods/ to"
				+ " silence this; %s is the player's own list and is not affected.", DisabledMods.FILE);

		if (keep) return Set.of();
		return found;
	}

	/** What was suppressed this boot, one line each, for the load report. Empty when nothing was. */
	public static List<String> suppressedThisBoot() {
		return suppressedThisBoot;
	}

	/** For tests, which suppress different trees in one JVM. */
	static void forget() {
		suppressedThisBoot = List.of();
	}

	private static final class RedundantWant {
		final Redundant rule;
		final List<Path> hits = new ArrayList<>();

		RedundantWant(Redundant rule) {
			this.rule = rule;
		}
	}

	/** A mod id a jar declares as its OWN, with the ecosystem that declared it. */
	record Declaration(String id, Ecosystem ecosystem) {
	}

	/**
	 * The ids {@code jar} declares as its own, one per ecosystem manifest it carries.
	 *
	 * <p>Its own ids only. A Forge-family manifest declares its dependencies as additional {@code modId} entries
	 * under {@code [[dependencies]]} — Connector Extras declares {@code neoforge} that way, and reading every
	 * {@code modId} in the file would suppress a jar for depending on the loader. The first {@code modId} after
	 * {@code [[mods]]} is the mod's own; the parse below stops there.
	 */
	static List<Declaration> declaredBy(Path jar) {
		List<Declaration> out = new ArrayList<>();
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry fabric = zip.getEntry("fabric.mod.json");
			if (fabric != null) {
				String id = jsonId(read(zip.getInputStream(fabric)));
				if (id != null) out.add(new Declaration(id, Ecosystem.FABRIC));
			}
			for (String toml : List.of("META-INF/neoforge.mods.toml", "META-INF/mods.toml")) {
				ZipEntry entry = zip.getEntry(toml);
				if (entry == null) continue;
				String id = firstOwnModId(read(zip.getInputStream(entry)));
				// NeoForge's manifest is used by the NeoForge family; mods.toml by MinecraftForge. One jar can carry
				// both only in a universal build, and the id is the same either way.
				if (id != null) out.add(new Declaration(id, Ecosystem.NEOFORGE));
			}
		} catch (IOException | RuntimeException unreadable) {
			// A jar that cannot be read is not evidence of a redundant mod. Silence is correct here and the caller
			// does not treat it as a hit — but it is logged at debug so a support case can see it was skipped.
			ForbricLog.debug("[Forbric/Redundant] %s could not be read for a mod id: %s", jar, unreadable);
		}
		return out;
	}

	private static String read(InputStream in) throws IOException {
		try (in) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	/** {@code "id": "..."} at the top level of a fabric.mod.json. */
	static String jsonId(String json) {
		if (json == null) return null;
		int at = json.indexOf("\"id\"");
		while (at >= 0) {
			int colon = json.indexOf(':', at + 4);
			if (colon < 0) return null;
			int open = json.indexOf('"', colon + 1);
			if (open < 0) return null;
			int close = json.indexOf('"', open + 1);
			if (close < 0) return null;
			String value = json.substring(open + 1, close);
			// "id" also appears inside nested objects (a dependency, an entrypoint); the mod's own id is the first
			// one that is not preceded by a key that belongs to a nested block. The published manifests put the mod
			// id first, and a wrong guess here would suppress a jar for mentioning an id, so prefer the first and
			// say so rather than trying to parse the whole document.
			if (!value.isBlank()) return value;
			at = json.indexOf("\"id\"", at + 4);
		}
		return null;
	}

	/**
	 * The mod's OWN id in a Forge-family toml: the first {@code modId} after {@code [[mods]]}, and never one from
	 * {@code [[dependencies]]}.
	 */
	static String firstOwnModId(String toml) {
		if (toml == null) return null;
		int mods = toml.indexOf("[[mods]]");
		if (mods < 0) return null;
		int dependencies = toml.indexOf("[[dependencies", mods);
		int end = dependencies < 0 ? toml.length() : dependencies;
		int at = toml.indexOf("modId", mods);
		while (at >= 0 && at < end) {
			int equals = toml.indexOf('=', at);
			if (equals < 0 || equals > end) return null;
			int open = toml.indexOf('"', equals);
			if (open < 0 || open > end) return null;
			int close = toml.indexOf('"', open + 1);
			if (close < 0) return null;
			String value = toml.substring(open + 1, close).trim();
			if (!value.isEmpty()) return value;
			at = toml.indexOf("modId", close);
		}
		return null;
	}
}
