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

package net.forbric.kernel.runtime;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import cpw.mods.jarhandling.SecureJar;
import net.forbric.kernel.discovery.ModFileScanner;
import net.forbric.kernel.util.ForbricLog;
import net.neoforged.neoforgespi.language.IModFileInfo;
import net.neoforged.neoforgespi.language.IModInfo;
import net.neoforged.neoforgespi.language.ModFileScanData;
import net.neoforged.neoforgespi.locating.IModFile;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;

/**
 * The {@code IModFile} behind a kernel-constructed mod, in 1.21.1's own terms.
 *
 * <p>THE SAME JOB AS THE SHARED FILE, and this is the 1.21.1 edition of it. It is an overlay rather than a
 * rename-table row because what moved between the lines is a MEMBER SET, not a spelling: the two interfaces ask
 * for members that are mutually exclusive, so no token substitution can carry this file from one line to the
 * other.
 *
 * <h2>What actually differs, read off both carriers with javap</h2>
 *
 * <pre>
 *                                 1.21.1                                        26.2
 *   findResource(String...)       Path                                          -            (absent)
 *   getSecureJar()                cpw.mods.jarhandling.SecureJar                -            (absent)
 *   setSecurityStatus(Status)     void                                          -            (absent)
 *   getId()                       -                                             String
 *   getContents()                 -                                             net.neoforged.fml.jarcontents.JarContents
 *   getSubstitutionMap, getType, getFilePath, getModInfos, getScanResult,
 *   getFileName, getDiscoveryAttributes, getModFileInfo                         identical on both
 * </pre>
 *
 * <p>The contents type did not vanish, it changed owner. {@code cpw.mods.jarhandling} is the SAME 15 classes on
 * both lines (measured: 15 in each line's {@code forge-runtime.jar}), and {@code net.neoforged.fml.jarcontents}
 * is 0 classes here against 15 on 26.2. NeoForge replaced modlauncher's {@code SecureJar} with FML's own
 * {@code JarContents} between the lines, and {@code getSecureJar()} became {@code getContents()}; both types are
 * on both classpaths, so this is a type that stopped being what {@code IModFile} answers with, not a type that
 * disappeared.
 *
 * <p>The three added members are 1.21.1's own bodies, taken from
 * {@code net.neoforged.fml.loading.moddiscovery.ModFile} by {@code javap -c}, member for member:
 *
 * <pre>
 *   getSecureJar()       aload_0; getfield jar; areturn                              (a held SecureJar)
 *   findResource         paths.length &lt; 1 -&gt; IllegalArgumentException("Missing path")
 *                        else getSecureJar().getPath(String.join("/", paths))
 *   setSecurityStatus    this.securityStatus = status; return
 * </pre>
 *
 * <p>{@code SecureJar.from(Path)} stands where {@code JarContents.ofPath(Path)} stood. Exercised against this
 * line's real jars before being written here, because the two factories do not behave alike:
 *
 * <pre>
 *   SecureJar.from(&lt;1.21.1 neoforge-runtime.jar&gt;)  -&gt; name=neoforge, packages=179,
 *                                                     getPath("pack.mcmeta") = /pack.mcmeta
 *   SecureJar.from()                               -&gt; UncheckedIOException: Invalid paths argument,
 *                                                     contained no existing paths: []
 *   SecureJar.from(&lt;nonexistent path&gt;)             -&gt; the same UncheckedIOException
 * </pre>
 *
 * <h2>What is lost</h2>
 *
 * <ol>
 *   <li><b>{@code IModFile.getId()} has no slot here.</b> The interface on this line does not declare it, so the
 *       kernel's file cannot be asked for its id <i>as a file</i>. The id is not lost — {@link
 *       KernelModFileInfo#moduleName()} answers the same string one step up the chain, which is the direction
 *       this line's SPI takes. No kernel code called {@code getId()} on an {@code IModFile}-typed expression
 *       (measured: grep over {@code src/runtime} finds none), and no mod compiled against this line can ask for
 *       it, because the interface it compiles against does not declare it.</li>
 *   <li><b>The {@code JarContents} read API is not on this line.</b> "A mod reads a file out of its own jar" is
 *       {@code getSecureJar().getPath("…")} here where 26.2 says {@code getContents().readFile("…")} /
 *       {@code containsFile}. That is a change of SHAPE, not of capability.</li>
 *   <li><b>{@code JarContents.empty(path)}'s job has no counterpart here, and this one is a capability loss.</b>
 *       On 26.2 a jar-less presence alias answers an EMPTY contents object, because null aborts the entire
 *       traversal that walks every published identity. On this line {@code SecureJar.from(…)} refuses a path
 *       that does not exist (measured above), so there is no empty {@code SecureJar} to hand back:
 *       {@link #getSecureJar()} answers <b>null</b> for an alias, the same answer the shared class already gives
 *       when a real jar fails to open. See {@link #findResource} for the one place this edition decides rather
 *       than ports.</li>
 * </ol>
 *
 * <h2>A risk this file cannot remove, measured while writing it</h2>
 *
 * <p>1.21.1's {@code net.neoforged.neoforge.resource.ResourcePackLoader.createPackForMod(IModFileInfo)} is
 * {@code new PathResourcesSupplier(getFile().getSecureJar().getRootPath())} — it DEREFERENCES the result, so a
 * null from {@link #getSecureJar()} would be an NPE out of {@code findResourcePacks}. It is not reachable today,
 * and the reason is in the boot half rather than here: {@code ModList.modFiles} is deliberately left EMPTY on
 * both lines ({@code KernelBoot}, {@code KernelDataPacks}, {@code KernelModLoader} all say so), so that walk
 * iterates nothing. If a future change fills {@code modFiles}, this null becomes a live NPE and this paragraph
 * is where to start. Nothing here can test it, because nothing on this line has been launched.
 */
