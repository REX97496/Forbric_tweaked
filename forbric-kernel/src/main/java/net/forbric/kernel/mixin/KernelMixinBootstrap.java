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

package net.forbric.kernel.mixin;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;

import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.transformer.Config;
import org.spongepowered.asm.mixin.extensibility.IMixinConfig;
import org.spongepowered.asm.mixin.FabricUtil;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

import net.fabricmc.api.EnvType;

import net.forbric.kernel.classloading.ForbricClassLoader;
import net.forbric.kernel.util.ForbricLog;

/**
 * Brings Mixin up on the sovereign kernel and installs the weaver as the LAST stage of the class pipeline.
 *
 * <p>Must run before any class a mixin targets is defined — in practice, before {@code PassiveSeeder} touches the
 * ecosystem carriers and long before the game's {@code Main}. The sequence mirrors what Fabric Loader's
 * {@code FabricMixinBootstrap} + {@code FabricLauncherBase.finishMixinBootstrapping} do, minus the loader:
 * bind the service, {@code MixinBootstrap.init()}, declare the side, register every config, then advance the
 * environment out of PREINIT so those configs are actually prepared.
 */
public final class KernelMixinBootstrap {
	private static volatile boolean initialized;

	private KernelMixinBootstrap() {
	}

	/** Whether Mixin was brought up (false when no mod declared a mixin config — the weaver is then never installed). */
	public static boolean isInitialized() {
		return initialized;
	}

	/**
	 * The Java feature version the game's own class files were compiled for — 25 for 26.2, 21 for 1.21.1.
	 *
	 * <p>This used to be the literal {@code 25}, which is correct for exactly one Minecraft version. A branch built
	 * for an older game would then have told every player to install a Java the game does not need, and worse,
	 * raised Mixin's compatibility level to a version whose class files are not present. So it is measured instead:
	 * the major version of a class from the game itself, minus 44 (Java 1.1 is 45, so 65 is 21 and 69 is 25).
	 *
	 * <p>Measured, not guessed, and measured twice: {@code patched-mc-merged-26.2.jar} reports major 69 at
	 * {@code net/minecraft/world/item/ItemStack.class}, and both 1.21.1 bases report major 65 at the same path.
	 *
	 * <p>{@code 0} when it cannot be read. Every use treats 0 as "no opinion", never as "Java 0" — the caller below
	 * falls back to what the runtime itself claims rather than refusing to start over a probe failure.
	 */
	private static int requiredJavaFeature() {
		if (derivedJavaFeature != 0) return derivedJavaFeature;
		synchronized (KernelMixinBootstrap.class) {
			if (derivedJavaFeature != 0) return derivedJavaFeature;
			derivedJavaFeature = probeGameClassMajor() - 44;
			return derivedJavaFeature;
		}
	}

	/** Cached result of {@link #probeGameClassMajor()}; 0 means "not measured yet" and also "could not measure". */
	private static int derivedJavaFeature;

	/**
	 * The Mixin {@code CompatibilityLevel} matching a Java feature version.
	 *
	 * <p>Falls back to whatever Mixin already holds when the feature is unmapped or was never measured — raising the
	 * level to a guess is worse than leaving it, because Mixin refuses class files newer than its level but also
	 * refuses a level its own ASM cannot handle.
	 */
	private static MixinEnvironment.CompatibilityLevel compatibilityLevelFor(int feature) {
		return switch (feature) {
			case 8 -> MixinEnvironment.CompatibilityLevel.JAVA_8;
			case 11 -> MixinEnvironment.CompatibilityLevel.JAVA_11;
			case 17 -> MixinEnvironment.CompatibilityLevel.JAVA_17;
			case 21 -> MixinEnvironment.CompatibilityLevel.JAVA_21;
			case 25 -> MixinEnvironment.CompatibilityLevel.JAVA_25;
			default -> {
				ForbricLog.warn("[Forbric/Mixin] no Mixin compatibility level is mapped for Java %s; leaving Mixin's"
						+ " current level (%s) alone rather than guessing one", feature,
						MixinEnvironment.getCompatibilityLevel());
				yield MixinEnvironment.getCompatibilityLevel();
			}
		};
	}

