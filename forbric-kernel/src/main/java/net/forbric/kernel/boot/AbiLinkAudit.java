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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.objectweb.asm.ClassReader;

import net.forbric.api.ModCatalog;
import net.forbric.kernel.util.ByteScan;
import net.forbric.kernel.util.ForbricLog;

/**
 * Names the mods whose classes resolve nowhere here: built against a NeoForge or MinecraftForge this instance
 * does not carry, or against a Fabric API that is not installed.
 *
 * <p>A mod built for another loader version links fine at load and dies at the first call into a class that is
 * not there — a {@code NoClassDefFoundError} inside a deferred task, a listener, a render pass — and the report
 * names the class, not the mod. This reads every class's constant pool for the names it judges and resolves each
 * against the carriers, the merged base and every installed jar (a Fabric port of a Forge library legitimately
 * ships {@code net.minecraftforge.*} classes for its dependants); what resolves nowhere is dangling.
 *
 * <p>Loader-bootstrap packages are out of scope: the kernel REPLACES FML's loading layer, so
 * {@code fml/loading}, {@code fml/relauncher} and the {@code locating} SPIs are absent here by design and a mod
 * naming them (CustomSkinLoader's six references) is not compiled against the wrong Forge.
 *
 * <p><b>The Fabric API root is judged, and only when Fabric API is absent.</b> See
 * {@link #FABRIC_API_ROOT}: it is the one dangling reference whose fix is an install rather than a rebuild, so it
 * gets its own sentence and its own condition. With Fabric API present the root is not judged at all, because the
 * question this class answers ("which classes resolve nowhere") already has its answer and asking it again would
 * report a player's partial Fabric API install as though every user were missing it.
 *
 * <p>Never throws, never refuses a jar; {@code -Dforbric.abiAudit=off}. Scanned at boot while the jar names are
 * in hand, reported after the catalog is published so the rows reach load-report.txt.
 */
public final class AbiLinkAudit {
	static final String SWITCH = "forbric.abiAudit";

	/** What is judged: everything under either family's root… */
	static final String[] FAMILIES = { "net/neoforged/", "net/minecraftforge/" };
	/** …except the loading layer the kernel replaces. */
	static final String[] OUT_OF_SCOPE = { "net/neoforged/fml/loading/", "net/minecraftforge/fml/loading/",
			"net/minecraftforge/fml/relauncher/", "net/neoforged/neoforgespi/locating/", "net/minecraftforge/forgespi/locating/" };
	private static final byte[][] NEEDLES = { ByteScan.needle("net/neoforged/"), ByteScan.needle("net/minecraftforge/") };

	/**
	 * fabric-api's public root, judged separately because the FIX is different in kind.
	 *
	 * <p>A mod naming a NeoForge or MinecraftForge class that this instance does not carry was built for another
	 * loader version; there is nothing the player can install to mend it. A mod naming {@code net/fabricmc/fabric/}
	 * is a different situation entirely: Fabric API is not a loader component here but an ordinary mod the player
	 * puts in {@code mods/}, so the instance can be made whole by installing one thing. That is exactly the crash
	 * the README documents — the NeoForge builds of Sodium and Iris link against Fabric API classes and die at
	 * startup without it — and before this the report said "NoClassDefFoundError:
	 * net.fabricmc.fabric.api.…", naming a class and never the missing mod.
	 *
	 * <p>The bare {@code net/fabricmc/} root is deliberately NOT judged: {@code net.fabricmc.loader},
	 * {@code net.fabricmc.api} and {@code net.fabricmc.mappingio} are supplied by the kernel itself, so a mod
	 * naming those has no problem to report.
	 */
	static final String FABRIC_API_ROOT = "net/fabricmc/fabric/";
	private static final byte[] FABRIC_NEEDLE = ByteScan.needle(FABRIC_API_ROOT);

