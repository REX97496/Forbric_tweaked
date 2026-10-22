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

package net.forbric.installer.kernel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads Mojang's obfuscation map ({@code client.txt}, published as {@code downloads.client_mappings} in the version
 * JSON) and turns it into the three lookups {@link MojmapRenamer} needs.
 *
 * <p>Only an <em>obfuscated</em> Minecraft has this file, which is what makes its presence the gate for the
 * deobfuscation step: 26.x ships Mojmap-native and publishes no {@code client_mappings} at all ({@code 26.2.json}
 * lists only {@code client} and {@code server}), so there is nothing to rename there and the step must not run.
 *
 * <h2>Format, and the traps that each produced a silent zero or a wrong name</h2>
 *
 * <pre>
 *   &lt;mojmap class&gt; -&gt; &lt;obf class&gt;:                          one class header, DOTTED names
 *   # {"fileName":"Foo.java","id":"sourceFile"}              the class's source file
 *       &lt;type&gt; &lt;mojmap field&gt; -&gt; &lt;obf field&gt;                field: no '(' on the left
 *       &lt;ls&gt;:&lt;le&gt;:&lt;ret&gt; &lt;mojmap method&gt;(&lt;params&gt;) -&gt; &lt;obf&gt;   method: has '('
 * </pre>
 *
 * <ol>
 *   <li>The direction is <b>mojmap -&gt; obf</b>, reversed relative to what a renamer wants. Both directions are built,
 *       because member keys have to be emitted in <em>obf</em> form: {@code SimpleRemapper} looks up the exact strings
 *       that appear in the obfuscated class file.</li>
 *   <li>{@code # {...}} lines must be skipped for owner tracking — but they are not ignorable: they carry the
 *       {@code SourceFile} value, and the obfuscated jar's own {@code SourceFile} is the useless literal
 *       {@code "SourceFile"}. This annotation is the only source of the real file name.</li>
 *   <li>Fields and methods are separate dimensions, but <b>not symmetrically</b>: ASM's {@code SimpleRemapper} looks a
 *       method up as {@code <obfOwner>.<obfName><obfDescriptor>} and a field up as {@code <obfOwner>.<obfName>} — the
 *       field descriptor is not part of the key at all, which is visible in that class as an ignored
 *       {@code descriptor} parameter. Getting this wrong fails silently in the most misleading way possible: every
 *       class still renames, every member name stays obfuscated, and nothing complains. Keyed this way an obf name
 *       shared by a field and a method still cannot collide, because a method key always carries its {@code (}.</li>
 *   <li>Path separators: the file is dotted, class files are slashed. Converted exactly once, on entry.</li>
 *   <li>Headers and member lines are interleaved, and a member line may reference a class whose header appears
 *       <em>later</em> ({@code com.mojang.blaze3d.*} is near the top and names {@code com.mojang.serialization
 *       .MapCodec}). So the file is read twice: headers first, then members.</li>
 * </ol>
 */
final class MojmapMappings {

	/** The lookups, all keyed on <em>obfuscated</em> names. */
	static final class Mappings {
		/** obf internal name -&gt; Mojmap internal name (identity entries included). */
		final Map<String, String> classNames;
		/**
		 * Keyed exactly the way {@code SimpleRemapper} looks names up: {@code <obfOwner>.<obfName><obfMethodDescriptor>}
		 * for methods and {@code <obfOwner>.<obfName>} for fields. -&gt; Mojmap member name.
		 */
		final Map<String, String> members;
		/** obf internal name -&gt; {@code <SimpleName>.java}, from the {@code sourceFile} annotation line. */
		final Map<String, String> sourceFiles;
		/** Counts actually parsed — for the log line only, never for a decision. */
		final int classes, fields, methods;
		/**
		 * Distinct member descriptor types that the file does not declare as a class — the JDK, Guava, fastutil:
		 * classes that were never obfuscated, so their name is the same in both namespaces. Not an error; reported
		 * because a sudden jump would mean the wrong mapping file, and because a type that <em>should</em> have been
		 * in there would leave a member key unlookup-able.
		 */
		final int unmappedTypes;

		Mappings(Map<String, String> classNames, Map<String, String> members, Map<String, String> sourceFiles,
		         int classes, int fields, int methods, int unmappedTypes) {
			this.classNames = classNames;
			this.members = members;
			this.sourceFiles = sourceFiles;
			this.classes = classes;
			this.fields = fields;
			this.methods = methods;
			this.unmappedTypes = unmappedTypes;
		}

		String summary() {
			return classes + " classes, " + fields + " fields, " + methods + " methods";
		}
	}

	private static final String[] PRIMITIVES =
			{"void", "boolean", "byte", "char", "short", "int", "long", "float", "double"};
	private static final String[] PRIMITIVE_DESCRIPTORS = {"V", "Z", "B", "C", "S", "I", "J", "F", "D"};

	private MojmapMappings() {
	}

	static Mappings parse(Path clientTxt) throws IOException {
		List<String> lines = Files.readAllLines(clientTxt, StandardCharsets.UTF_8);

		// Pass 1 — class headers. Member descriptors need the reverse direction and may name a class declared
		// further down the file, so the whole class table has to exist before any member line is read.
		Map<String, String> classNames = new LinkedHashMap<>(); // obf -> mojmap
		Map<String, String> mojToObf = new LinkedHashMap<>();   // mojmap -> obf
		for (String line : lines) {
			if (!isHeader(line)) continue;
			int arrow = line.indexOf(" -> ");
			classNames.put(internal(stripColon(line.substring(arrow + 4))), internal(line.substring(0, arrow)));
		}
		for (Map.Entry<String, String> e : classNames.entrySet()) mojToObf.put(e.getValue(), e.getKey());

		// Pass 2 — member lines, plus the SourceFile annotation that follows every class header.
		Map<String, String> members = new LinkedHashMap<>();
		Map<String, String> sourceFiles = new LinkedHashMap<>();
		Set<String> unmapped = new LinkedHashSet<>();
		String owner = null;        // obf name of the class whose member lines we are inside
		String awaitingFile = null; // class header whose annotation line has not been read yet
		int fields = 0, methods = 0;
		for (String line : lines) {
			if (line.isEmpty()) continue;
			char first = line.charAt(0);
			if (first == '#') {
				// Never an owner change; only ever adds information.
				if (awaitingFile != null) {
					int at = line.indexOf("\"fileName\":\"");
					int end = at < 0 ? -1 : line.indexOf('"', at + 12);
					if (end > at) sourceFiles.put(awaitingFile, line.substring(at + 12, end));
					awaitingFile = null;
				}
				continue;
			}
			if (first == ' ' || first == '\t') {
				if (owner == null) continue; // malformed: a member line before any header
				String body = line.trim();
				int arrow = body.indexOf(" -> ");
				if (arrow < 0) continue;
				String obfName = body.substring(arrow + 4).trim();
				if (obfName.isEmpty() || obfName.startsWith("<")) continue; // <init>/<clinit> map to themselves
				String left = stripLineNumbers(body.substring(0, arrow).trim());
				int paren = left.indexOf('(');
				int close = paren < 0 ? -1 : left.lastIndexOf(')');
				if (paren >= 0 && close > paren) {
					String head = left.substring(0, paren).trim();
					String name = afterLastSpace(head);
					String descriptor = methodDescriptor(head, left.substring(paren + 1, close), mojToObf, unmapped);
					if (name == null || descriptor == null) continue; // malformed line, not a mapping miss
					// method key carries the descriptor: SimpleRemapper composes owner + '.' + name + descriptor
					members.putIfAbsent(owner + "." + obfName + descriptor, name);
					methods++;
				} else if (paren < 0) {
					String name = afterLastSpace(left);
					if (name == null) continue;
					// field key carries NO descriptor: SimpleRemapper composes owner + '.' + name and ignores the
					// field's descriptor entirely. Two fields of one class cannot share a name, so this is unique.
					members.putIfAbsent(owner + "." + obfName, name);
					fields++;
				}
				continue;
			}
			owner = internal(stripColon(line.substring(line.indexOf(" -> ") + 4)));
			awaitingFile = owner;
		}
		return new Mappings(classNames, members, sourceFiles, classNames.size(), fields, methods, unmapped.size());
	}

	/** True for a class header line: not blank, not indented, not a {@code #} annotation, and has the arrow. */
	private static boolean isHeader(String line) {
		if (line.isEmpty()) return false;
		char first = line.charAt(0);
		if (first == '#' || first == ' ' || first == '\t') return false;
		return line.indexOf(" -> ") >= 0;
	}

	/** The two directions of a class header, in internal (slashed) form. */
	private static String internal(String dotted) throws IOException {
		String slashed = dotted.trim().replace('.', '/');
		// A class key must never contain '.', because the renamer's map answers "no such member" for anything
		// dotted — that is how a member key is told apart from a class key. Fail loudly rather than mis-answer.
		if (slashed.indexOf('.') >= 0) throw new IOException("unexpected dotted name in client mappings: " + dotted);
		return slashed;
	}

	private static String stripColon(String s) {
		String t = s.trim();
		return t.endsWith(":") ? t.substring(0, t.length() - 1) : t;
	}

	/** Drop a leading {@code <lineStart>:<lineEnd>:} from a method line. */
	private static String stripLineNumbers(String s) {
		int i = 0;
		for (int colon = 0; colon < 2; colon++) {
			int start = i;
			while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
			if (i == start || i >= s.length() || s.charAt(i) != ':') return s; // not a line-number prefix
			i++;
		}
		return s.substring(i);
	}

	/** {@code "<type> <name>"} -> name. Types never contain spaces. */
	private static String afterLastSpace(String left) {
		int sp = left.lastIndexOf(' ');
		return sp < 0 ? null : left.substring(sp + 1).trim();
	}

	/** {@code "<type> <name>"} -> the type part. */
	private static String beforeLastSpace(String left) {
		int sp = left.lastIndexOf(' ');
		return sp < 0 ? null : left.substring(0, sp).trim();
	}

	private static String methodDescriptor(String head, String params, Map<String, String> mojToObf,
	                                       Set<String> unmapped) {
		String ret = typeDescriptor(beforeLastSpace(head), mojToObf, unmapped);
		if (ret == null) return null;
		StringBuilder sb = new StringBuilder("(");
		for (String p : params.split(",")) {
			String t = p.trim();
			if (t.isEmpty()) continue;
			String d = typeDescriptor(t, mojToObf, unmapped);
			if (d == null) return null;
			sb.append(d);
		}
		return sb.append(')').append(ret).toString();
	}

	/**
	 * Mojmap dotted source type -&gt; JVM descriptor, in <em>obfuscated</em> form.
	 *
	 * <p>A type the file does not declare as a class is not obfuscated at all — the JDK, Guava, fastutil — so its
	 * obfuscated name <em>is</em> its own name. That fallback is what makes the descriptor right; treating it as a
	 * miss would silently leave a third of the members unmapped.
	 */
	private static String typeDescriptor(String dottedType, Map<String, String> mojToObf, Set<String> unmapped) {
		if (dottedType == null) return null;
		String t = dottedType.trim();
		int dims = 0;
		while (t.endsWith("[]")) {
			t = t.substring(0, t.length() - 2).trim();
			dims++;
		}
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < dims; i++) sb.append('[');
		for (int i = 0; i < PRIMITIVES.length; i++) {
			if (PRIMITIVES[i].equals(t)) {
				if (dims > 0 && i == 0) return null; // void[]
				return sb.append(PRIMITIVE_DESCRIPTORS[i]).toString();
			}
		}
		String internal = t.replace('.', '/');
		String obf = mojToObf.get(internal);
		if (obf == null) unmapped.add(internal);
		return sb.append('L').append(obf != null ? obf : internal).append(';').toString();
	}
}
