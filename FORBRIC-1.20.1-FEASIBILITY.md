# Forbric 1.20.1：可行性报告与版本矩阵缺口

Status: **结论——不可行，除非作为独立代次重做。** 本文给出实证依据、工作量下界，以及「版本矩阵骨架」真正
需要先补齐什么。所有结论都标了它在仓库里的出处，可以逐条复核。

调查日期 2026-10-10。调查对象是解压出来的这棵树，不是上游 main 的最新提交。

---

## 1. 一句话结论

**1.20.1 不是「加一个分支」，而是「换一条代次」。** 现有内核的每一层——解码器、工件产出工具链、修补器、
合并基底——都是为 **MC 26.2 + NeoForge 26.2** 这一对上游写死的，而 1.20.1 缺少其中两个基础前提：真
NeoForge，以及产出合并基底要用的 NeoFormRuntime。把 2400+ 个 Java 文件对着另一套引导重做，不是本轮能交付
的东西；本文交付的是「要动什么、下限在哪、先补什么」。

---

## 2. 实测：版本钉在哪里

单一真相源是 `forbric-kernel-installer/.../Pins.java`，但**真正决定构建成败的钉选散落在至少六处**：

| 钉选 | 值 | 位置 |
|---|---|---|
| Minecraft | `26.2` | `Pins.java:33`（注释：「the only Minecraft version this generation supports」） |
| MinecraftForge | `26.2-65.0.1` | `Pins.java:36` |
| NeoForge | `26.2.0.88` | `Pins.java:55` |
| NeoFormRuntime | `2.0.18` | `Pins.java:64` |
| NFRT 产出 | `gameJarNoRecomp` | `Pins.java:79` |
| Fabric Loader | `0.19.3` | `forbric-kernel-installer/.../Installer.java:152` |
| 合并基底 jar 名 | `patched-mc-merged-26.2.jar` | `forbric-kernel/build.gradle:35` |
| Fabric API | `0.155.2+26.2` | `forbric-kernel/build.gradle:49,51` |
| 版本 JSON | `versions/26.2/26.2.json` | `forbric-kernel/build.gradle:113,153` |
| 默认版本 | `26.2` | `forbric-kernel-installer/.../InstallerGui.java:48` |
| 编译级别 | Java 21 编译 / 25 运行游戏 | `forbric-kernel/build.gradle:12-18` |

**没有任何抽象层**：没有 `minecraftVersion` 参数，没有 property 覆盖，没有 version module。`build.gradle:35`
的 jar 名字符串、`:49` 的 Fabric API 文件名、`:113` 的版本 JSON 路径都是字面量。**这就是「版本矩阵骨架」
要解决的第一个问题，而它目前完全不存在。**

---

## 3. 为什么 1.20.1 特别难：两个基础前提都不在

### 3.1 1.20.1 没有「真 NeoForge」

- 1.20.1 的 NeoForge 是 **legacyforge 分叉**：包名、事件层级、引导链与 1.20.2+ 的 NeoForge 不同。
- 现有内核的**核心价值**恰好建立在「两个 Forge 家族都能加载、且它们的补丁在同一方法上冲突」之上
  （README「约一千处同点冲突，只有一份保留」）。
- 在 1.20.1 上这个前提变形：NeoForge 1.20.1 ≈ Forge 47.x 的改名版。那约一千处「冲突修补器」要么无事可做，
  要么按错误的假设去修。**不是移植，是重写判据。**

### 3.2 1.20.1 没有 NeoFormRuntime → 合并基底的产出路径不存在

这条是从工件产出器读出来的，不是推测：

- `MergedBaseTool.java:58` → 实际调用 `net.forbric.tools.MergedBaseBuilder`
- `Pins.java:79` → `NFRT_RESULT = "gameJarNoRecomp"`
- `Pins.java:69-71` → 该结果是 NeoForm 的**二进制补丁路径**：`preProcessJar → binaryPatch →
  copyUnpatchedClasses → applyDevTransforms`，直接产出 10,963 个类
- `Pins.java:82-84` → 喂给 NFRT 的是 `neoforge:<ver>:userdev`

**NeoForm / NeoFormRuntime 从 1.20.2 才出现。** 1.20.1 时代产出游戏 jar 的是另一套（ForgeGradle userdev +
MCPConfig/v2 + installertools）。所以 `PatchedMcBuilder`、`NfrtRunner`、`MergedBaseTool` 这三个类在 1.20.1 上
**没有对应的实现路径可走**——它们要重写成一套 1.20.1 的工具链，而不是改几个版本号。

