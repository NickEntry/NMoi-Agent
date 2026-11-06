//! 两个"起真进程"的工具：
//!
//! - `System-Shell`（D159）：平台的**原生命令**，跑在发起方的身份下；
//! - `WorkSpace-EnvirShell`（D172）：**隔离环境里**的命令（Android 上是 proot + Ubuntu rootfs）。
//!
//! 两者共用同一份"起进程、读两个流、超时杀进程组、输出封顶"的实现（[`execute`]），
//! 差别只在命令行怎么拼、以及门控用的是哪一项能力。
//!
//! 为什么它不在 `tools.rs` 里：那个文件是"形状与门控"，而这个工具要做的事是起进程、
//! 读两个管道、超时杀进程组——真出事的地方。混在一起会让人以为工具层只是拼 JSON。
//!
//! # 它有多危险，界面上就要写多清楚
//!
//! 命令跑在**发起方的身份**下：Android 上就是 App 自己（能碰这个 App 能碰的一切），
//! Linux 上是当前用户。所以：
//!
//! - 它是 [`Capability::NativeShell`] 门控的，默认关（`AssistantPolicy::locked_down`）；
//! - 描述第一句就写"没有用户明确允许时不要调用"；
//! - 结果里同时给 `exit_code` / `stdout` / `stderr` / `duration_ms`，界面上照原样摆出来——
//!   用户要能看见"它到底跑了什么、回了什么"，而不是一句"执行成功"。
//!
//! # 几个具体的坑（都踩过或想过）
//!
//! 1. **超时要杀整个进程组**。只 `kill` 子进程，`sleep 1000 &` 会留在后台，
//!    下一次超时再留一个，久了就是一堆僵尸。所以 spawn 时 `process_group(0)`，
//!    超时用 `kill(-pgid, SIGKILL)`。
//! 2. **stdout 与 stderr 必须同时读**。只读一个，另一个管道写满 64 KB 就卡死，
//!    表现是"命令跑不完、也不超时"。
//! 3. **输出要有上限**。`yes` 这种命令一秒能吐几百兆，读进内存就是 OOM。
//!    每个流封顶 64 KiB，超了继续读但丢弃，并在结果里标 `truncated`。
//! 4. **退出码可能是信号**。`exit_code` 用 `Option<i32>`，被信号杀掉时是 `null`，
//!    别写成 0（那会被当成成功）。

use std::io::Read;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::mpsc;
use std::time::{Duration, Instant};

use serde_json::{json, Value};

use crate::policy::Capability;
use crate::tools::{required_str, Lane, Tool, ToolContext, ToolOutcome};

/// 每个流最多留这么多字节。够看完报错，又不会把内存撑爆。
const STREAM_CAP: usize = 64 * 1024;

/// 默认 30 秒：够绝大多数事。上限 10 小时（用户 2026-11-02 说的 1–36000 秒）。
///
/// **`0` 不是「不超时」**：不超时只有一个写法——字符串 `"inf"`。
/// 两个写法并存的话，"没设"和"设成不超时"在日志里长得一样。
const DEFAULT_TIMEOUT_MS: u64 = 30_000;
const MIN_TIMEOUT_MS: u64 = 1_000;
const MAX_TIMEOUT_MS: u64 = 36_000_000;

/// 解析 `timeout_ms`：`None` = 不设闹钟（`"inf"`）。
///
/// 越界**报错**，不静默夹到边界上：模型以为"给了 300 秒"，实际跑 5 秒就被杀，
/// 而结果里只有一句 `timed_out: true`——这种错查起来比报错贵得多。
fn timeout_of(args: &Value) -> Result<Option<Duration>, ToolOutcome> {
    let Some(raw) = args.get("timeout_ms") else {
        return Ok(Some(Duration::from_millis(DEFAULT_TIMEOUT_MS)));
    };
    if let Some(text) = raw.as_str() {
        if text.eq_ignore_ascii_case("inf") {
            return Ok(None);
        }
        return Err(ToolOutcome::failed(format!(
            "timeout_ms 只认整数毫秒（{MIN_TIMEOUT_MS}–{MAX_TIMEOUT_MS}）或字符串 \"inf\"，拿到的是 \"{text}\""
        )));
    }
    let Some(ms) = raw.as_u64() else {
        return Err(ToolOutcome::failed(format!(
            "timeout_ms 只认整数毫秒（{MIN_TIMEOUT_MS}–{MAX_TIMEOUT_MS}）或字符串 \"inf\""
        )));
    };
    if !(MIN_TIMEOUT_MS..=MAX_TIMEOUT_MS).contains(&ms) {
        return Err(ToolOutcome::failed(format!(
            "timeout_ms 只能是 {MIN_TIMEOUT_MS}–{MAX_TIMEOUT_MS} 毫秒（1 秒 – 10 小时），\
             或者字符串 \"inf\" 表示不超时；{ms} 不在这个范围里——0 不是「不超时」"
        )));
    }
    Ok(Some(Duration::from_millis(ms)))
}

pub struct SystemShellTool;

impl Tool for SystemShellTool {
    fn lane(&self) -> Lane {
        Lane::Process
    }

    fn name(&self) -> &str {
        // 用户 2026-10-27 定的名字。连字符在 OpenAI / Anthropic / Gemini 的函数名规则里都合法。
        "System-Shell"
    }

    fn description(&self) -> &str {
        concat!(
            "在工作区目录里跑一条平台原生命令。**没有用户明确允许时不要调用**；",
            "一般情况优先用 read_file / write_file / edit_file / list_dir / find_files / search。\n",
            "命令跑在 App 自己的身份下：它能碰到的文件，这条命令都能碰。",
            "不要用它做删除目录、改系统设置、装服务、发网络请求这类收不回来的事。\n",
            "Linux/Android 上是 `/bin/sh -c`（Android 没有 bash，coreutils 是 toybox 的裁剪版）；",
            "Windows 上是 `powershell -NoProfile -Command`——那是本机原生命令，谨慎操作。\n",
            "返回 JSON：exit_code（被信号杀掉时是 null）、stdout、stderr、truncated、duration_ms、timed_out。",
            "每个流最多 64 KiB；默认超时 30 秒，超时会杀掉整个进程组。",
            "timeout_ms 可以给 1000–36000000（毫秒）；要「一直跑到它自己结束」就写字符串 \"inf\"（0 不是不超时）。",
            "用户点「关闭」时结果里 killed_by_user 为 true、timed_out 为 false——那是用户停的，不是命令失败。"
        )
    }

