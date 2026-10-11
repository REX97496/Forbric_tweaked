package net.forbric.kernel.fabric;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Every type in Fabric Loader's public API must actually be loadable from the kernel, because a mod's constant
 * pool names it whether or not the kernel has ever heard of it.
 *
 * <h2>The failure this exists to stop</h2>
 *
 * <p>The kernel serves the Fabric ecosystem natively, so it reproduces Fabric Loader's API surface rather than
 * shipping Fabric Loader. That is a list somebody has to keep complete, and it was not:
 * {@code net.fabricmc.loader.api.EntrypointException} was absent — 9 of the 10 types in that package were here —
 * while the real loader has it. Nothing in the kernel referenced it, so nothing failed in any test, and the module
 * compiled and shipped.
 *
 * <p>It failed in a mod. {@code catch (EntrypointException e)} around entrypoint loading puts that type in the
 * mod's constant pool, and the JVM resolves it when the method is verified: the mod dies with
 * {@link NoClassDefFoundError} having called nothing unusual. A mod doing entrypoint work is exactly the kind that
 * writes that catch, and JEI is one. Upstream's own Javadoc marks every constructor {@code @Deprecated} — "for
 * internal use only, to be removed!" — which is probably why it looked skippable. Deprecated is not absent.
 *
 * <p>For a while a first-generation jar on the launch list happened to carry a copy of the class, so the search
 * found something and the mod started. That jar is gone, so the gap went from hidden to fatal. Relying on an
 * obsolete artifact to satisfy a public API class was never safe; the list is asserted here instead.
 *
 * <h2>Where the list comes from</h2>
 *
 * <p>The published Javadoc for fabric-loader, package by package, not from memory:
 * {@code net/fabricmc/loader/api}, {@code .../entrypoint}, {@code .../metadata} and
 * {@code .../metadata/version}. Adding to it is fine when Fabric adds a type; REMOVING one is the regression, so
 * the assertion is "each of these loads", and the count is checked too so a silently-emptied list cannot pass.
 */
class FabricLoaderApiSurfaceTest {

	/** The public API types of fabric-loader, by binary name, from the published package Javadocs. */
	private static final List<String> API = List.of(
			// net.fabricmc.loader.api
			"net.fabricmc.loader.api.EntrypointException",
			"net.fabricmc.loader.api.FabricLoader",
			"net.fabricmc.loader.api.LanguageAdapter",
			"net.fabricmc.loader.api.LanguageAdapterException",
			"net.fabricmc.loader.api.MappingResolver",
			"net.fabricmc.loader.api.ModContainer",
			"net.fabricmc.loader.api.ObjectShare",
			"net.fabricmc.loader.api.SemanticVersion",
			"net.fabricmc.loader.api.Version",
			"net.fabricmc.loader.api.VersionParsingException",
			// net.fabricmc.loader.api.entrypoint
			"net.fabricmc.loader.api.entrypoint.EntrypointContainer",
			"net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint",
			// net.fabricmc.loader.api.metadata
			"net.fabricmc.loader.api.metadata.ContactInformation",
			"net.fabricmc.loader.api.metadata.CustomValue",
			"net.fabricmc.loader.api.metadata.ModDependency",
			"net.fabricmc.loader.api.metadata.ModEnvironment",
			"net.fabricmc.loader.api.metadata.ModMetadata",
			"net.fabricmc.loader.api.metadata.ModOrigin",
			"net.fabricmc.loader.api.metadata.Person",
			// net.fabricmc.loader.api.metadata.version
			"net.fabricmc.loader.api.metadata.version.VersionComparisonOperator",
			"net.fabricmc.loader.api.metadata.version.VersionInterval",
			"net.fabricmc.loader.api.metadata.version.VersionPredicate");

	@Test
	void everyTypeInFabricLoadersPublicApiCanBeLoadedFromTheKernel() {
		// Guard the list itself: an empty or truncated list would make the assertion below vacuous, which is the
		// same class of mistake as the omission being tested for.
		assertTrue(API.size() >= 22,
				"the pinned API list shrank to " + API.size() + " types; fabric-loader's public API has 22, and a"
						+ " shortened list is a weaker test rather than a passing one");

		List<String> missing = new ArrayList<>();
		for (String name : API) {
			try {
				assertNotNull(Class.forName(name, false, FabricLoaderApiSurfaceTest.class.getClassLoader()), name);
			} catch (ClassNotFoundException | LinkageError absent) {
				missing.add(name + " (" + absent.getClass().getSimpleName() + ")");
			}
		}

		if (!missing.isEmpty()) {
			fail("Fabric Loader's public API is incomplete in the kernel, so a mod naming any of these in its"
					+ " constant pool dies with NoClassDefFoundError: " + missing);
		}
	}
}
