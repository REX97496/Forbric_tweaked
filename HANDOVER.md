# Forbric — handover after 39 working rounds

This file is the durable record of what was done on this repository, what was **measured** rather than
assumed, what is deliberately left undone, and what a person would need to unblock in order to finish it.
Every number here was produced by a command, not recalled.

---

## 1. Where the repository stands

| Fact | Value | How it was produced |
|---|---|---|
| Tests | **5493 tests, 2141 skipped, 60 failed** | `gradlew.bat --no-daemon test --rerun-tasks` |
| Real failures | **0** — all 60 are one platform limit | every failure's suppressed exception is the temp-dir one |
| Session start baseline | 5465 tests, 2132 skipped, **181 failed** | first full run of the session |
| Deliverable kernel jar | `forbric-kernel-0.1.0-SNAPSHOT.jar`, 4,551,045 bytes | `gradlew jar mergeToolsJar -x test` |
| Deliverable merge-tools jar | `forbric-merge-tools-0.1.0-SNAPSHOT.jar`, 413,773 bytes | same |
| First-generation rollback | `.firstgen-backup/` (installer, 8 scripts, tools, 2 withdrawn files) | gitignored, present |

The **60** are the *same* tests that pass on the ubuntu-only CI. They are a Windows platform limit, and
section 4 records the mechanism as measured, including three explanations that were disproved.

---

## 2. What was completed

### ① Silent failures that swallowed real data loss — loud
**14 code sites** converted, each verified by a full suite run. They are not grep hits: every one was read
in context and judged, and the 8 *empty* `catch` bodies in `forbric-kernel/src/main` were each audited and
six of them recorded as deliberate (with the reason written in place).

Two are worth naming because they were real defects, not style:

* `ContendedCallSites.codeRoot` swallowed every exception and returned `null`, so a mixin's owning jar
  became unattributable — and the caller saw only a `NullPointerException` at its own line. Its fallback
  also handed a **percent-encoded** URL path to `Path.of`, which names no file for any mod jar containing
  brackets, spaces or non-ASCII (`[补丁] cloak patch+1.0.jar` is the shape). Fixed by decoding `%XX` only —
  **not** `URLDecoder`, which turns a literal `+` into a space.
* `DependencyDialog.launcherProblem` asked `Files.isExecutable`, which on Windows answers from readability,
  so a text file named `java` was judged runnable and the fork then failed with `CreateProcess error=5`. Its
  own contract says the whole point is to answer *before* the fork. Now a launcher is recognised by its
  `MZ` header on Windows.

Plus **6 assembly points hardened** so a table cannot shrink silently (section 3).

### ② First-generation residue — removed
Dead residue deleted, 5000 lines of tooling moved into the kernel, `forbric-loader` now delegates to it,
and the installer points at the kernel. Both jars build. Nothing was deleted until the build and test
baseline was shown intact, and everything removed is in `.firstgen-backup/`.

### ④ Decoupling, tool migration, Fabric API, coverage
* `forbric-merge-tools-0.1.0-SNAPSHOT.jar` is produced **by the kernel** now, not by `forbric-loader`.
* Fabric API is given its own diagnosis with an actionable sentence ("Install Fabric API in mods/") and a
  test; its absence is no longer conflated with a mod's dangling reference.
* Merge coverage is **pinned** (section 3): 1006 hook losses, 314 of them in data generators (31%), 692
  reachable by a player across 400 classes, 1001 of the 1006 lost by MinecraftForge.

### ⑤ Startup — measured, then halved by fusion
Four separate per-class passes over the same jars and the same class entries are now **one traversal**.
Measured with the candidates interleaved in a single loop, so drift cannot favour either: two of the four
went from 204.6 ms to 120.0 ms. Each audit keeps its own enable switch and its own unreadable-jar
behaviour, so fusing cannot enable one or silence another. **No boot step moved** — the fusion sits where
the first step did and ends where the last did.

A cached-bytes scheme was measured first and **rejected**: the "bounded" cache that looked good was in fact
holding everything (the set has only 4067 classes), and every genuinely bounded size came out level with or
worse than not caching at all (−5% to −86%).

---

## 3. The numbers that cannot now drift

Two families of tests exist for one purpose: to make "how much is still broken" impossible to change by
accident. Each fails and **names the thing** when it moves.

| Pinned value | Test |
|---|---|
| 1006 hook losses / 314 datagen / 692 runtime / 400 owners / 1001 Forge | `MergeCoverageCensusTest` |
| 10 dead events, each with its consequence sentence | `DeadEventBacklogTest` |
| 7 partially posted events, each with its "N of M paths" fraction | `PartialPostingBacklogTest` |
| 5 repaired events, each naming the injector that justifies it | `RepairedEventBacklogTest` |
| 102 declared bridges / 59 audit rows and the relationship between them | `BridgeInventoryRelationshipTest` |
| 6 fabric-api loss rows, unique in every field a report shows | `FabricApiLossTableTest` |