    fn schema(&self) -> Value {
        json!({
            "type": "object",
            "properties": {
                "command": {
                    "type": "string",
                    "description": "要执行的命令行。按平台写：Unix 用 sh 语法，Windows 用 PowerShell 语法"
                },
                "cwd": {
                    "type": "string",
                    "description": "相对工作区根目录的工作目录，默认就是根目录；不能指到工作区外"
                },
                "timeout_ms": {
                    "type": "integer",
                    "description": "超时毫秒数：1000–36000000，默认 30000；要一直跑就写字符串 \"inf\"（0 不合法）"
                }
            },
            "required": ["command"]
        })
    }

    fn capability(&self) -> Capability {
        Capability::NativeShell
    }

    fn invoke(&self, args: &Value, ctx: &ToolContext<'_>) -> ToolOutcome {
        // 门控在最前面：没开这一项，连工作区都不解析（tools.rs 的三条硬规矩之一）。
        let workspace = match ctx.gate(self.capability()) {
            Ok(workspace) => workspace,
            Err(outcome) => return outcome,
        };
        let command = match required_str(args, "command") {
            Ok(command) => command,
            Err(outcome) => return outcome,
        };
        if command.trim().is_empty() {
            return ToolOutcome::failed("command 是空的");
        }
        if command.contains('\0') {
            return ToolOutcome::failed("command 里有 NUL 字节");
        }

        // 工作目录：必须在工作区里。命令本身能不能跑出去是它自己的事，
        // 但"起点"不该由模型随手指定到别处。
        let cwd = match args.get("cwd").and_then(Value::as_str) {
            Some(relative) => match workspace.resolve(relative) {
                Ok(path) => path,
                Err(error) => return ToolOutcome::failed(error.to_string()),
            },
            None => workspace.root().to_path_buf(),
        };
        if !cwd.is_dir() {
            return ToolOutcome::failed(format!("工作目录不存在：{}", cwd.display()));
        }

        let timeout = match timeout_of(args) {
            Ok(timeout) => timeout,
            Err(outcome) => return outcome,
        };

        match run(&command, &cwd, timeout, ctx.call) {
            Ok(completed) => ToolOutcome::Ok {
                text: completed.to_json(&command).to_string(),
                truncated: completed.truncated,
            },
            Err(message) => ToolOutcome::failed(message),
        }
    }
}

/// 容器里的 `PATH`。**必须显式给**：不给的话容器里的 `sh` 继承的是宿主的 PATH
/// （Android 上是 `/product/bin:/system/bin:…`），那些目录在容器里不存在，
/// 于是 `head`/`uname`/`ls` 全 "not found"、退出码 127——真机上撞过。
const GUEST_PATH: &str = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin";

/// 容器里工作区的挂载点（D233：回到 `/workspace`）。
///
/// 这是**用户的工作区**——可复用、可绑给助手、天生共享。
/// 会话自己的东西（附件、工具产物）不在这儿，那是 `/chat`（`sessions/<id>/chat`）。
pub const GUEST_WORKSPACE: &str = "/workspace";

/// 跑容器需要的那几样东西的**宿主机路径**。
///
/// core 不知道 Android 的目录长什么样（`filesDir` / `nativeLibraryDir` 都是运行时才知道的），
/// 所以这些值一律由调用方给：FFI 从 `turn_run` 的配置里读，Kotlin 那边现取。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct EnvirSpec {
    /// `proot` 可执行文件（Android 上是 APK 的 `lib/arm64/libnmoi_proot.so`）。
    pub proot: PathBuf,
    /// proot 自己的静态 loader（没有它任何动态链接的 guest 程序都起不来，D169）。
    pub loader: PathBuf,
    /// 放 `libtalloc.so.2` / `libandroid-shmem.so` 的目录（proot 的 `NEEDED` 从这儿找）。
    pub lib_dir: PathBuf,
    /// 解包好的 rootfs 根目录。
    pub rootfs: PathBuf,
    /// proot 自己的临时目录（要可写；默认往 `/tmp` 走，Android 上不可写）。
    pub tmp: PathBuf,
    /// 绑到容器 `/etc/resolv.conf` 的**宿主机**文件（D173）。
    ///
    /// proot 不建 netns：容器里的进程和宿主同一套网络栈、同一个 uid 的权限，所以"能不能联网"
    /// 其实只剩"DNS 有没有"。Ubuntu Base 里的 `/etc/resolv.conf` 是空的/指向不存在的
    /// systemd-resolved，没有这一条 `apt update` 会卡在解析域名上。
    ///
    /// 可选：没有（离线、或设备当前没有 DNS）就不绑——容器照跑，只是解析不出域名。
    pub resolv: Option<PathBuf>,
    /// 这个会话自己的暂存目录（`sessions/<id>/chat`），绑到容器里的 `/chat`（D234）。
    ///
    /// 与工作区是两件事：工作区是用户建的、可复用、天生共享，挂在 `/workspace`；
    /// `/chat` 只属于这一条会话，工具产物与附件落在这儿。模型要看就自己用命令读。
    ///
    /// 可选：没有会话上下文（或目录建不出来）就不绑，容器照跑。
    pub chat: Option<PathBuf>,
    /// 这个会话的附件目录（`sessions/<id>/images`），绑到 `/chat/images`。
    ///
    /// 单独一条是因为附件不挪地方（D216 的路径写进了库，也写进了存储页），
    /// 而模型要能在容器里读到它——两条绑定比一次数据迁移便宜得多。
    pub chat_images: Option<PathBuf>,
}

