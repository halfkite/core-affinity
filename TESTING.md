# 验证记录（2026-09-21）

基准：Minecraft 1.21.1、Fabric Loader 0.16.14、JDK 21.0.11、Windows 本机。

最终构建命令：`./gradlew.bat build :common:nativeSmoke --console plain`，结果成功。

最终产物：`mod-builds/20260921-140616/core-affinity-fabric-1.0.0+mc1.21.1.jar`

SHA-256：`2d0d438e7c590814e27cf36078e89335451f7480a574c406ebadda5877668346`

## 已完成

- 12 项 JUnit 测试全部通过：大小核过滤、物理核心序号与 SMT、手动选核、无效/受限 CPU、未知/同构拓扑、范围解析、配置生成与保留。
- Windows 原生线程亲和性实测：当前测试线程绑定 CPU 0，读回一致；预先创建的另一线程亲和性未改变；恢复原始范围成功。
- Windows 自动大核范围实测：本机识别 CPU 0–11 为性能等级 1，12–19 为等级 0；自动选择并读回 0–11，成功恢复。
- `:fabric:runServer` 开发环境启动到 EULA 提示，模组初始化和配置生成成功。
- 独立目录运行 Fabric 官方 server launcher，加载最终成品 JAR；Fabric 识别主模组及内嵌 `dev_bigcore_common`、`net_java_dev_jna_jna`，初始化无原生接口或缺类错误，正常运行至 EULA 提示。
- 成品启动测试路径：`fabric/build/production-smoke/`，日志：`logs/latest.log`。服务器 JAR 下载连接等待后改用本机 Gradle 缓存中的官方 1.21.1 server JAR；Loader 依赖由官方启动器安装。

## 尚未完成

- 测试目录没有接受 EULA，服务端未进入世界或执行 tick，因此不声称完成服务端实际游戏测试。
- 未启动图形客户端，未验证客户端/单人世界运行时 Mixin 与性能表现。
- 未进行 Linux 原生调用实机验证、双服务端同时运行、长期压力测试、跨 processor group 测试。
- 没有 FPS/TPS 提升的量化结论。

下一步可在用户已有的 Fabric 1.21.1 实例安装最终 JAR，按 README 的步骤检查 `bound and verified`；多个服务端分别设置 `coreIndex=0/1` 后检查实际 `physical cores` 不同。完成基准版验证后再考虑 NeoForge 或其他游戏版本。

## 多服务端 Carpet 压力测试（2026-09-21）

使用 JDK 21.0.11 重新完成基准测试，避免 Java 25 与 Minecraft 1.21.1 的版本差异影响结果。测试脚本为 [multi-server-harness.ps1](D:/ai/dxh/test-runs/multi-server-harness.ps1)，它复制独立实例，加入 Carpet 1.4.147，分别在 25601/25602/25603 启动三个服务端，并将 `server.mode=explicit` 分别设为 CPU 0、2、4。

Carpet 文件是 `fabric-carpet-1.21-1.4.147+v240613.jar`，兼容 Minecraft 1.21–1.21.1；测试使用 `/player <name> spawn`、spectator 模式及 `/player <name> move forward`。每个服务端生成 4 个假人，分别向北、南、东、西移动 60 秒，每 5 秒记录位置和实际 Minecraft JVM 的进程 CPU 时间。

最终测试目录：[multi-server-20260921-143240](D:/ai/dxh/test-runs/multi-server-20260921-143240)

- 三个日志均出现 `Server thread ... bound and verified`：server-a → CPU 0 / physical core `0:0`；server-b → CPU 2 / `0:2`；server-c → CPU 4 / `0:4`。
- 每个实例有 48 次假人位置采样。最终位置约为北/南 ±500、东/西 ±430–560 方块，确认假人持续移动并加载远处区块。
- 每个服务端生成 10–12 个 `.mca` region 文件。
- CPU 采样按“一个逻辑核满载=100%”归一化；稳定阶段（最后 8 个采样）为 server-a **207.8%**、server-b **202.4%**、server-c **183.5%**。机器有 20 个逻辑 CPU，折算为整机总 CPU 约 **10.4% / 10.1% / 9.2%**；这里是整个 Minecraft JVM，包含 worker 线程，主 Server thread 仍按日志确认只在指定核心上运行。
- 稳定阶段峰值分别为 **326.2% / 265.2% / 246.0%**；首次生成和新区块加载期间出现多次 `Can't keep up`，说明单线程绑到一个核心后，4 个移动假人的并发区块生成已经造成 TPS 落后。这是压力测试达到负载的证据，不是模组崩溃。
- CPU 原始采样在 [cpu-samples.json](D:/ai/dxh/test-runs/multi-server-20260921-143240/cpu-samples.json)，每个实例的完整日志在各自 `server-*/logs/latest.log`。