**Assembly guards.** `DeadEventAudit`'s two tables were built with `Map.put`, so a repeated event name
silently dropped the earlier row **and its consequence sentence**. Both now go through `putOnce`, which
throws at class initialisation and names the event. `FabricApiModuleLossAudit.LOSSES` is a `List.of` whose
`Loss` records compare by value — two identical rows would merge their users into one report line — so the
visible fields are asserted unique instead.

**A gap recorded as a failing assertion, not as prose.** `DeadEventAudit` says in its own comment that
partial deadness "has no row shape here and is a real gap". `HookCallSiteCensusTest` now asserts both
halves: the census *does* know which poster is gone under a live event, and nothing the audit reads can
carry that. Whoever closes the gap makes that line fail and must say what changed.

---

## 4. The 60 failures — final characterisation

They are **not** a kernel defect and **not** a `@TempDir` defect. The mechanism, measured:

An `@AfterEach` hook was instrumented to report what the OS says while retrying the delete 8 times with
`System.gc()` and a 25 ms sleep:

```
[probe] junit…574 deleted by the hook on attempt 0      <- a test that opens no jar: JUnit would succeed
[probe] junit…072 deleted by the hook on attempt 0      <- likewise
[probe] junit…280 STILL THERE after 8 attempts; last: java.nio.file.FileSystemException:
        C:\Users\…\junit12766535657225051280\renamed-platform.jar:
        另一个程序正在使用此文件，进程无法访问。      ("another program is using this file")
```

So Windows names the file: the jar a `ForbricClassLoader` opened. **Closing the loader does not release it** —
the hook runs after the test method returns, so the `try-with-resources` had already closed it — because the
`JarFile` inside a closed `URLClassLoader` is released only when it is collected.

Three explanations were tested and disproved along the way, all recorded in
`forbric-kernel/src/test/resources/junit-platform.properties` so nobody pays for them again:

1. "a test that keeps a loader alive holds the jar" — the failing test already closed it;
2. "a test that loads a class is the one that fails" — the failure is a test that loads **no** class, and the
   test that does load one **passes**;
3. "the cleanup mechanism is broken" — a probe with a shutdown hook found such a directory already gone.

**Why it is left undone:** fixing it needs the loader's `JarFile` to be collected, which no per-test edit can
guarantee. A change that only *sometimes* removes the failure would trade a reported platform limit for an
intermittent one. **Do not** set `cleanup.mode.default=NEVER`: that "passes" by never deleting anything,
leaving a directory per `@TempDir` test (841 of them once accumulated) and hiding the cleanup path instead
of reporting it.

---

## 5. What is undone, and exactly what would unblock it

Neither of these is difficulty. Both are missing input, and both were asked for in **17 consecutive rounds**
without an answer.

### A. Real event-loss verification, merge-grammar widening, bridge implementation
All three need the **merged base jar** — vanilla plus the Forge and NeoForge patches composed. It is produced
by `tools/dev.py prepare`.

**Authorised and RUN on this machine. The merge itself fails, and that is the finding.** Recorded here in
full because it changes what the remaining work is.

What `prepare --no-assets` completed (592 MB, 114 files under `forbric-kernel/.dev/`):

| Artifact | Result |
|---|---|
| vanilla `26.2.jar` (client) | built, 39.2 MB |
| vanilla `server.jar` | built, 60.9 MB |
| `patched-mc-forge-26.2.jar` | **built, 33 MB** |
| `patched-mc-neoforge-26.2.jar` | **built, 35.3 MB** |
| `forge-runtime.jar` / `neoforge-runtime.jar` | built (3 MB / 7 MB) |
| `merged-base/patched-mc-merged-26.2.jar` | **NOT PRODUCED — the merge aborts** |

The failure, reproduced twice, is deterministic:

```
[merge] reading jars
[merge]   vanilla=10952  forge-patched=10963  neo-patched=10964 classes
Exception in thread "main" java.lang.IllegalStateException: Incompatible initializer dependency on
  net/minecraft/world/level/chunk/ChunkGenerator#featuresPerStep:
  (Lnet/minecraft/world/level/biome/BiomeSource;Ljava/util/function/Function;)V
```

`FieldContractReconciler.java:14` throws it, and **the call site — `MergedBaseBuilder.java:1057` — has no
try/catch**, so the exception ends the merge. Without this jar, nothing downstream can be verified: no real
event-loss list, no decision on the three weave tests, no proof for any grammar widening. **So this is the
first thing to fix, and it is a merge defect, not a missing input.**

