package probe;

import net.fabricmc.api.ModInitializer;

/**
 * A minimal Fabric mod whose only job is to prove that a mod's entrypoint RUNS on a given Minecraft line.
 *
 * <p>It prints one line and does nothing else -- no registries, no mixins, no events. That is deliberate: a probe that
 * touched those would be testing three other things at once and would be as hard to interpret as a real mod.
 *
 * <p>Compile against the kernel jar, which is where net/fabricmc/api/ModInitializer.class lives:
 *
 * <pre>
 *   javac -cp build/libs/forbric-kernel-&lt;version&gt;.jar -d classes src/probe/Probe.java
 *   jar cf forbric-probe.jar fabric.mod.json -C classes probe
 * </pre>
 *
 * <p>Then drop the jar in a server's mods/ and look for the two independent observations in the log:
 * {@code entrypoint keys [main]} (the manifest was parsed) and {@code [STDOUT]: [PROBE] onInitialize RAN} (it ran).
 * Either alone is weaker than both.
 */
public final class Probe implements ModInitializer {
	@Override
	public void onInitialize() {
		System.out.println("[PROBE] onInitialize RAN on this line");
	}
}