impl EnvirSpec {
    /// 拼出一条"进容器跑 `command`"的命令行。
    ///
    /// 参数按真机验证过的那一套写（D169 / D171）：
    /// `-0` 假装 root、`-r rootfs`、绑 `/dev` `/proc` `/sys`、把工作区绑到 [GUEST_WORKSPACE]、
    /// 有 [EnvirSpec::resolv] 就一起绑到 `/etc/resolv.conf`、`-w` 到工作区，
    /// 最后 `/bin/sh -c <command>`。
    pub fn command(&self, workspace: &Path, guest_cwd: Option<&str>, command: &str) -> Command {
        let mut cmd = Command::new(&self.proot);
        cmd.arg("-0")
            .arg("-r")
            .arg(&self.rootfs)
            .arg("-b")
            .arg("/dev")
            .arg("-b")
            .arg("/proc")
            .arg("-b")
            .arg("/sys")
            .arg("-b")
            .arg(format!("{}:{}", workspace.display(), GUEST_WORKSPACE));
        // DNS（D173）：只在拿到一份真的写了 `nameserver` 的文件时才绑。
        // 绑一个空文件比不绑更糟——它会把 rootfs 里那份原样的 `resolv.conf` 盖掉，
        // 之后再排查就少了一条线索。
        if let Some(resolv) = &self.resolv {
            cmd.arg("-b")
                .arg(format!("{}:/etc/resolv.conf", resolv.display()));
        }
        // 会话自己的暂存目录（D234）：`/chat`，附件在 `/chat/images`。
        // 顺序要紧：先绑 `/chat` 再绑它里面的 `images`，否则第二条的目标路径在 guest 里还不存在。
        if let Some(chat) = &self.chat {
            cmd.arg("-b").arg(format!("{}:/chat", chat.display()));
            if let Some(images) = &self.chat_images {
                cmd.arg("-b")
                    .arg(format!("{}:/chat/images", images.display()));
            }
        }
        // 工作目录：`-w` 只认一个，模型给的相对目录就接在后面（已经过词法校验，不含 `..`）。
        let cwd = match guest_cwd {
            Some(relative) if !relative.is_empty() => format!("{GUEST_WORKSPACE}/{relative}"),
            _ => GUEST_WORKSPACE.to_string(),
        };
        cmd.arg("-w")
            .arg(&cwd)
            .arg("/bin/sh")
            .arg("-c")
            .arg(command);
        cmd.env("LD_LIBRARY_PATH", &self.lib_dir)
            .env("PROOT_LOADER", &self.loader)
            .env("PROOT_TMP_DIR", &self.tmp)
            // SELinux 拦 ptrace 时的老办法；设了不亏（真机上首次就是这么跑通的）。
            .env("PROOT_NO_SECCOMP", "1")
            .env("PATH", GUEST_PATH)
            .env("HOME", "/root")
            .env("TERM", "xterm-256color");
        cmd
    }
}

/// 模型给的那个相对目录能不能用：**只做词法检查**，不碰文件系统。
///
/// 它在容器里，宿主机上不存在这个路径，没法也不该用 `canonicalize`。
/// 规则只有两条：不许绝对路径、不许任何一段是 `..`。
fn guest_cwd_is_safe(relative: &str) -> bool {
    if relative.starts_with('/') {
        return false;
    }
    relative
        .split('/')
        .all(|part| part != ".." && !part.contains('\0'))
}

/// `WorkSpace-EnvirShell`（D172）：在隔离环境里跑一条命令。
///
/// 与 `System-Shell` 的区别只有一条，但很要紧：**它跑在容器里**。
/// 容器里动 `/`、`apt install`、`rm -rf /usr` 都伤不到宿主机的 App 数据；
/// 唯一共享的是被绑进来的工作区（`GUEST_WORKSPACE`）。
pub struct EnvirShellTool;

impl Tool for EnvirShellTool {
    fn lane(&self) -> Lane {
        Lane::Process
    }

    fn name(&self) -> &str {
        "WorkSpace-EnvirShell"
    }

    fn description(&self) -> &str {
        concat!(
            "在隔离环境（容器里的 Ubuntu）里跑一条命令，起点是工作区（容器里是 /workspace）。",
            "**没有用户明确允许时不要调用**；读写文件优先用 read_file / write_file / edit_file。\n",
            "容器里只有工作区（/workspace）和这个会话自己的 /chat 是共享的：宿主机上别的地方它碰不到，",
            "容器里装的东西也不影响宿主。附件在 /chat/images 下，要看就自己用命令读。\n",
            "容器里**没有内核**：uname -r 显示的是手机内核，systemd / docker / iptables / 内核模块都用不了；",
            "proot 是 ptrace 模拟，比原生慢。\n",
            "返回 JSON：exit_code（被信号杀掉时是 null）、stdout、stderr、truncated、duration_ms、timed_out。",
            "每个流最多 64 KiB；默认超时 30 秒，超时会杀掉整个进程组（包括容器里的进程）。",
            "timeout_ms 可以给 1000–36000000（毫秒）；要一直跑就写字符串 \"inf\"。",
            "用户点「关闭」时结果里 killed_by_user 为 true、timed_out 为 false。"
        )
    }

    fn schema(&self) -> Value {
        json!({
            "type": "object",
            "properties": {
                "command": {
                    "type": "string",
                    "description": "要执行的命令行（容器里的 /bin/sh 语法）"
                },
                "cwd": {
                    "type": "string",
                    "description": "相对工作区的目录（容器里就是 /workspace 下），默认工作区根；不许写绝对路径或 .."
                },
                "timeout_ms": {
                    "type": "integer",
                    "description": "超时毫秒数：1000–36000000，默认 30000；要一直跑就写字符串 \"inf\"（0 不合法）"
                }
            },
            "required": ["command"]
        })
    }

    fn capability(&self) -> Capability {
        Capability::ContainerExec
    }

    fn invoke(&self, args: &Value, ctx: &ToolContext<'_>) -> ToolOutcome {
        // 门控在最前面：没开 `ContainerExec` 连工作区都不解析。
        let workspace = match ctx.gate(self.capability()) {
            Ok(workspace) => workspace,
            Err(outcome) => return outcome,
        };
        // 开了门控但没有运行时坐标 = 界面说"能用"其实没装好。**明说**，不要给一个看不懂的起进程失败。
        let envir =
            match ctx.envir {
                Some(spec) => spec,
                None => return ToolOutcome::failed(
                    "隔离环境没就绪：设置 → 工具与工作区 → 隔离环境里先装一次（缺运行时的路径）",
                ),
            };
        let command = match required_str(args, "command") {
            Ok(command) => command,
            Err(outcome) => return outcome,
        };
        if command.trim().is_empty() {
            return ToolOutcome::failed("command 是空的");
        }
        if command.contains('\0') {
            return ToolOutcome::failed("command 里有 NUL 字节");
        }
        let cwd_arg = args.get("cwd").and_then(Value::as_str);
        if let Some(relative) = cwd_arg {
            if !guest_cwd_is_safe(relative) {
                return ToolOutcome::failed(
                    "cwd 只能是工作区下的相对路径（不许绝对路径、不许 ..）",
                );
            }
        }
        let timeout = match timeout_of(args) {
            Ok(timeout) => timeout,
            Err(outcome) => return outcome,
        };

        let cmd = envir.command(workspace.root(), cwd_arg, &command);
        match execute(cmd, timeout, ctx.call) {
            Ok(completed) => ToolOutcome::Ok {
                text: completed.to_json(&command).to_string(),
                truncated: completed.truncated,
            },
            Err(message) => ToolOutcome::failed(message),
        }
    }
}

