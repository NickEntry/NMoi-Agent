//! `System-Shell`：跑平台的**原生命令**（D159）。
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
use std::process::{Command, Stdio};
use std::sync::mpsc;
use std::time::{Duration, Instant};

use serde_json::{json, Value};

use crate::policy::Capability;
use crate::tools::{required_str, Tool, ToolContext, ToolOutcome};

/// 每个流最多留这么多字节。够看完报错，又不会把内存撑爆。
const STREAM_CAP: usize = 64 * 1024;

/// 默认 30 秒：够 `cargo check` 之外的绝大多数事。上限 300 秒，再长就该用容器了。
const DEFAULT_TIMEOUT_MS: u64 = 30_000;
const MAX_TIMEOUT_MS: u64 = 300_000;

pub struct SystemShellTool;

impl Tool for SystemShellTool {
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
            "每个流最多 64 KiB；默认超时 30 秒，超时会杀掉整个进程组。"
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
                    "description": "超时毫秒数，默认 30000，最大 300000"
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

        let timeout = args
            .get("timeout_ms")
            .and_then(Value::as_u64)
            .unwrap_or(DEFAULT_TIMEOUT_MS)
            .clamp(1_000, MAX_TIMEOUT_MS);

        match run(&command, &cwd, Duration::from_millis(timeout)) {
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
    duration_ms: u64,
}

impl Completed {
    fn to_json(&self, command: &str) -> Value {
        json!({
            "command": command,
            "exit_code": self.exit_code,
            "stdout": self.stdout,
            "stderr": self.stderr,
            "truncated": self.truncated,
            "timed_out": self.timed_out,
            "duration_ms": self.duration_ms,
        })
    }
}

/// 起进程、读两个流、按超时收工。
///
/// 结构是"两个读线程 + 主线程 `try_wait` 轮询"：这样两个管道同时有人读（不会互相堵），
/// 主线程又能按时超时。用 `wait_with_output` 就没有超时可言了。
fn run(command: &str, cwd: &std::path::Path, timeout: Duration) -> Result<Completed, String> {
    let mut cmd = platform_command(command);
    cmd.current_dir(cwd)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());

    #[cfg(unix)]
    {
        use std::os::unix::process::CommandExt;
        // 自成一组：超时的时候能一次杀掉它和它拉起来的所有子进程。
        cmd.process_group(0);
    }

    let started = Instant::now();
    let mut child = cmd
        .spawn()
        .map_err(|error| format!("起不了命令（{}）：{error}", cwd.display()))?;

    let (stdout, stderr, out_rx, err_rx) = (
        child.stdout.take().ok_or("拿不到 stdout")?,
        child.stderr.take().ok_or("拿不到 stderr")?,
        None::<()>,
        None::<()>,
    );
    let _ = (out_rx, err_rx);

    let (out_tx, out_rx) = mpsc::channel();
    let (err_tx, err_rx) = mpsc::channel();
    drain(stdout, out_tx);
    drain(stderr, err_tx);

    let mut timed_out = false;
    let status = loop {
        match child.try_wait() {
            Ok(Some(status)) => break Some(status),
            Ok(None) => {}
            Err(error) => return Err(format!("等命令结束时出错：{error}")),
        }
        if started.elapsed() >= timeout {
            timed_out = true;
            kill_tree(&mut child);
            break None;
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
        duration_ms: started.elapsed().as_millis() as u64,
    })
}

/// 读一个管道，最多留 `STREAM_CAP` 字节；超出的照读不误（不然对方写满就卡住），只是丢掉。
fn drain<R: Read + Send + 'static>(mut reader: R, tx: mpsc::Sender<(String, bool)>) {
    std::thread::spawn(move || {
        let mut kept: Vec<u8> = Vec::new();
        let mut cut = false;
        let mut chunk = [0u8; 8192];
        loop {
            match reader.read(&mut chunk) {
                Ok(0) => break,
                Ok(count) => {
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
        assert_eq!(value["stdout"], json!("x"));
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
        assert_eq!(value["exit_code"], Value::Null);
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
}