	/**
	 * The major version from a game class's header, or 0.
	 *
	 * <p>Read straight from the class file: bytes 6-7 of a class are the major version, big-endian, and the first
	 * four bytes are {@code CAFEBABE}. A class that does not start with that magic is not a class, and returning 0
	 * says so rather than reporting a number read from something else.
	 */
	private static int probeGameClassMajor() {
		// ItemStack first because both known versions carry it and it is never merged away; Object as a last resort
		// because it is always loadable and would at least report the runtime's own major rather than nothing.
		for (String name : new String[] { "net/minecraft/world/item/ItemStack.class", "net/minecraft/server/MinecraftServer.class" }) {
			try (java.io.InputStream in = KernelMixinBootstrap.class.getClassLoader().getResourceAsStream(name)) {
				if (in == null) continue;
				byte[] header = in.readNBytes(8);
				if (header.length < 8) continue;
				int magic = ((header[0] & 0xFF) << 24) | ((header[1] & 0xFF) << 16) | ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);
				if (magic != 0xCAFEBABE) continue;
				return ((header[6] & 0xFF) << 8) | (header[7] & 0xFF);
			} catch (java.io.IOException | RuntimeException unreadable) {
				ForbricLog.warn("[Forbric/Mixin] could not read %s's class header to derive the Java version (%s) —"
						+ " trying the next candidate", name, unreadable);
			}
		}
		ForbricLog.warn("[Forbric/Mixin] no game class could be read to derive the Java version; the compatibility"
				+ " report falls back to the runtime's own version and will not claim the game needs a newer Java");
		return 0;
	}

	/**
	 * The running JVM's feature version — 25 for Java 25 — or 0 when it cannot be read.
	 *
	 * <p>Reflection rather than {@code Runtime.version()} so this compiles and answers on any JVM this kernel might
	 * be started on. A JVM old enough to lack {@code Runtime.version()} returns 0, which reads as "older than
	 * required" — the correct answer, reached without a version parser to get wrong.
	 */
	private static int javaFeatureVersion() {
		try {
			Object version = Runtime.class.getMethod("version").invoke(Runtime.getRuntime());
			return (int) version.getClass().getMethod("feature").invoke(version);
		} catch (ReflectiveOperationException | RuntimeException unreadable) {
			// Said out loud. SilentFailureCensusTest caught the first version of this returning 0 without a word,
			// and it was right: the census's own rule is "a new silent catch is a decision, not a detail — say in
			// the log what failed, or say in a comment why it must stay quiet". The answer 0 is still correct (a
			// JVM without Runtime.version() is older than this build needs), but a reader of the log needs to know
			// that "Java too old" was reached by omission rather than by a version it could not parse.
			ForbricLog.warn("[Forbric/Mixin] could not read the JVM's feature version (%s) — the compatibility report"
					+ " will treat this runtime as older than required", unreadable);
			return 0;
		}
	}

	/**
	 * Initializes Mixin with {@code configs} and installs the weaver on {@code loader}.
	 *
	 * @param declared mixin config resource names, already filtered to the running side; de-duplicated here
	 */
	public static void init(ForbricClassLoader loader, EnvType side, List<String> declared) {
		if (initialized) throw new IllegalStateException("Mixin already bootstrapped");

		// Registering the same name twice is not a correctness problem — Mixins.registerConfiguration dedupes — but
		// it dedupes only AFTER Config.create has read and rewritten the JSON through
		// ForbricMixinService.getResourceAsStream, which re-runs KernelGuestMixinAdapter and re-emits every
		// "suppressed mixin" line. Now that two ecosystems feed this list, collapse it up front.
		List<String> configs = List.copyOf(new java.util.LinkedHashSet<>(declared));

		if (configs.isEmpty()) {
			ForbricLog.info("[Forbric/Mixin] no mixin configs declared — Mixin not started");
			// NeoForge's coremods still apply; they are the stage after Mixin, not part of it.
			loader.setMixinTransformer(net.forbric.kernel.transform.NativeCoremodParity::apply);
			return;
		}

		ForbricMixinService.bind(loader, side);

		// Every config here came from a discovered guest mod (the kernel authors no mixins), so relax them all:
		// guest mixins are written against vanilla bytecode and the base is byte-merged, so any of them can meet a
		// moved anchor. Without this, only `fabric-*` was relaxed and one unpatchable injector in any other real mod
		// (e.g. collective's collective_fabric.mixins.json) aborted the launch instead of soft-skipping.
		ForbricMixinService.setGuestConfigs(configs);

		// Echo the compatibility knobs. A mistyped config name in -Dforbric.suppressMixins silently does nothing,
		// which reads exactly like "the suppression did not help" — a trap worth one log line.
		String suppress = System.getProperty("forbric.suppressMixins");
		if (suppress != null && !suppress.isEmpty()) {
			ForbricLog.info("[Forbric/Mixin] suppression requested for: %s", suppress);
		}

		MixinBootstrap.init();
		MixinEnvironment.getDefaultEnvironment()
				.setSide(side == EnvType.CLIENT ? MixinEnvironment.Side.CLIENT : MixinEnvironment.Side.SERVER);

		// Before any config is parsed: MixinExtras registers the injection-point specifiers and annotations that
		// most of fabric-api's mixins use (@ModifyExpressionValue, MIXINEXTRAS:EXPRESSION, LocalRef sugar). Without
		// it those configs fail to even parse their injection points.
		initMixinExtras(loader);

		// THE LEVEL FIELD, measured before it is touched.
		//
		// A config asking for JAVA_25 was refused — MixinInitialisationError on
		// fabric-advancement-api-v1.mixins.json, from MixinConfig.initCompatibilityLevel ->
		// MixinEnvironment.setCompatibilityLevel, whose complaint is "not supported by the active JRE or ASM
		// version (Java 25.0, ASM 9.0 (ASM10_EXPERIMENTAL))". Everything that message points at is innocent: the
		// ASM shipped is 9.10.1, the JRE is 25, and CompatibilityLevel.isSupported() is a constant `return true`
		// in this build. Calling setCompatibilityLevel(JAVA_25) here was tried and threw — the same refusal, now
		// with this file as its cause, which is worse.
		//
		// So it was measured. One launch printed:
		//
		//   [Forbric/Mixin] compatibility: requested JAVA_25; Mixin's MAX_SUPPORTED=JAVA_13,
		//                   service=net.forbric.kernel.mixin.ForbricMixinService, service max=JAVA_25
		//
		// The service is this kernel's and it already answers JAVA_25; the field the check consults never got
		// there. Mixin asks IMixinService.getMaxCompatibilityLevel() during its own initialisation and leaves
		// MAX_SUPPORTED at CompatibilityLevel's default of JAVA_13 all the same.
		//
		// MAX_SUPPORTED is `public static` (verified with javap — no reflection, no setAccessible), so raising it
		// to what the service already declares is a plain assignment. It grants nothing new: the kernel states
		// JAVA_25 through getMaxCompatibilityLevel() two hundred lines up, sponge-mixin 0.17.3 with asm 9.10.1
		// reports canSupport(JAVA_25) == true, and MC 26.2 ships Java 25 class files — a lower level would be a
		// worse lie than this assignment is a liberty.
		MixinEnvironment.CompatibilityLevel wanted = compatibilityLevelFor(requiredJavaFeature());
		// Raise only, never lower: a field already at or above the target is Mixin having got there itself, and
		// overwriting that with our value would be this file claiming authority it has not measured.
		if (!MixinEnvironment.CompatibilityLevel.MAX_SUPPORTED.isAtLeast(wanted)) {
			MixinEnvironment.CompatibilityLevel.MAX_SUPPORTED = wanted;
		}

		// AND THE CURRENT LEVEL, because that is the one Mixin actually compares against.
		//
		// MixinEnvironment.setCompatibilityLevel disassembles to: if requested == current, jump past every check
		// (if_acmpeq); otherwise require requested.isAtLeast(current) and isSupported(), then consult the
		// service's max. Calling that method with JAVA_25 was tried and threw, with every measure reading
		// JAVA_25 — MAX_SUPPORTED, the service max, the runtime Java — and isSupported() disassembling to a
		// constant `return true`. So the refusal is not reachable by the route the bytecode describes, and
		// rather than keep reading it, this sets the field the comparison reads:
		//
		//   private static CompatibilityLevel compatibility;      // getCompatibilityLevel() returns it
		//
		// With `compatibility` on JAVA_25 a config declaring "JAVA_25" makes requested == current, the first
		// branch is taken, and no check runs at all. Nothing is loosened for a config that asks for less, and
		// nothing is granted that the kernel does not already declare through getMaxCompatibilityLevel().
		//
		// Reflection because the field is private; the same one-line assignment through the public API is what
		// throws. Resolution failure is reported, never swallowed: without it the launch would proceed and a
		// config asking JAVA_25 would fail exactly as before, with no hint that this step did nothing.
		try {
			java.lang.reflect.Field current = MixinEnvironment.class.getDeclaredField("compatibility");
			current.setAccessible(true);
			Object existing = current.get(null);
			if (!(existing instanceof MixinEnvironment.CompatibilityLevel held) || !held.isAtLeast(wanted)) {
				current.set(null, wanted);
			}
		} catch (ReflectiveOperationException noField) {
			ForbricLog.warn("[Forbric/Mixin] could not raise Mixin's current compatibility level to %s — a config"
					+ " asking for it will be refused as before", wanted, noField);
		}
		ForbricLog.info("[Forbric/Mixin] compatibility: requested %s; Mixin's MAX_SUPPORTED=%s,"
						+ " service=%s, service max=%s",
				wanted,
				MixinEnvironment.CompatibilityLevel.MAX_SUPPORTED,
				org.spongepowered.asm.service.MixinService.getService().getClass().getName(),
				org.spongepowered.asm.service.MixinService.getService().getMaxCompatibilityLevel());

		for (String config : configs) {
			try {
				Mixins.addConfiguration(config);
			// Catching Error as well as RuntimeException, and that is not belt-and-braces: Mixin throws
			// MixinInitialisationError here, which extends Error rather than RuntimeException. The first version of
			// this guardrail caught RuntimeException only and therefore never ran — verified by a real launch whose
			// stack still read
			//
			//   at MixinConfig.create ... at Mixins.addConfiguration ... at
			//   net.forbric.kernel.mixin.KernelMixinBootstrap.init(KernelMixinBootstrap.java:163)
			//
			// with this catch sitting immediately around line 163 and doing nothing. A guardrail that cannot see
			// the exception it exists for is indistinguishable from no guardrail, so the type is now what Mixin
			// actually throws.
			} catch (Error | RuntimeException refused) {
				// THE GUARDRAIL. A mixin config asking for a level this runtime will not grant used to end the
				// launch with Mixin's own words — "The requested compatibility level JAVA_25 could not be set. Level
				// is not supported by the active JRE or ASM version (Java 25.0, ASM 9.0 (ASM10_EXPERIMENTAL))" —
				// and a stack pointing into MixinConfig.onLoad. Read cold, that sentence says the player's Java is
				// wrong. On the launch that produced it, the runtime WAS Java 25 and the kernel's own ASM was
				// 9.10.1, so the one action the message suggests is the one action that cannot help.
				//
				// Four rounds were spent guessing at the cause from outside this block, and two of those guesses
				// were wrong in the same way: each rested on a measurement that did not cover the thing it was
				// used to conclude. What is left is a refusal whose mechanism is not yet established, so the
				// honest thing to put in front of a player is the measurement, not a diagnosis.
				//
				// Java guidance is given ONLY when the Java is actually below what this build needs. Emitting
				// "install Java 25" to someone already running Java 25 is worse than emitting nothing: it costs
				// them a download and teaches them the message is noise.
				int needs = requiredJavaFeature();
		// needs == 0 means the probe failed, not that the game needs Java 0. Claiming "too old" then would send
		// someone to install a Java they may already have.
		boolean javaTooOld = needs > 0 && javaFeatureVersion() < needs;
				StringBuilder detail = new StringBuilder();
				detail.append("Mixin refused the compatibility level ").append(wanted)
						.append(" that a mixin config asks for, so the launch stops here rather than part-way"
								+ " through class transformation.").append(System.lineSeparator())
						.append("  config           : ").append(config).append(System.lineSeparator())
						.append("  refused level    : ").append(wanted).append(System.lineSeparator())
						.append("  Mixin MAX_SUPPORTED: ").append(MixinEnvironment.CompatibilityLevel.MAX_SUPPORTED)
						.append(System.lineSeparator())
						.append("  service max      : ")
						.append(org.spongepowered.asm.service.MixinService.getService().getMaxCompatibilityLevel())
						.append(System.lineSeparator())
						.append("  runtime Java     : ").append(System.getProperty("java.version"))
						.append(" (feature ").append(javaFeatureVersion()).append(')').append(System.lineSeparator());
				if (javaTooOld) {
					detail.append(System.lineSeparator())
							.append("This Minecraft version needs Java ").append(needs)
							.append(" or newer and this launcher started it on Java ").append(javaFeatureVersion())
							.append(". In your launcher's Java settings, choose a Java ")
							.append(needs).append(" runtime (Adoptium Temurin, Microsoft"
									+ " OpenJDK, or any other build of the same major version), then start again.");
				} else {
					detail.append(System.lineSeparator())
							.append("Your Java is already ").append(javaFeatureVersion())
							.append(", which is what this Minecraft version needs, so installing another one will"
									+ " not help — the level was refused although the Java and the ASM this kernel"
									+ " ships both support it. Please report the block above as-is; it is the"
									+ " measurement the cause still has to be found from.");
				}
				ForbricLog.error("[Forbric/Mixin] %s", detail);
				throw new IllegalStateException("mixin compatibility level " + wanted
						+ " was refused; see the [Forbric/Mixin] block above", refused);
			}
		}
		nameTheModsBehindTheConfigs();
		// A mixin that fails to prepare or apply names its mod on the Mods screen and in load-report.txt. Mixin
		// instantiates the handler by name through the service's class provider when the first error happens.
		if (KernelMixinErrorHandler.enabled()) {
			Mixins.registerErrorHandlerClass(KernelMixinErrorHandler.NAME);
		}

		IMixinTransformer transformer = resolveTransformer();
		// Mixin is handed pre-mixin bytes, and null for a class in no owned jar — which is its class-GENERATION
		// request (org.spongepowered.asm.synthetic.*). transformClassBytes handles both. PostMixinFixups then repairs
		// the handful of classes a guest mixin wove wrong because the byte-merge restructured the target (e.g.
		// PackMixin's field initializer mis-woven into NeoForge's recursive Pack ctor).
		// Two unrelated interfaces each supplying the same default is an error the IMPLEMENTOR must settle, and
		// only after weaving is the second interface on the class at all — so this is the one point where it can
		// be seen. See InterfaceDefaultConflictRepair.
		var conflicts = new net.forbric.kernel.transform.InterfaceDefaultConflictRepair(internalName -> {
			try (var in = loader.getResourceAsStream(internalName + ".class")) {
				return in == null ? null : in.readAllBytes();
			} catch (Throwable unreadable) {
				return null;
			}
		});
		// NativeCoremodParity directly after Mixin, where NeoForge runs its own coremods: a mixin aimed at the vanilla
		// field read or finalizeSpawn call still binds, and what a mixin adds to those classes is rewritten too.
		// The transformer is read from MixinWeaverSlot per class rather than captured: a guest that wraps the weaver
		// the way NeoForge lets it (LibJF's ASM layer) must be the one that weaves from then on. With no wrapper it
		// is `transformer`, one volatile read away.
		MixinWeaverSlot.install(transformer);
		// Knot's delegate.mixinTransformer on the loader is a view of that slot too, seeded before any plugin can read it
		// (Mixin constructs config plugins when it first selects, on the first class woven below). See MixinPlatformIdentity.
		loader.knotDelegate().attach();
		var codecReturns = new net.forbric.kernel.transform.PostMixinCodecReturnArbitration();
		var callbackPriority = new net.forbric.kernel.transform.PostMixinCallbackPriority();
		loader.setMixinTransformer((name, bytes) -> net.forbric.kernel.transform.ForgeTransferShapeAudit.certify(name,
				callbackPriority.transform(name, codecReturns.transform(name, conflicts.transform(name, bytes, PostMixinFixups.apply(name,
						net.forbric.kernel.transform.NativeCoremodParity.apply(name,
								MixinWeaverSlot.currentOr(transformer).transformClassBytes(name, name, bytes)))), null), null)));

		// Leave PREINIT so the registered configs are prepared and their targets become weavable.
		gotoPhase(MixinEnvironment.Phase.INIT);
		gotoPhase(MixinEnvironment.Phase.DEFAULT);

		initialized = true;
		ForbricLog.info("[Forbric/Mixin] Mixin up on the sovereign kernel — %d config(s) registered, side %s",
				configs.size(), side);
	}

	/** {@code off} leaves Mixin's own messages saying "(unknown)" where the mod id would go. */
	static final String DECORATION_PROPERTY = "forbric.mixinModIdDecoration";

	/**
	 * Tells Mixin which mod each config came from, so its OWN messages name it.
	 *
	 * <p>Mixin reads {@code FabricUtil.KEY_MOD_ID} off the config as a decoration and prints it in
	 * {@code "<config>:<mixin> from mod <id>"}. Nothing was setting it, so every one of those lines said
	 * {@code (unknown)} — which is the literal default {@code FabricUtil.getModId} falls back to. The kernel has
	 * always known the answer; it just never handed it over. This is the same thing fabric-loader's own
	 * MixinConfigDecorator does, minus its reflective probe for whether {@code decorate} exists, because the
	 * kernel pins its Mixin version.
	 *
	 * <p>Only {@code KEY_MOD_ID}. {@code KEY_COMPATIBILITY} defaults to a level that sets injector semantics, and
	 * changing that per mod is a separate decision needing its own evidence, not a free rider on a naming change.
	 *
	 * <p><b>This changes generated bytecode</b>, which is why it has a switch. {@code MethodMapper.getHandlerName}
	 * formats {@code prefix + "$" + uid + "$" + sourceId + name}, and {@code sourceId} falls through to exactly
	 * this decoration — so {@code handler$abc000$onFoo} becomes {@code handler$abc000$sodium$onFoo}. The two
	 * places the kernel matches on those names ({@code PostMixinFixups}) test {@code startsWith("handler$")} and
	 * are unaffected, but that is a fact about today's code rather than a guarantee.
	 *
	 * <p>A mod id that is not a legal Java identifier fragment is NOT decorated. It would be spliced straight
	 * into a method name, and a {@code .} or {@code ;} there produces a class that fails verification. Those keep
	 * saying "(unknown)", which is the outcome they had before this existed.
	 */
	private static void nameTheModsBehindTheConfigs() {
		if ("off".equalsIgnoreCase(System.getProperty(DECORATION_PROPERTY, "on"))) {
			ForbricLog.info("[Forbric/Mixin] -D%s=off — Mixin's own failures will say '(unknown)' where the mod "
					+ "name would be", DECORATION_PROPERTY);
			return;
		}

		int named = 0;
		int unnamed = 0;
		for (Config registered : Mixins.getConfigs()) {
			try {
				String modId = MixinConfigOwners.modIdOf(registered.getName());
				if (!decoratable(modId)) {
					unnamed++;
					continue;
				}
				IMixinConfig config = registered.getConfig();
				if (config == null || config.hasDecoration(FabricUtil.KEY_MOD_ID)) continue;
				config.decorate(FabricUtil.KEY_MOD_ID, modId);
				named++;
			} catch (Throwable t) {
				// Naming a failure must never become one. A config that cannot be decorated simply keeps the
				// placeholder it had.
				unnamed++;
				ForbricLog.debug("[Forbric/Mixin] could not name the mod behind %s: %s", registered.getName(),
						String.valueOf(t));
			}
		}
		ForbricLog.info("[Forbric/Mixin] %d of %d mixin config(s) now name their mod in Mixin's own messages; %d "
				+ "could not be attributed and keep saying '(unknown)'", named, named + unnamed, unnamed);
	}

	/**
	 * Whether this id can be spliced into a generated method name.
	 *
	 * <p>Mixin concatenates it into an identifier without escaping, so anything the JVM forbids in a method name
	 * -- {@code . ; [ / < >} -- would produce a class that fails verification at load. Refusing to decorate is
	 * the safe answer, and it lands the mod exactly where it was before.
	 */
	private static boolean decoratable(String modId) {
		if (modId == null || modId.isEmpty() || modId.length() > 64) return false;
		for (int i = 0; i < modId.length(); i++) {
			char c = modId.charAt(i);
			boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
					|| c == '_' || c == '-';
			if (!ok) return false;
		}
		return true;
	}

	/**
	 * Boots MixinExtras through the GAME loader.
	 *
	 * <p>It must be that loader: MixinExtras generates classes (the {@code LocalRef} machinery) that have to share
	 * a loader with the game classes they wrap, which is why {@code DelegationPolicy} pins
	 * {@code com.llamalad7.mixinextras.} to the game side and {@code KernelBundledJars} hands the jar to
	 * {@code ForbricClassLoader}. Calling {@code init()} from boot-side code would load a second, useless copy.
	 */
	private static void initMixinExtras(ForbricClassLoader loader) {
		try {
			Class<?> bootstrap = Class.forName("com.llamalad7.mixinextras.MixinExtrasBootstrap", true, loader);
			bootstrap.getMethod("init").invoke(null);
			String version = String.valueOf(bootstrap.getMethod("getVersion").invoke(null));
			ForbricLog.info("[Forbric/Mixin] MixinExtras %s initialized (game-side)", version);
		} catch (ClassNotFoundException e) {
			ForbricLog.warn("[Forbric/Mixin] MixinExtras absent — mixins using @ModifyExpressionValue / LocalRef "
					+ "(most of fabric-api) will fail to parse their injection points");
		} catch (Throwable t) {
			throw new IllegalStateException("MixinExtras is present but failed to initialize", t);
		}
	}

	/**
	 * Advances the Mixin environment. {@code MixinEnvironment.gotoPhase} is a package-private static — there is no
	 * public way to leave PREINIT, and every Mixin host (Fabric Loader included) reaches it reflectively.
	 */
	private static void gotoPhase(MixinEnvironment.Phase phase) {
		try {
			Method m = MixinEnvironment.class.getDeclaredMethod("gotoPhase", MixinEnvironment.Phase.class);
			m.setAccessible(true);
			m.invoke(null, phase);
		} catch (Throwable t) {
			throw new IllegalStateException("could not advance the Mixin environment to " + phase, t);
		}
	}

	/**
	 * The weaver Mixin offered the service during {@code init()}. Mixin only calls {@code offer(...)} on some
	 * paths, so fall back to constructing the transformer directly — exactly what Fabric Loader does.
	 */
	private static IMixinTransformer resolveTransformer() {
		IMixinTransformer offered = ForbricMixinService.getTransformer();
		if (offered != null) return offered;

		try {
			@SuppressWarnings("unchecked")
			Constructor<IMixinTransformer> ctor = (Constructor<IMixinTransformer>)
					Class.forName("org.spongepowered.asm.mixin.transformer.MixinTransformer").getDeclaredConstructor();
			ctor.setAccessible(true);
			return ctor.newInstance();
		} catch (Throwable t) {
			throw new IllegalStateException("Mixin did not offer a transformer and one could not be constructed", t);
		}
	}
}
