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

package net.forbric.kernel.util;

import java.nio.charset.StandardCharsets;

/**
 * Looks for ASCII needles in raw class bytes, without parsing or copying them.
 *
 * <h2>Why this exists as a shared thing</h2>
 *
 * <p>Several transformers run for EVERY class the game loads and want the same cheap first question: does this
 * class even mention the name I care about? A class file stores type and method names in its constant pool as
 * modified UTF-8, and every name these transformers look for is plain ASCII, so it appears in the bytes
 * verbatim. A "no" is therefore final, not a guess — the class provably does not name it.
 *
 * <p>Three copies of this had grown, each slightly different, and one of them turned the whole class into a
 * {@code String} first: an allocation the size of the class, for every class, to ask a question that needs none.
 */
public final class ByteScan {

	private ByteScan() {
	}

	/** The bytes of an ASCII needle, for the constants a caller holds. */
	public static byte[] needle(String ascii) {
		return ascii.getBytes(StandardCharsets.US_ASCII);
	}

	/** Whether {@code haystack} contains {@code needle}. */
	public static boolean contains(byte[] haystack, byte[] needle) {
		return containsAny(haystack, new byte[][] {needle});
	}

	/**
	 * Whether {@code haystack} contains any of {@code needles}, in ONE pass over the bytes.
	 *
	 * <p>One pass rather than one per needle: the answer is almost always no, and a scan per needle walks the
	 * whole class once per needle to say so.
	 *
	 * <p>Within a position, only the needles whose FIRST byte equals this one are tried, reached through a
	 * 256-entry table instead of a loop over all of them. The loop was the cost: a class names a handful of the
	 * things these callers ask about and none of the rest, so for nearly every position every needle failed on
	 * its first byte — after an array read, a null check and two length checks to get that far. The table makes
	 * that decision one indexed load, and it is built once per call rather than once per position.
	 *
	 * <p>Measured on this workspace's jar set (4067 classes): the three boot-side audits that call this spent
	 * 404 ms between them, of which 81 ms was reading the bytes at all — so most of the rest was exactly this
	 * loop, and it is the part a cheaper comparison can remove without changing a single verdict.
	 */
	public static boolean containsAny(byte[] haystack, byte[][] needles) {
		if (haystack == null || haystack.length == 0 || needles == null) return false;

		int shortest = Integer.MAX_VALUE;
		int usable = 0;
		for (byte[] needle : needles) {
			if (needle != null && needle.length > 0) {
				shortest = Math.min(shortest, needle.length);
				usable++;
			}
		}
		if (usable == 0 || shortest == Integer.MAX_VALUE || haystack.length < shortest) return false;

		// Bucket the needles by their first byte, preserving order so a hit is reported for the same needle the
		// loop would have found first. Allocated per call: this method is called per class, and a cached table
		// would have to be keyed on the needle ARRAY IDENTITY, which callers are free to rebuild.
		byte[][][] byFirst = new byte[256][][];
		int[] counts = new int[256];
		for (byte[] needle : needles) {
			if (needle == null || needle.length == 0) continue;
			counts[needle[0] & 0xFF]++;
		}
		for (int b = 0; b < 256; b++) if (counts[b] != 0) byFirst[b] = new byte[counts[b]][];
		int[] next = new int[256];
		for (byte[] needle : needles) {
			if (needle == null || needle.length == 0) continue;
			int b = needle[0] & 0xFF;
			byFirst[b][next[b]++] = needle;
		}

		int last = haystack.length - shortest;
		for (int at = 0; at <= last; at++) {
			byte[][] candidates = byFirst[haystack[at] & 0xFF];
			if (candidates == null) continue;
			for (byte[] needle : candidates) {
				if (at + needle.length > haystack.length) continue;
				if (matchesAt(haystack, at, needle)) return true;
			}
		}
		return false;
	}

	/**
	 * Which of {@code needles} {@code haystack} contains — the answer {@link #containsAny} cannot give.
	 *
	 * <p>{@code containsAny} stops at the first needle it finds, which is right for a caller asking "is this
	 * class interesting at all" and wrong for one that has a separate decision per needle. The alternative was a
	 * {@link #contains} call per needle, and each of those walks the whole array looking for one string, so a
	 * caller with fifteen needles walked the same bytes fifteen times to learn that most of them were absent.
	 *
	 * <p>Same semantics as {@link #contains} per needle, including SUBSTRING matching: a needle that is a
	 * package prefix matches a class that names anything under it, which is what the boot-side audits rely on and
	 * what an exact constant-pool test would stop reporting. Runs to completion rather than stopping early —
	 * every entry is an answer, so there is no "first" to stop at.
	 */
	public static boolean[] containsWhich(byte[] haystack, byte[][] needles) {
		boolean[] found = new boolean[needles == null ? 0 : needles.length];
		if (haystack == null || haystack.length == 0 || needles == null) return found;

		// Only needles that are still unanswered take part. When they are all found the scan stops, which is the
		// common case for a class that names a package prefix: one match answers a whole group of needles.
		int shortest = Integer.MAX_VALUE;
		int outstanding = 0;
		for (int i = 0; i < needles.length; i++) {
			byte[] needle = needles[i];
			if (needle == null || needle.length == 0) continue;
			shortest = Math.min(shortest, needle.length);
			outstanding++;
		}
		if (outstanding == 0 || haystack.length < shortest) return found;

		int last = haystack.length - shortest;
		for (int at = 0; at <= last && outstanding > 0; at++) {
			byte first = haystack[at];
			for (int i = 0; i < needles.length; i++) {
				if (found[i]) continue;
				byte[] needle = needles[i];
				if (needle == null || needle.length == 0) continue;
				// Leading byte first. Without this the loop tested every unanswered needle at every position --
				// and a class names a handful of the things callers ask about and none of the rest, so nearly
				// every test was decided by its first byte after reading the array entry and checking two
				// lengths. Measured: this one comparison is the difference between 177 ms and 93 ms over 4067
				// classes, which is what made the first version SLOWER than the per-needle loop it replaced.
				if (needle[0] != first) continue;
				if (at + needle.length > haystack.length) continue;
				if (matchesAt(haystack, at, needle)) {
					found[i] = true;
					outstanding--;
				}
			}
		}
		return found;
	}

