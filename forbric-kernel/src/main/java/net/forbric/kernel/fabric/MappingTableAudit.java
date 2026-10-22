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

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Replays every record of a mapping table through the REAL resolver and reports what it answered.
 *
 * <h2>Why the audit is not a file check</h2>
 *
 * <p>The table is written by build tooling and read by {@link KernelMappingResolver}. Between those two there is a
 * format both must agree on, and a disagreement does not throw: the resolver returns the input unchanged, which
 * looks exactly like a member that has no mapping. So the tool that writes the table cannot be the thing that
 * certifies it. This class loads the file <em>through the resolver</em> — system property, static table, the same
 * code path a mod's lookup takes — and compares the answer for every single record against what the file says.
 *
 * <p>Its output is also the input to {@code tools/namespace/verify-runtime-table.py}, which asks the real game jar
 * whether those answers name a member that exists. The split is deliberate: this half proves
 * <em>the resolver returns the table</em>, that half proves <em>the table is true of the game</em>. Neither half
 * alone can tell you both.
 *
 * <h2>What it asserts, and how it fails</h2>
 *
 * <p>Exit status 1 if any record comes back different from the file, with the first ten mismatches printed. Every
 * record is checked; nothing is sampled.
 *
 * <pre>
 *   java -Dforbric.mappings=&lt;table&gt; ... net.forbric.kernel.fabric.MappingTableAudit --table &lt;table&gt; --answers &lt;out&gt;
 * </pre>
 */
public final class MappingTableAudit {

	private MappingTableAudit() {
	}

	public static void main(String[] args) throws IOException {
		Path table = null;
		Path answers = null;
		for (int i = 0; i < args.length; i++) {
			switch (args[i]) {
				case "--table" -> table = Path.of(args[++i]);
				case "--answers" -> answers = Path.of(args[++i]);
				default -> throw new IllegalArgumentException("usage: --table <tsv> [--answers <tsv>]");
			}
		}
		if (table == null) throw new IllegalArgumentException("usage: --table <tsv> [--answers <tsv>]");
		if (!Files.isRegularFile(table)) throw new IllegalArgumentException("no such table: " + table);

		// Exactly how a launch supplies it: the property, before the resolver's first lookup. The table is static
		// and loads once, so this has to be set before anything touches the resolver.
		System.setProperty(KernelMappingResolver.TABLE_PROPERTY, table.toString());
		KernelMappingResolver.forgetTableForTest();
		KernelMappingResolver resolver = new KernelMappingResolver();

		System.out.printf("[audit] table %s%n", table);
		System.out.printf("[audit] namespaces %s, runtime %s%n",
				new ArrayList<>(resolver.getNamespaces()), resolver.getCurrentRuntimeNamespace());

		int classes = 0;
		int methods = 0;
		int fields = 0;
		int matched = 0;
		List<String> mismatches = new ArrayList<>();
		List<String> out = new ArrayList<>();
		for (String raw : Files.readAllLines(table, StandardCharsets.UTF_8)) {
			if (raw.isEmpty() || raw.charAt(0) == '#') continue;
			String[] f = raw.split("\t");
			String kind = f[0];
			String line;
			String answer;
			switch (kind) {
				case "C" -> {
					classes++;
					answer = resolver.mapClassName("intermediary", f[1]);
					line = "C\t" + f[1] + "\t\t" + answer;
				}
				case "M" -> {
					methods++;
					// The descriptor is deliberately the empty one: the resolver's member key is owner#name and
					// the join is 1:1 in that key (asserted by the verifier), so the descriptor cannot change the
					// answer. Passing a real one would only make this look like it proved something it did not.
					answer = resolver.mapMethodName("intermediary", f[1], f[2], "()V");
					line = "M\t" + f[1] + "\t" + f[2] + "\t" + answer;
				}
				case "F" -> {
					fields++;
					answer = resolver.mapFieldName("intermediary", f[1], f[2], "I");
					line = "F\t" + f[1] + "\t" + f[2] + "\t" + answer;
				}
				default -> throw new IllegalStateException("unknown record kind '" + kind + "' in " + table);
			}
			String expected = f[kind.equals("C") ? 2 : 3];
			// Class answers are compared in the form fabric-loader's contract gives them. A table may spell an
			// official class name internally (join-keyed.py does, for the same file's other consumer); the resolver
			// answers binary names either way, so comparing the raw strings would report a correct table as wrong.
			boolean same = kind.equals("C")
					? answer.replace('/', '.').equals(expected.replace('/', '.'))
					: answer.equals(expected);
			if (same) {
				matched++;
			} else if (mismatches.size() < 10) {
				mismatches.add(kind + " " + f[1] + " " + f[2] + ": table says " + expected
						+ ", resolver answered " + answer);
			}
			out.add(line);
		}

		int records = classes + methods + fields;
		System.out.printf("[audit] %d records: C=%d M=%d F=%d%n", records, classes, methods, fields);
		System.out.printf("[audit] resolver returned the table's name for %d of %d records%n", matched, records);
		if (answers != null) {
			Files.createDirectories(answers.toAbsolutePath().getParent());
			try (BufferedWriter w = Files.newBufferedWriter(answers, StandardCharsets.UTF_8)) {
				w.write("# kind\towner\tname\tresolver answer -- written by MappingTableAudit\n");
				for (String line : out) w.write(line + "\n");
			}
			System.out.printf("[audit] answers written to %s%n", answers);
		}
		if (!mismatches.isEmpty()) {
			for (String m : mismatches) System.out.println("[audit] MISMATCH " + m);
			System.out.printf("FAIL: %d of %d records were not answered as written%n", records - matched, records);
			System.exit(1);
		}
		System.out.printf("PASS: %d of %d records answered exactly as written%n", matched, records);
	}
}
