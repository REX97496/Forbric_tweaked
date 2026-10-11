# Forbric_tweaked

[English](README.md) | 简体中文

[Forbric](https://github.com/Ray-T-r/Minecraft-Forbric-mod-loader) 的一个分支，基于 **Forbric 0.3.0**
· Minecraft 26.2。

Forbric_tweaked 让 Fabric、Forge、NeoForge 三种 mod 在**同一个** Minecraft 实例里同时运行——这一点和
Forbric 完全一样。**这份 README 只讲这里有哪里不同。** 加载器本身是什么、内部怎么工作，请看上游的
[introduction.zh-CN.md](introduction.zh-CN.md) 和 [forbric-kernel/README.md](forbric-kernel/README.md)，
那部分没有改动。

下面的改动分两类：**本来坏掉、现在能用的**，以及**本来静默失败、现在会明说的**。

> **关于名字**：本文里的产品名已改为 Forbric_tweaked。但**真实存在的文件名、目录名、jar 名、系统属性名
> 一律没改**（例如 `26.2-forbric`、`forbric-kernel-installer-0.3.0.jar`、
> `-Dforbric.compatibilityPolicy=continue`）——它们在磁盘上和代码里就叫这些名字，改了本文档你照抄就会
> 失败。

---

## 1. 合并基底原本根本造不出来

这是最要紧的一条。`tools/dev.py prepare` 下载完所有东西后，死在合并这一步：

```
[merge] reading jars
[merge]   vanilla=10952  forge-patched=10963  neo-patched=10964 classes
Exception in thread "main" java.lang.IllegalStateException: Incompatible initializer dependency on
  net/minecraft/world/level/chunk/ChunkGenerator#featuresPerStep
```

`patched-mc-forge-26.2.jar` 与 `patched-mc-neoforge-26.2.jar` **都成功产出**，但把两者合起来的那个 jar 没
有。而游戏跑的就是合并基底，所以**下游什么都验不了**——mod 兼容性、事件清单，全部卡死。

**原因。** `FieldContractReconciler` 要调和两个生态改成不同类型的私有字段
（`ChunkGenerator.featuresPerStep`），除非它能证明两侧构造器是同一套计算，否则拒绝。这次证明失败了——但
**从错误信息里完全看不出为什么**，因为那个方法有**六条不同的路径**返回「不」，而消息一条都没说。

顺着真实字节码追下去：

- 失败的其实是最后那个检查：lambda `lambda$new$1`「没有非空返回证明」；
- 那个 lambda 返回 `FeatureSorter.buildFeaturesPerStep(...)`，其 `List` 来自
  `ImmutableList$Builder.build()`；
- 要证明它非空，得去读 `ImmutableList.builder()` —— 也就是 **Guava**；
- 证明器向类图索要它，得到的是 `null`，因为**磁盘上没有 Guava**。

**根因：`prepare` 完全不下载 Minecraft 的库。** `MojangDownloader` 只取 `versions/<v>/<v>.json` 和
`<v>.jar`，把 `libraries/` 留给启动器；而合并拿到的是 `mcDir/libraries`，并从里面读取它要进行证明所需的
类。在这个目录为空的机器上，证明器**分不清**「这段代码不安全」与「能证明它安全的那个库根本没下载」——于是
它归咎于字节码，并终止了整个构建。

针对这一点做了两件事：

- **拒绝现在会说明是哪个检查失败。** `sameConstruction` 改为返回原因而不是裸 `false`，消息例如：
  `… ChunkGenerator#featuresPerStep: (…)V — the lambda body lambda$new$1(…)Ljava/util/List; has no
  non-null return proof on net/minecraft/world/level/chunk/ChunkGenerator`。
  「两侧补丁真的不一致」与「这个检查器没有建模其中一侧的行为」现在是**两条不同的消息**，因为它们是两个不
  同的问题。
- **要让合并跑起来，`libraries/` 必须有内容。** 把 `guava-33.6.0-jre.jar` 与
  `failureaccess-1.0.3.jar` 放进 `forbric-kernel/.dev/minecraft/libraries`（SHA-1 已对照版本 JSON 校验）
  之后，`prepare` 正常结束：合并基底写出，**88 MB**，链接检查报告 7 个悬空引用——7 个全部已知，**新增 0**。

## 2. 合并真正丢掉的东西，比这个仓库声称的多

合并能跑之后，覆盖率数字就可以**实测**而不是继承。仓库里已提交的
`forbric-loader/run/merged-base/merge-conflicts.txt` 是一份快照，而它偏乐观：

| | 已提交的快照 | 现在真实产出 |
|---|---|---|
| 钩子丢失总数 | 1006 | **1144** |
| 其中在数据生成器里（玩家游戏中到不了） | 314 | 330 |
| **其中玩家能碰到的** | **692** | **814** |
| 承担运行时丢失的不同类数 | 400 | — |

**运行时丢失比已提交的数字多 122 个（17.6%）。** 任何依据那份快照做的优先级判断，用的都是一个偏低的数。

而且那份快照是**「防止覆盖率静默漂移」的那个测试唯一读取的来源**——也就是说，**那个测试钉的是一个过期的
数**。把它改为钉真实报告，是接下来的第一步。

## 3. Windows：跑不起来、或跑得不对的地方

- **`gradlew` 根本无法被执行。** 两个开发脚本直接调用 wrapper。在 Windows 上 wrapper 是 `.bat`，不能当作
  程序直接启动；改用 shell 转一层又会得到 `WinError 193`。由这些脚本驱动的构建**连结果都没有被检查**。
- **中文区域下 Gradle 的输出被按 UTF-8 解码。** Gradle 在那里写的是 GBK，读取时抛
  `UnicodeDecodeError`——看起来像脚本坏了，实际是一次**失败的构建没有被识破**。
- **判断「有没有可运行的 java」用了错的依据。** `DependencyDialog` 调用 `Files.isExecutable`，而 Windows
  上这个答案来自「可读」——于是一个装着文本、名为 `java` 的文件被判为可运行，随后 fork 才以
  `CreateProcess error=5` 失败。它自己的契约写着「要在 fork **之前**回答」，所以现在 Windows 上改为识别启
  动器的 `MZ` 头。
- **任何名字里带方括号、空格或非 ASCII 的 mod jar 都会变得无法归因。**
  `ContendedCallSites.codeRoot` 吞掉一切异常并返回 `null`，而它的兜底把**百分号编码**的 URL 路径交给
  `Path.of`——那指向一个不存在的文件。于是一个叫 `[补丁] cloak patch+1.0.jar` 的 jar 被解析成含
  `%5B补丁%5D%20…` 的路径，**谁拥有这个 mixin 就无从判定**。修法是只解码 `%XX`——**刻意不用**
  `URLDecoder`，它会把字面 `+` 变成空格，从而毁掉 `patch+1.0.jar`。

## 4. 把静默失败改成明说

**14 处**原本吞掉真实失败的地方，现在会报告出来。其中两处揭示了什么：

- **`DeadEventAudit` 的两张表是用 `Map.put` 构建的。** 重复的事件名会**静默覆盖**先前那一行，连同「玩家到
  底损失了什么」那句描述一起丢掉。而这些行是手写的内部名，其中两行只差一个嵌套类。现在它们走 `putOnce`，
  在启动阶段就拒绝并**点名那个事件**。
- **上面那个 `FieldContractReconciler` 的拒绝**——六种不同原因共用一个消息。

其余是同一形状的较小实例。凡是查明「静默其实是刻意的」的地方，我**没有动它**，而是把理由写了下来，让「这
是决定过的」和「没人注意到」不再长得一样。

## 5. 两处变快，一处从「据说」变成「实测」

- **启动扫描把每个类解压了四遍。** 四趟独立的遍历走同一批 jar、同一批类条目，各自解压一遍。现在它们共用一
  次遍历，一次只持有**一个类**的字节。测量时把两个候选放在**同一个循环里交替执行**，这样漂移无法偏袒任一
  方：其中两者从 **204.6 ms 降到 120.0 ms**。**启动步骤一步没挪**——融合落在原来第一个的位置、结束在原来
  最后一个的位置——并且每一趟保留自己的开关，所以融合**不会**把某个关掉的打开，也**不会**让某一趟遇到的坏
  jar 静默掉别趟的输入。
- 先测过一个「缓存字节」的方案并**否决了**：那个看起来很好的缓存其实**把全部内容都留住了**，而所有真正有上
  界的尺寸，结果都与「不缓存」持平或更差（−5% 到 −86%）。
- **Windows 测试的真实失败数从 181 降到 0。** 剩下的 60 个是**一条平台限制，不是内核缺陷**：它们在
  ubuntu-only 的 CI 上全部通过，里面**每一条断言都通过**，只有临时目录清理失败。Windows 自己说明了原因
  ——`java.nio.file.FileSystemException: …\renamed-platform.jar: another program is using this file`
  ——而那个句柄属于一个**已经关闭**的 `URLClassLoader`：关闭它并不释放该 jar，我写过一个带重试和
  `System.gc()` 的探针钩子也没能释放。所以**不存在能可靠修好它的逐测试改法**，它被如实报告，而不是被掩盖
  （把清理模式设成 `NEVER` 能让它「通过」，代价是从此永不删除，每个 `@TempDir` 测试都留一个目录）。

---

## 安装

**这一节与 Forbric 相同**，引用的仍是真实的文件名与目录名，它们没有被改名。

### 准备工作

- **一个能从 `.minecraft/versions` 启动版本的启动器。** 在 Windows 上用 **PCL2** 测过。HMCL 读同一批文
  件，应当可用。官方启动器未测。Prism Launcher 与 MultiMC 用自己的实例，看不到这里。
- **Java。** 能玩 Minecraft 就已经有了。
- **网络**，以及安装过程中约 **730 MB** 可用磁盘（完成后保留约 190 MB）。

你**不需要**先装 Minecraft 26.2，也**不需要** Fabric、Forge 或 NeoForge：安装器会自己下载并构建。你的 mod
仍然需要它自己的前置，例如多数 Fabric mod 需要 Fabric API。

### 安装

按 Forbric 的文档安装即可：运行 `forbric-kernel-installer-0.3.0.jar`，它会把一个 `26.2-forbric` 版本配
置写进 `.minecraft/versions/`，然后从启动器启动该版本。安装器、目录名与启动器步骤在这里完全一样。

### mod 放在哪里

- 如果你的启动器为每个版本保留独立目录（版本隔离；PCL2 与 HMCL 都能这样做）：
  `.minecraft/versions/26.2-forbric/mods/`
- 否则就是共享的 `.minecraft/mods/`。所有没有独立目录的版本都用这一个，所以本加载器也会尝试加载里面已有
  的 mod。

安装器结束时会同时告诉你这两个位置。不确定你的是哪一种？先把游戏启动一次：正确的 `mods` 文件夹旁边会出现
一个叫 `.forbric-kernel` 的目录。

**每个 mod 只放一个构建版本。** 很多 mod 同时提供 Fabric、Forge、NeoForge 三种构建；放多个也只会加载其中
一个。第一次发生这种情况时，加载器会把它的选择写进 `mods` 旁边的 `forbric-mods.txt`，你可以在那里换成另一
个构建。几个 mod 共用的前置库同理——通常一个构建就够，因为一个 mod 一般也能用另一个加载器构建的前置。

你的启动器可能把 `26.2-forbric` 标成 Fabric 版本。这是有意的：启动器每个版本只显示一个加载器，所以这里告
诉它 Fabric。它仍然会从 `mods` 加载三种 mod。

### 装好了吗？

打开暂停菜单：有一个**三个重叠方块**的按钮，提示是 *Mods (Forbric)*。它列出你装的每个 mod 并标明种类；选
中一个按 **Config**（或双击）就打开那个 mod 自己的设置。Fabric mod 只有在同时装了 Mod Menu 时才会在这里
出现 Config 按钮。

### 出了问题怎么办

| 你看到什么 | 怎么办 |
| --- | --- |
| **窗口说某个 mod 缺它需要的东西** | 它会点名 mod 与要装什么。装好，或按 **Launch anyway**。 |
| **窗口说某个 mod 的部分功能不可用** | 该 mod 的一部分没能启动。可以继续玩，也可以退出并移除它。 |
| **游戏崩溃** | 看 `mods` 旁边的 `.forbric-kernel`。里面的 `crash-analysis.txt` 点名最可能相关的 mod；完整报告在 `crash-reports/`。 |
| **mod 装了但什么都没发生** | mod 列表会标出没有加载完成的 mod。同一份列表也在 `.forbric-kernel` 的 `load-report.txt` 里。常见原因是该 mod 是给别的 Minecraft 版本构建的，或者你放了两份构建。 |
| **专用服务器起不来**，日志说是兼容性策略拦下的 | 服务器没有可以问你的界面，所以它停下。移除它点名的 mod，或在启动命令里加 `-Dforbric.compatibilityPolicy=continue` 强行运行。 |
| **安装好像卡住了** | 通常是你的网络与 Mojang 之间有代理或 VPN。先跑 *Install* 末尾提到的 `--doctor` 检查，然后关掉它重试。 |

日志与状态在 `.minecraft/versions/26.2-forbric/`；安装器自己的工作目录在
`.minecraft/.forbric-build/` 与 `.minecraft/libraries/net/forbric/`。

它是并存安装的，所以你原有的环境不受影响；卸载就是删掉 `26.2-forbric` 那个目录。

---

## 写给 mod 开发者

**你的 mod 不需要改动**——这是上游 Forbric 的设计，本分支没有改变它。你调用的是真正的 Fabric API、
MinecraftForge 或 NeoForge 类；被重新实现的是加载器，而游戏是安装时构建出的**一个合并 jar**。

本分支的工作里有两点值得知道：

- **合并基底丢掉的东西比已提交的冲突报告多**（§2）。要判断某个 Forge 钩子在合并后会不会触发，请**新产出一份
  报告**，不要读仓库里那份快照。
- **现在跑 `prepare` 需要 `libraries/` 有内容**（§1），否则合并无法完成。`prepare` 不会替你做这件事；见上
  面的修复说明。

### 从源码构建与运行

构建内核自己的 Gradle 工程需要 `git` 与 JDK 21 或更新（boot 侧编译不需要 Fabric 底座）：

```bash
git clone https://github.com/REX97496/Forbric_tweaked.git
cd Forbric_tweaked
cd forbric-kernel && ./gradlew build
```

**全新 clone 上构建通过，不代表游戏能启动。** 没有本地已暂存的游戏 jar 时，runtime 源集与 transfer 测试会
被跳过。

要在开发用游戏里跑当前源码，需要 **JDK 25+ 与 Python 3.9+**，然后在仓库根目录（Windows 上把 `python3` 换成
`py`）：

```bash
python3 tools/dev.py prepare --no-assets    # 下载并构建游戏产物
python3 tools/dev.py client                 # 构建并启动
```

如果 `forbric-kernel/.dev/minecraft/libraries` 是空的，`prepare` 会在合并阶段以 *Incompatible initializer
dependency* 停下——那就是 §1，背后是**缺一个 Minecraft 库**，而不是真的不兼容。按版本 JSON 里
`downloads.artifact.url` 把 `libraries/` 填好后重跑即可。

如果 JVM 连不上 `maven.neoforged.net`：它的 `HttpClient` **不读代理环境变量**，代理必须以系统属性给出，而
`dev.py` 的 `--jvm` **传不进嵌套的构建 JVM**。`JAVA_TOOL_OPTIONS` 可以：

```bash
JAVA_TOOL_OPTIONS='-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897' python3 tools/dev.py prepare --no-assets
```

下载物与组装出的 jar 都在被忽略的 `forbric-kernel/.dev/` 下。

---

## 这个分支没有修的

- **合并基底的事件面。** 调用点被合并丢掉的钩子，如果没有东西重新投递，仍然不会触发。已知**完全死且无桥
  接**的 MinecraftForge 事件有 **10 个**，另有 **7 个**只在原来的一部分路径上投递，而真实的运行时丢失就是
  §2 的 **814** —— 这些都在内核自己的表里被记录并断言，但**没有在这里被修好**。
- **合并语法的放宽。** 合并器只合成三种方法形状，其余一律拒绝。放宽它是「让 mod 的改动被更多应用」的唯一杠
  杆，而我**刻意没有做**：编译器假定「一个实参 = 一条指令」，一次错误的合成会**静默产生错误行为**（比拒绝
  更难查），而且当时没有真实基底可以证明任何改动。这是剩下最要紧的一件事，而上面那套覆盖率测量就是用来指
  挥它的。

## 许可证

Apache-2.0 —— 见 [LICENSE](LICENSE) 与 [NOTICE](NOTICE)。这是
[Ray-T-r/Minecraft-Forbric-mod-loader](https://github.com/Ray-T-r/Minecraft-Forbric-mod-loader) 的分
支；加载器本身的功劳全属上游。清洁室边界记录在
[forbric-loader/CREDITS.md](forbric-loader/CREDITS.md) 与
[forbric-loader/MAPPINGS.md](forbric-loader/MAPPINGS.md)。

与 Mojang、FabricMC、MinecraftForge、NeoForged 无隶属关系。
