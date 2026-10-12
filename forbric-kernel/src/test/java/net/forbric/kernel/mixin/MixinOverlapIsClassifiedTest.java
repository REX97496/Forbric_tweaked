package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

/**
 * Every mixin overlap the kernel reports is classified, and the classification is the one that matters: on the 26.2
 * test instance, NONE of them is benign.
 *
 * <h2>What an overlap is, and why "benign" was the question</h2>
 *
 * <p>Two mods injecting into one method is ordinary and usually harmless — different {@code @At} points compose
 * fine. The dangerous overlaps are the ones where one side REPLACES what the other attached to, and those are not
 * a matter of taste:
 *
 * <ul>
 *   <li><b>R1</b> — two {@code @Overwrite} of the same method. Mixin keeps the one with the higher priority and
 *       silently drops the other, so one mod's optimisation or rule simply is not there.
 *   <li><b>R2</b> — two {@code @Redirect} of the same call. Mixin keeps one and skips the other, same silence.
 *   <li><b>R3</b> — an {@code @Overwrite} against anything else. The overwrite replaces the body, so the other
 *       side's anchor no longer exists and its injector never binds — which then shows up as a separate CONFIRMED
 *       loss on the mod that did nothing wrong.
 * </ul>
 *
 * <h2>Measured on the 152-mod instance</h2>
 *
 * <p>23 overlap findings, which are 12 distinct pairs seen from both sides: 8 R1, 2 R2, 13 R3. Zero benign. The
 * recurring counterpart is lithium, on 8 of them, which follows from its shape rather than from anything about
 * this loader: it carries 476 mixins and reaches for {@code @Overwrite} where the other performance mods
 * ({@code c2me}, {@code ferritecore}, {@code krypton}) do too.
 *
 * <h2>This is not a defect to fix, and saying otherwise would be the wrong conclusion</h2>
 *
 * <p>Two {@code @Overwrite}s of one method cannot both take effect on any loader. A player assembling a normal
 * Fabric instance picks lithium OR ferritecore; this loader is built to merge modpacks, so it meets both and says
 * so. What the kernel owes the player here is the report, and the report is what this test pins — per pair, the
 * rule, the two sides by config and class, and the sentence explaining what is lost. Making the conflict disappear
 * is not a thing that can be done; naming it is.
 *
 * <p>Skips without the report, which is written by running the instance.
 */
class MixinOverlapIsClassifiedTest {

	private static final List<Path> REPORTS = List.of(
			Path.of("build", "..", ".dev-test", "server", ".forbric-kernel", "compatibility-report.json").normalize(),
			Path.of(".dev-test", "server", ".forbric-kernel", "compatibility-report.json"),
			Path.of("..", ".dev-test", "server", ".forbric-kernel", "compatibility-report.json").normalize());

	/** The rules, and the one thing each has to say. Pinned by name so a rule cannot quietly change meaning. */
	private static final Map<String, String> RULES = new LinkedHashMap<>(Map.of(
			"R1", "Overwrite",
			"R2", "Redirect",
			"R3", "replaces the body"));

	private static Path report() {
		for (Path candidate : REPORTS) {
			if (Files.isRegularFile(candidate)) return candidate.toAbsolutePath().normalize();
		}
		return null;
	}

	@Test
	void everyReportedOverlapCarriesARuleAndBothSides() throws IOException {
		Path report = report();
		net.forbric.kernel.TestFixtures.require(net.forbric.kernel.TestFixtures.Fixture.STAGED,
				report != null, "no compatibility report under " + REPORTS
						+ "; run the server against the test instance to write one");

		String json = Files.readString(report, StandardCharsets.UTF_8);
		List<String> blocks = overlapBlocks(json);
		assertTrue(blocks.size() >= 10,
				"the report holds " + blocks.size() + " mixin-overlap finding(s), too few to be the 152-mod"
						+ " instance the classification was measured on — re-measure rather than trusting this test");

		Map<String, Integer> byRule = new TreeMap<>();
		List<String> incomplete = new ArrayList<>();
		for (String block : blocks) {
			String rule = field(block, "rule");
			String own = field(block, "own");
			String other = field(block, "other");
			if (rule == null || own == null || other == null) {
				incomplete.add(block.substring(0, Math.min(120, block.length())));
				continue;
			}
			byRule.merge(rule, 1, Integer::sum);
		}

		assertTrue(incomplete.isEmpty(),
				"overlap finding(s) without a rule and both sides. An overlap nobody classified is the failure this"
						+ " test exists for: an unclassified overlap reads as noise, and noise gets scrolled past. "
						+ incomplete);
		System.out.println("[overlap] " + blocks.size() + " finding(s) by rule: " + byRule);
		for (String rule : byRule.keySet()) {
			assertTrue(RULES.containsKey(rule),
					"rule " + rule + " is not one of " + RULES.keySet() + ", so nothing documents what it means;"
							+ " a new rule needs its meaning written down here and in the lint that emits it");
		}
	}

