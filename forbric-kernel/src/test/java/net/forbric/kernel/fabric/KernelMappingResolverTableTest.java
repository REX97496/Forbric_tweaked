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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

/**
 * Proves the resolver actually reads its table, which is the thing the previous commit could not claim.
 *
 * <h2>Why the table is synthetic here</h2>
 *
 * <p>Using the real 1.21.1 table would make this test depend on a five-megabyte generated file whose location is a
 * machine path, and a test that skips when the file is missing proves nothing on the machine where it skips. A
 * three-line synthetic table proves the wiring in every environment: if a lookup through a loaded table returns the
 * mapped value, the table was read, parsed, keyed and queried.
 *
 * <p>The real table's <em>content</em> was verified separately, against a real merged base, by
 * tools/namespace/join-mappings.ps1 (8 of 8 classes present) and join-members.ps1 (77,152 member pairs, then
 * javap-confirmed member names). The two halves together are the reason to believe the mapping layer works: this
 * test covers "the resolver uses a table", that work covers "the table is right".
 */
@ResourceLock("system-properties")
class KernelMappingResolverTableTest {

	@TempDir
	Path root;

	private static final String TABLE = """
			# synthetic
			C\tnet/minecraft/class_11\tnet.minecraft.world.item.ItemStack
			M\tnet/minecraft/class_11\tmethod_22\tgetItem
			F\tnet/minecraft/class_11\tfield_33\tcount
			""";

	@BeforeEach
	@AfterEach
	void clearStaticState() {
		// Both before and after: the table is static and loads once per JVM, so a test that left one loaded would
		// silently change what the next test's identity-path assertions mean.
		System.clearProperty(KernelMappingResolver.TABLE_PROPERTY);
		KernelMappingResolver.forgetTableForTest();
	}

	@Test
	void aLoadedTableIsUsedForClassesMethodsAndFields() throws Exception {
		Path table = root.resolve("synthetic.tsv");
		Files.writeString(table, TABLE, StandardCharsets.UTF_8);
		System.setProperty(KernelMappingResolver.TABLE_PROPERTY, table.toString());
		KernelMappingResolver.forgetTableForTest();

		KernelMappingResolver resolver = new KernelMappingResolver();

		assertEquals("net.minecraft.world.item.ItemStack",
				resolver.mapClassName("intermediary", "net/minecraft/class_11"),
				"mapClassName did not read the table — the whole point of this test");
		assertEquals("getItem", resolver.mapMethodName("intermediary", "net/minecraft/class_11", "method_22", "()V"));
		assertEquals("count", resolver.mapFieldName("intermediary", "net/minecraft/class_11", "field_33", "I"));
	}

	@Test
	void aLoadedTableAlsoAnswersTheReverseDirection() throws Exception {
		Path table = root.resolve("synthetic.tsv");
		Files.writeString(table, TABLE, StandardCharsets.UTF_8);
		System.setProperty(KernelMappingResolver.TABLE_PROPERTY, table.toString());
		KernelMappingResolver.forgetTableForTest();

		KernelMappingResolver resolver = new KernelMappingResolver();

		assertEquals("net/minecraft/class_11",
				resolver.unmapClassName("intermediary", "net.minecraft.world.item.ItemStack"),
				"unmapClassName must invert mapClassName, or a mod going the other way gets a name that is not there");
	}

	@Test
	void aLoadedTableWidensTheNamespaceList() throws Exception {
		Path table = root.resolve("synthetic.tsv");
		Files.writeString(table, TABLE, StandardCharsets.UTF_8);
		System.setProperty(KernelMappingResolver.TABLE_PROPERTY, table.toString());
		KernelMappingResolver.forgetTableForTest();

		List<String> namespaces = List.copyOf(new KernelMappingResolver().getNamespaces());

		// A mod that branches on getNamespaces() to decide whether it must remap itself was previously told only
		// the runtime namespace existed, i.e. that its own namespace was not a thing.
		assertTrue(namespaces.contains("intermediary"),
				"with a table loaded the resolver carries intermediary, but getNamespaces() reported " + namespaces);
		assertTrue(namespaces.contains(KernelMappingResolver.RUNTIME_NAMESPACE),
				"the runtime namespace must still be reported, got " + namespaces);
	}