struct Completed {
    exit_code: Option<i32>,
    stdout: String,
    stderr: String,
    truncated: bool,
    timed_out: bool,
    /// 用户点了「关闭」。与 `timed_out` **互斥**：一个是用户停的，一个是闹钟停的，
    /// 合成一个字段的话模型分不清"该不该重试"。
    killed_by_user: bool,
    duration_ms: u64,
}

impl Completed {
    fn to_json(&self, command: &str) -> Value {
        let mut value = json!({
            "command": command,
            "exit_code": self.exit_code,
            "stdout": self.stdout,
            "stderr": self.stderr,
            "truncated": self.truncated,
            "timed_out": self.timed_out,
            "killed_by_user": self.killed_by_user,
            "duration_ms": self.duration_ms,
        });
        if self.killed_by_user {
            // 给模型看的那一行：只说 `exit_code: null` 它会当成"命令自己挂了"，
            // 然后照着同一条命令再试一次。
            value["note"] = json!("被用户关闭（不是命令自己退出）");
        }
        value
    }
}

/// 起进程、读两个流、按超时收工。
///
/// 结构是"两个读线程 + 主线程 `try_wait` 轮询"：这样两个管道同时有人读（不会互相堵），
/// 主线程又能按时超时。用 `wait_with_output` 就没有超时可言了。
fn run(
    command: &str,
    cwd: &Path,
    timeout: Option<Duration>,
    call: Option<&crate::kills::CallHandle>,
) -> Result<Completed, String> {
    let mut cmd = platform_command(command);
    cmd.current_dir(cwd);
    execute(cmd, timeout, call)
}

/// 起一个已经拼好的命令，读两个流、按超时（或用户点关闭）收工。
///
/// `System-Shell` 与 `WorkSpace-EnvirShell` 走同一条路：**超时/关闭/封顶/杀进程组只有一份实现**，
/// 否则"容器里的命令超时"和"原生命令超时"迟早会不一样，而那种不一样没人测得到。
fn execute(
    mut cmd: Command,
    timeout: Option<Duration>,
    call: Option<&crate::kills::CallHandle>,
) -> Result<Completed, String> {
    let label = cmd.get_program().to_string_lossy().into_owned();
    cmd.stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());

    #[cfg(unix)]
    {
        use std::os::unix::process::CommandExt;
        // 自成一组：超时的时候能一次杀掉它和它拉起来的所有子进程。
        // 容器里杀的是 proot 这一组，它拉起来的 tracee 也在组里。
        cmd.process_group(0);
    }

    let started = Instant::now();
    let mut child = cmd
        .spawn()
        .map_err(|error| format!("起不了命令（{label}）：{error}"))?;

    let (stdout, stderr, out_rx, err_rx) = (
        child.stdout.take().ok_or("拿不到 stdout")?,
        child.stderr.take().ok_or("拿不到 stderr")?,
        None::<()>,
        None::<()>,
    );
    let _ = (out_rx, err_rx);

    let (out_tx, out_rx) = mpsc::channel();
    let (err_tx, err_rx) = mpsc::channel();
    // 实时输出（D264）：两个流都往同一份缓冲里追加，界面每 100 ms 读一次。
    // 结果那份仍然是分开的 stdout / stderr——这里只是"它现在在吐什么"。
    let live = call.map(|call| call.live());
    drain(stdout, out_tx, live.clone());
    drain(stderr, err_tx, live);

    let mut timed_out = false;
    let mut killed_by_user = false;
    let status = loop {
        match child.try_wait() {
            Ok(Some(status)) => break Some(status),
            Ok(None) => {}
            Err(error) => return Err(format!("等命令结束时出错：{error}")),
        }
        // 手动关闭（0.15ak）：每 20 ms 看一眼。**先看关闭再看超时**——
        // 两个条件同一拍成立时，用户按下的那一下更该被记住。
        if call.map(|call| call.cancelled()).unwrap_or(false) {
            killed_by_user = true;
            kill_tree(&mut child);
            break None;
        }
        if let Some(limit) = timeout {
            if started.elapsed() >= limit {
                timed_out = true;
                kill_tree(&mut child);
                break None;
            }
        }
        std::thread::sleep(Duration::from_millis(20));
    };

    // 超时被杀之后，子进程可能还有最后一口气；等它收尸（最多再等 2 秒）。
    let status = match status {
        Some(status) => Some(status),
        None => {
            let deadline = Instant::now() + Duration::from_secs(2);
            loop {
                match child.try_wait() {
                    Ok(Some(status)) => break Some(status),
                    Ok(None) if Instant::now() < deadline => {
                        std::thread::sleep(Duration::from_millis(20))
                    }
                    _ => break None,
                }
            }
        }
    };

    let (out, out_cut) = out_rx
        .recv_timeout(Duration::from_secs(2))
        .unwrap_or_default();
    let (err, err_cut) = err_rx
        .recv_timeout(Duration::from_secs(2))
        .unwrap_or_default();

    Ok(Completed {
        exit_code: status.and_then(|status| status.code()),
        stdout: out,
        stderr: err,
        truncated: out_cut || err_cut,
        timed_out,
        killed_by_user,
        duration_ms: started.elapsed().as_millis() as u64,
    })
}