	/**
	 * The claim the classification was measured for: on this instance no overlap is benign, so a test that starts
	 * passing because overlaps became rare is a change in the MOD SET, not a change in the loader.
	 */
	@Test
	void noneOfTheMeasuredOverlapsIsBenign() throws IOException {
		Path report = report();
		net.forbric.kernel.TestFixtures.require(net.forbric.kernel.TestFixtures.Fixture.STAGED,
				report != null, "no compatibility report under " + REPORTS);

		String json = Files.readString(report, StandardCharsets.UTF_8);
		List<String> blocks = overlapBlocks(json);
		Map<String, Integer> byRule = new TreeMap<>();
		List<String> unexplained = new ArrayList<>();
		for (String block : blocks) {
			String rule = field(block, "rule");
			String detail = field(block, "detail");
			byRule.merge(String.valueOf(rule), 1, Integer::sum);
			// Each rule has to carry its own explanation in the finding, because the rule name alone does not tell a
			// player what they lose. R1 and R2 say "cannot both"; R3 says a body something else's overwrite REPLACES.
			// The phrasing is matched loosely on purpose: the first version looked for "replaces the body", and the
			// report says "written against a body lithium's @Overwrite replaces" — the same statement with the words
			// in the other order, so a word-order assumption failed a finding that was already correct.
			boolean explained = detail != null
					&& (detail.contains("cannot both") || detail.contains("replaces") || detail.contains("never binds"));
			if (!explained) unexplained.add(String.valueOf(rule) + ": "
					+ (detail == null ? "(no detail)" : detail.substring(0, Math.min(120, detail.length()))));
		}

		assertTrue(unexplained.isEmpty(),
				"overlap finding(s) whose detail does not say what is lost, so the rule name is all a player gets: "
						+ unexplained);

		// All three rules are in play, which is what makes the claim "none is benign" a measurement of this set
		// rather than of one rule's luck. If a rule disappears the mod set changed, not the loader.
		assertTrue(byRule.size() >= 3,
				"only " + byRule.size() + " rule(s) present (" + byRule + "); the measured set exercised three, so"
						+ " this run is not comparable to the classification and the counts above mean less than they"
						+ " look like");
	}

	/** The {@code mixin-overlap:} findings, as raw JSON object slices. */
	private static List<String> overlapBlocks(String json) {
		List<String> blocks = new ArrayList<>();
		int at = json.indexOf("\"mixin-overlap:");
		while (at >= 0) {
			int open = json.lastIndexOf('{', at);
			if (open < 0) break;
			int depth = 0;
			int i = open;
			for (; i < json.length(); i++) {
				char c = json.charAt(i);
				if (c == '{') depth++;
				else if (c == '}') {
					depth--;
					if (depth == 0) break;
				}
			}
			if (i <= open || i >= json.length()) break;
			blocks.add(json.substring(open, i + 1));
			at = json.indexOf("\"mixin-overlap:", i);
		}
		return blocks;
	}

	/**
	 * The value of {@code key} in a JSON slice, or null.
	 *
	 * <p>Handles both shapes the report uses: {@code "key": "value"} for the finding's own fields, and
	 * {@code "key=value"} inside the evidence ARRAY, where each entry is a single string rather than a member. The
	 * first version only handled the member form, so every evidence lookup returned null and the test failed
	 * claiming nothing was classified — when the report classified all 23 of them. A parsing helper that returns
	 * "absent" for a shape it was never taught reads exactly like missing data.
	 */
	private static String field(String block, String key) {
		String value = memberValue(block, key);
		return value != null ? value : embeddedValue(block, key);
	}

	/** {@code "key": "value"} — the object-member form. */
	private static String memberValue(String block, String key) {
		int at = block.indexOf("\"" + key + "\"");
		while (at >= 0) {
			int colon = block.indexOf(':', at);
			if (colon < 0) return null;
			// Only a colon directly after the key makes this a member; "key=value" has none.
			if (!block.substring(at + key.length() + 2, Math.min(colon, at + key.length() + 8)).isBlank()) {
				at = block.indexOf("\"" + key + "\"", at + 1);
				continue;
			}
			return quoted(block, colon);
		}
		return null;
	}

	/** {@code "key=value"} — one entry of the evidence array. */
	private static String embeddedValue(String block, String key) {
		int at = block.indexOf("\"" + key + "=");
		if (at < 0) return null;
		int open = at + key.length() + 1;   // on the '='
		StringBuilder value = new StringBuilder();
		for (int i = open + 1; i < block.length(); i++) {
			char c = block.charAt(i);
			if (c == '\\') { value.append(block.charAt(++i)); continue; }
			if (c == '"') break;
			value.append(c);
		}
		return value.length() == 0 ? null : value.toString();
	}

	/** The quoted string whose opening quote is the first one after {@code from}. */
	private static String quoted(String block, int from) {
		int open = block.indexOf('"', from + 1);
		if (open < 0) return null;
		StringBuilder value = new StringBuilder();
		for (int i = open + 1; i < block.length(); i++) {
			char c = block.charAt(i);
			if (c == '\\') { value.append(block.charAt(++i)); continue; }
			if (c == '"') break;
			value.append(c);
		}
		return value.toString();
	}
}
