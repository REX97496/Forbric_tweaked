# `run/` — the pipeline that produces a runnable Forbric instance

For current kernel development, start with [the kernel development guide](../../forbric-kernel/run/README.md).
This directory still supplies the shared merge tools and staged artifacts, but its `launch-*.sh` scripts
use the first-generation loader boot path. The current player installer is `../../forbric-kernel-installer/`.

Forbric's loader is Apache-2.0 source. What it runs on top of is not: the Minecraft jar belongs to Mojang,
and the MinecraftForge and NeoForge runtimes are LGPL. None of that is committed here. This directory holds
the scripts that **fetch and assemble those pieces on your machine**, plus the launchers that put them
together, plus the sources of the small test mods used to check that the result actually works.

Nothing in this directory is committed except the scripts, those mod sources, and one report. Everything the
scripts produce — patched game jars, runtime carriers, game instances, logs, worlds — is deliberately
ignored (see `../.gitignore`).

## Build pipeline

Run in this order. Each writes into `run/` and is idempotent.

| Script | Produces |
|---|---|
| `build-patched-forge.sh` | the traditional-MinecraftForge-patched, Mojmap-named MC 26.2 jar. Forge patches the game with BinaryPatcher + MCPConfig, not NeoForm, so this is the Forge analogue of NFRT. |
| `assemble-minecraftforge-runtime.sh` | `forge-runtime.jar` — the Knot-loaded traditional-Forge runtime, merged from Forge's `-universal` jar and its declared libraries. |
| `assemble-neoforge-runtime.sh` | `neoforge-runtime.jar` — the same for NeoForge. |
| `build-merged-base.sh` | `patched-mc-merged-26.2.jar` — vanilla 26.2 carrying **both** the MinecraftForge and the NeoForge injections in one jar, which is what lets all three ecosystems load in one instance. Compiles and runs `MergedBaseBuilder`. |
| `dedupe-runtime-overlap.sh` | resolves split packages between the two runtime carriers. Both bundle their own copies of third-party classes (maven-artifact, terminalconsoleappender, annotation jars); in one shared JPMS layer that is a split package, and `Configuration.resolve()` rejects it outright. |
| `build-testmods.sh` | compiles the canary mods from `livemod-src/` and `livemod-src-neoforge/` against the runtime-supplied Forge and patched MC. |

`merged-base/merge-conflicts.txt` is the report `build-merged-base.sh` writes: every class where the two
Forge families' injections collided, and which side won. It is the one build output that **is** committed,
because which way each conflict resolved is a design decision worth reviewing rather than re-deriving.

**Read the committed copy as a snapshot, not as the current state of the loader.** Two things widen the gap
between what it says and what a player experiences, and neither is recorded anywhere in the file:

- **It has no date or revision stamp.** Nothing in it or beside it says which merge produced it, so a reader
  cannot tell a month-old copy from yesterday's. (`.provenance.json` is written by the merge scripts into a
  staged tree, not committed here.)
- **It describes the merge, not the kernel.** Every repair the kernel applies afterwards — the game-event
  bridges in `DeadEventAudit.BRIDGED`, the injections in `DeadEventAudit.REPAIRED`, the transform-time fixes
  under `forbric-kernel/src/main/java/net/forbric/kernel/transform/` — is invisible to it. Its rows say a hook
  was lost by the BYTE MERGE, which is only the same thing as "the hook is dead" until a repair covers it.

This is not hypothetical: it is how one reader concluded that 682 runtime hooks are still lost. That figure is
the MERGE's, and the kernel has since covered a large share of those sites (`ForgeDamageSeamsInjector` takes
`LivingEntity.actuallyHurt` and `Player.hurtServer`, `MixinPlayerWorldCallbackAdapter` takes
`LivingEntity.swapHandItems`, and so on). To find out what is *still* lost, read the staged report produced by
a fresh merge, and ask `DeadEventAudit` what is bridged on top of it — not this file.

The tools tests that read this file use it as a PARSER FIXTURE — its rows exercise attribution and census
code. `LostHookAttributionTest.everyHookLostRowOfTheTrackedReportEntersTheDenominator` is the one that reads
it directly, and the transform tests named for specific rows (the seven `left null` field entries, the lost
ancestors) pin those rows to explain what was done about them. They assert its shape, not that its content is
still accurate, so a kernel-side repair does not invalidate them.

**The merge tools are not in this project any more.** `MergedBaseBuilder`, `AdditiveMethodMerger`,
`RuntimeInteropPatcher` and `MergedLinkChecker` (with the classes they use) live in
`../forbric-kernel/src/tools/java`, and `:forbric-kernel:mergeToolsJar` builds and shades their jar. They
produce the kernel's staged game artifacts, so the project that consumes them owns them, and a second copy
here is how the two would eventually disagree about the merge. The scripts above were updated to compile from
that tree, so what you run from this directory is the same source the kernel builds. `forbric-loader` keeps
its `mergeToolsJar` task as a delegating name, because this project's own tools tests and anything calling the
task by name expect it to exist.

## Launchers

Nothing here launches the game any more. The first generation's launchers — `launch-server-merged.sh`,
`launch-client-merged.sh`, `launch-server-minecraftforge.sh`, `launch-client-forge-26.2.sh`,
`launch-server-26.2.sh` and the older `launch-1.21.11.sh` (which was the one path still needing an
`intermediary` mapping and a Fabric profile for LWJGL natives) — were removed together with
`forbric-installer/`. They booted the first generation's own Knot host, which the kernel does not use, and
nothing in the current tree or in CI referenced them.

Use [`tools/dev.py`](../../tools/dev.py) (`python3 tools/dev.py client`, Windows: `py tools/dev.py client`)
or the Gradle tasks `prepareDev` / `runClient` / `runServer`.


## Mod sources

- `bridge-src/`, `bridge-src-neoforge/` — Forbric's own bridge mods. Each opens the Fabric-content window
  inside the genuine Forge-family registration span, so Fabric-registered content lands in the same window
  Forge mods register in.
- `livemod-src/`, `livemod-src-neoforge/` — canaries. They exercise a traditional-Forge `SimpleChannel`, a
  server config that must sync over the socket, gameplay events, and cross-ecosystem mod-presence lookups,
  and log a line per outcome. Built by `build-testmods.sh`.
- `testmod-src/` — minimal Fabric and Forge probe mods, used to check that discovery classifies each one
  correctly and that a remapped Forge class links against the live game.

## Checks

- `verify-substrate-patches.sh` — the eight required `../fabric-loader` patches still match
  `patches/fabric-loader/*.patch`. Compared against the pinned upstream release, so it passes whether you
  left them as working-tree changes or committed them. `../../bootstrap.sh` runs it for you.

The two standing regression gates, `regress-real-mods.sh` and `regress-merged-client.sh`, were removed with
the first generation's launchers: both drove a first-generation launch script, so neither could run once
those were gone. Their coverage now lives in the kernel's own suites — `run/gate-m*.sh` for real server
boots, and the unit/transfer suites for the protocol, known-pack and registry-sync paths.

## Running the pipeline

```bash
cd ..                    # the repository root
./bootstrap.sh           # fetch and patch the fabric-loader substrate
cd forbric-loader
./gradlew jar runtimeJar # the two loader jars
run/build-patched-forge.sh
run/assemble-minecraftforge-runtime.sh
# ... then build and launch the kernel: python3 ../tools/dev.py client
```

These scripts assume a working JDK, `git`, and network access to Mojang's and Forge's Maven repositories.
They are development tools — for the current installer, see `../../forbric-kernel-installer/`.
