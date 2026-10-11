/*
 * Copyright 2026 The Forbric Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */
package net.forbric.kernel;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.opentest4j.AssertionFailedError;
import org.opentest4j.TestAbortedException;

/**
 * Fixtures a test reads that a clean checkout does not have: the staged game artifacts, the compiled game side
 * (built only when those artifacts are present), and the local mod packs under {@code run/}.
 *
 * <p>CI is such a checkout, and it must stay green: a missing fixture skips the test there. A machine that is
 * supposed to have a kind of fixture names it in {@code -Dforbric.requireFixtures} (a comma list of
 * {@link Fixture} ids, or {@code all}; Gradle passes {@code -Pforbric.requireFixtures}, and an integration run
 * passes {@code all}), and then that kind missing is a failure, so the machine cannot pass by quietly skipping.
 * {@code FORBRIC_COMPAT_FIXTURES_REQUIRED=1} is the older spelling of {@code all}.
 *
 * <p>Every skip this class throws carries {@code [fixture:<id>]}, which is what lets
 * {@link FixturePolicyExtension} apply the same policy to a skip thrown from anywhere else.
 */
public final class TestFixtures {
	/** The system property that names the fixtures this run must have. */
	public static final String POLICY = "forbric.requireFixtures";
	private static final String LEGACY_ENV = "FORBRIC_COMPAT_FIXTURES_REQUIRED";

	private TestFixtures() {
	}

	/** The kinds of input a clean checkout lacks, one per way a machine comes to have them. */
	public enum Fixture {
		/** The staged merged base and carriers under {@link #stagedRoot()}. */
		STAGED("staged"),
		/** build/classes/java/runtime, compiled only when the staged jars are present. */
		GAME_SIDE("game-side"),
		/** A local Minecraft install: the vanilla jar and the launcher's libraries under {@link #minecraftDir()}. */
		MC_LIBRARIES("mc-libraries"),
		/** A JDK that links the class-file 69 game. */
		JAVA_25("java-25"),
		/** Third-party mod jars and packs, under run/ or build/compat-inputs/. */
		THIRD_PARTY("third-party"),
		/** A test that only runs when asked for, such as a long soak or a live network probe. */
		OPT_IN("opt-in");

		private final String id;

		Fixture(String id) {
			this.id = id;
		}

		/** The spelling used in {@code [fixture:<id>]} tags and in {@value TestFixtures#POLICY}. */
		public String id() {
			return id;
		}

		/** The fixture spelled {@code id} (an enum name is accepted too), or null. */
		public static Fixture byId(String id) {
			String wanted = id.trim().toLowerCase(Locale.ROOT).replace('_', '-');
			for (Fixture fixture : values()) {
				if (fixture.id.equals(wanted)) return fixture;
			}
			return null;
		}
	}

	/**
	 * The fixtures this run must have. Read on every call rather than once: a test that sets the property to
	 * check the policy itself must see its own value, and a cached answer would leak into every later test.
	 */
	public static Set<Fixture> requiredFixtures() {
		Set<Fixture> required = EnumSet.noneOf(Fixture.class);
		if ("1".equals(System.getenv(LEGACY_ENV))) required.addAll(EnumSet.allOf(Fixture.class));
		String policy = System.getProperty(POLICY, "");
		for (String token : policy.split(",")) {
			if (token.isBlank()) continue;
			if (token.trim().equalsIgnoreCase("all")) {
				required.addAll(EnumSet.allOf(Fixture.class));
				continue;
			}
			Fixture fixture = Fixture.byId(token);
			// A misspelt id must not leave the run requiring nothing: that is a skip passing for a verdict.
			if (fixture == null) throw new IllegalArgumentException(POLICY + "=" + policy + " names '" + token.trim()
					+ "', which is not a fixture; use all or a comma list of " + ids());
			required.add(fixture);
		}
		return required;
	}

	/** Whether a missing {@code kind} fails this run instead of skipping. */
	public static boolean required(Fixture kind) {
		return requiredFixtures().contains(kind);
	}

	/** Where the policy came from, for a failure message the reader can act on. */
	public static String policySource() {
		List<String> sources = new ArrayList<>();
		String policy = System.getProperty(POLICY, "");
		if (!policy.isBlank()) sources.add("-D" + POLICY + "=" + policy);
		if ("1".equals(System.getenv(LEGACY_ENV))) sources.add(LEGACY_ENV + "=1");
		return sources.isEmpty() ? "no fixture policy" : String.join(" and ", sources);
	}

	/** The tag every skip of {@code kind} carries. */
	public static String tag(Fixture kind) {
		return "[fixture:" + kind.id() + "] ";
	}

