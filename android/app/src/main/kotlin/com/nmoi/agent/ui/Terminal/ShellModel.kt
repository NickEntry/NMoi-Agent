package com.nmoi.agent.ui.Terminal

/**
 * 终端一条命令的结果（D395）。
 *
 * 字段与 `System-Shell` 的工具结果同形：那边是模型发起的，这边是用户敲的，
 * 但"退出码 / 两路输出 / 有没有被截断 / 超时没有"这四件事一模一样。
 */
internal data class ShellResult(
    val command: String,
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val truncated: Boolean,
    val timedOut: Boolean,
    val durationMs: Long,
    /** 这一条命令改了哪些文件（相对工作区）。空 = 没改，或者判成只读没扫。 */
    val changes: List<String> = emptyList(),
) {
    /** 有没有东西可画。空输出 + 退出码 0 是"跑成功了但什么都没说"，那也要有一行。 */
    val isEmpty: Boolean get() = stdout.isBlank() && stderr.isBlank()

    /** 这一行提示：`exit 0 · 202ms` / `超时 · 600000ms`。 */
    fun summary(): String = buildString {
        if (timedOut) {
            append("超时")
        } else {
            append("exit ")
            append(exitCode?.toString() ?: "null（被信号杀掉）")
        }
        append(" · ")
        append(durationMs)
        append("ms")
        if (truncated) append(" · 输出被截断")
    }
}

/**
 * 终端的会话抽象（D395）。
 *
 * **第一版不做 PTY**：真终端要 `forkpty` / `openpty`，Android 上没有公开 API，
 * 得自己写 native 或者塞一套 termios。这一版做"命令回显式"——一行一条命令，
 * 输出追加到滚动区，提示符自己画。
 *
 * 能跑：`ls` `cat` `python x.py` `make` `npm install`、真正的构建与脚本。
 * **不能跑**：`vim` `top` 这类全屏程序、需要交互输入的命令（`sudo` 要密码）、
 * 需要 TTY 才能输出的程序（很多程序在非 TTY 时会关掉颜色——这倒是好事）。
 *
 * 升级路径：将来换 PTY 只换这个类的实现，界面那一层不动。
 */
internal interface ShellSession {
    /** 这一档叫什么（`系统 shell` / `容器（Ubuntu 24.04）` / `宿主 shell（容器环境）`）。 */
    val label: String

    /** 能不能用。不可用时 [unavailableReason] 要能回答"为什么"。 */
    fun available(): Boolean

    fun unavailableReason(): String?

    suspend fun run(session: String, command: String, cwd: String): Result<ShellResult>

    /**
     * 往**正在跑**的那条命令里送一段字节（D397）。
     *
     * 控制字节（`\x03` Ctrl+C、`\x1b` Esc）不是合法 UTF-8，所以走 base64 过桥。
     * 返回实际写进去几个字节；`0` 表示没有在跑的命令、或者管道已经断了——**不是错误**。
     */
    suspend fun write(session: String, bytes: ByteArray): Int

    /** 停掉这个会话里正在跑的那条命令。`false` = 没有在跑（点晚了一拍，不算错误）。 */
    suspend fun cancel(session: String): Boolean
}
