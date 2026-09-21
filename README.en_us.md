# Core Affinity

[中文](README.md) | **English**

Minecraft **1.21.1 · Fabric · Java 21**. Core Affinity binds the Minecraft server thread, integrated-server thread, or client main thread to performance cores or explicitly selected logical CPUs. It uses native thread affinity and does not change the affinity of the entire Java process.

## Installation

The Fabric mod id is `core_affinity` and the display name is `Core Affinity`. When upgrading, remove the old `big-core-affinity-fabric` JAR before installing the new one, so both mod ids are not loaded together. An existing `config/big-core-affinity.properties` file is migrated automatically to `config/core-affinity.json5` and is left untouched.

Put `core-affinity-fabric-1.0.0+mc1.21.1.jar` in the instance `mods` directory. Fabric Loader 0.16.14 or newer is required. Fabric API is not required; JNA is bundled in the mod JAR. The JNA attribution and license are documented in [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).

The first launch creates `config/core-affinity.json5`. It is JSON5, so it supports trailing comments, bilingual explanations, and trailing commas. The default server and client mode is `auto`: when the operating system reports a reliable heterogeneous topology, the mod selects the available P cores. If the topology cannot be identified, the mod leaves scheduling unchanged and logs a message recommending explicit selection.

```json5
{
  // Core Affinity configuration / Core Affinity 配置文件
  "language": "auto", // Chat language: auto follows config, Carpet, then system / 聊天语言：auto 依次跟随本模组、Carpet、系统语言
  "server": {
    "mode": "auto", // Server main thread: auto=P cores, explicit=cpus, off=disabled / 服务端主线程：auto=大核，explicit=按 cpus，off=关闭
    "cpus": [], // Logical CPU IDs used only by explicit mode / 仅 explicit 模式使用的逻辑 CPU 编号
    "coreIndex": -1, // In auto mode: -1=all P cores, 0+=one physical P core / auto 模式：-1=全部 P 核，0+=指定一个物理 P 核
    "avoidSmt": false // true keeps one logical thread per physical core; SMT hardware stays on / true=每个物理核保留一个逻辑线程；不关闭硬件超线程
  },
  "client": {
    "mode": "auto", // Client main thread: auto=P cores, explicit=cpus, off=disabled / 客户端主线程：auto=大核，explicit=按 cpus，off=关闭
    "cpus": [], // Logical CPU IDs used only by explicit mode / 仅 explicit 模式使用的逻辑 CPU 编号
    "coreIndex": -1 // In auto mode: -1=all P cores, 0+=one physical P core / auto 模式：-1=全部 P 核，0+=指定一个物理 P 核
  },
  "groups": {
    "main": [], // CPUs used by the server main thread after Confirm apply / 点击确认应用后供服务端主线程使用的 CPU
    "shared": [], // Reserved for future worker-thread affinity; currently informational / 预留给后续工作线程绑核；当前仅记录和显示
    "disabled": [] // CPUs rejected by automatic selection and group application / 自动选核和分组应用时排除的 CPU
  }
}
```

`server.mode` and `client.mode` accept `auto`, `explicit`, or `off`. `server.cpus` and `client.cpus` are used only by `explicit` mode and contain logical CPU IDs. `coreIndex=-1` selects all available P cores in `auto` mode; `0`, `1`, and so on select one physical P core. `server.avoidSmt=true` keeps one selected logical thread per physical core; it does not disable SMT in firmware.

CPU IDs start at zero. Windows IDs are `processor group * 64 + group-local index`; Linux uses kernel CPU numbers. These are logical CPUs, not physical-core numbers. Do not assume that P cores are always even-numbered.

## Assigning different cores to multiple servers

Use a different `coreIndex` in each server's `core-affinity.json5`:

```json5
{
  "server": {
    "mode": "auto", // First available physical P core / 第一个可用物理 P 核
    "coreIndex": 0
  }
}
```

```json5
{
  "server": {
    "mode": "auto", // Second available physical P core / 第二个可用物理 P 核
    "coreIndex": 1
  }
}
```

