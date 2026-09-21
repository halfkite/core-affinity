# Core Affinity / 核心亲和性绑定

Minecraft **1.21.1 · Fabric · Java 21**。将独立服务端/单人世界服务端的 `Server thread`、客户端的游戏与渲染主线程绑定到大核，或指定的逻辑 CPU。通过原生线程亲和性限制调度位置；不修改整个 Java 进程的亲和性。

## 安装

Fabric mod id 为 `core_affinity`，显示名称为 `Core Affinity`。把 `core-affinity-fabric-1.0.0+mc1.21.1.jar` 放进对应实例的 `mods` 目录。需要 Fabric Loader 0.16.14 或更高版本；不需要 Fabric API，也不需要另装 JNA（已内嵌）。服务端可单独安装，玩家无需安装；要绑定客户端线程则在客户端也安装。

首次启动生成 `config/big-core-affinity.properties`。修改后重启对应实例。默认服务端和客户端均为 `auto`：仅在可靠检测到不同性能等级时绑定到最高等级的全部可用逻辑 CPU；拓扑不明或同构处理器上保持原状，日志提示手动配置。

```properties
server.mode=auto
server.cpus=
server.coreIndex=-1
client.mode=auto
client.cpus=
client.coreIndex=-1
language=auto
main.cpus=
shared.cpus=
disabled.cpus=
```

| 配置 | 作用 |
| --- | --- |
| `server.mode` / `client.mode` | `auto` 自动选大核；`explicit` 按 `cpus` 指定；`off` 关闭 |
| `server.cpus` / `client.cpus` | 仅 explicit 使用；逻辑 CPU 编号，例如 `2`、`2,4`、`2-5` |
| `server.coreIndex` / `client.coreIndex` | 仅 auto 使用；`-1` 使用全部大核；`0`、`1`…选择第几个可用物理大核上的一个逻辑线程 |
| `language` | 指令语言；`auto` 先看本模组配置，其次尝试 Carpet 语言设置，最后使用系统语言；也可写 `zh_cn` 或 `en_us` |
| `main.cpus` | `/servercore list` 中的主核心分组，逗号或范围分隔；服务端重启后优先绑定这些逻辑 CPU |
| `shared.cpus` | 共享核心分组，供后续线程调度接口使用；当前版本保留分组并显示，不改变其他线程 |
| `disabled.cpus` | 禁用核心分组；自动选核时排除这些逻辑 CPU |

CPU 编号从 0 开始。Windows 编号为 `processor group * 64 + group 内编号`；Linux 为内核 CPU 编号。`cpus` 是逻辑线程，不是物理核序号，不要假定不同机器的大核都是偶数编号。

## 同机多个服务端分配不同大核

在每个服务端自己的配置中设置不同的 `coreIndex`，例如：

```properties
# 服务端 A：第一个可用物理大核
server.mode=auto
server.coreIndex=0
```

```properties
# 服务端 B：第二个可用物理大核
server.mode=auto
server.coreIndex=1
```

这要求实例具有相同的初始 CPU 可用范围和拓扑；否则序号可能指向不同 CPU，建议使用 explicit。编号超出可用大核数量会记录错误并跳过绑定，不会悄悄改绑到小核。

也可以直接指定：

```properties
# 服务端 A 的文件
server.mode=explicit
server.cpus=2
```

```properties
# 服务端 B 的文件
server.mode=explicit
server.cpus=4
```

以上只是示例，请根据启动日志的 `physicalCore` 判断：两个逻辑 CPU 若共享同一 `physicalCore`，仍会争抢同一物理核资源。这种配置分配不会为 CPU 建立系统级独占锁；其他软件、未安装本模组的服务端，以及配置相同的实例仍然可能使用同一核心。默认 `auto` 使用全部大核，也不会自动协调不同实例。

## 游戏内指令

服务端启动后使用 `/servercore help` 查看帮助。帮助正文来自模组资源文件，不写死在 Java 代码中；默认语言优先级为本模组配置 `language`，其次是 Carpet 的语言设置（安装 Carpet 时），最后是 Java/操作系统语言。使用 `/servercore language` 查看可用语言，使用 `/servercore language zh_cn` 或 `/servercore language en_us` 设置自己的显示语言；玩家设置后会在聊天栏询问是否设为全服默认，不确认则只改变自己的显示。语言参数支持自动补全，候选项来自模组实际语言文件。

有权限的玩家可以点击确认按钮，等价于 `/servercore language global <语言>`，将语言写入全服默认配置；`/servercore language personal <语言>` 可明确保持为个人显示。控制台执行 `/servercore language <语言>` 时没有个人聊天显示，会直接设置全服默认。

`/servercore list` 会列出操作系统可见的每个逻辑 CPU，每行标出 `P`/`E`，并显示当前分组：

```text
[主核心]:0P,1P
[共享核心]:2P,3P
[禁用核心]:14E,15E
[未分配]:5P,6P
```

