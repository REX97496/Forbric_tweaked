/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.runtime;

import java.util.Optional;
import net.forbric.kernel.util.ForbricLog;
import net.minecraftforge.fml.loading.JarVersionLookupHandler;

/**
 * The gate MinecraftForge's jar-version lookup passes through, so a package inside the merged base can still answer
 * "what version am I".
 *
 * <p>On 1.21.1 ForgeVersion.&lt;clinit&gt; ends the boot with
 *
 * <pre>
 *   IllegalStateException: Failed to find version for package net.minecraftforge.versions.forge
 *     This is an invalid environment
 * </pre>
 *
 * because its static initialiser does:
 *
 * <pre>
 *   Info info = JarVersionLookupHandler.getInfo(ForgeVersion.class);
 *   if (info.impl().version().isEmpty() || info.spec().version().isEmpty()) throw new IllegalStateException(...)
 * </pre>
 *
 * and JarVersionLookupHandler reads Implementation-Version / Specification-Version out of the JAR MANIFEST. The class
 * lives inside patched-mc-merged-1.21.1.jar, whose manifest carries neither -- so both Optionals are empty and the
 * environment is declared invalid.
 *
 * <p>WHY A RETARGET AND NOT A GUARD: the kernel never writes a StackMapTable (COMPUTE_FRAMES cannot run while a class
 * is being defined), so injecting a branch into ForgeVersion would need a hand-written frame. Redirecting the one
 * invokestatic here keeps the instruction count identical, and the branch lives in this .java file.
 *
 * <p>WHAT THIS DOES NOT DO: it does not change the manifest and does not claim the jar carries a version it does not.
 * It answers with the version this instance actually provides, taken from the same provider the seeder uses, and logs
 * once so the substitution is visible rather than silent.
 */
public final class ForbricJarVersionGate {

	private static boolean announced;

	private ForbricJarVersionGate() { }

	/** Same signature as {@code JarVersionLookupHandler.getInfo(Class)}, so a transformer can swap the owner. */
	public static JarVersionLookupHandler.Info getInfo(Class<?> owner) {
		JarVersionLookupHandler.Info real = JarVersionLookupHandler.getInfo(owner);
		if (!empty(real.impl().version()) && !empty(real.spec().version())) return real;

		String version = providerVersion();
		if (version == null) return real; // nothing honest to substitute; let the caller decide

		if (!announced) {
			announced = true;
			ForbricLog.info("[Forbric/JarVersion] %s has no Implementation/Specification-Version in the merged base's "
					+ "manifest — answering %s from this instance's own provider instead, so ForgeVersion's "
					+ "'invalid environment' check passes. The jar genuinely carries no such attribute; this is a "
					+ "substitution, not a reading", owner.getName(), version);
		}
		JarVersionLookupHandler.Version both = new JarVersionLookupHandler.Version(
				Optional.of("Forge"), Optional.of("Forge"), Optional.of(version));
		return new JarVersionLookupHandler.Info(both, both);
	}

	private static boolean empty(Optional<String> value) {
		return value == null || value.isEmpty();
	}

	private static String providerVersion() {
		String forge = net.forbric.kernel.metadata.forge.EcosystemVersions.provided("forge");
		if (forge != null && !forge.isBlank()) return forge;
		String neo = net.forbric.kernel.metadata.forge.EcosystemVersions.provided("neoforge");
		return neo == null || neo.isBlank() ? null : neo;
	}
}