	@Test
	void anUnmappedNameIsReturnedUnchanged() throws Exception {
		Path table = root.resolve("synthetic.tsv");
		Files.writeString(table, TABLE, StandardCharsets.UTF_8);
		System.setProperty(KernelMappingResolver.TABLE_PROPERTY, table.toString());
		KernelMappingResolver.forgetTableForTest();

		KernelMappingResolver resolver = new KernelMappingResolver();

		// Absent from the table. Returning the input is what Fabric Loader does for an unmapped member, and it is
		// better than inventing a name or throwing during mod preparation.
		assertEquals("net/minecraft/class_999",
				resolver.mapClassName("intermediary", "net/minecraft/class_999"));
		assertEquals("method_999",
				resolver.mapMethodName("intermediary", "net/minecraft/class_11", "method_999", "()V"));
	}

	@Test
	void withoutThePropertyEveryLookupIsIdentity() {
		// The 26.2 path. No property, no table, every method returns its input — and this must be true of a fresh
		// resolver even after another test loaded a table, which is why the statics are cleared first.
		KernelMappingResolver resolver = new KernelMappingResolver();

		assertEquals("net/minecraft/class_11", resolver.mapClassName("intermediary", "net/minecraft/class_11"));
		assertEquals("method_22", resolver.mapMethodName("intermediary", "net/minecraft/class_11", "method_22", "()V"));
		assertEquals("field_33", resolver.mapFieldName("intermediary", "net/minecraft/class_11", "field_33", "I"));
		assertEquals(List.of(KernelMappingResolver.RUNTIME_NAMESPACE),
				List.copyOf(resolver.getNamespaces()),
				"with no table the resolver carries exactly one namespace, which is what 26.2 needs");
	}

	@Test
	void aSingleNamespaceQueryIsNotRemapped() throws Exception {
		Path table = root.resolve("synthetic.tsv");
		Files.writeString(table, TABLE, StandardCharsets.UTF_8);
		System.setProperty(KernelMappingResolver.TABLE_PROPERTY, table.toString());
		KernelMappingResolver.forgetTableForTest();

		KernelMappingResolver resolver = new KernelMappingResolver();

		// Asking to map INTO the runtime namespace is a no-op: the name is already there. A resolver that looked
		// this up anyway would take an official name and return whatever intermediary happened to share the key.
		assertNotEquals("net/minecraft/class_11",
				resolver.mapClassName(KernelMappingResolver.RUNTIME_NAMESPACE, "net.minecraft.world.item.ItemStack"),
				"a runtime-namespace query must not be answered from the intermediary side of the table");
	}

	/**
	 * The two tables in this tree spell official class names differently, and the resolver has to answer both.
	 *
	 * <p>{@code tools/namespace/join-keyed.py --full} writes the runtime table with the names ASM uses
	 * ({@code net/minecraft/world/item/ItemStack}); the table it replaced carried Mojang's binary names
	 * ({@code net.minecraft.world.item.ItemStack}). Measured by replaying both through this class before it was
	 * changed: the same lookup answered {@code net/minecraft/client/Minecraft} from one and
	 * {@code net.minecraft.client.Minecraft} from the other, and the reverse lookup answered nothing at all from
	 * the second — neither of which throws. fabric-loader's own {@code MappingResolverImpl} returns binary names
	 * and rejects a '/' in its input, so binary is the form a mod is owed.
	 */
	@Test
	void aSlashedTableAndADottedTableAnswerIdenticallyInBinaryForm() throws Exception {
		String slashed = """
				C\tnet/minecraft/class_11\tnet/minecraft/world/item/ItemStack
				M\tnet/minecraft/class_11\tmethod_22\tgetItem
				""";
		String dotted = """
				C\tnet/minecraft/class_11\tnet.minecraft.world.item.ItemStack
				M\tnet/minecraft/class_11\tmethod_22\tgetItem
				""";
		Path slashedTable = root.resolve("slashed.tsv");
		Path dottedTable = root.resolve("dotted.tsv");
		Files.writeString(slashedTable, slashed, StandardCharsets.UTF_8);
		Files.writeString(dottedTable, dotted, StandardCharsets.UTF_8);

		String fromSlashed = answerFor(slashedTable);
		String fromDotted = answerFor(dottedTable);

		assertEquals("net.minecraft.world.item.ItemStack + getItem + intermediary:net/minecraft/class_11", fromSlashed,
				"a table written with internal names must still be answered with binary ones");
		assertEquals(fromDotted, fromSlashed,
				"the separator a table happens to use must not change what the resolver answers");
	}