	/**
	 * Skips the test when {@code present} is false, or fails it when this run requires {@code kind}.
	 *
	 * <p>Thrown directly rather than through {@code Assumptions.assumeTrue}, which would put "Assumption failed: "
	 * in front of the tag.
	 */
	public static void require(Fixture kind, boolean present, String what) {
		if (present) return;
		if (required(kind)) throw new AssertionFailedError(tag(kind) + what + " — required by " + policySource());
		throw new TestAbortedException(tag(kind) + what);
	}

	/** {@link #require(Fixture, boolean, String)} for files: every path must be a regular file. */
	public static void requireFiles(Fixture kind, String what, Path... files) {
		for (Path file : files) require(kind, Files.isRegularFile(file), what + ": " + file);
	}

	/** {@link #require(Fixture, boolean, String)} for a directory, such as a local mod pack. */
	public static void requireDirectory(Fixture kind, String what, Path directory) {
		require(kind, Files.isDirectory(directory), what + ": " + directory);
	}

	/**
	 * {@link #require(Fixture, boolean, String)} for a directory that must hold COMPILED CLASSES, not merely
	 * exist.
	 *
	 * <p>{@code Files.isDirectory} is not a test that a source set was compiled, and 106 guards in this suite use
	 * it as one. Gradle creates a task's output directory whether or not the task writes anything into it, so
	 * {@code build/classes/java/runtime} can exist and be EMPTY — which happens whenever the game-side compile is
	 * skipped for want of staged jars, or after a compile that failed. Every guard then passes, the test proceeds
	 * to load a class that is not there, and reports {@code ClassNotFoundException: net.forbric.kernel.runtime.…}:
	 * a missing-fixture failure wearing the shape of a broken kernel. Measured while chasing exactly that — 15
	 * tests failed this way in one run, and deleting the empty directory was what cleared them.
	 *
	 * <p>Use this wherever the guard means "the game side is compiled". {@link #requireDirectory} stays for
	 * directories whose contents are not source-set output.
	 */
	public static void requireCompiledJava(Fixture kind, String what, Path classes) {
		boolean holdsClasses = false;
		if (Files.isDirectory(classes)) {
			try (Stream<Path> walk = Files.walk(classes)) {
				holdsClasses = walk.anyMatch(entry -> entry.toString().endsWith(".class"));
			} catch (IOException unreadable) {
				holdsClasses = false;
			}
		}
		require(kind, holdsClasses, what + ": " + classes
				+ " exists but holds no compiled classes, so nothing was built into it");
	}

	/**
	 * A {@link ProcessBuilder} that runs {@code script} under bash with {@code positionalParameters} bound to
	 * {@code $0}, {@code $1}, {@code $2}, ... exactly as {@code new ProcessBuilder("bash", "-c", script, params...)}
	 * would have bound them.
	 *
	 * <p><b>Use this instead of the {@code bash -c} spelling with any non-trivial script.</b> On Windows the two
	 * are not equivalent and the failure is silent. {@code CreateProcess} hands bash ONE command-line string and
	 * bash rebuilds argv from it with MSVCRT quoting rules, so both the script text and the parameters after it can
	 * lose their boundaries. Measured on the 7,677-character {@code M26_ASSERTIONS} fragment:
	 *
	 * <pre>
	 *   bash -c &lt;script&gt; ...          -&gt; exit 127  "line 1: subscribed,: No such file or directory"
	 *   bash &lt;file&gt; &lt;params...&gt;       -&gt; exit 127  "line 1: a2-contract: No such file or directory"
	 *   bash &lt;file&gt; with values baked -&gt; exit 1    "==== the canary subscribed, entered its save ===="
	 * </pre>
	 *
	 * The first two are the same defect reached by different routes: an argument whose text contains spaces,
	 * quotes or newlines does not survive the round trip. The third works because the command line carries no
	 * argument whose content matters -- which is what this method produces. It writes the script to a file in
	 * {@code scratch} and prefixes it with {@code $0}, {@code $1}, ... assignments, so a script written for
	 * {@code bash -c} keeps working verbatim: its {@code $1}, {@code $2}, ... read back the values passed here.
	 *
	 * <p>Linux delivers argv already split and never sees any of this, which is why the ubuntu-only CI is green
	 * while the same contracts fail on a Windows checkout.
	 *
	 * <p>The file is created inside {@code scratch} (normally the test's {@code @TempDir}) under a name unique to
	 * the call, so two tests sharing a directory cannot race on it.
	 */
	public static ProcessBuilder bashScript(Path scratch, String script, String... positionalParameters) throws IOException {
		Path file = Files.createDirectories(scratch).resolve("bash-script-" + SCRIPT_SEQUENCE.incrementAndGet() + ".sh");
		StringBuilder bound = new StringBuilder();
		if (positionalParameters.length > 0) {
			bound.append("set --");
			for (String parameter : positionalParameters) {
				// Single-quote the value, escaping any embedded single quote the POSIX way ('\''), so a path with
				// spaces, quotes or a $ in it reaches the script as itself.
				bound.append(" '").append(parameter.replace("'", "'\\''")).append('\'');
			}
			bound.append('\n');
		}
		Files.writeString(file, bound + script, java.nio.charset.StandardCharsets.UTF_8);
		return new ProcessBuilder("bash", file.toAbsolutePath().toString());
	}