Two things about it worth knowing before digging in:

* The class is **not** unknown. `MergedBaseBuilder.java:1326` documents that `ChunkGenerator.featuresPerStep`
  is retyped per ecosystem, `ForgeClearableLazyInjector` exists specifically to recover Forge's
  `ClearableLazy` semantics on the merged game, `KernelBoot.java:923` serialises a read of it, and
  `AccessCensusTest` / `RestoredAccessTransformerTest` / `AccessCensusGateM9ContractTest` all touch it. The
  design plainly intends this to reconcile, so the failure reads as a regression or an environment
  difference rather than an impossible case.
* **`FieldContractReconciler` has no test of its own** — a grep for it across `src/test` returns nothing. It
  is load-bearing (it can abort the build) and unexercised, which is the same shape as the other defects
  found this session.

The next step is to instrument `sameConstruction` — it returns `false` through six different early returns
and the message says only "Incompatible", so which check failed is currently unknown. That is the same
"a refusal that does not say why" pattern as the rest of this session.

### Environment notes needed to get this far
`prepare` requires **JDK 25+** (`jdk-25.0.2` here), not the JDK 21 the kernel compiles with. The JVM's
`HttpClient` **does not read proxy environment variables** — `maven.neoforged.net` resolved and was reachable
from PowerShell (HTTP 200, both directly and through the proxy) while every JVM attempt timed out after 20 s.
The proxy has to be given as system properties, and `dev.py`'s `--jvm` flag does **not** reach the nested
DevPrepare JVM measured here; `JAVA_TOOL_OPTIONS='-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897 …'`
does. With that, the same request returned HTTP 200 in 831 ms.

My earlier note calling this "a multi-gigabyte download" was wrong: `--no-assets` skips the ~1 GB of assets
(`dev.py:552`) and the whole run came to 592 MB.

**Why widening was not attempted blind.** `AdditiveMethodMerger.Site` computes `start = index -
arguments.length`, i.e. it assumes **one argument is one instruction**. Supporting arguments computed by a
short instruction sequence means changing the argument-slot model, the branch-target check and the handler
coverage together — and handling a branch *inside* the argument computation, which is the common shape
(`a ? b : c`). A wrong composition **silently produces wrong behaviour**, which is worse than a refusal. No
widening was done without a real jar to prove it on.

### B. The Bilibili source
The task asks for the problems named in a specific Bilibili channel's videos. **No video content was ever
obtained.** Three independent routes were tried and all three are shut:

| Route | Result |
|---|---|
| Channel page `space.bilibili.com/3546717207137211/video` | HTTP 200, body is `验证码_哔哩哔哩` — the anti-bot CAPTCHA interstitial |
| `api.bilibili.com/x/space/wbi/arc/search?mid=…` | HTTP 412 `{"code":-412,"message":"request was banned"}` |
| `api.bilibili.com/x/space/arc/search?mid=…` | HTTP 412, identical |

The API endpoints now require a WBI signature computed from values the site serves only to a real browser
session, so there is no fetch this session can perform. `web_search` returned nothing about the channel, and
there is no transcript tool here.

**That part of the objective is not done and could not be done.** Any transcript, subtitle file, or even a
pasted list of the bugs the videos describe would unblock it immediately — the fixes themselves need no
special access, only the list of what to fix.

Note for whoever supplies it: the channel id above is the one given in the task. If the videos have been
mirrored elsewhere (a forum post, a gist, a re-upload), that route has not been tried and might simply work.

---

## 6. How to re-verify everything

```
# the full baseline (about 12 minutes)
cd forbric-kernel
gradlew.bat --no-daemon test --rerun-tasks

# the deliverables
gradlew.bat --no-daemon jar mergeToolsJar -x test

# the merged-base coverage numbers, printed by the test that pins them
gradlew.bat --no-daemon test --tests net.forbric.tools.MergeCoverageCensusTest --rerun-tasks
#   [census] hook losses=1006 datagen=314 (31%) runtime=692 over 400 owners
#   [census] by lost family: forge=1001 neo=5

# the startup measurement, which is opt-in because it runs ten passes over the jar set
gradlew.bat --no-daemon test --tests net.forbric.kernel.boot.ZzBootScanTimingTest --rerun-tasks \
    -Pforbric.requireFixtures=opt-in
```

Environment notes that cost time to find, recorded so they cost no one else any:
`JAVA_HOME` must be a JDK 21 here; `GRADLE_USER_HOME` was pointed inside the workspace; and on Windows a
build must be invoked as `cmd /c "gradlew.bat …"` — PowerShell's batch shim corrupts arguments such as
`-Pforbric.skipBaseline=ci-unstaged`.