public final class KernelModFile implements IModFile {
	private final String modId;
	private final Path path;
	/** This line's file-contents type. Null for a jar-less presence alias — see the class doc, loss 3. */
	private final SecureJar secureJar;
	private final Path jar;

	/**
	 * The info that owns this file. Set by {@link KernelModFileInfo}'s constructor, which is what builds this
	 * object: the two refer to each other, so one of them has to be filled in second.
	 */
	private IModFileInfo modFileInfo;

	/** Memoised: most instances are never asked, and walking a hundred jars for nobody is pure boot cost. */
	private ModFileScanData scanResult;

	/**
	 * Set by {@link #setSecurityStatus}, and read by nothing in the kernel. It exists because the interface
	 * declares it; 1.21.1's own {@code ModFile} holds it in a field the same way.
	 */
	private SecureJar.Status securityStatus;

	/**
	 * @param jar the mod's real jar, or null for a presence alias which has none. A mod that reads files out of
	 *            its own jar through {@code getModInfo().getOwningFile().getFile().getSecureJar()} gets nothing
	 *            without it: Tectonic builds its bundled datapack that way, and against a null it produced a null
	 *            Pack, after which {@code PackRepository.discoverAvailable} died on "Cannot invoke
	 *            Pack.streamSelfAndChildren() because pack is null" and the world would not load.
	 */
	public KernelModFile(String modId, Path jar) {
		this.modId = modId;
		this.jar = jar;
		this.path = jar != null ? jar : Path.of("forbric-kernel", modId + ".jar");
		// A presence alias contributes no resources but is still a valid file-shaped entry. 26.2 answers an EMPTY
		// contents object here; this line has no empty SecureJar (see the class doc, loss 3), so it answers null.
		this.secureJar = jar == null ? null : secureJarOf(jar);
	}

	private static SecureJar secureJarOf(Path jar) {
		try {
			return SecureJar.from(jar);
		} catch (Throwable t) {
			ForbricLog.debug("[Forbric/Container] no SecureJar for %s: %s", jar.getFileName(),
					String.valueOf(t));
			return null;
		}
	}

	@Override
	public Path getFilePath() {
		return path;
	}

	/** Null for a jar-less presence alias. See the class doc, loss 3, and the risk paragraph. */
	@Override
	public SecureJar getSecureJar() {
		return secureJar;
	}