每个核心后面都有 `[主核心]`、`[共享核心]`、`[禁用核心]`、`[未分配]` 可点击按钮。还提供 `[禁用全部 P 核]`、`[禁用全部 E 核]`、SMT 过滤和刷新按钮。修改分组需要权限等级 2；修改后点击 `[确认应用]`，主线程绑定会在线更新并写回 `big-core-affinity.properties`。命令行也可以使用 `/servercore assign <核心编号> <main|shared|disabled|unassigned>`、`/servercore disable-all <p|e>` 和 `/servercore smt <off|on>`。

## 日志与验证

第一次 tick 时打印 CPU 拓扑及 `bound and verified`，后者表示原生设置后已成功读回亲和性。`allowed` 是模组初始化时的线程允许范围；`performanceClass` 是系统提供的相对性能等级；`physicalCore` 用于识别超线程兄弟。

1. 启动服务端，等世界加载完成，检查 `logs/latest.log` 中的 `CoreAffinity` 与 `bound and verified`。
2. 客户端进入世界后分别检查 `CLIENT` 和 `SERVER` 日志；多人游戏客户端只绑定 CLIENT。
3. 两个服务端分别设 `coreIndex=0/1`，重启后确认输出不同的 `physical cores`。
4. Windows 可以用能查看线程亲和性的工具检查 `Server thread`；任务管理器的进程亲和性不是本模组的线程亲和性。Linux 可对 `/proc/<pid>/task/<tid>/status` 查看 `Cpus_allowed_list`。

配置错误、原生库加载失败、系统拒绝绑核时记录原因并继续运行 Minecraft。运行中不轮询、不持续重设亲和性；外部工具之后改变线程亲和性会覆盖本模组设置。停止线程时尝试恢复原范围。

## 系统支持与边界

- Windows 10+/Windows Server 2016+，64 位：用 `GetSystemCpuSetInformation` 的 EfficiencyClass 判断性能等级，用 `SetThreadGroupAffinity` 硬绑定。当前版本只使用初始化线程所在 processor group 中原本允许的 CPU，不支持跨 group 迁移；一般 <=64 逻辑 CPU 的桌面机器只有一个 group。
- Linux：用 `sched_setaffinity(0, ...)` 设置当前线程；Intel 混合架构优先读取 `cpu_core/cpus` 和 `cpu_atom/cpus`，否则读取 `cpu_capacity`。文件不存在/性能等级相同则不猜测，可用 explicit。兼容原始 taskset 范围；容器 cpuset 等限制由内核执行，读回不一致则恢复。
- macOS 等其他系统不实现绑核，会提示并继续启动。
- 在首个 tick 才绑定，以便启动阶段已有工作线程不受影响。不过 **Linux 会让之后新建的线程继承创建者的亲和性**；本模组没有拦截其他模组的线程创建。公共服务在绑核前保留原始允许范围，让之后创建的单人世界服务端仍能独立选核。Linux 对工作线程特别敏感的整合包应实测吞吐量。
- 单核固定可减少迁移，也可能限制调度弹性；不保证提高 FPS/TPS。默认全部大核更适合先验证，再按需给多实例分核。
- 自动识别依赖操作系统/固件正确报告；识别失败请查日志并手动配置。

## 项目结构与 NeoForge 接口

```
common/  不依赖 Minecraft 或 Fabric 的配置、选核策略和 Windows/Linux 原生实现
fabric/  Fabric 初始化及两个主线程的 Mixin 接入
```

以后 NeoForge 适配模块可复用 `common`，在任何绑核之前于初始化阶段调用 `AffinityService.load(configDirectory)`，然后在真正的服务端/客户端主线程首个 tick 调用 `bindCurrentThread(Role.SERVER/CLIENT)`；停止时在同一线程调用 `restoreCurrentThread()`。适配层负责每个线程生命周期只尝试一次。`AffinityBackend` 是操作系统扩展接口。此版不包含 NeoForge 成品，也没有迁移其他 Minecraft 版本。

## 构建和验证

```powershell
$env:JAVA_HOME='你的 JDK 21 路径'
.\gradlew.bat build :common:nativeSmoke
```

输出：`fabric/build/libs/core-affinity-fabric-1.0.0+mc1.21.1.jar`。`-sources.jar` 不用于安装。Gradle Wrapper 固定 9.5.1，首次构建需要下载依赖。`common:test` 验证选核与配置；`common:nativeSmoke` 只在独立测试 JVM 中实际绑定、读回、恢复，并检查已有旁路线程未受影响。

构建归档保存在 `mod-builds/时间戳/`，含可安装 jar 和 SHA-256 清单。测试记录见 `TESTING.md`。

实现依据：[Microsoft CPU set 字段定义](https://learn.microsoft.com/en-us/windows/win32/api/winnt/ns-winnt-system_cpu_set_information)、[Windows 线程分组亲和性](https://learn.microsoft.com/en-us/windows/win32/api/processtopologyapi/nf-processtopologyapi-setthreadgroupaffinity)、[Linux Intel 混合 PMU 源码](https://github.com/torvalds/linux/blob/master/arch/x86/events/intel/core.c)。
