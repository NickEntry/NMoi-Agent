package com.nmoi.agent.ui.Terminal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * 系统 shell（D395）：跑在 App 自己的身份下，起点是工作区。
 *
 * 走 `terminalRunJson` → core 的 `terminal_run` → 同一条 `run`：
 * 超时、输出封顶、杀进程组只有一份实现。
 */
internal object SystemShell : ShellSession {
    override val label: String = "系统 shell"

    override fun available(): Boolean = com.nmoi.agent.ui.NativeBridge.loaded

    override fun unavailableReason(): String? =
        if (available()) null else "原生层没加载（${com.nmoi.agent.ui.NativeBridge.failureMessage ?: "未知原因"}）"

    override suspend fun run(command: String, cwd: String): Result<ShellResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                val config = JSONObject()
                    .put("root", File(cwd).absolutePath)
                    .put("command", command)
                    .put("timeout_ms", TIMEOUT_MS)
                    .toString()
                val envelope = JSONObject(com.nmoi.agent.ui.NativeBridge.terminalRunJson(config))
                if (!envelope.optBoolean("ok", false)) {
                    val error = envelope.optJSONObject("error")
                    error("${error?.optString("kind", "unknown")}：${error?.optString("message", "")}")
                }
                ShellResult(
                    command = envelope.optString("command", command),
                    exitCode = if (envelope.isNull("exit_code")) null else envelope.optInt("exit_code"),
                    stdout = envelope.optString("stdout", ""),
                    stderr = envelope.optString("stderr", ""),
                    truncated = envelope.optBoolean("truncated", false),
                    timedOut = envelope.optBoolean("timed_out", false),
                    durationMs = envelope.optLong("duration_ms", 0L),
                )
            }
        }

    /** 10 分钟：够跑一次构建，又不至于一条挂住的命令永远占着（与 core 同值）。 */
    private const val TIMEOUT_MS = 10 * 60 * 1000L
}

/**
 * 容器终端（D395）——**这一版没有实现**。
 *
 * 它要 `EnvirSpec`（proot / rootfs / 挂载点），而那一份现在由 `TurnRepository`
 * 在发请求时现拼。终端要复用同一份，得先把 `envirJson` 抽出来共用——
 * 那是下一批的事。这里留一个**如实说明**的实现，而不是一个拨了没反应的按钮。
 */
internal object EnvirShell : ShellSession {
    override val label: String = "容器（Ubuntu 24.04）"

    override fun available(): Boolean = false

    override fun unavailableReason(): String =
        "容器终端还没接：它要复用发请求时那一份 proot 配置，得先把 envirJson 抽出来共用（下一批）"

    override suspend fun run(command: String, cwd: String): Result<ShellResult> =
        Result.failure(UnsupportedOperationException(unavailableReason()))
}

/**
 * 宿主 shell（容器环境）（D395）——**这一版没有实现**。
 *
 * 这一档的真实边界写在这里，免得以后有人以为它只是"没做完"：
 * 宿主的 `sh` 是 bionic 编的，链上 rootfs 里的 glibc 会直接 `CANNOT LINK EXECUTABLE`。
 * 所以它**跑不了 rootfs 里的 ELF**，只能跑"已经装在宿主上、但按容器目录找数据"的东西。
 */
internal object HostShell : ShellSession {
    override val label: String = "宿主 shell（容器环境）"

    override fun available(): Boolean = false

    override fun unavailableReason(): String =
        "这一档要按容器的目录找数据，但跑不了 rootfs 里的程序（宿主 sh 是 bionic 编的，" +
            "链 glibc 会 CANNOT LINK EXECUTABLE）。等容器终端接上之后再一起做"

    override suspend fun run(command: String, cwd: String): Result<ShellResult> =
        Result.failure(UnsupportedOperationException(unavailableReason()))
}

/** 三档终端，按优先级排（容器可用就用容器，否则系统 shell）。 */
internal object ShellSessions {
    val all: List<ShellSession> = listOf(EnvirShell, SystemShell, HostShell)

    /** 默认那一档：第一颗可用的。 */
    fun default(): ShellSession = all.firstOrNull { it.available() } ?: SystemShell
}
