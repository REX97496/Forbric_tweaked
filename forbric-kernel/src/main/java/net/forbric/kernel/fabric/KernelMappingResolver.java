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

package net.forbric.kernel.fabric;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.fabricmc.loader.api.MappingResolver;
import net.forbric.kernel.util.ForbricLog;

/**
 * The kernel's {@link MappingResolver}: identity on a Mojmap-native game, a real lookup on an obfuscated one.
 *
 * <h2>Why both modes exist</h2>
 *
 * <p>Minecraft 26.2 onwards ships deobfuscated, and mods built for it carry no {@code class_} / {@code method_} /
 * {@code field_} symbols — a constant-pool scan of fabric-api 0.161.0+26.2 finds zero (one {@code method_} hit in
 * one of forty-four modules). On such a version every lookup IS the identity, and returning the input is the
 * correct answer rather than a shortcut.
 *
 * <p>1.21.1 is not that. The same scan of fabric-api 0.116.17+1.21.1 finds <b>19,859</b> {@code class_}
 * references, 1,729 {@code method_} and 862 {@code field_}, spread over 48 of 49 modules. Identity there would
 * hand every Fabric mod a name the game has never heard of.
 *
 * <h2>How the table is supplied, and why not parsed here</h2>
 *
 * <p>{@code -Dforbric.mappings=<file>} names a pre-joined table. The join itself (intermediary from Fabric's tiny,
 * official from Mojang's client mappings, through the shared obfuscated name) is done ahead of time — see
 * {@code tools/namespace/join-mappings.ps1} and {@code join-members.ps1}, which also assert the result against a
 * real merged base. Parsing two mapping formats and joining them at class-load time would put a piece of build
 * tooling on the startup path and give it a second implementation to drift from a verified one.
 *
 * <p>With no property set the resolver is the identity, which is exactly the behaviour 26.2 needs and what every
 * existing test expects. That is why this change cannot alter 26.2: the table is absent there.
 *
 * <p>Table format, tab-separated, one record per line, UTF-8, '#' comments:
 * <pre>
 *   C &lt;intermediaryClass&gt;                  &lt;officialClass&gt;
 *   M &lt;intermediaryOwner&gt; &lt;intermediaryMethod&gt; &lt;officialMethod&gt;
 *   F &lt;intermediaryOwner&gt; &lt;intermediaryField&gt;  &lt;officialField&gt;
 * </pre>
 *
 * <p>Fields and methods are separate records on purpose. The obfuscated names are shared between the two kinds, so
 * a table that did not keep them apart maps a field onto a method's name — measured during the join work:
 * {@code field_18276} came out as {@code copyPosition} until the dimension was added.
 */
public final class KernelMappingResolver implements MappingResolver {
	/**
	 * The namespace the kernel runs the game in: Mojang's own names, which Fabric calls {@code official}.
	 *
	 * <p>This said {@code named}, and that is not what any real instance of this Fabric Loader reports.
	 * {@code javap} on {@code MappingConfiguration} in fabric-loader 0.19.5: the runtime namespace is read from
	 * {@code fabric.runtimeMappingNamespace} and falls back to the literal {@code official}. The kernel runs the
	 * game under Mojang's names, so {@code official} is both the truthful answer and the one a mod compares
	 * against — a mod branching on this was told a namespace that exists nowhere and took the other branch.
	 *
	 * <p>The override property is honoured for the same reason it exists there: an instance that really is
	 * running under another naming can say so.
	 */
	public static final String RUNTIME_NAMESPACE =
			System.getProperty("fabric.runtimeMappingNamespace", "official");

	/** Where the pre-joined table lives, when this instance needs one. */
	public static final String TABLE_PROPERTY = "forbric.mappings";

	/**
	 * The joined table, or null for identity.
	 *
	 * <p>Loaded once, on first use, because the first lookup happens while mods are being prepared and a second
	 * read of a multi-megabyte file at that point is pure latency. A load failure is reported and the resolver
	 * becomes the identity rather than throwing: a mod asking for a name during startup should get an answer, and
	 * an exception here would replace "this one name is wrong" with "nothing starts".
	 */
	private static volatile Table table;
	private static volatile boolean loadAttempted;

	/**
	 * Public because {@code KernelFabricLoader} constructs one directly and the tests do too; the table is static,
	 * so instances carry no state and are interchangeable. Made private in a first draft and the compile caught it
	 * — worth noting because a resolver whose instances could disagree would be a much harder bug than a build
	 * error.
	 */
	public KernelMappingResolver() {
	}

	/** Convenience alias for callers that would rather not construct their own. */
	public static final KernelMappingResolver INSTANCE = new KernelMappingResolver();

	@Override
	public Collection<String> getNamespaces() {
		// With a table, this instance really does carry two namespaces: the one mods are written in and the one
		// the game runs in. Reporting only the runtime namespace told a mod that its own namespace did not exist.
		Table t = table();
		return t == null ? List.of(RUNTIME_NAMESPACE) : List.of("intermediary", RUNTIME_NAMESPACE);
	}

