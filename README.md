# Forbric_tweaked

English | [简体中文](README.zh-CN.md)

A fork of [Forbric](https://github.com/Ray-T-r/Minecraft-Forbric-mod-loader), based on **Forbric 0.3.0**
· Minecraft 26.2.

Forbric_tweaked runs Fabric mods, Forge mods and NeoForge mods in **one** Minecraft instance — the same
thing Forbric does. **This README is only about what is different here.** For what the loader is and how
it works inside, read the upstream [introduction.md](introduction.md) and
[forbric-kernel/README.md](forbric-kernel/README.md); none of that has changed.

The changes below fall into two groups: things that were **broken and now work**, and things that were
**failing silently and now say so**.

---

## 1. The merged base could not be built at all

This was the big one. `tools/dev.py prepare` downloaded everything and then died at the merge step:

```
[merge] reading jars
[merge]   vanilla=10952  forge-patched=10963  neo-patched=10964 classes
Exception in thread "main" java.lang.IllegalStateException: Incompatible initializer dependency on
  net/minecraft/world/level/chunk/ChunkGenerator#featuresPerStep
```

`patched-mc-forge-26.2.jar` and `patched-mc-neoforge-26.2.jar` were both produced; the jar that combines
them was not. Since the merged base is what the game runs, **nothing downstream could be tested or
verified** — no mod compatibility work, no event audit, nothing.

**Why.** `FieldContractReconciler` tries to reconcile a private field that the two ecosystems retyped
differently (`ChunkGenerator.featuresPerStep`), and refuses unless it can prove the constructors compute it
the same way. The proof failed — but it failed for a reason no one could see from the message, because that
method had six separate ways to say "no" and the message named none of them.

Chasing it through the real bytecode:

- the failing check was the last one: the lambda `lambda$new$1` "has no non-null return proof";
- that lambda returns `FeatureSorter.buildFeaturesPerStep(...)`, whose `List` comes from
  `ImmutableList$Builder.build()`;
- proving that non-null requires reading `ImmutableList.builder()` — **Guava**;
- the proof engine asks a class graph for it and gets `null`, because **Guava was not on disk**.

**The root cause: `prepare` downloads no Minecraft libraries at all.** `MojangDownloader` fetches only
`versions/<v>/<v>.json` and `<v>.jar` and leaves `libraries/` to a launcher, while the merge is handed
`mcDir/libraries` and reads it for the classes it needs to prove things about. On a machine where that
directory is empty, the proof engine cannot tell **"this code is unsafe"** from **"the library that would
prove it safe was never downloaded"** — so it blamed the bytecode and killed the build.

Two things were done about it:

- **The refusal now says which check failed.** `sameConstruction` returns the reason instead of a bare
  `false`, and the message reads, for example:
  `… ChunkGenerator#featuresPerStep: (…)V — the lambda body lambda$new$1(…)Ljava/util/List; has no
  non-null return proof on net/minecraft/world/level/chunk/ChunkGenerator`.
  "The two patches disagree" and "this checker does not model what one of them does" are now different
  messages, because they are different problems.
- **`libraries/` has to be populated for the merge to run.** With
  `guava-33.6.0-jre.jar` and `failureaccess-1.0.3.jar` in `forbric-kernel/.dev/minecraft/libraries`
  (SHA-1 checked against the version JSON), `prepare` completes: the merged base is written, **88 MB**,
  with the link check reporting 7 dangling references, all 7 already known, 0 new.

## 2. What the merge loses is worse than this repository claimed

Now that the merge can actually run, the coverage figure could be measured instead of inherited. The
report committed under `forbric-loader/run/merged-base/merge-conflicts.txt` is a snapshot, and it is
optimistic:

| | Committed snapshot | Actually produced now |
|---|---|---|
| Hook losses, total | 1006 | **1144** |
| …in data generators (unreachable in a player's game) | 314 | 330 |
| **…reachable by a player** | **692** | **814** |
| Distinct classes carrying runtime losses | 400 | — |

**The runtime loss is 122 hooks — 17.6% — larger than the committed number.** Anything that decided
priority from that snapshot was working from a figure that was too kind.

The committed snapshot is also the only thing the pinned coverage test read, which means **the test that
exists to stop the number drifting was itself pinning a stale one.** Re-pinning it against a freshly
produced report is the next step.

## 3. Windows: things that did not run, or ran wrongly

- **`gradlew` could not be executed.** Two development scripts invoked the wrapper directly. On Windows
  the wrapper is a `.bat`, which cannot be spawned as a program, and running it through the shell instead
  produced `WinError 193`. Builds driven from those scripts did not report their result at all.
- **Gradle's output was decoded as UTF-8 under a Chinese locale.** Gradle writes GBK there, so reading its
  output raised `UnicodeDecodeError` — which looked like a broken script rather than a build that had
  failed, and meant a failed build was not actually being checked.
- **A launcher was judged runnable by the wrong test.** `DependencyDialog` asked `Files.isExecutable`,
  which on Windows answers from readability — so a text file named `java` passed, and the fork then failed
  with `CreateProcess error=5`. Its own contract says the point is to answer *before* forking, so on
  Windows a launcher is now recognised by its `MZ` header.
- **Any mod jar with brackets, spaces or non-ASCII in its name became unattributable.**
  `ContendedCallSites.codeRoot` swallowed every exception and returned `null`, and its fallback handed a
  **percent-encoded** URL path to `Path.of`, which names no file. A jar called
  `[补丁] cloak patch+1.0.jar` therefore resolved to a path containing `%5B补丁%5D%20…`, and a mixin's
  owning jar could not be determined. Decoding `%XX` fixes it — deliberately *not* `URLDecoder`, which
  turns a literal `+` into a space and would break `patch+1.0.jar`.

## 4. Silent failures made loud

Fourteen places that swallowed a real failure now report it. Two examples of what that was hiding:

- **`DeadEventAudit`'s two tables were built with `Map.put`.** A repeated event name **silently
  overwrote** the earlier row, dropping it together with the sentence explaining what a player loses. The
  rows are hand-written internal names of which two differ only by a nested class. They now go through
  `putOnce`, which refuses at startup and names the event.
- **`FieldContractReconciler`'s refusal** described above — one message for six different causes.

The rest were the same shape at smaller scale. Where a silent failure turned out to be *deliberate* it was
left alone and the reason written down instead, so that "this was decided" and "nobody noticed" stop
looking alike.

## 5. Two things got faster, and one got measured

- **The boot scan inflated every class four times.** Four separate passes walked the same jars and the
  same class entries, each decompressing them for itself. They now share one traversal, holding one
  class's bytes at a time. Measured with the candidates interleaved in a single loop, so that drift cannot
  favour either: two of the four went from **204.6 ms to 120.0 ms**. No boot step moved — the fusion sits
  where the first of them did and ends where the last did — and each pass keeps its own switch, so fusing
  cannot turn one on or let one pass's unreadable jar silence another.
- A cached-bytes scheme was measured first and **rejected**: the cache that looked good was in fact
  holding everything, and every genuinely bounded size came out level with or worse than not caching at
  all (−5% to −86%).
- **The Windows test suite's real failures went from 181 to 0.** The 60 that remain are one platform
  limit, not a kernel defect: they are the same tests that pass on the Ubuntu CI, every assertion in them
  passes, and only the temporary-directory cleanup fails. Windows names the reason itself —
  `java.nio.file.FileSystemException: …\renamed-platform.jar: another program is using this file` — and
  the handle belongs to a **closed** `URLClassLoader`: closing it does not release the jar, and the retries
  and `System.gc()` of an instrumented cleanup hook did not get it released either. So there is no
  per-test edit that reliably fixes it, and it is reported rather than papered over.

---

## Installing

**This section is unchanged from Forbric** and still refers to the real file and folder names, which have
not been renamed.

### Before you start

- **A launcher that starts versions from your `.minecraft/versions` folder.** Tested with **PCL2** on
  Windows. HMCL reads the same files and should work. The official Minecraft Launcher has not been tested.
  Prism Launcher and MultiMC keep their own instances and will not see this.
- **Java.** If you can already play Minecraft, you have it.
- **An internet connection**, and about 730 MB of free disk while the installer works (about 190 MB kept
  afterwards).

You do **not** need to install Minecraft 26.2 first, and you do **not** need Fabric, Forge or NeoForge:
the installer downloads and builds what it needs. Your mods still need their own prerequisites as usual,
for example Fabric API for most Fabric mods.

### Install

Install exactly as Forbric documents it: run `forbric-kernel-installer-0.3.0.jar`, which writes a
`26.2-forbric` version profile into `.minecraft/versions/`, then start that version from your launcher.
The installer, the folder names and the launcher steps are identical here.

### Where the mods go

- If your launcher keeps each version separate (version isolation; PCL2 and HMCL can do this):
  `.minecraft/versions/26.2-forbric/mods/`
- Otherwise the shared `.minecraft/mods/`. Every version without its own folder uses that one, so the loader
  will also try to load whatever is already in it.

The installer names both when it finishes. Unsure which yours uses? Start the game once: a folder called
`.forbric-kernel` appears next to the correct `mods` folder.

**Put one build of each mod in the folder.** Many mods ship as a Fabric, a Forge and a NeoForge build; if you
add more than one, only one gets loaded. The first time that happens the loader writes its choice to
`forbric-mods.txt` next to your `mods` folder, where you can switch to another build. The same goes for a
library several mods need — one build is usually enough, since a mod can normally use another loader's build
of its prerequisite.

Your launcher may label `26.2-forbric` a Fabric version. That is on purpose: a launcher shows one loader per
version, so this one tells it Fabric. It still loads all three kinds from `mods`.

### Did it work?

Open the pause menu: there is a button with **three overlapping squares**, tooltipped *Mods (Forbric)*. It
lists every installed mod with the kind it is; select one and press **Config** (or double-click) to open that
mod's own settings. A Fabric mod gets a Config button there only when Mod Menu is also installed.

### If something goes wrong

| What you see | What to do |
| --- | --- |
| **A window says a mod is missing something it needs** | It names the mod and what to install. Install it, or press **Launch anyway**. |
| **A window says required mod features are unavailable** | Some part of a mod could not start. Continue playing, or quit and remove that mod. |
| **The game crashes** | Look in `.forbric-kernel` next to your `mods` folder. `crash-analysis.txt` there names the mods most likely to blame; the full report is in `crash-reports/`. |
| **A mod is installed but does nothing** | The mods list marks a mod that did not finish loading. The same list is in `load-report.txt`, in `.forbric-kernel`. Often the mod is built for another Minecraft version, or you have two builds of it. |
| **A dedicated server will not start**, and the log blames the compatibility policy | A server has no screen to ask you on, so it stops. Remove the mod it names, or add `-Dforbric.compatibilityPolicy=continue` to the start command to run anyway. |
| **The install seems stuck** | Usually a proxy or VPN between you and Mojang. Run the `--doctor` check from the end of *Install*, then retry with it off. |

Logs and state live in `.minecraft/versions/26.2-forbric/`; the installer's own work is under
`.minecraft/.forbric-build/` and `.minecraft/libraries/net/forbric/`.

Installing alongside everything else means your other setups are untouched, and uninstalling is deleting
the `26.2-forbric` folder.

---

## For mod developers

**Your mod does not need to change**, and that is upstream Forbric's design, not something this fork
alters. You call the real Fabric API, MinecraftForge or NeoForge classes; what is re-implemented is the
loader, and the game is one merged jar built at install time.

Two things from this fork's work are worth knowing:

- **The merged base loses more than the committed conflict report says** (§2). If you are deciding whether
  a Forge hook will fire on the merged game, produce a fresh report rather than reading the snapshot in the
  repository.
- **A `prepare` run now needs `libraries/` to be populated** (§1) before the merge can complete. `prepare`
  does not do it for you; see the fix note above.

### Building and running from source

You need `git` and a JDK 21 or newer for the kernel's own Gradle build (boot-side compilation does not
need the Fabric substrate):

```bash
git clone https://github.com/REX97496/Forbric_tweaked.git
cd Forbric_tweaked
cd forbric-kernel && ./gradlew build
```

**A green build on a fresh clone does not mean the game can launch.** Without locally staged game jars the
runtime source set and transfer tests are skipped.

To run the current source in a development game, install **JDK 25+ and Python 3.9+**, then from the
repository root (Windows: use `py` instead of `python3`):

```bash
python3 tools/dev.py prepare --no-assets    # download and build the game artifacts
python3 tools/dev.py client                 # build and launch
```

On a machine where `forbric-kernel/.dev/minecraft/libraries` is empty, `prepare` will stop during the merge
with *Incompatible initializer dependency* — that is §1, and the backend is a missing Minecraft library
rather than a real incompatibility. Populate `libraries/` from the version JSON's
`downloads.artifact.url` entries and re-run.

If the JVM cannot reach `maven.neoforged.net`, note that its `HttpClient` **does not read proxy
environment variables**; the proxy has to be given as system properties, and `dev.py`'s `--jvm` does not
reach the nested build JVM. `JAVA_TOOL_OPTIONS` does:

```bash
JAVA_TOOL_OPTIONS='-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897' python3 tools/dev.py prepare --no-assets
```

Downloads and assembled jars stay under the ignored `forbric-kernel/.dev/`.

---

## What this fork does not fix

- **The merged-base event surface.** Hooks whose call site the merge lost still do not fire unless
  something re-emits them. Ten MinecraftForge events are known dead with no bridge, seven more are posted
  on only some of the paths that used to post them, and the real runtime loss figure is §2's 814 — all
  recorded and asserted in the kernel's own tables, not fixed here.
- **Merge-grammar widening.** The merger composes three method shapes and refuses the rest. Widening it is
  the lever for applying more of what mods changed, and it was deliberately **not** attempted: the compiler
  assumes one argument is one instruction, a wrong composition produces wrong behaviour silently rather
  than a refusal, and there was no staged base to prove any change against. That is the main thing left to
  do, and the coverage measurement above is what it should be steered by.

## Licence

Apache-2.0 — see [LICENSE](LICENSE) and [NOTICE](NOTICE). This is a fork of
[Ray-T-r/Minecraft-Forbric-mod-loader](https://github.com/Ray-T-r/Minecraft-Forbric-mod-loader); all
credit for the loader itself is upstream's. The clean-room boundary is documented in
[forbric-loader/CREDITS.md](forbric-loader/CREDITS.md) and
[forbric-loader/MAPPINGS.md](forbric-loader/MAPPINGS.md).

Not affiliated with Mojang, FabricMC, MinecraftForge or NeoForged.