This assumes the instances have the same initial CPU allowance and topology. For deterministic deployment, use explicit CPU IDs:

```json5
{
  "server": {
    "mode": "explicit", // Explicit selection / 指定核心
    "cpus": [2]
  }
}
```

```json5
{
  "server": {
    "mode": "explicit", // Explicit selection / 指定核心
    "cpus": [4]
  }
}
```

Check `physicalCore` in the startup log. Two logical CPUs with the same `physicalCore` still share one physical core. Core Affinity does not create a system-wide exclusive reservation, so other software or another server can still use the same CPU.

## In-game commands

Commands are clickable for completion in the in-game help. The gray text after `#` is the short description.

```text
/coreaffinity help #Show help
/coreaffinity language <language> #Set your display language
/coreaffinity list #Show CPU core groups
/coreaffinity assign <cpu> <main|shared|disabled|unassigned> #Change a core group
/coreaffinity disable-all <p|e> #Disable all P-cores or E-cores
/coreaffinity language global <language> #Set the server-wide language
/coreaffinity language personal <language> #Set only your own language
/coreaffinity smt <off|on> #Set main-thread SMT filtering
/coreaffinity apply <confirmation-token> #Apply pending groups
```

`/coreaffinity language zh_cn` and `/coreaffinity language en_us` ask whether to make the selected language server-wide; declining keeps it personal. Core-list edits stay pending until a permission-level-2 player clicks **Confirm apply**, which updates the binding and saves `core-affinity.json5`.

## Logs and verification

On the first tick, the mod logs the CPU topology and `bound and verified`. The latter means the native affinity was set and read back successfully. `allowed` is the original CPU allowance, `performanceClass` is the operating-system performance class, and `physicalCore` identifies SMT siblings.

On Windows, inspect the `Server thread` with a tool that shows thread affinity; Task Manager's process affinity is a different setting. On Linux, inspect `/proc/<pid>/task/<tid>/status` and its `Cpus_allowed_list` field.

Configuration errors, native-library failures, and OS rejections are logged and Minecraft continues running. The mod does not continuously poll or reset affinity. An external tool can overwrite the mask. On shutdown, the original mask is restored on the same thread.

## Platform boundaries

- Windows 10+/Windows Server 2016+ 64-bit: uses `GetSystemCpuSetInformation` and `SetThreadGroupAffinity`. The current backend stays inside the initial processor group and does not migrate across groups.
- Linux: uses `sched_getaffinity` and `sched_setaffinity`. Intel hybrid systems prefer `cpu_core/cpus` and `cpu_atom/cpus`; other systems use `cpu_capacity` when available. Container cpusets remain enforced by the kernel.
- macOS and other unsupported systems leave affinity unchanged and log a notice.
- On Linux, newly created threads can inherit the creator's affinity. The mod does not intercept other mods' thread creation, and the current Shared group is informational.
- Pinning a server to one core can reduce migration but also reduce scheduling headroom. It does not guarantee higher TPS or FPS.

## Project structure and NeoForge interface

```text
common/  Loader-neutral configuration, selection policy, and Windows/Linux backends
fabric/  Fabric initialization, commands, and main-thread mixins
```

A future NeoForge adapter can reuse `common`: call `AffinityService.load(configDirectory)`, bind the actual server/client main thread with `bindCurrentThread(Role.SERVER/CLIENT)`, and restore it with `restoreCurrentThread()` during shutdown. This repository currently contains only the Fabric 1.21.1 implementation.

## Build and validation

```powershell
$env:JAVA_HOME='Path to JDK 21'
.\gradlew.bat build :common:nativeSmoke
```

The installable output is `fabric/build/libs/core-affinity-fabric-1.0.0+mc1.21.1.jar`. `-sources.jar` is not an installable mod. `common:test` checks selection and JSON5 migration; `common:nativeSmoke` performs native bind, readback, live rebind, SMT filtering, and restoration in a short-lived test JVM.

Timestamped build archives are stored under `mod-builds/`. Each archive contains the installable JAR and a SHA-256 manifest. See `TESTING.md` for Carpet and multi-server results.

License information for the bundled JNA dependency is in [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).
