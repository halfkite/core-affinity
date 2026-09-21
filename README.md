# Core Affinity / 核心亲和性绑定

[English](README.en_us.md)

本模组可以将独立服务端/单人世界服务端的 `Server thread`、客户端游戏主线程绑定到大核，或指定的逻辑 CPU。通过原生线程亲和性限制调度位置；不修改整个 Java 进程的亲和性。

Fabric mod id 为 `core_affinity`，显示名称为 `Core Affinity`<br>
加载器版本要求Fabric Loader 0.16.14+<br>
前置 [JNA](https://github.com/java-native-access/jna)（已内嵌），JNA 的许可证和归属见仓库中的 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)。服务端可单独安装，玩家无需安装；要绑定客户端线程则在客户端也安装。

首次启动生成 `config/core-affinity.json5`。`language` 为 `auto` 时只在首次启动读取 Carpet 或系统语言，并把结果写回配置；后续启动直接使用配置中的具体语言，不再重复查找。
```json5
{
  // Core Affinity configuration / Core Affinity 配置文件
  "language": "auto", // 首次启动读取 Carpet/系统语言并写入此处；后续启动直接复用
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

P为大核 E为小核心<br>
CPU 编号从 0 开始<br>
Windows 编号为 `processor group * 64 + group 内编号`<br>
Linux 为内核 CPU 编号<br>
`cpus` 是逻辑线程，不是物理核序号，不要假定不同机器的大核都是偶数编号

## 游戏内指令

帮助中的白色命令可点击补全，灰色 `#` 后是简短说明。语言参数支持自动补全，候选项来自模组语言文件。

```text
/coreaffinity help                                      #获取帮助
/coreaffinity language <语言>                           #设置自己的显示语言
/coreaffinity list                                      #查看 CPU 核心分组
/coreaffinity assign <核心编号> <分组>                  #修改核心分组
/coreaffinity disable-all <p|e>                        #一键禁用 P 核或 E 核
/coreaffinity language global <语言>                   #设置全服默认语言
/coreaffinity language personal <语言>                 #仅设置自己的语言
/coreaffinity smt <off|on>                             #设置主线程超线程过滤
/coreaffinity apply <确认令牌>                         #确认应用待处理分组
```

玩家执行 `/coreaffinity language zh_cn` 或 `/coreaffinity language en_us` 后，可以在聊天栏选择是否设为全服默认；不确认则只改变自己的显示。核心列表中的按钮修改待应用方案，权限等级 2 的玩家点击确认后才会在线更新绑定并保存配置。

`/coreaffinity list` 的核心分组界面如下；按钮会根据当前状态显示为选中颜色，点击后只修改待应用方案：

```text
CPU核心分组 点击按键进行更改
✓：主核心：游戏进程主要调用的核心
✕：禁用核心：不调用的核心
？：未分配核心：加载地图等多核行为会调用的核心
P为大核心 E为小核心
0P  :[✓][✕][？]
1P  :[✓][✕][？]
2E  :[✓][✕][？]
快捷操作
[✓全部P核心][✓全部E核心]
[✕全部P核心][✕全部E核心]
[？全部P核心][？全部E核心]
[应用更改]
```

## 系统支持与边界

- Windows 10+/Windows Server 2016+，64 位：用 `GetSystemCpuSetInformation` 的 EfficiencyClass 判断性能等级，用 `SetThreadGroupAffinity` 硬绑定。当前版本只使用初始化线程所在 processor group 中原本允许的 CPU，不支持跨 group 迁移；一般 <=64 逻辑 CPU 的桌面机器只有一个 group。
- Linux：用 `sched_setaffinity(0, ...)` 设置当前线程；Intel 混合架构优先读取 `cpu_core/cpus` 和 `cpu_atom/cpus`，否则读取 `cpu_capacity`。文件不存在/性能等级相同则不猜测，可用 explicit。兼容原始 taskset 范围；容器 cpuset 等限制由内核执行，读回不一致则恢复。
- macOS 等其他系统不实现绑核，会提示并继续启动。
- 在首个 tick 才绑定，以便启动阶段已有工作线程不受影响。不过 **Linux 会让之后新建的线程继承创建者的亲和性**；本模组没有拦截其他模组的线程创建。公共服务在绑核前保留原始允许范围，让之后创建的单人世界服务端仍能独立选核。Linux 对工作线程特别敏感的整合包应实测吞吐量。
- 单核固定可减少迁移，也可能限制调度弹性；不保证提高 FPS/TPS。默认全部大核更适合先验证，再按需给多实例分核。
- 自动识别依赖操作系统/固件正确报告；识别失败请查日志并手动配置。

实现依据：[Microsoft CPU set 字段定义](https://learn.microsoft.com/en-us/windows/win32/api/winnt/ns-winnt-system_cpu_set_information)、[Windows 线程分组亲和性](https://learn.microsoft.com/en-us/windows/win32/api/processtopologyapi/nf-processtopologyapi-setthreadgroupaffinity)、[Linux Intel 混合 PMU 源码](https://github.com/torvalds/linux/blob/master/arch/x86/events/intel/core.c)。