/// 读一个管道，最多留 `STREAM_CAP` 字节；超出的照读不误（不然对方写满就卡住），只是丢掉。
///
/// 同时把**每一段**追加给 `live`（实时输出）。`carry` 存着"上一个读到的半个 UTF-8 字符"：
/// 一次 `read` 可能正好切在一个汉字中间，直接 `from_utf8_lossy` 会在界面上闪一个 ``。
fn drain<R: Read + Send + 'static>(
    mut reader: R,
    tx: mpsc::Sender<(String, bool)>,
    live: Option<crate::kills::LiveBuffer>,
) {
    std::thread::spawn(move || {
        let mut kept: Vec<u8> = Vec::new();
        let mut cut = false;
        let mut carry: Vec<u8> = Vec::new();
        let mut chunk = [0u8; 8192];
        loop {
            match reader.read(&mut chunk) {
                Ok(0) => break,
                Ok(count) => {
                    if let Some(live) = &live {
                        carry.extend_from_slice(&chunk[..count]);
                        let valid = match std::str::from_utf8(&carry) {
                            Ok(_) => carry.len(),
                            Err(error) => error.valid_up_to(),
                        };
                        if valid > 0 {
                            let piece = String::from_utf8_lossy(&carry[..valid]).into_owned();
                            live.push(&piece);
                            carry.drain(..valid);
                        }
                        // 一直凑不成一个字符（二进制输出）：按坏字节吐掉，别让 carry 长下去。
                        if carry.len() > 8 {
                            let piece = String::from_utf8_lossy(&carry).into_owned();
                            live.push(&piece);
                            carry.clear();
                        }
                    }
                    let room = STREAM_CAP.saturating_sub(kept.len());
                    if room == 0 {
                        cut = true;
                        continue;
                    }
                    kept.extend_from_slice(&chunk[..count.min(room)]);
                    if count > room {
                        cut = true;
                    }
                }
                Err(_) => break,
            }
        }
        let _ = tx.send((String::from_utf8_lossy(&kept).into_owned(), cut));
    });
}

#[cfg(unix)]
fn platform_command(command: &str) -> Command {
    // Android 没有 /bin/sh，但一定有 /system/bin/sh。
    let shell = if std::path::Path::new("/system/bin/sh").exists() {
        "/system/bin/sh"
    } else {
        "/bin/sh"
    };
    let mut cmd = Command::new(shell);
    cmd.arg("-c").arg(command);
    cmd
}

#[cfg(windows)]
fn platform_command(command: &str) -> Command {
    let mut cmd = Command::new("powershell");
    cmd.args(["-NoProfile", "-NonInteractive", "-Command", command]);
    cmd
}

/// 杀整个进程组；不行再退回落单杀。
#[cfg(unix)]
fn kill_tree(child: &mut std::process::Child) {
    let pid = child.id() as i32;
    // SAFETY: 只是发一个信号给一个已知的进程组；失败也没关系（进程可能刚好自己退了）。
    unsafe {
        libc::kill(-pid, libc::SIGKILL);
    }
    let _ = child.kill();
    let _ = child.wait();
}