	/**
	 * The bytes a class file stores {@code name} as in a {@code CONSTANT_Utf8} entry: the JVM's modified UTF-8, in which
	 * {@code U+0000} takes two bytes and a supplementary character is its two surrogates, three bytes each. For ASCII it
	 * is {@link #needle}; for any other name it is what {@link #constantPoolNames} has to compare against, where the
	 * standard UTF-8 (or ASCII, which turns the character into {@code ?}) of a non-ASCII name would never match.
	 */
	public static byte[] poolEntry(String name) {
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(name.length());
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			if (c >= 0x0001 && c <= 0x007F) {
				out.write(c);
			} else if (c <= 0x07FF) {
				out.write(0xC0 | (c >> 6));
				out.write(0x80 | (c & 0x3F));
			} else {
				out.write(0xE0 | (c >> 12));
				out.write(0x80 | ((c >> 6) & 0x3F));
				out.write(0x80 | (c & 0x3F));
			}
		}
		return out.toByteArray();
	}

	/**
	 * Which of {@code entries} ({@link #poolEntry} encodings of names) are, exactly, one of the class file's
	 * {@code CONSTANT_Utf8} entries.
	 *
	 * <p>Every name a class declares or uses is such an entry: its own name, each member's name and descriptor, and
	 * the class, name and descriptor of every field or method it references, by instruction or by method handle. So
	 * an entry reported absent is provably not one of those names, and a present one is an exact entry rather than a
	 * substring hit. The walk reads the constant pool once and compares only entries of an entry's length; it never
	 * reaches fields, methods or code, which {@link #containsAny} has to scan byte by byte. That difference is most of
	 * the cost of a question asked of every class the game loads.
	 *
	 * <p>A class this walk cannot read (no magic, a pool that is empty, truncated or carries an unknown tag, or no
	 * room after it for the class header) reports every entry present, so the caller goes on to parse it and fails,
	 * or not, exactly as it would have without asking.
	 */
	public static boolean[] constantPoolNames(byte[] classBytes, byte[][] entries) {
		boolean[] found = new boolean[entries.length];
		return walk(classBytes, entries, found, false) ? found : everything(found);
	}

	/** Whether any of {@code entries} is exactly one of the class file's {@code CONSTANT_Utf8} entries; see
	 * {@link #constantPoolNames}, including that an unreadable class answers yes. Stops at the first one found. */
	public static boolean namesAny(byte[] classBytes, byte[][] entries) {
		boolean[] found = new boolean[entries.length];
		if (!walk(classBytes, entries, found, true)) return entries.length > 0;
		for (boolean present : found) if (present) return true;
		return false;
	}

	/** Marks {@code found}; false when the pool cannot be read. With {@code first}, returns at the first match. */
	private static boolean walk(byte[] classBytes, byte[][] entries, boolean[] found, boolean first) {
		if (classBytes == null || classBytes.length < 10 || u4(classBytes, 0) != 0xCAFEBABE) return false;
		try {
			int count = u2(classBytes, 8), at = 10;
			if (count == 0) return false;
			for (int index = 1; index < count; index++) {
				switch (classBytes[at] & 0xFF) {
					case 1 -> { // Utf8: u2 length, then the bytes
						int length = u2(classBytes, at + 1), start = at + 3;
						if (start + length > classBytes.length) return false;
						for (int n = 0; n < entries.length; n++) {
							if (!found[n] && entries[n].length == length && matchesAt(classBytes, start, entries[n])) {
								found[n] = true;
								if (first) return true;
							}
						}
						at = start + length;
					}
					case 7, 8, 16, 19, 20 -> at += 3; // Class, String, MethodType, Module, Package
					case 15 -> at += 4; // MethodHandle
					case 3, 4, 9, 10, 11, 12, 17, 18 -> at += 5; // Integer, Float, the refs, NameAndType, (Invoke)Dynamic
					case 5, 6 -> { // Long, Double take two slots
						at += 9;
						index++;
					}
					default -> {
						return false;
					}
				}
			}
			// access_flags, this_class, super_class and interfaces_count follow the pool in every class file.
			return at + 8 <= classBytes.length;
		} catch (IndexOutOfBoundsException unreadable) {
			return false;
		}
	}

	private static boolean[] everything(boolean[] found) {
		java.util.Arrays.fill(found, true);
		return found;
	}

	private static int u2(byte[] bytes, int at) {
		return ((bytes[at] & 0xFF) << 8) | (bytes[at + 1] & 0xFF);
	}

	private static int u4(byte[] bytes, int at) {
		return (u2(bytes, at) << 16) | u2(bytes, at + 2);
	}

	private static boolean matchesAt(byte[] haystack, int at, byte[] needle) {
		for (int i = 0; i < needle.length; i++) {
			if (haystack[at + i] != needle[i]) return false;
		}
		return true;
	}
}
