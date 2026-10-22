# ShapeFingerprint

Prints `ForgeTransferShapeAudit`'s fingerprint for each audited Forge class in a jar, without running the kernel.

## Why it exists

`ForgeTransferShapeAudit.AUDITED` pins a SHA-256 of seven Forge/NeoForge classes. Those pins are
the gate that lets the transfer bridge trust a class it just transformed: an unrecognised shape is
refused (`deny writes until explicitly reviewed`), which is a safety net rather than a crash, but it
also means a new Minecraft/Forge line silently loses transfer writes until the pins are redone.

Redoing them needs the exact same normalisation the kernel applies — skip debug and frames, strip the
marker method, canonicalise the erased class signature, apply the known non-transfer repair, prune to
the reachable critical set where `ROOTS` covers the class, re-serialise, hash. Any difference in that
chain produces a different number, so a tool that merely *looks* equivalent is worthless.

## The self-check, which is the whole point

Run it against a base whose pins are known to be current and the output must reproduce them exactly:

```
java -cp <classes>:<asm> net.forbric.kernel.transform.FingerprintMain \
     forbric-kernel/.dev-test/minecraft/.forbric-build/out/forge-runtime.jar
```

Measured 2026-10-20: 7 of 7 audited classes reproduced the values pinned in the source, 0 mismatches.
Only after that did the 1.21.1 run mean anything.

## What it found on 1.21.1

```
class                                         26.2        1.21.1
ItemStackHandler                              be15e7bb…   572fb811…
ItemHandlerHelper                             ad43a680…   b8bfaa89…
CapabilityProvider                            caea2630…   7ec42620…
CapabilityProvider$ItemStacks                 a8819ec2…   (absent)
FluidTank                                     02e10477…   f7ad8934…
EnergyStorage                                 311f4f17…   21b61e64…
FluidStack                                    87cd2931…   59300c22…
```

All six present classes differ, as expected. The seventh is more interesting than a mismatch: Forge
1.21.1's `CapabilityProvider` has **two** inner classes where 26.2 has **seven** — `$Entities`,
`$BlockEntities`, `$Levels`, `$ItemStacks` and `$AsField$LevelChunks` do not exist. So the audit's
assumption that an ItemStack's capability provider is that inner class does not hold on 1.21.1 at all.
Re-pinning hashes would not fix that; it needs the audit reasoned about per version.

## Limits

- The hashes above are measured, but **nothing here has been run inside a kernel on 1.21.1**, and
  1.21.1 has never been launched. This tool prints numbers; it does not prove the audit is right.
- The `ForbricLog` here is a six-line stub; only `warn` is referenced, and only from `dump()`, which
  the fingerprint path never calls.