	@Override
	public String getCurrentRuntimeNamespace() {
		return RUNTIME_NAMESPACE;
	}

	@Override
	public String mapClassName(String namespace, String className) {
		Table t = table();
		if (t == null || className == null) return className;
		// Already in the runtime namespace: nothing to do, and looking it up would be a no-op miss.
		if ("intermediary".equals(namespace) || "named".equals(namespace)) {
			String mapped = t.classes.get(className);
			return mapped != null ? mapped : className;
		}
		return className;
	}

	@Override
	public String unmapClassName(String targetNamespace, String className) {
		Table t = table();
		if (t == null || className == null) return className;
		if ("intermediary".equals(targetNamespace) || "named".equals(targetNamespace)) {
			String mapped = t.classesReverse.get(className);
			return mapped != null ? mapped : className;
		}
		return className;
	}

	@Override
	public String mapFieldName(String namespace, String owner, String name, String descriptor) {
		Table t = table();
		if (t == null || owner == null || name == null) return name;
		if (!"intermediary".equals(namespace) && !"named".equals(namespace)) return name;
		String mapped = t.fields.get(owner + '#' + name);
		return mapped != null ? mapped : name;
	}

	@Override
	public String mapMethodName(String namespace, String owner, String name, String descriptor) {
		Table t = table();
		if (t == null || owner == null || name == null) return name;
		if (!"intermediary".equals(namespace) && !"named".equals(namespace)) return name;
		// Deliberately keyed without the descriptor. The join has no descriptor to key on (Mojang's client
		// mappings carry none), so including it here would guarantee a miss for every overloaded method rather
		// than sharing one mapping across overloads, which is what the source data offers.
		String mapped = t.methods.get(owner + '#' + name);
		return mapped != null ? mapped : name;
	}

	/** The table, loaded on first request; null when none is configured or it failed to load. */
	private static Table table() {
		Table loaded = table;
		if (loaded != null || loadAttempted) return loaded;
		synchronized (KernelMappingResolver.class) {
			if (loadAttempted) return table;
			loadAttempted = true;
			String path = System.getProperty(TABLE_PROPERTY);
			if (path == null || path.isBlank()) {
				// No table: identity. This is the 26.2 path and must stay silent-ish rather than noisy, because
				// it is the normal case there.
				ForbricLog.debug("[Forbric/Mappings] no -D%s given; the resolver is the identity (correct on a"
						+ " Mojmap-native game such as 26.2)", TABLE_PROPERTY);
				return null;
			}
			try {
				table = Table.read(Path.of(path));
				ForbricLog.info("[Forbric/Mappings] loaded %d class, %d method and %d field mappings from %s — the"
						+ " resolver now maps intermediary to %s instead of returning its input",
						table.classes.size(), table.methods.size(), table.fields.size(), path, RUNTIME_NAMESPACE);
				return table;
			} catch (IOException | RuntimeException unreadable) {
				// Reported at ERROR: falling back to identity here means every Fabric mod gets names the game
				// does not have, which fails later and elsewhere. Saying it here is the only place that names
				// the file.
				ForbricLog.error("[Forbric/Mappings] could not read %s despite -D%s naming it; falling back to the"
						+ " identity resolver, so intermediary names will NOT be mapped this session", path,
						TABLE_PROPERTY, unreadable);
				return null;
			}
		}
	}

	/** One loaded table. Keys for members are {@code owner#name}; no descriptor, see mapMethodName. */
	private record Table(Map<String, String> classes, Map<String, String> classesReverse,
			Map<String, String> methods, Map<String, String> fields) {

		static Table read(Path file) throws IOException {
			Map<String, String> classes = new HashMap<>(1 << 14);
			Map<String, String> reverse = new HashMap<>(1 << 14);
			Map<String, String> methods = new HashMap<>(1 << 16);
			Map<String, String> fields = new HashMap<>(1 << 15);
			int line = 0;
			for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
				line++;
				if (raw.isEmpty() || raw.charAt(0) == '#') continue;
				String[] f = raw.split("\t");
				if (f.length < 3) {
					throw new IOException("line " + line + ": expected at least 3 tab-separated fields, got "
							+ f.length);
				}
				switch (f[0]) {
					case "C" -> { classes.put(f[1], f[2]); reverse.put(f[2], f[1]); }
					case "M" -> { if (f.length >= 4) methods.put(f[1] + '#' + f[2], f[3]); }
					case "F" -> { if (f.length >= 4) fields.put(f[1] + '#' + f[2], f[3]); }
					default -> throw new IOException("line " + line + ": unknown record kind '" + f[0] + "'");
				}
			}
			return new Table(classes, reverse, methods, fields);
		}
	}
}