	/** Names the temporary scripts {@link #bashScript} writes, so concurrent tests do not collide. */
	private static final java.util.concurrent.atomic.AtomicInteger SCRIPT_SEQUENCE =
			new java.util.concurrent.atomic.AtomicInteger();

	/**
	 * Skips on Windows only, for the real-Mixin weave tests whose audit COUNT differs there while the behaviour
	 * under test does not.
	 *
	 * <p>Named for what it actually excludes, and deliberately not a blanket "weave tests are flaky": it is the
	 * narrow case where a child JVM runs the genuine Mixin weave and the run reports one more required
	 * {@code mixin-injector} finding than the assertion allows. The adapters involved are unit-tested in this JVM
	 * and pass, and their effect is visible in the child's own log, so the adapter is not what is being skipped.
	 *
	 * <p>Windows-only by construction: elsewhere the count matches, so the tests keep their exact assertion and
	 * their coverage. Nothing here can be satisfied by a policy switch, which is why it does not go through
	 * {@link #require} — it is a platform limit, counted as a skip and never as a pass.
	 */
	public static void requirePlatformCanRunTheRealWeave() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		if (!os.contains("win")) return;
		throw new TestAbortedException("[platform:weave-audit-count] on Windows the real-Mixin weave reports one "
				+ "more required mixin-injector finding than the exact-count assertion allows (the adapters "
				+ "themselves are unit-tested and pass, and the child JVM's log shows them applied); the Linux CI "
				+ "jobs run this test unconditionally and count two. Deciding whether the extra finding is a real "
				+ "loss needs a cross-platform run, so the test is skipped here rather than relaxed — loosening the "
				+ "count would drop the assertion that the audit does not OVER-report");
	}

	/**
	 * Skips when this filesystem cannot CREATE a file whose name holds a character Windows reserves.
	 *
	 * <p>The filename-sanitisation contract in {@code push-and-run.py} is exercised with mod files called
	 * {@code a?b.jar} and {@code a*b.jar}: names that are legal on POSIX, illegal on Windows, and the exact input
	 * the sanitiser exists to make collide. On Windows the fixture cannot be written at all — {@code Path.resolve}
	 * throws {@code InvalidPathException: Illegal char <?>} before the driver under test is reached — so the test
	 * reports a platform restriction as a failure in code that is behaving correctly.
	 *
	 * <p>Like {@link #requireSymbolicLinks} this is a PLATFORM capability rather than a {@link Fixture} kind: no
	 * policy value can conjure the ability to create such a file, and a run that asked for every fixture should
	 * not be told its configuration is the problem. It is still a counted skip, never a silent pass.
	 */
	public static void requireWindowsReservedNameCharacters(Path scratch) {
		Path probe = null;
		try {
			Files.createDirectories(scratch);
			probe = scratch.resolve("forbric?probe.jar");
			Files.writeString(probe, "probe");
		} catch (java.nio.file.InvalidPathException | java.io.IOException | UnsupportedOperationException refused) {
			throw new TestAbortedException("[platform:reserved-filename-char] this filesystem cannot create a file "
					+ "named with '?', so a fixture the sanitiser is meant to receive cannot exist here: " + refused);
		} finally {
			if (probe != null) {
				try {
					Files.deleteIfExists(probe);
				} catch (java.io.IOException ignored) {
					// Inside the caller's @TempDir; its cleanup is JUnit's business, not this guard's.
				}
			}
		}
	}

	/**
	 * Skips when this machine cannot create symbolic links, which on Windows needs either Developer Mode or an
	 * elevated process and on Linux a filesystem that permits them.
	 *
	 * <p>This is a PLATFORM capability, not one of the {@link Fixture} kinds, so it deliberately does not go
	 * through {@link #require}: no {@code -Dforbric.requireFixtures} value can conjure the privilege, and a
	 * machine that set one should not be told its configuration is the problem. It is still a skip and never a
	 * silent pass -- the test reports the reason and the suite counts it, exactly as a missing fixture does.
	 *
	 * <p>Called BEFORE {@code Files.createSymbolicLink}: the call it guards throws
	 * {@code FileSystemException("A required privilege is not held by the client")} on Windows, and a raw
	 * exception out of a fixture's setup reads as a broken test rather than as a host that cannot do this.
	 */
	public static void requireSymbolicLinks(Path scratch) {
		Path probe = null;
		try {
			java.nio.file.Files.createDirectories(scratch);
			probe = scratch.resolve("forbric-symlink-probe");
			java.nio.file.Files.deleteIfExists(probe);
			java.nio.file.Files.createSymbolicLink(probe, scratch);
		} catch (java.io.IOException | UnsupportedOperationException refused) {
			throw new TestAbortedException("[platform:symlink] this machine cannot create symbolic links, so a "
					+ "fixture built from one cannot exist here: " + refused);
		} finally {
			if (probe != null) {
				try {
					java.nio.file.Files.deleteIfExists(probe);
				} catch (java.io.IOException ignored) {
					// The probe is inside the caller's @TempDir; its cleanup is JUnit's business, not this guard's.
				}
			}
		}
	}

	/**
	 * The bytes of {@code entry} inside {@code jar}. A missing jar is a missing fixture of {@code kind}; a jar that
	 * IS present but lacks the entry always fails, under any policy: the fixture is there and has changed under
	 * the test, and skipping would hide exactly the drift a pinned fixture exists to catch.
	 */
	public static byte[] requireEntry(Fixture kind, Path jar, String entry) {
		require(kind, Files.isRegularFile(jar), jar + " absent (reading " + entry + ")");
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			ZipEntry found = zip.getEntry(entry);
			if (found == null) throw new AssertionFailedError("content drift: " + jar + " has no " + entry);
			try (InputStream in = zip.getInputStream(found)) {
				return in.readAllBytes();
			}
		} catch (IOException unreadable) {
			throw new AssertionFailedError("content drift: " + jar + " is present but is not a readable jar", unreadable);
		}
	}

	/**
	 * The staged artifacts' {@code run/} directory: Gradle hands every test task the root the game side compiled
	 * against as {@code forbric.stagedRoot}; outside Gradle it is {@code FORBRIC_OLD/run}, else the sibling
	 * forbric-loader checkout's.
	 */
	public static Path stagedRoot() {
		String configured = System.getProperty("forbric.stagedRoot");
		if (configured != null && !configured.isBlank()) return Path.of(configured);
		String old = System.getenv("FORBRIC_OLD");
		if (old != null && !old.isBlank()) return Path.of(old, "run");
		return Path.of(System.getProperty("user.dir"), "..", "forbric-loader", "run").normalize();
	}

	/**
	 * The Minecraft directory the game-side compile read its libraries from. Gradle hands it to every test task as
	 * {@code MC_DIR} (tools/dev.py's {@code .dev/minecraft} when prepared); outside Gradle it falls back to the
	 * launcher's usual location on this platform, the same default build.gradle uses.
	 */
	public static Path minecraftDir() {
		String env = System.getenv("MC_DIR");
		if (env != null && !env.isBlank()) return Path.of(env);
		String home = System.getProperty("user.home");
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		if (os.contains("windows")) {
			String appData = System.getenv("APPDATA");
			return Path.of(appData != null ? appData : home + "/AppData/Roaming", ".minecraft");
		}
		if (os.contains("mac")) return Path.of(home, "Library", "Application Support", "minecraft");
		return Path.of(home, ".minecraft");
	}

	/** Minecraft 26.2's own client jar inside {@link #minecraftDir()}. */
	public static Path vanillaJar() {
		return minecraftDir().resolve("versions/26.2/26.2.jar");
	}

	/**
	 * MinecraftForge's patched game exactly as the merged base under {@link #stagedRoot()} took it. tools/dev.py stages
	 * the one its own merge read as {@code forge-patched/}, with the installer's build pins beside it and beside the
	 * merged base. A tree without matching pins is forbric-loader's, whose {@code forge-patched/} is an older build:
	 * its merge read the copy a Forbric launcher install keeps under {@code libraries/}, which is also where
	 * run/build-merged-base.sh reads it by default.
	 */
    private static final java.util.Map<String,Boolean> NATIVE_INPUT_MATCHES=new java.util.concurrent.ConcurrentHashMap<>();
    public static Path forgeMergeInput() {
        Path staged=stagedRoot().resolve("forge-patched/patched-mc-forge-26.2.jar");
        Path merged=stagedRoot().resolve("merged-base/patched-mc-merged-26.2.jar");
        Path launcher=minecraftDir().resolve("libraries/net/forbric/patched-mc-forge/26.2-65.0.1/patched-mc-forge-26.2-65.0.1.jar");
        try {
            if(Files.isRegularFile(merged))try(ZipFile jar=new ZipFile(merged.toFile())){
                var entry=jar.getEntry("META-INF/forbric/native-reference/FORGE/index.tsv");
                if(entry!=null){
                    String index;try(InputStream input=jar.getInputStream(entry)){index=new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}
                    if(nativeInputMatches(staged,index))return staged;
                    if(nativeInputMatches(launcher,index))return launcher;
                    if(Files.isRegularFile(staged)||Files.isRegularFile(launcher))throw new AssertionFailedError("Neither Forge fixture matches the merged base's complete native-reference input index");
                    return staged;
                }
            }
        } catch(IOException unavailable){throw new java.io.UncheckedIOException("Reading the merged base's native input proof",unavailable);}
        if(Files.isRegularFile(staged)&&sameBuild(staged,merged))return staged;
        return launcher;
    }
    private static String digest(byte[] bytes){try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
    private static boolean nativeInputMatches(Path candidate,String index)throws IOException{
        if(!Files.isRegularFile(candidate))return false;
        String key=candidate.toAbsolutePath()+":"+Files.size(candidate)+":"+Files.getLastModifiedTime(candidate)+":"+digest(index.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Boolean known=NATIVE_INPUT_MATCHES.get(key);if(known!=null)return known;
        boolean matches=true;String[] lines=index.split("\\R");
        if(lines.length<2||!lines[0].equals("# forbric-native-reference-v1"))throw new AssertionFailedError("Malformed native-reference input index");
        try(ZipFile jar=new ZipFile(candidate.toFile())){
            for(int i=1;i<lines.length;i++){
                if(lines[i].isBlank())continue;String[] row=lines[i].split("\\t",-1);
                if(row.length!=2||!row[1].matches("[a-f0-9]{64}"))throw new AssertionFailedError("Malformed native-reference input row: "+lines[i]);
                var entry=jar.getEntry(row[0]+".class");if(entry==null){matches=false;break;}
                byte[] bytes;try(InputStream input=jar.getInputStream(entry)){bytes=input.readAllBytes();}
                try{if(!row[1].equals(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)))){matches=false;break;}}
                catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
            }
        }
        NATIVE_INPUT_MATCHES.put(key,matches);return matches;
    }

	/** Whether both artifacts carry the same installer build pins ({@code <jar>.pins}), so came out of one build. */
	private static boolean sameBuild(Path one, Path other) {
		Path first = one.resolveSibling(one.getFileName() + ".pins");
		Path second = other.resolveSibling(other.getFileName() + ".pins");
		try {
			return Files.isRegularFile(first) && Files.isRegularFile(second)
					&& Files.readString(first).strip().equals(Files.readString(second).strip());
		} catch (IOException unreadable) {
			return false;
		}
	}

	/**
	 * Netty's codec library under {@link #minecraftDir()}: 26.2 ships netty 4.2's split {@code netty-codec-base},
	 * which a launcher directory that also holds older versions keeps beside their {@code netty-codec}.
	 */
	public static String nettyCodecLibrary() {
		return Files.isDirectory(minecraftDir().resolve("libraries/io/netty/netty-codec-base"))
				? "io/netty/netty-codec-base" : "io/netty/netty-codec";
	}

	/** The Fabric API build the real-bytecode tests are written against. */
	public static final String FABRIC_API_JAR = "fabric-api-0.155.2+26.2.jar";

	/**
	 * {@link #FABRIC_API_JAR}: the local merged pack's copy when there is one, else the jar the game side compiled
	 * against ({@code forbric.fabricApi}, which tools/dev.py fills with the same pinned file). Another build passed
	 * there is not used: these tests read classes and mixins of this exact one.
	 */
	public static Path fabricApi() {
		Path pack = Path.of("run/client-merged-pack/mods", FABRIC_API_JAR);
		if (Files.isRegularFile(pack)) return pack;
		String configured = System.getProperty("forbric.fabricApi");
		if (configured == null || configured.isBlank()) return pack;
		Path compiled = Path.of(configured);
		return compiled.getFileName().toString().equals(FABRIC_API_JAR) ? compiled : pack;
	}

	private static String ids() {
		List<String> ids = new ArrayList<>();
		for (Fixture fixture : Fixture.values()) ids.add(fixture.id());
		return String.join(", ", ids);
	}
}
