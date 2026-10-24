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

import net.neoforged.neoforgespi.language.IConfigurable;
import net.neoforged.neoforgespi.language.IModFileInfo;
import net.neoforged.neoforgespi.language.IModInfo;
import net.neoforged.neoforgespi.locating.IModFile;

/**
 * The {@code IModFileInfo} a kernel-constructed mod reports as its owning file, in 1.21.1's own terms.
 *
 * <p>It exists because NeoForge's error path dereferences it: when ANY mod-bus event listener throws,
 * {@code ModContainer.acceptEvent} → {@code ModLoadingIssue.withAffectedMod} walks
 * {@code getOwningFile().getFile().getFilePath()}. Against a null owning file that walk NPEs and MASKS the real
 * listener error — caught empirically on the client's {@code RegisterKeyMappingsEvent}, where the actual cause
 * was invisible until this chain existed.
 *
 * <h2>Why this file is an overlay, and why it is not optional</h2>
 *
 * <p>{@code IModFileInfo} differs between the lines by exactly ONE member, and it is the only member difference
 * in the whole interface:
 *
 * <pre>
 *   1.21.1   the ten members below, plus  public abstract String moduleName();
 *   26.2     the same ten, and no moduleName()
 * </pre>
 *
 * <p>One member is enough: an interface with an abstract member a class does not implement makes that class
 * unbuildable, so on this line the whole class was refused by javac
 * ({@code KernelModFileInfo is not abstract and does not override abstract method moduleName()}). That is why
 * this file and {@link KernelModFile} are one subsystem and not two — {@code KernelModFileInfo}'s constructor is
 * what builds the {@code KernelModFile}, so repairing only one of them would leave {@code KernelModInfo:76}
 * resolving to nothing.
 *
 * <p>{@link #moduleName()} is 1.21.1's own body, taken from
 * {@code net.neoforged.fml.loading.moddiscovery.ModFileInfo} by {@code javap -c}:
 *
 * <pre>
 *   moduleName()   getMods().get(0).getModId()
 * </pre>
 *
 * <p>It is the same string {@code IModFile.getId()} answers on 26.2, one step up the chain: this line's SPI asks
 * the file info for the module name rather than the file for its id, which is the direction the interface moved.
 * That is where 26.2's {@code getId()} survives here — see {@link KernelModFile}'s class doc, loss 1.
 *
 * <p>The guard around the carrier's body covers one window and answers the same string in it. {@code getMods()}
 * is backed by a holder {@code KernelModInfo}'s constructor back-fills AFTER this object is constructed, so
 * during construction the list is empty and the carrier's body would throw {@code NoSuchElementException};
 * the {@link #modId} field is already set at that point and is the identical string. Outside that window the two
 * agree by construction, because the object in the holder is the {@code KernelModInfo} built for this mod id.
 *
 * <p>{@link #getConfig()} was null here, while {@code IModInfo.getConfig()} on the very same mod was carefully
 * non-null, and it was left that way until a reader was established. There is more than one. Not Enough Crashes
 * builds its description of every mod it names from {@code getOwningFile().getConfig().getConfigElement(
 * "issueTrackerURL")} and threw on the null, and NeoForge's own mod-loading crash report asks the same object for
 * the same key.
 */
public final class KernelModFileInfo implements IModFileInfo {
	private final String modId;
	private final KernelModFile file;
	private final IModInfo[] owner;
	private final IConfigurable config;

	/**
	 * @param owner  a one-slot holder back-filled with the {@link KernelModInfo} once it exists. The two types
	 *               refer to each other — a mod info has an owning file, and that file's {@code getMods()} must
	 *               return the mod info — so one of them has to be constructed second. NeoForge's title-screen
	 *               version check is what reads it back: {@code getModFileById(id).getMods().get(0)}.
	 * @param config the file's top level, or null with {@code -Dforbric.fileConfigElements=off}
	 */
	KernelModFileInfo(String modId, Path jar, IModInfo[] owner, IConfigurable config) {
		this.modId = modId;
		this.file = new KernelModFile(modId, jar);
		this.file.setModFileInfo(this);
		this.owner = owner;
		this.config = config;
	}

	@Override
	public IModFile getFile() {
		return file;
	}

	@Override
	public List<IModInfo> getMods() {
		return owner[0] != null ? List.of(owner[0]) : List.of();
	}

	/** The empty string, as before — {@code ModListScreen.updateCache} reads it straight into the info pane. */
	@Override
	public String getLicense() {
		return "";
	}

	/**
	 * The module name — 1.21.1's own body, {@code getMods().get(0).getModId()}, with the construction window
	 * answered from the field instead. See the class doc for why the guard is not a difference in answer.
	 */
	@Override
	public String moduleName() {
		List<IModInfo> mods = getMods();
		return mods.isEmpty() ? modId : mods.getFirst().getModId();
	}

	// --- answered the way the proxy's defaultReturn answered them -----------------------------------------

	/**
	 * The top level of the mod's {@code neoforge.mods.toml}, answered as NeoForge's {@code NightConfigWrapper} over the
	 * parsed file answers it; native {@code ModFileInfo.getConfig()} is the file itself, which delegates to that
	 * wrapper. {@code issueTrackerURL} and {@code license} are here, and so is any top-level table one mod addresses to
	 * another. Null only with {@code -Dforbric.fileConfigElements=off}, as it always was before.
	 */
	@Override
	public IConfigurable getConfig() {
		return config;
	}

	/**
	 * Both carriers report the first mod's resolved version, not the raw jar manifest's version. The owner is
	 * back-filled before this file info is published, and already carries discovery's TOML/manifest substitutions.
	 */
	@Override
	public String versionString() {
		return getMods().getFirst().getVersion().toString();
	}

	/** Empty, as before. The kernel constructs mods itself and asks FML for no language loader. */
	@Override
	public List<IModFileInfo.LanguageSpec> requiredLanguageLoaders() {
		return List.of();
	}

	/** Empty, as before. */
	@Override
	public Map<String, Object> getFileProperties() {
		return Map.of();
	}

	/** Empty, as before. */
	@Override
	public List<String> usesServices() {
		return List.of();
	}

	/** False, as before. A kernel container is not a resource pack source in its own right. */
	@Override
	public boolean showAsResourcePack() {
		return false;
	}

	/** False, as before. */
	@Override
	public boolean showAsDataPack() {
		return false;
	}

	@Override
	public String toString() {
		return "KernelModFileInfo[" + modId + "]";
	}
}