	/**
	 * Whether {@code present} carries fabric-api at all — which decides whether its root is judged.
	 *
	 * <p>Derived from the resolved class set rather than from a mod id, so it answers the question the audit
	 * actually asks ("are these classes reachable") and cannot disagree with the dangling check. One module of
	 * Fabric API is enough: the report is about classes that resolve NOWHERE, so a module the player happens to
	 * be missing is reported while a module they have is not.
	 */
	private static boolean fabricApiPresent(Set<String> present) {
		for (String name : present) if (name.startsWith(FABRIC_API_ROOT)) return true;
		return false;
	}

	/** The family a dangling class belongs to, for the report's prose. */
	static String familyOf(String internal) {
		if (internal.startsWith(FABRIC_API_ROOT)) return "Fabric API";
		return internal.startsWith("net/neoforged/") ? "NeoForge" : "MinecraftForge";
	}

	/** One jar with dangling references: which family, and the classes (internal names) that resolve nowhere. */
	public record Finding(String jar, String family, List<String> missing) {
	}

	/** jar file name → its finding. */
	private static final Map<String, Finding> FINDINGS = new LinkedHashMap<>();
	private static int scannedJars;
	private static long scanNanos;

	private AbiLinkAudit() {
	}

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(SWITCH, "on"));
	}

	/** Scans {@code jars}, resolving against {@code jars} themselves plus {@code alsoAgainst} (carriers, merged base). */
	public static void scan(List<Path> jars, List<Path> alsoAgainst) {
		if (!enabled()) return;
		long start = System.nanoTime();
		List<Path> universe = new ArrayList<>(alsoAgainst);
		for (Path jar : jars) if (!universe.contains(jar)) universe.add(jar);
		Set<String> present = classesOf(universe);
		List<Finding> findings = audit(jars, present);
		synchronized (FINDINGS) {
			for (Finding f : findings) FINDINGS.put(f.jar(), f);
			scannedJars += jars.size();
			scanNanos += System.nanoTime() - start;
		}
	}

	/**
	 * Publishes a sweep's findings as this audit's own result, with the same bookkeeping {@link #scan} does after
	 * its own traversal — so a fused scan is indistinguishable from a standalone one to every reader of
	 * {@link #findings}.
	 */
	static void record(Sweep sweep, int jarCount, long nanos) {
		synchronized (FINDINGS) {
			for (Finding f : sweep.findings()) FINDINGS.put(f.jar(), f);
			scannedJars += jarCount;
			scanNanos += nanos;
		}
	}

	/** Every {@code .class} entry name (without the extension) across {@code jars}. */
	static Set<String> classesOf(List<Path> jars) {		Set<String> present = new HashSet<>();
		for (Path jar : jars) {
			try (ZipFile zip = new ZipFile(jar.toFile())) {
				for (ZipEntry entry : zip.stream().toList()) {
					String name = entry.getName();
					if (name.endsWith(".class")) present.add(name.substring(0, name.length() - 6));
				}
			} catch (IOException unreadable) {
				ForbricLog.debug("[Forbric/AbiAudit] could not list %s: %s", jar.getFileName(), unreadable);
			}
		}
		return present;
	}

	/** The findings over {@code jars}, given the set of classes that exist. Pure; the test's entry point. */
	static List<Finding> audit(List<Path> jars, Set<String> present) {
		Sweep sweep = new Sweep(present);
		for (Path jar : jars) {
			String name = jar.getFileName().toString();
			sweep.begin(name);
			try (ZipFile zip = new ZipFile(jar.toFile())) {
				for (ZipEntry entry : zip.stream().toList()) {
					if (!entry.getName().endsWith(".class")) continue;
					byte[] bytes;
					try (InputStream in = zip.getInputStream(entry)) {
						bytes = in.readAllBytes();
					}
					sweep.accept(bytes);
				}
			} catch (IOException | RuntimeException unreadable) {
				ForbricLog.debug("[Forbric/AbiAudit] could not read %s: %s", jar.getFileName(), unreadable);
			}
			// Finished either way, so the jar's findings are recorded before the next one starts and a partially
			// read jar contributes what it did name rather than being dropped with its accumulator.
			sweep.finish();
		}
		return sweep.findings();
	}

	/** Whether {@code internal} is a class this audit judges. */
	static boolean inScope(String internal) {
		return inScope(internal, true);
	}

	/**
	 * One jar's worth of links, accumulated a class at a time so a single traversal can feed this audit alongside
	 * the others instead of inflating every class again for it.
	 *
	 * <p>{@link #audit} is the traversal own entry point and is now built on this: the judgement lives in one
	 * place, so a fused scan and a standalone scan cannot drift apart. A sweep holds only the names found missing
	 * in ONE jar — a few strings — and is dropped when the jar ends, because that is all the state the judgement
	 * ever needed. AbiLinkAudit was the only one of the three boot scans without a per-class entry point, and the
	 * reason was this per-jar state; holding it in a caller-owned object removes the reason without buffering a
	 * single class's bytes.
	 *
	 * <h2>The shape a caller must respect</h2>
	 *
	 * <p>{@code begin(jar)} → {@code accept(bytes)} per class → {@code finish()}. Calling {@code accept} without a
	 * {@code begin}, or reusing a sweep across a {@code finish}, is not supported and will not be silently
	 * tolerated: the accumulated names would be attributed to the wrong jar.
	 */
	static final class Sweep {
		private final Set<String> present;
		private final boolean judgeFabricApi;
		private final byte[][] needles;
		private final List<Finding> out = new ArrayList<>();
		private String jar;
		private final java.util.ArrayList<String> named = new java.util.ArrayList<>();
		private boolean anyMissing;

		Sweep(Set<String> present) {
			this.present = present;
			// fabric-api's root is judged only when NO fabric-api class is reachable: with Fabric API installed
			// the question this answers ("which classes resolve nowhere") has a different answer, and asking it
			// anyway would report a player's own partial Fabric API install as if every user were missing it.
			this.judgeFabricApi = !fabricApiPresent(present);
			this.needles = judgeFabricApi ? new byte[][] { NEEDLES[0], NEEDLES[1], FABRIC_NEEDLE } : NEEDLES;
		}

		/** Starts a jar. The previous jar must have been finished. */
		void begin(String jarName) {
			if (jar != null) throw new IllegalStateException("a sweep must be finished before the next jar: " + jar);
			jar = jarName;
			// Collected into a plain array list and deduplicated ONLY when something is missing. The obvious
			// shape -- build a LinkedHashSet of every name the class mentions, then test each -- allocates and
			// hashes a set of up to several thousand strings per class, to answer two questions that are cheap on
			// the name itself. Nearly every class names no missing family class at all, so that set was built and
			// thrown away. (Measured on this workspace's jar set: this audit was 142 ms of the three boot scans,
			// above a 78 ms floor of just reading the bytes.)
			named.clear();
			anyMissing = false;
		}

		/** One class of the current jar. */
		void accept(byte[] classBytes) {
			if (jar == null) throw new IllegalStateException("accept() before begin()");
			if (!ByteScan.containsAny(classBytes, needles)) return;
			for (String name : namedClasses(classBytes)) {
				if (!inScope(name, judgeFabricApi) || present.contains(name)) continue;
				anyMissing = true;
				if (!named.contains(name)) named.add(name);
			}
		}

		/** Ends the current jar, recording a finding when it named anything missing. */
		void finish() {
			if (jar == null) throw new IllegalStateException("finish() without begin()");
			if (anyMissing && !named.isEmpty()) out.add(new Finding(jar, familyOf(named.get(0)), List.copyOf(named)));
			jar = null;
		}

		List<Finding> findings() {
			return List.copyOf(out);
		}
	}

	/**
	 * {@link #inScope(String)} with control over the Fabric API root, so a run WITH Fabric API installed does not
	 * judge it. The two-argument form is what {@link #audit} uses; the one-argument form exists for callers and
	 * tests that ask about the widest scope.
	 */
	static boolean inScope(String internal, boolean judgeFabricApi) {
		boolean family = false;
		for (String root : FAMILIES) family |= internal.startsWith(root);
		if (judgeFabricApi) family |= internal.startsWith(FABRIC_API_ROOT);
		if (!family) return false;
		for (String skip : OUT_OF_SCOPE) if (internal.startsWith(skip)) return false;
		return true;
	}

	/** Every CONSTANT_Class in the constant pool, array descriptors reduced to their element class. */
	static Set<String> namedClasses(byte[] classBytes) {
		Set<String> out = new LinkedHashSet<>();
		ClassReader reader = new ClassReader(classBytes);
		char[] buf = new char[reader.getMaxStringLength()];
		for (int i = 1; i < reader.getItemCount(); i++) {
			int offset = reader.getItem(i);
			if (offset == 0 || reader.readByte(offset - 1) != 7) continue; // CONSTANT_Class
			String name = reader.readUTF8(offset, buf);
			if (name == null) continue;
			if (name.startsWith("[")) {
				int l = name.indexOf('L');
				if (l < 0 || !name.endsWith(";")) continue;
				name = name.substring(l + 1, name.length() - 1);
			}
			out.add(name);
		}
		return out;
	}

	/** One summary line always; one WARN per jar with dangling references; DEGRADED on every row from that jar. */
	public static void report() {
		if (!enabled()) return;
		List<Finding> findings;
		int scanned;
		long nanos;
		synchronized (FINDINGS) {
			findings = List.copyOf(FINDINGS.values());
			scanned = scannedJars;
			nanos = scanNanos;
		}
		for (Finding f : findings) {
			List<String> shown = f.missing().subList(0, Math.min(5, f.missing().size()));
			String classes = String.join(", ", shown).replace('/', '.') + (f.missing().size() > shown.size() ? ", …" : "");
			// Two situations print the same shape and have opposite answers, so they do not share a sentence.
			// Naming a Forge family this instance lacks is a mod built for another loader version: nothing the
			// player installs mends it. Naming Fabric API is a mod that expects a mod, and the player fixes it by
			// installing that mod -- so the action is named, because "NoClassDefFoundError: net.fabricmc.fabric…"
			// is exactly the message that sends a player looking in the wrong place.
			if ("Fabric API".equals(f.family())) {
				ForbricLog.warn("[Forbric/AbiAudit] %s is built against Fabric API, which is NOT installed — %d of the "
						+ "classes it names do not exist here: %s. Install Fabric API in mods/ (the NeoForge builds of "
						+ "Sodium, Iris and anything plugging into them need it on this loader); Forbric does not bundle it.",
						f.jar(), f.missing().size(), classes);
				ModCatalog.markByJar(f.jar(), ModCatalog.Status.DEGRADED,
						"built against Fabric API, which is not installed — " + f.missing().get(0).replace('/', '.')
								+ " is not in this instance; installing Fabric API fixes it");
				continue;
			}
			ForbricLog.warn("[Forbric/AbiAudit] %s was compiled against a different %s than this instance carries — %d "
					+ "class(es) it names do not exist here: %s%s", f.jar(), f.family(), f.missing().size(),
					String.join(", ", shown).replace('/', '.'), f.missing().size() > shown.size() ? ", …" : "");
			ModCatalog.markByJar(f.jar(), ModCatalog.Status.DEGRADED, "compiled against a different " + f.family() + " — "
					+ f.missing().get(0).replace('/', '.') + " is not in this instance");
		}
		ForbricLog.info("[Forbric/AbiAudit] scanned %d jar(s) in %d ms: %d with dangling references", scanned,
				nanos / 1_000_000, findings.size());
	}

	/** Package-private, for the test. */
	static void reset() {
		synchronized (FINDINGS) {
			FINDINGS.clear();
			scannedJars = 0;
			scanNanos = 0;
		}
	}
}
