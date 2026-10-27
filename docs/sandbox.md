# 容器 / 沙盒选型（可选增强）

> 前提：**工作区不依赖容器**。文件读写、目录、查找、搜索、编辑这些都在
> `core::workspace` 里独立实现（见 D25）。容器只解决一件事：**跑命令**（构建、测试、脚本）。
>
> 状态：选型分析完成，实现未开始。挂到 P7。

---

## 1. 为什么不做成"必须有容器"

| 不做 | 理由 |
|---|---|
| 把 bash 当成核心能力 | 没有沙盒的设备（部分 Android、部分受限内核）会直接不可用；工作区工具本来就够用 |
| 在应用内静默下载 rootfs | 几百 MB 的下载 + 解包，失败率高，且用户不知道发生了什么 |
| 依赖 root / Magisk | 平板、工作机、非 root 用户的绝大多数 |

所以：**容器是"用户显式开通的可选能力"**，没开通时工具置灰并说明原因，不假装能跑。

---

## 2. 候选方案与许可

| 方案 | 许可 | 是否需要 root | 优点 | 代价 |
|---|---|---|---|---|
| **PRoot**（proot-me/PRoot） | `GPL-2.0-or-later` | 否 | 成熟、Termux 生态验证过、ptrace 实现 chroot/bind/binfmt | 每次 syscall 走 ptrace，重负载明显慢；线程/fork 多时开销大 |
| **proot-distro**（termux） | `GPL-3.0-only` | 否 | 一条命令装发行版、支持 OCI 镜像、备份/恢复 | 需要 Python 运行时；glibc/bionic 混用坑多 |
| **Android AVF**（pKVM 虚拟机） | 系统特性，无第三方代码 | 否 | 真内核、无翻译开销、隔离好 | 需 Android 13+ 且设备支持；API 复杂；不是所有机型有 |
| **iSH** | `GPL-3.0` | 否 | iOS 侧的成熟方案 | 与 Android 无关，只能作参考 |
| **bubblewrap** | `LGPL-2.1` | 否 | 轻量、无 ptrace | 需要 user namespaces，Android 上通常不可用 |

**结论（第一版）**：Android 用 **PRoot + 自管 rootfs（Alpine）**。
不直接依赖 proot-distro 的发行版管理（避免把 Python 运行时也塞进包里），
但它的 rootfs 布局、绑定挂载参数、备份思路可以直接照抄。

**第二条路径**：设备支持 AVF 时，提供"原生虚拟机"模式。它没有许可问题、性能也更好，
但可用性取决于机型，所以是增强而不是基线。

---

## 2.5 平台矩阵（定稿）

| 平台 | 容器 | 原生命令 | 密钥保护 | 是否需要凭据代理 |
|---|---|---|---|---|
| Android | PRoot + 自管 Alpine rootfs | 不可用（必须进容器） | Keystore（StrongBox 优先） | **需要**（PRoot 不是边界） |
| Linux 无桌面 | rootless podman 为主；nspawn / bwrap 备用 | 可选，默认关（等同用户 shell 权限） | systemd-creds（TPM2/host key） | **不需要** |
| Windows | 不做容器 | 可选，默认关 | DPAPI | **不需要** |

Linux 上 PRoot 没有必要：命名空间是真的，伪 chroot 只会更慢更弱。

## 3. 许可影响（这是切换项目许可的直接原因）

- PRoot 是 `GPL-2.0-or-later`，proot-distro 是 `GPL-3.0-only`。
- 把 PRoot 二进制放进 APK = **分发 GPL 组件**。GPLv2-or-later 可以升级到 GPLv3，
  所以整体取 **GPL-3.0-only** 最干净（见 D15），不必逐文件论证"聚合还是衍生"。
- 集成方式是**进程边界**：`execve` 调 proot，不做动态链接、不 include 头文件。
  即便如此，分发包仍要满足 GPL（提供对应源码、保留许可与声明），所以直接整体 GPL 更省心。
- **绝对不能**把 kelivo（AGPL-3.0）的代码拿进来：AGPL 与 GPL 不兼容。

---

## 4. 实现计划（P7）

| 步 | 内容 | 验收 |
|---|---|---|
| 1 | 能力探测：ABI、`/proc` 可读性、ptrace 是否被 SELinux 拦、可用存储空间 | 探测结果能在 UI 上解释"为什么不可用" |
| 2 | rootfs 管理：下载 → 校验（sha256 + 大小）→ 解包到 app-private → 版本记录 | 断网/空间不足时给明确错误，不留半截目录 |
| 3 | 启动器：`proot -r <rootfs> -0 -w / -b /dev -b /proc -b /sys /bin/sh`，环境变量白名单 | 能跑 `apk --version`；退出码透传 |
| 4 | 工作区挂载：只绑定用户选定的工作区目录（读写），不绑 `/sdcard` 全盘 | 容器内 `rm -rf` 不会碰到工作区外的文件 |
| 5 | 工具接线：`bash` 工具仅在容器就绪时出现；命令超时、输出截断（沿用工作区的三级截断 + `force_full`） | 超时不挂死；大输出不会打爆上下文 |
| 6 | 退出与清理：进程组回收、残留 mount 检查 | 反复进出 20 次不泄漏进程 |

**实现状态（D165）**：第 2 步（rootfs 下载 → 校验 → 解包 → 版本记录）与第 3 步的启动器参数
（`proot -0 -r <rootfs> -b /dev -b /proc -b /sys -w /root`，加上 `LD_LIBRARY_PATH` / `PROOT_TMP_DIR`）
已经落在 `ui/EnvirRepository.kt` 与 `ui/EnvirScreen.kt`，界面里还带一个自检按钮能就地跑一次。
**第 4 步（工作区挂载）与第 5 步（工具接线）已经做了**（D172）：工作区只绑到
`/root/workspace` 一个目录（读写），工具是 `WorkSpace-EnvirShell`，门控 `ContainerExec` 默认关。

**还没做**：第 1 步能力探测（`/proc` 可读性、ptrace 是否被拦、剩余空间）与第 6 步的
"反复进出 20 次不泄漏进程"验收；容器里的网络（DNS / `resolv.conf`）也没接。布局与理由见 D165。

**已知死穴（必须先说清）**：PRoot 不能嵌套运行。我们自己的开发沙箱就是 PRoot，
所以"在沙箱里测 PRoot"这条路走不通——容器相关测试只能在真机/CI 的普通容器里跑，
或者干脆做成手动验收清单。这一点会影响 P7 的验收方式，提前记下来。

---

## 5. 密钥怎么办

容器里跑陌生代码，密钥必须按 `docs/secrets.md` 的方案走：**长期令牌不进容器**，
git / gh 通过凭据代理（credential helper / 透明代理 / SSH agent 转发）现用现取，只给短时能力。

## 6. 参考

- PRoot：<https://github.com/proot-me/PRoot>（GPL-2.0-or-later）
- proot-distro：<https://github.com/termux/proot-distro>（GPL-3.0-only）
- Termux wiki：<https://wiki.termux.com/wiki/PRoot>
- Android AVF：Android 平台的受保护 KVM 虚拟机框架（系统能力，无第三方许可问题）