#[cfg(windows)]
fn kill_tree(child: &mut std::process::Child) {
    // Windows 上进程树要靠 taskkill /T；拿不到就退回落单杀。
    let _ = Command::new("taskkill")
        .args(["/PID", &child.id().to_string(), "/T", "/F"])
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .status();
    let _ = child.kill();
    let _ = child.wait();
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::policy::{AssistantPolicy, DenyReason};
    use crate::workspace::Workspace;

    fn context<'a>(policy: &'a AssistantPolicy, workspace: &'a Workspace) -> ToolContext<'a> {
        ToolContext::new(policy, Some(workspace))
    }

    fn policy() -> AssistantPolicy {
        // 没有工作区绑定时 `check(NativeShell)` 会回 `no_workspace`——那是门控的正确行为，
        // 所以这里的"开着"要连绑定一起给。
        AssistantPolicy::locked_down()
            .with_binding(crate::policy::WorkspaceBinding::read_write("test-ws"))
            .enable_native_shell(true)
    }

    /// 测试用的命令：两个平台的壳不一样，**命令也必须分开写**——
    /// 用 POSIX 语法去测 PowerShell，测到的是"语法错误"而不是"工具能不能跑"。
    #[cfg(unix)]
    fn command_two_streams() -> &'static str {
        "echo out; echo err 1>&2; exit 3"
    }
    #[cfg(windows)]
    fn command_two_streams() -> &'static str {
        "Write-Output 'out'; [Console]::Error.WriteLine('err'); exit 3"
    }
    #[cfg(unix)]
    fn command_cat_marker() -> &'static str {
        "cat marker.txt"
    }
    #[cfg(windows)]
    fn command_cat_marker() -> &'static str {
        "Get-Content marker.txt"
    }
    #[cfg(unix)]
    fn command_sleep() -> &'static str {
        "sleep 30"
    }
    #[cfg(windows)]
    fn command_sleep() -> &'static str {
        "Start-Sleep -Seconds 30"
    }

    #[test]
    fn the_capability_gate_is_checked_before_anything_else() {
        let dir = tempfile::tempdir().unwrap();
        let workspace = Workspace::open(dir.path()).unwrap();
        let locked = AssistantPolicy::locked_down();
        let outcome = SystemShellTool.invoke(
            &json!({"command": "echo hi"}),
            &context(&locked, &workspace),
        );
        assert!(matches!(
            outcome,
            ToolOutcome::Denied {
                capability: Capability::NativeShell,
                ..
            }
        ));
    }

    #[test]
    fn without_a_workspace_there_is_no_place_to_start() {
        let locked = policy();
        let ctx = ToolContext::new(&locked, None);
        let outcome = SystemShellTool.invoke(&json!({"command": "echo hi"}), &ctx);
        assert!(matches!(
            outcome,
            ToolOutcome::Denied {
                reason: DenyReason::NoWorkspace,
                ..
            }
        ));
    }

    /// 实时输出（D264）：命令还在跑的时候，第一段就已经在缓冲里了。
    ///
    /// 判据是"跑完之前读到"——跑完之后再读一遍谁都会，那证明不了流式。
    #[cfg(unix)]
    #[test]
    fn live_output_arrives_while_the_command_is_still_running() {
        let dir = tempfile::tempdir().unwrap();
        let handle = crate::kills::begin("sess-live", "call-live", "System-Shell", "{}");
        let live = handle.live();
        let path = dir.path().to_path_buf();
        let worker = std::thread::spawn(move || {
            run(
                "echo 111; sleep 0.4; echo 222",
                &path,
                Some(Duration::from_secs(10)),
                Some(&handle),
            )
        });

        let deadline = Instant::now() + Duration::from_secs(5);
        let mut early = String::new();
        while Instant::now() < deadline {
            early = live.snapshot().0;
            if early.contains("111") {
                break;
            }
            std::thread::sleep(Duration::from_millis(10));
        }
        assert!(
            early.contains("111"),
            "第一段输出要在命令结束之前就能看到：{early:?}"
        );

        let done = worker.join().expect("线程没 panic").expect("命令要能跑完");
        assert!(done.stdout.contains("222"));
        assert!(live.snapshot().0.contains("222"), "第二段也要进缓冲");
    }

    #[test]
    fn runs_a_command_and_separates_the_two_streams() {
        let dir = tempfile::tempdir().unwrap();
        let workspace = Workspace::open(dir.path()).unwrap();
        let policy = policy();
        let outcome = SystemShellTool.invoke(
            &json!({"command": command_two_streams()}),
            &context(&policy, &workspace),
        );
        let text = outcome.text();
        let value: Value = serde_json::from_str(&text).expect("结果是 JSON");
        assert_eq!(value["exit_code"], json!(3));
        assert!(value["stdout"].as_str().unwrap().contains("out"));
        assert!(value["stderr"].as_str().unwrap().contains("err"));
        assert_eq!(value["timed_out"], json!(false));
    }

    #[test]
    fn the_working_directory_is_the_workspace_root() {
        let dir = tempfile::tempdir().unwrap();
        std::fs::write(dir.path().join("marker.txt"), "x").unwrap();
        let workspace = Workspace::open(dir.path()).unwrap();
        let policy = policy();
        let outcome = SystemShellTool.invoke(
            &json!({"command": command_cat_marker()}),
            &context(&policy, &workspace),
        );
        let text = outcome.text();
        let value: Value = serde_json::from_str(&text).unwrap();
        // Windows 的换行是 \r\n：断言的是"内容对不对"，不是"行尾是不是 LF"。
        assert_eq!(value["stdout"].as_str().unwrap().trim(), "x");
    }

    #[test]
    fn a_hanging_command_is_killed_at_the_timeout() {
        let dir = tempfile::tempdir().unwrap();
        let workspace = Workspace::open(dir.path()).unwrap();
        let policy = policy();
        let outcome = SystemShellTool.invoke(
            &json!({"command": command_sleep(), "timeout_ms": 1000}),
            &context(&policy, &workspace),
        );
        let value: Value = serde_json::from_str(&outcome.text()).unwrap();
        assert_eq!(value["timed_out"], json!(true));
        // 被信号杀掉时没有退出码；写成 0 会被当成成功。
        // Windows 上强杀 powershell 会得到 1（不是信号），所以那边只要求"不是 0"。
        #[cfg(unix)]
        assert_eq!(value["exit_code"], Value::Null);
        #[cfg(windows)]
        assert_ne!(value["exit_code"], json!(0));
    }

    #[test]
    fn a_cwd_outside_the_workspace_is_refused() {
        let dir = tempfile::tempdir().unwrap();
        let workspace = Workspace::open(dir.path()).unwrap();
        let policy = policy();
        let outcome = SystemShellTool.invoke(
            &json!({"command": "echo hi", "cwd": "../.."}),
            &context(&policy, &workspace),
        );
        assert!(!outcome.is_ok(), "越界的工作目录必须被拒");
    }

    // ------------------------------------------------------------------
    // WorkSpace-EnvirShell（D172）
    //
    // 这一组用一个**假 proot**：它把 `-c` 后面那段当真命令跑，别的参数一律忽略。
    // 验的是我们这一侧——命令怎么拼、环境变量给没给、门控、超时、输出封顶；
    // proot 本身是别人的活，真机上验（见 docs/verification.md V11）。
    // ------------------------------------------------------------------

    /// 假 proot：找到 `-c` 就把后面那一条当命令跑。
    #[cfg(unix)]
    fn fake_proot(dir: &Path) -> EnvirSpec {
        use std::io::Write;
        use std::os::unix::fs::PermissionsExt;

        let script = dir.join("fake-proot");
        let mut file = std::fs::File::create(&script).unwrap();
        writeln!(file, "#!/bin/sh").unwrap();
        writeln!(file, "while [ \"$#\" -gt 0 ]; do").unwrap();
        writeln!(
            file,
            "  if [ \"$1\" = \"-c\" ]; then shift; exec /bin/sh -c \"$1\"; fi"
        )
        .unwrap();
        writeln!(file, "  shift").unwrap();
        writeln!(file, "done").unwrap();
        writeln!(file, "exit 9").unwrap();
        // 显式关掉写句柄再 chmod：`exec` 一个"还被写打开"的文件会 ETXTBSY（os error 26），
        // 而 cargo test 是多线程的，别的线程 fork 时正好把这个 fd 带进子进程。
        // 这一条在 CI 上偶发过一次（`envir_shell_times_out_and_kills_the_group`）。
        drop(file);
        let mut perms = std::fs::metadata(&script).unwrap().permissions();
        perms.set_mode(0o755);
        std::fs::set_permissions(&script, perms).unwrap();

        EnvirSpec {
            proot: script,
            loader: dir.join("loader"),
            lib_dir: dir.join("lib"),
            rootfs: dir.join("rootfs"),
            tmp: dir.join("tmp"),
            resolv: None,
            chat: None,
            chat_images: None,
        }
    }

    /// `resolv` 是**可选**的，而且只在有时才绑（D173）。
    ///
    /// 这条测的是拼命令行这一侧：`-b <file>:/etc/resolv.conf` 出现在工作区那条之后、
    /// `-w` 之前。顺序不重要，**"有没有"很重要**——绑一个空文件会把 rootfs 里那份原样的
    /// `resolv.conf` 盖掉，排障时少一条线索，所以没有 DNS 时必须完全不绑。
    #[test]
    fn envir_binds_resolv_only_when_it_is_given() {
        let dir = Path::new("/tmp/nmoi-envir-spec");
        let workspace = Path::new("/tmp/nmoi-envir-ws");
        let base = EnvirSpec {
            proot: dir.join("proot"),
            loader: dir.join("loader"),
            lib_dir: dir.join("lib"),
            rootfs: dir.join("rootfs"),
            tmp: dir.join("tmp"),
            resolv: None,
            chat: None,
            chat_images: None,
        };

        let plain: Vec<String> = base
            .command(workspace, None, "true")
            .get_args()
            .map(|arg| arg.to_string_lossy().into_owned())
            .collect();
        assert!(
            !plain.iter().any(|arg| arg.contains("resolv.conf")),
            "没给 DNS 时不该出现 resolv.conf：{plain:?}"
        );

        let resolv = dir.join("resolv.conf");
        let mut with_dns = base.clone();
        with_dns.resolv = Some(resolv.clone());
        let bound: Vec<String> = with_dns
            .command(workspace, None, "true")
            .get_args()
            .map(|arg| arg.to_string_lossy().into_owned())
            .collect();
        let expected = format!("{}:/etc/resolv.conf", resolv.display());
        let at = bound
            .iter()
            .position(|arg| arg == &expected)
            .unwrap_or_else(|| panic!("应当绑 resolv.conf：{bound:?}"));
        assert_eq!(bound[at - 1], "-b", "{bound:?}");
    }

    /// 会话自己的 `/chat`（D234）：**先 `/chat` 再 `/chat/images`**，两条都要有 `-b`。
    ///
    /// 顺序反了的话第二条的目标路径在 guest 里还不存在；这条测试就是钉住那个顺序的。
    /// 没给的时候一条都不该出现——绑一个不存在的宿主目录，proot 会直接起不来。
    #[test]
    fn envir_binds_chat_before_its_images() {
        let dir = Path::new("/tmp/nmoi-envir-chat");
        let workspace = Path::new("/tmp/nmoi-envir-ws");
        let chat = dir.join("chat");
        let images = dir.join("images");
        let base = EnvirSpec {
            proot: dir.join("proot"),
            loader: dir.join("loader"),
            lib_dir: dir.join("lib"),
            rootfs: dir.join("rootfs"),
            tmp: dir.join("tmp"),
            resolv: None,
            chat: None,
            chat_images: None,
        };

        let plain: Vec<String> = base
            .command(workspace, None, "true")
            .get_args()
            .map(|arg| arg.to_string_lossy().into_owned())
            .collect();
        assert!(
            !plain.iter().any(|arg| arg.contains(":/chat")),
            "没给会话目录时不该出现 /chat：{plain:?}"
        );

        let mut with_chat = base.clone();
        with_chat.chat = Some(chat.clone());
        with_chat.chat_images = Some(images.clone());
        let bound: Vec<String> = with_chat
            .command(workspace, None, "true")
            .get_args()
            .map(|arg| arg.to_string_lossy().into_owned())
            .collect();
        let chat_arg = format!("{}:/chat", chat.display());
        let images_arg = format!("{}:/chat/images", images.display());
        let chat_at = bound
            .iter()
            .position(|arg| arg == &chat_arg)
            .unwrap_or_else(|| panic!("应当绑 /chat：{bound:?}"));
        let images_at = bound
            .iter()
            .position(|arg| arg == &images_arg)
            .unwrap_or_else(|| panic!("应当绑 /chat/images：{bound:?}"));
        assert_eq!(bound[chat_at - 1], "-b", "{bound:?}");
        assert_eq!(bound[images_at - 1], "-b", "{bound:?}");
        assert!(
            chat_at < images_at,
            "/chat 必须先于 /chat/images：{bound:?}"
        );
    }

    /// **不加 `#[cfg(unix)]`**：`envir_shell_without_a_runtime_spec…` 与门控那条在 Windows 上也跑，
    /// 而它们只用到策略对象，不起进程。加过一次，CI 的 windows job 当场报
    /// `cannot find function container_policy`（本地是 unix，看不出来）。
    fn container_policy() -> AssistantPolicy {
        AssistantPolicy::locked_down()
            .with_binding(crate::policy::WorkspaceBinding::read_write("test-ws"))
            .enable_container(true)
    }

    /// 假 proot 刚写完就 `exec` 时，别的测试线程可能正好 `fork` 并把那个写句柄
    /// 带进子进程——内核于是给 ETXTBSY（`Text file busy`，os error 26）。
    /// 这不是被测代码的问题，也不是 `drop(file)` 能完全挡住的（别的线程已经复制了 fd）；
    /// 重试几次即可。CI 上偶发过一次，2026-11-02 又偶发一次。
    #[cfg(unix)]
    fn retry_etxtbsy(mut run: impl FnMut() -> ToolOutcome) -> ToolOutcome {
        for attempt in 0..40u64 {
            let outcome = run();
            if !format!("{outcome:?}").contains("Text file busy") {
                return outcome;
            }
            std::thread::sleep(std::time::Duration::from_millis(10 * (attempt + 1)));
        }
        run()
    }

    #[cfg(unix)]
    #[test]
    fn envir_shell_runs_the_command_through_the_launcher() {
        let dir = tempfile::tempdir().unwrap();
        let workspace = Workspace::open(dir.path()).unwrap();
        let spec = fake_proot(dir.path());
        let policy = container_policy();
        let ctx = ToolContext::new(&policy, Some(&workspace)).with_envir(&spec);

        let outcome =
            retry_etxtbsy(|| EnvirShellTool.invoke(&json!({"command": "echo envir-ok"}), &ctx));
        let text = match outcome {
            ToolOutcome::Ok { text, .. } => text,
            other => panic!("应当跑通：{other:?}"),
        };
        let value: Value = serde_json::from_str(&text).unwrap();
        assert_eq!(value["exit_code"], json!(0), "{value}");
        assert!(
            value["stdout"].as_str().unwrap().contains("envir-ok"),
            "{value}"
        );
    }

    /// `PATH` 是这条工具在真机上第一个踩的坑（head/uname/ls 全 not found）——
    /// 所以这里专门看一眼：guest 拿到的必须是容器里那几条目录。
    #[cfg(unix)]
    #[test]
    fn envir_shell_gives_the_guest_its_own_path() {
        let dir = tempfile::tempdir().unwrap();
        let workspace = Workspace::open(dir.path()).unwrap();
        let spec = fake_proot(dir.path());
        let policy = container_policy();
        let ctx = ToolContext::new(&policy, Some(&workspace)).with_envir(&spec);

        let outcome = retry_etxtbsy(|| {
            EnvirShellTool.invoke(&json!({"command": "printf %s \"$PATH\""}), &ctx)
        });
        let text = match outcome {
            ToolOutcome::Ok { text, .. } => text,
            other => panic!("应当跑通：{other:?}"),
        };
        let value: Value = serde_json::from_str(&text).unwrap();
        assert_eq!(value["stdout"].as_str().unwrap(), GUEST_PATH, "{value}");
    }

    #[cfg(unix)]
    #[test]
    fn envir_shell_times_out_and_kills_the_group() {
        let dir = tempfile::tempdir().unwrap();
        let workspace = Workspace::open(dir.path()).unwrap();
        let spec = fake_proot(dir.path());
        let policy = container_policy();
        let ctx = ToolContext::new(&policy, Some(&workspace)).with_envir(&spec);

        let outcome = retry_etxtbsy(|| {
            EnvirShellTool.invoke(&json!({"command": "sleep 30", "timeout_ms": 1000}), &ctx)
        });
        let text = match outcome {
            ToolOutcome::Ok { text, .. } => text,
            other => panic!("超时也要给结果：{other:?}"),
        };
        let value: Value = serde_json::from_str(&text).unwrap();
        assert_eq!(value["timed_out"], json!(true), "{value}");
    }

    #[test]
    fn envir_shell_without_a_runtime_spec_says_it_is_not_ready() {
        let dir = tempfile::tempdir().unwrap();
        let workspace = Workspace::open(dir.path()).unwrap();
        let policy = container_policy();
        // 门控开着、但没给坐标：这时**不能**去起进程，要明说缺什么。
        let ctx = ToolContext::new(&policy, Some(&workspace));
        let outcome = EnvirShellTool.invoke(&json!({"command": "echo hi"}), &ctx);
        match outcome {
            ToolOutcome::Failed { message, .. } => {
                assert!(message.contains("没就绪"), "{message}");
            }
            other => panic!("应当失败并说明原因：{other:?}"),
        }
    }

    #[test]
    fn envir_shell_is_gated_by_container_exec() {
        let dir = tempfile::tempdir().unwrap();
        let workspace = Workspace::open(dir.path()).unwrap();
        let locked = AssistantPolicy::locked_down()
            .with_binding(crate::policy::WorkspaceBinding::read_write("test-ws"));
        let ctx = ToolContext::new(&locked, Some(&workspace));
        let outcome = EnvirShellTool.invoke(&json!({"command": "echo hi"}), &ctx);
        assert!(
            matches!(outcome, ToolOutcome::Denied { .. }),
            "没开 ContainerExec 就该被门控挡下：{outcome:?}"
        );
    }

    #[test]
    fn guest_cwd_rejects_absolute_and_parent_paths() {
        assert!(guest_cwd_is_safe("sub/dir"));
        assert!(guest_cwd_is_safe(""));
        assert!(!guest_cwd_is_safe("/etc"));
        assert!(!guest_cwd_is_safe("../etc"));
        assert!(!guest_cwd_is_safe("sub/../../etc"));
    }

    // ------------------------------------------------------------------
    // 超时与手动关闭（0.15ak / D246）
    // ------------------------------------------------------------------

    #[test]
    fn timeout_accepts_the_range_and_the_word_inf() {
        assert_eq!(
            timeout_of(&json!({})).ok().flatten(),
            Some(Duration::from_millis(30_000))
        );
        assert_eq!(
            timeout_of(&json!({"timeout_ms": 1000})).ok().flatten(),
            Some(Duration::from_millis(1_000))
        );
        assert_eq!(
            timeout_of(&json!({"timeout_ms": 36_000_000}))
                .ok()
                .flatten(),
            Some(Duration::from_millis(36_000_000))
        );
        // "inf" = 不设闹钟。**这是唯一一种"不超时"的写法**。
        assert_eq!(
            timeout_of(&json!({"timeout_ms": "inf"})).ok().flatten(),
            None
        );
        assert_eq!(
            timeout_of(&json!({"timeout_ms": "INF"})).ok().flatten(),
            None
        );
    }

    #[test]
    fn timeout_zero_and_out_of_range_are_named_errors() {
        // 0 不是"不超时"：它不合法。悄悄当成 30 秒的话，模型以为它关掉了闹钟。
        let zero = timeout_of(&json!({"timeout_ms": 0})).unwrap_err().text();
        assert!(zero.contains("0 不是"), "{zero}");
        assert!(zero.contains("1000"), "{zero}");
        let too_long = timeout_of(&json!({"timeout_ms": 36_000_001}))
            .unwrap_err()
            .text();
        assert!(too_long.contains("36000000"), "{too_long}");
        let nonsense = timeout_of(&json!({"timeout_ms": "5m"})).unwrap_err().text();
        assert!(
            nonsense.contains("\"5m\""),
            "报错要点名拿到的是什么：{nonsense}"
        );
    }

    /// 用户点「关闭」：进程组被杀，`killed_by_user` 为真、`timed_out` 为假。
    ///
    /// 不靠 sleep 猜时序：登记是同步的，标志在起进程之前就置上，
    /// 等待循环的第一拍就会看到它。命令是 `sleep 30`——它要是没被杀，这个用例要等 30 秒。
    #[cfg(unix)]
    #[test]
    fn a_cancelled_call_is_killed_and_says_it_was_the_user() {
        let dir = tempfile::tempdir().unwrap();
        let workspace = Workspace::open(dir.path()).unwrap();
        let policy = policy();
        let handle = crate::kills::begin("s-shell-cancel", "call_1", "System-Shell", "");
        let ctx = ToolContext::new(&policy, Some(&workspace)).with_call_session("s-shell-cancel");
        let ctx = ToolContext {
            call: Some(&handle),
            ..ctx
        };
        assert!(crate::kills::cancel("s-shell-cancel", "call_1"));

        let outcome = SystemShellTool.invoke(
            &json!({"command": command_sleep(), "timeout_ms": "inf"}),
            &ctx,
        );
        let value: Value = serde_json::from_str(&outcome.text()).unwrap();
        assert_eq!(value["killed_by_user"], json!(true), "{value}");
        assert_eq!(
            value["timed_out"],
            json!(false),
            "两件事不许合成一个：{value}"
        );
        assert!(
            value["note"].as_str().unwrap().contains("被用户关闭"),
            "{value}"
        );
        assert_eq!(value["exit_code"], Value::Null, "{value}");
    }

    /// 没登记调用（直接 invoke）时，`"inf"` 照样能用，且没有关闭可点。
    #[cfg(unix)]
    #[test]
    fn without_a_registered_call_inf_still_means_no_alarm() {
        let dir = tempfile::tempdir().unwrap();
        let workspace = Workspace::open(dir.path()).unwrap();
        let policy = policy();
        let outcome = SystemShellTool.invoke(
            &json!({"command": "echo done", "timeout_ms": "inf"}),
            &context(&policy, &workspace),
        );
        let value: Value = serde_json::from_str(&outcome.text()).unwrap();
        assert_eq!(value["exit_code"], json!(0), "{value}");
        assert_eq!(value["killed_by_user"], json!(false), "{value}");
        assert_eq!(value["timed_out"], json!(false), "{value}");
    }
}