	/**
	 * A resource inside this mod's own jar, by this line's own rule.
	 *
	 * <p>1.21.1's {@code ModFile.findResource} is {@code getSecureJar().getPath(String.join("/", paths))}, and it
	 * rejects an empty path with {@code IllegalArgumentException("Missing path")} before dereferencing anything.
	 * Both halves are ported, not invented.
	 *
	 * <p>THE ONE PLACE THIS EDITION DECIDES RATHER THAN PORTS. With no {@code SecureJar} there is nothing to ask
	 * (loss 3), and the two candidate answers are not equally good. {@code null} is what {@code SecureJar.getPath}
	 * never returns, and the carrier's callers hand the result straight to {@code Files.isRegularFile} — where
	 * null is an NPE that surfaces as a {@code ModLoadingIssue} against the wrong mod and the wrong cause. A path
	 * that does not exist is the true statement ("this mod has no such file") in the shape every caller already
	 * handles, so that is what an alias answers: the placeholder path with the requested path appended, which
	 * cannot exist because the placeholder names a jar that was never written.
	 */
	@Override
	public Path findResource(String... paths) {
		if (paths.length < 1) {
			throw new IllegalArgumentException("Missing path");
		}
		SecureJar contents = getSecureJar();
		if (contents == null) {
			return getFilePath().resolve(String.join("/", paths));
		}
		return contents.getPath(String.join("/", paths));
	}

	/**
	 * Held, not forwarded. 1.21.1's {@code ModFile} answers a {@code getSecurityStatus()} out of the same field;
	 * this interface declares the setter only, and nothing in the kernel asks.
	 */
	@Override
	public void setSecurityStatus(SecureJar.Status status) {
		this.securityStatus = status;
	}

	/**
	 * Built on demand, and never null.
	 *
	 * <p>It is reached from further away than it looks: {@code ModList.getAllScanData()} streams sortedList →
	 * getOwningFile → getFile → getScanResult, so EVERY published mod is asked for one the moment anything calls
	 * it. Sodium does, right after its config walk, and NPE'd on a null inside {@code Minecraft.<init>} before
	 * the window ever opened.
	 *
	 * <p>The scan is real: {@code ModFileScanner.scan} walks the jar's classes and builds FML's own
	 * {@code ModFileScanData}, so annotation-driven discovery (JEI plugins, Jade providers, Sophisticated Core,
	 * Sodium's third-party config hooks) finds what it would on the carrier. Only a jar-less presence alias — a
	 * mod id the kernel publishes without a file behind it — gets the empty result, which reads exactly like a
	 * mod file that declares no annotations.
	 *
	 * <p>The index is the one the seeded {@code LoadingModList}'s {@code ModFile} for the same jar hands out
	 * ({@code ModFileScanner.scanShared}): natively {@code ModList} is built out of {@code LoadingModList}, so
	 * both hand out the same {@code ModFile} and therefore the same index — and the jar is walked once, however
	 * many entries of the two lists ask.
	 */
	@Override
	public synchronized ModFileScanData getScanResult() {
		if (scanResult == null) {
			Object real = jar == null ? null : ModFileScanner.scanShared(jar, getClass().getClassLoader());
			scanResult = real instanceof ModFileScanData data ? data : new ModFileScanData();
		}
		return scanResult;
	}

	/** The jar's file name, or the placeholder path's for a mod that has no jar. Never null. */
	@Override
	public String getFileName() {
		Path name = path.getFileName();
		return name == null ? modId + ".jar" : name.toString();
	}

	/**
	 * {@code MOD}, which is what every file the kernel publishes is.
	 *
	 * <p>Null was not a neutral answer: consumers filter the file list by type, and one comparing against
	 * {@code Type.MOD} dropped every kernel-loaded mod from whatever it was building.
	 */
	@Override
	public IModFile.Type getType() {
		return IModFile.Type.MOD;
	}

	/** The info that owns this file — the walk back up, which NeoForge's own error path takes. */
	@Override
	public IModFileInfo getModFileInfo() {
		return modFileInfo;
	}

	/** Called once by {@link KernelModFileInfo}'s constructor. */
	void setModFileInfo(IModFileInfo info) {
		this.modFileInfo = info;
	}

	// --- answered the way the proxy's defaultReturn answered them -----------------------------------------

	/** Empty, as before. The mods of this file are reached through {@code IModFileInfo.getMods()} instead. */
	@Override
	public List<IModInfo> getModInfos() {
		return List.of();
	}

	/** Null, as before. */
	@Override
	public Supplier<Map<String, Object>> getSubstitutionMap() {
		return null;
	}

	/** Null, as before. The kernel does not discover mod files the way FML's locators do. */
	@Override
	public ModFileDiscoveryAttributes getDiscoveryAttributes() {
		return null;
	}

	@Override
	public String toString() {
		return "KernelModFile[" + modId + "]";
	}
}