这轮测试只验证了绑核、Carpet 假人移动、区块加载和 CPU 观测，没有把三个服务端长期保留运行；脚本在采样结束后发送 `stop` 并等待保存完成。旧的 Java 25 轮次仍保留在 `multi-server-20260921-142934`，用于对比，但结论以 JDK 21 轮次为准。

## `/coreaffinity` 指令冒烟测试（2026-09-21）

使用当前构建的 JAR 更新已有 Fabric 服务端实例（复用原来的 server.jar、世界、库和 Carpet），配置 `language=zh_cn`，依次执行：

```text
/coreaffinity help
/coreaffinity language en_us
/coreaffinity language
/coreaffinity list
/coreaffinity assign 0 main
/coreaffinity disable-all e
```

结果：中文帮助、英文帮助、动态语言菜单、CPU 列表、P/E 标注、核心分组命令和一键禁用 E 核均成功。帮助日志显示可点击指令与 `#` 后的灰色说明；指令使用可点击补全事件。列表行实际输出为 `0P  :[✓][✕][？]`、`10P :[✓][✕][？]`，当前分组按钮显示选中颜色，其余按钮保持灰色；快捷操作按 P/E 批量设置，最后使用“应用更改”提交。服务端随后正常执行 `stop`，退出码为 0。冒烟脚本为 [coreaffinity-command-smoke.ps1](D:/ai/dxh/test-runs/coreaffinity-command-smoke.ps1)。

## 核心分组与 P/E 对比测试（2026-09-21）

测试目录 [multi-server-core-groups-20260921-185036](D:/ai/dxh/test-runs/multi-server-core-groups-20260921-185036) 直接复制并复用了上一轮已安装的三个服务端，只替换 Core Affinity JAR；每个服务端继续使用原来的 server.jar、库、世界和 Carpet。配置为：server-a 主核心 `0P`、共享 `2,4`；server-b 主核心 `12E`、共享 `6,8`；server-c 主核心 `2P`、共享 `10,11`。

三个日志均确认主线程分别绑定 `0:0`、`0:12`、`0:2`，没有 `Can't keep up`。四个 Carpet 假玩家分别向四个方向移动约 400–468 格并持续加载地图。45 秒压力期间 JVM 进程 CPU（一个逻辑核满载=100%）稳定阶段平均：server-a `419.9%`、server-b `417.2%`、server-c `439.5%`；峰值分别 `469.1%`、`482.5%`、`526.8%`。这说明 E 核主线程仍能被准确锁定，但该轮负载下不能仅凭总 JVM CPU 百分比断言 P/E 的 TPS 优劣；主线程绑定证据以各服 `bound and verified` 日志为准。共享核心目前保存并显示分组，当前版本只绑定服务器主线程。

最新可安装归档：[mod-builds/20260921-2236](D:/ai/dxh/mod-builds/20260921-2236)，目录时间戳精确到分钟；SHA-256：`65315014155D4C36F4B2C3C046C1D54357078D12DC5F88F6C53F2B2F30DFC3E3`。

## Confirming live affinity changes (2026-09-21 19:33)

SMT filtering update: `/coreaffinity smt off` stages one selected logical thread per physical core; `/coreaffinity smt on` stages removal of that filter. Both require Confirm apply and persist as `server.avoidSmt`. Explicit groups are retained so removing the filter restores their sibling selections. Unit tests cover non-adjacent siblings, persistence and startup behavior. Windows native smoke verified a 20-CPU selection reduced to 14 physical-core representatives and restored to 20. This only filters server main-thread affinity; it does not disable hardware SMT, restrict workers or reserve cores across servers. Linux and actual player clicks remain untested.

On Fabric 1.21.1, core assignment and disable-all buttons now edit a server-wide, in-memory draft. `/coreaffinity list` shows the draft and a localized Confirm apply button. Only this permission-level-2 confirmation changes the server main-thread affinity and persists the groups. Each edit invalidates previous confirmation buttons. Unconfirmed drafts are lost on server shutdown. Language settings continue to apply immediately. Shared groups still do not constrain worker threads.

Validation: `gradlew.bat build :common:nativeSmoke --console plain` passed. Transaction tests cover native failure, persistence failure rollback, repeated changes and restoration of the original affinity. Windows native smoke verified CPU 0 -> 19 -> 0 with readback. The existing installed Fabric/Carpet command-smoke server was reused with the updated JAR; draft editing and stale confirmation were checked. Actual player clicks and visual chat rendering were not tested.
