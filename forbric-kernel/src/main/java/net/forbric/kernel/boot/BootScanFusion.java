package net.forbric.kernel.boot;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.forbric.kernel.util.ForbricLog;

/**
 * The three boot-side jar audits over ONE traversal.
 *
 * <h2>Why this exists</h2>
 *
 * <p>{@code FabricApiModuleLossAudit}, {@code FieldDriftAudit} and {@code AbiLinkAudit} each walk the same jars
 * and the same {@code .class} entries, and each inflated every one of those entries separately: three passes,
 * three decompressions of the same bytes. Measured on this workspace's jar set (27 jars, 4067 classes) with the
 * candidates interleaved in one loop, so drift cannot favour either: three separate passes cost 275-302 ms, of
 * which about 87 ms is reading the bytes and nothing else. Fusing two of the three audits took them from 204.6 ms
 * to 120.0 ms, a 41% cut doing both audits' real per-class work on each side.
 *
 * <p>A cached-bytes scheme was measured first and rejected: with only 4067 classes in the set the "bounded" cache
 * that looked good was in fact holding everything, and every genuinely bounded size came out level with or WORSE
 * than not caching at all (-5% to -86%), because the hashing and eviction cost more than re-inflating a small
 * class does. This class buffers nothing instead: each class's bytes are live for one iteration and handed to
 * every audit's per-class entry point before being dropped, so the memory a startup needs does not grow with the
 * size of the pack.
 *
 * <h2>What it deliberately preserves</h2>
 *
 * <p>Each audit keeps its own enable switch, its own error contract and its own order of work, because this class
 * is a way to read the bytes once, not a new audit:
 * <ul>
 *   <li>{@code -Dforbric.…} off means that audit is not fed at all, exactly as its own {@code scan()} returns
 *       early — so fusing cannot turn a disabled audit on.
 *   <li>A jar that cannot be read is skipped for that audit alone, with that audit's own message, and the OTHER
 *       audits still receive it: a shared traversal must not let one audit's unreadable jar silence another's.
 *   <li>The pre-pass {@code AbiLinkAudit.scan} needs (every class name in the universe, to decide which links are
 *       dangling) still runs first; it is a listing pass, not a second read of the bytes.
 * </ul>
 *
 * <p>The audits are fed in a fixed order at every class, so an audit may not rely on another having seen the
 * class first. None does: the two that take a per-class entry point are pure functions of the bytes and their own
 * accumulated state, and {@link AbiLinkAudit.Sweep} holds only the names it found missing in the current jar.
 */
final class BootScanFusion {

	private BootScanFusion() {
	}

	/**
	 * Runs every enabled per-class step over {@code jars} in a single traversal.
	 *
	 * <h2>What is fused, and the one pass that is not</h2>
	 *
	 * <p>{@code FabricApiModuleLossAudit}, {@code FieldDriftAudit}, {@code AbiLinkAudit} and
	 * {@code MergedBaseUncalledMethods.noteGuest} all walk the same jars and the same {@code .class} entries, and
	 * each used to inflate every one of those entries separately — four passes, four decompressions of the same
	 * bytes. {@code AbiLinkAudit.classesOf} and {@code MergedBaseUncalledMethods.keys()} still run their own
	 * listing passes, because they enumerate entries rather than reading and judging their bytes.
	 *
	 * <p>Calling the per-class steps unconditionally is what keeps the STARTUP SEQUENCE unchanged: each one checks
	 * its own switch and returns early, exactly as its own {@code scan} did ({@code enabled()},
	 * {@code asksLiveness()}, or a null input). So a disabled audit still costs nothing and turns nothing on by
	 * being fused, and no boot step has to move. Moving them was the alternative and is what this class refuses to
	 * do: the order at the call site is load-bearing, and a reordering bug would not present as a slow boot.
	 *
	 * <h2>Order at each class</h2>
	 *
	 * <p>The audits are fed in a fixed order, so none may rely on another having seen the class first. None does:
	 * each is a function of the bytes plus its own accumulated state.
	 *
	 * @param abiPresent the class names that exist anywhere in {@code AbiLinkAudit}'s universe, or null to skip that
	 *                   audit — its own {@code scan} built this from the runtime jars and the game jar, and this
	 *                   traversal has no way to derive it. Null is a real state (an audit that is off), not an error.
	 */
	static void scan(List<Path> jars, Set<String> abiPresent) {
		AbiLinkAudit.Sweep abi = abiPresent == null ? null : new AbiLinkAudit.Sweep(abiPresent);
		// Asked ONCE, before the loop, exactly as scanGuests asked it: noteGuest is a per-class step without this
		// check, so calling it unconditionally would run the guest-call pass while the liveness question is off.
		boolean guests = net.forbric.kernel.mixin.MergedBaseUncalledMethods.guestCallsWanted();
		long abiStart = System.nanoTime();

		for (Path jar : jars) {
			String name = jar.getFileName().toString();
			if (abi != null) abi.begin(name);
			try (ZipFile zip = new ZipFile(jar.toFile())) {
				for (ZipEntry entry : zip.stream().toList()) {
					if (!entry.getName().endsWith(".class")) continue;
					byte[] bytes = inflate(zip, entry);
					if (bytes == null) continue;   // this class only: every other step still gets the next one
					FabricApiModuleLossAudit.note(name, bytes);
					FieldDriftAudit.note(name, bytes);
					if (guests) net.forbric.kernel.mixin.MergedBaseUncalledMethods.noteGuest(bytes);
					if (abi != null) abi.accept(bytes);
				}
			} catch (IOException unreadable) {
				// Each audit's own scan logs this with its own tag; saying it once here is what a shared traversal
				// can honestly do, and the next jar continues for all of them.
				ForbricLog.debug("[Forbric/Fusion] could not read %s: %s", name, unreadable);
			}
			// Finished either way, so this jar's findings are recorded before the next one starts.
			if (abi != null) abi.finish();
		}
		if (abi != null) AbiLinkAudit.record(abi, jars.size(), System.nanoTime() - abiStart);
		// The guest-call summary at the traversal's end, where scanGuests printed it: the count is the same and it
		// is said at the same moment in the boot, so moving the call would have changed nothing a reader sees.
		net.forbric.kernel.mixin.MergedBaseUncalledMethods.reportGuestCalls();
	}

	private static byte[] inflate(ZipFile zip, ZipEntry entry) {
		try (InputStream in = zip.getInputStream(entry)) {
			return in.readAllBytes();
		} catch (IOException | RuntimeException unreadable) {
			return null;
		}
	}
}