	/**
	 * A caller that follows Fabric's documented form — binary class names, so dots — must be answered.
	 *
	 * <p>Before the name was canonicalised, the table key was the slashed intermediary class name and every dotted
	 * query missed: the mod got its own input back, believed it had been remapped, and used a name the game does
	 * not have. Nothing threw.
	 */
	@Test
	void aDottedCallerIsAnsweredRatherThanSilentlyMissed() throws Exception {
		Path table = root.resolve("synthetic.tsv");
		Files.writeString(table, TABLE, StandardCharsets.UTF_8);
		System.setProperty(KernelMappingResolver.TABLE_PROPERTY, table.toString());
		KernelMappingResolver.forgetTableForTest();

		KernelMappingResolver resolver = new KernelMappingResolver();

		assertEquals("net.minecraft.world.item.ItemStack",
				resolver.mapClassName("intermediary", "net.minecraft.class_11"),
				"a binary (dotted) query is the form fabric-loader's MappingResolver takes");
		assertEquals("getItem", resolver.mapMethodName("intermediary", "net.minecraft.class_11", "method_22", "()V"),
				"a member owner arrives in the same binary form as a class name");
		assertEquals("count", resolver.mapFieldName("intermediary", "net.minecraft.class_11", "field_33", "I"));
	}

	/**
	 * A member record that is missing its official name is refused, rather than skipped.
	 *
	 * <p>The loader used to guard member records with {@code if (f.length >= 4)}: a truncated line vanished without
	 * a word, the table loaded, and the only symptom was lookups that came back unchanged. A table that cannot be
	 * read whole is not half-used now — the resolver falls back to identity and says so, which is loud in one place
	 * instead of silent in every lookup.
	 */
	@Test
	void aTruncatedMemberRecordRefusesTheWholeTableInsteadOfMappingLess() throws Exception {
		Path table = root.resolve("truncated.tsv");
		Files.writeString(table, """
				C\tnet/minecraft/class_11\tnet.minecraft.world.item.ItemStack
				M\tnet/minecraft/class_11\tmethod_22
				""", StandardCharsets.UTF_8);
		System.setProperty(KernelMappingResolver.TABLE_PROPERTY, table.toString());
		KernelMappingResolver.forgetTableForTest();

		KernelMappingResolver resolver = new KernelMappingResolver();

		assertEquals("net/minecraft/class_11", resolver.mapClassName("intermediary", "net/minecraft/class_11"),
				"the table is refused, not partially loaded: the intact class record must not be served either");
		assertEquals(List.of(KernelMappingResolver.RUNTIME_NAMESPACE),
				List.copyOf(resolver.getNamespaces()),
				"a refused table leaves the identity resolver, which carries one namespace");
	}

	private static String answerFor(Path table) {
		System.setProperty(KernelMappingResolver.TABLE_PROPERTY, table.toString());
		KernelMappingResolver.forgetTableForTest();
		KernelMappingResolver resolver = new KernelMappingResolver();
		String mapped = resolver.mapClassName("intermediary", "net/minecraft/class_11");
		String member = resolver.mapMethodName("intermediary", "net/minecraft/class_11", "method_22", "()V");
		String reverse = resolver.unmapClassName("intermediary", mapped);
		return mapped + " + " + member + " + intermediary:" + reverse;
	}
}