### 3.3 附带：Java 级别与加载器生态

1.20.1 是 Java 17（现内核钉 21 编译 / 25 运行）。Fabric 侧 1.20.1 有 loom 1.17.19 + loader 0.19.3 +
Fabric API `0.92.11+1.20.1` 可用，这一侧没问题；**问题全在 Forge 家族侧。**

---

## 4. 工作量下界（按实测规模，不是估算）

| 面积 | 规模 | 1.20.1 需要的动作 |
|---|---|---|
| `forbric-kernel/src/main/java` | 531 个 java | 逐个核对 26.2 API 引用；补丁点与事件名全部重审 |
| `forbric-kernel/src/runtime/java` | 150 个 java | 游戏侧接线，对着**另一套反混淆名**（1.20.1 用 MCP，不是 Mojmap deobf 直读） |
| `forbric-kernel/src/test/java` | 911 个 java | 近千个「对真实产物字节断言」的测试要重新取证 |
| 合并基底类数 | 30,471 entries（`Pins.java:73`） | 1.20.1 需重新取证一份等价的合并基底 |
| 冲突修补器 | 约 1000 处同点冲突 | 判据在 1.20.1 上变形，需重新设计 |
| 工件产出工具链 | 3 个核心类 + NFRT 依赖 | **需换成 1.20.1 时代的工具链** |
| 版本矩阵 | 目前 0 个抽象 | 需先建，否则每加一个版本都要改 10 处字面量 |

**下限判断**：这不是「分支级」改动，是「代次级」改动。与现有 `forbric-kernel` 共用代码的程度，乐观估计也只有
解码器、Mixin 服务、分类与仲裁（`net.forbric.api` 那一圈）——那些确实与版本无关的部分。

---

## 5. 如果要做，正确的顺序

按依赖排序，每一步都可独立验证：

1. **先抽版本矩阵**（与 1.20.1 无关，对现有 26.2 也有价值）
   - 把 6 处字面量收进一个 `ForbricVersion` 对象：MC / Forge / NeoForge / NFRT / Fabric API / Java 级别。
   - `patched-mc-merged-<mc>.jar`、Fabric API 文件名、版本 JSON 路径全部由它派生。
   - 验证标准：现有 26.2 构建产物**字节不变**（这是可测的，`build.gradle` 已有 provenance/stamp 机制）。
2. **把「合并基底怎么产出」抽象成接口**，为 1.20.1 留第二个实现位（`MergedBaseBuilder` 与未来的
   `LegacyMergedBaseBuilder`）。此步不改行为，只切接口。
3. **写 1.20.1 的工件产出器**（这一块是新代码，不是移植）。
4. **按已有判据逐条重审修补器**：哪些在 1.20.1 上仍然成立、哪些必须关掉、哪些判据本身要改。
5. **重建测试基线**：现有 410KB 的 `ci-unstaged.tsv` / 157KB 的 `ci-prepared.tsv` 全部针对 26.2 产物，
   1.20.1 需要自己的一份。

**第 1 步是唯一可以立刻做、且对现有版本零风险的一步。** 建议从这里开始。

---

## 6. 与本次已交付改动的边界

本次已完成、已构建验证的改动**不涉及 1.20.1**，它们是：

- 构建环境（代理、wrapper、bash PATH）——让这棵树在 Windows 上可构建、可测试；
- 8 处「吞掉真实数据损失」的静默失败改为响亮报错（见下节）；
- 第一代残留清理（`forbric-installer/` + 8 个 launch/regress 脚本），备份在 `.firstgen-backup/`。

它们都是**与版本无关**的修正，1.20.1 若将来启动也需要它们。

---

## 7. 附：本次实测到的 Windows 侧问题（与版本矩阵独立的另一条线）

CI 只跑 `ubuntu-latest`，所以下面这些在解压当天全部是红的，共 181 个失败；修掉环境项后降到 131：

| 根因 | 数量 | 判定 |
|---|---|---|
| `TempDirectory` 清理失败（IOException） | 60 | Windows 文件共享语义；测试夹具持有已加载 jar 的句柄。已配 `junit.jupiter.tempdir.cleanup.mode.default=ON_SUCCESS`，但实测**未生效于该批**（仍失败）——需继续查 |
| 断言不匹配 | 64 | gate 测试现在真在跑，暴露的是真逻辑不符（含 M20 契约 8 个） |
| symlink 权限 / `InvalidPathException` / NPE | 7 | Windows 权限与平台守卫 |
