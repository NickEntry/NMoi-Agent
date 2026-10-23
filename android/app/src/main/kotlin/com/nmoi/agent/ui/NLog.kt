package com.nmoi.agent.ui

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 日志门面。**三种通道**（[LogKind]），每路双写：logcat + 应用内文件。
 *
 * 为什么包一层：`Log.*` 散在各处之后就没法统一关掉了，用户装个正式版还得看我们的调试输出。
 * 为什么落盘：2026-10-23 在一台真机上，开关是开的、进程是新的，`logcat -d` 里
 * `NMoi/` 前缀的日志一条都没有（`I` 级被 ROM 吞了）。排查时"日志拿不到"比"日志少"更糟。
 *
 * 三条入口，**调用点自己选通道**，不要靠"这条看起来像调试"来猜：
 * - `NLog.i/w/e` → 调试通道（应用事件）
 * - `NLog.context(...)` → 上下文通道（组装、token、压缩）
 * - `NLog.request(...)` → 请求通道（HTTP；**默认脱敏**，body 只在详细模式下写）
 *
 * 关掉某一通道之后，那一通道**连 logcat 也不打、文件也不写**——那是用户的意愿，
 * 不是我们能替他决定的。崩溃堆栈由系统打（`AndroidRuntime`），不受影响。
 */
internal object NLog {
    private const val PREFS = "app_log"

    /** 旧版只有一个全局开关，键名没有后缀。迁移时当三个通道的初值。 */
    private const val LEGACY_ENABLED = "enabled"

    private const val MIB = 1024L * 1024L

    /** 上限默认 8 MiB。手机上一份纯文本日志能到这个量级已经很长了。 */
    const val DEFAULT_LIMIT_BYTES = 8L * MIB

    /** 界面给用户选的几档（MB）。不给任意输入：没人真的需要 7.3 MB。 */
    val LIMIT_CHOICES_MB = listOf(2, 4, 8, 16, 32)

    /**
     * 开关与上限各存一份**不可变快照**：读的人（界面、日志调用点）永远看到一致的组合，
     * 不用加锁；改的时候整份替换。三个枚举项的规模，复制一次不值一提。
     */
    @Volatile
    private var enabledMap: Map<LogKind, Boolean> = LogKind.entries.associateWith { true }

    @Volatile
    private var limitMap: Map<LogKind, Long> = LogKind.entries.associateWith { DEFAULT_LIMIT_BYTES }

    /** 日志目录，`init` 之前是 null（那时候的日志只进 logcat）。 */
    @Volatile
    private var dir: File? = null

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // 旧版那个全局开关是"全部日志"的总闸；迁移成三个通道的初值，
        // 否则老用户升级上来会突然发现日志全没了（他会以为自己关过）。
        val legacy = prefs.getBoolean(LEGACY_ENABLED, true)
        enabledMap = LogKind.entries.associateWith { kind ->
            prefs.getBoolean("enabled.${kind.id}", legacy)
        }
        limitMap = LogKind.entries.associateWith { kind ->
            prefs.getLong("limit.${kind.id}", DEFAULT_LIMIT_BYTES).coerceIn(1 * MIB, 64 * MIB)
        }
        dir = File(context.filesDir, "logs")
    }

    fun isEnabled(kind: LogKind): Boolean = enabledMap[kind] ?: true

    fun setEnabled(context: Context, kind: LogKind, value: Boolean) {
        enabledMap = enabledMap + (kind to value)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean("enabled.${kind.id}", value).apply()
    }

    fun limitBytes(kind: LogKind): Long = limitMap[kind] ?: DEFAULT_LIMIT_BYTES

    fun setLimitBytes(context: Context, kind: LogKind, value: Long) {
        limitMap = limitMap + (kind to value)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong("limit.${kind.id}", value).apply()
    }

    // ---- 三个通道的写入口 ---------------------------------------------------

    fun i(tag: String, message: String) = emit(LogKind.Debug, 'I', tag, message)

    fun w(tag: String, message: String) = emit(LogKind.Debug, 'W', tag, message)

    fun e(tag: String, message: String) = emit(LogKind.Debug, 'E', tag, message)

    fun context(tag: String, message: String) = emit(LogKind.Context, 'I', tag, message)

    fun request(tag: String, message: String) = emit(LogKind.Request, 'I', tag, message)

    // ---- 给日志页用的读接口（调用方放 IO 线程） ----------------------------

    fun fileSize(context: Context, kind: LogKind): Long =
        LogFile.size(logDir(context), kind.fileName)

    fun readTail(context: Context, kind: LogKind, maxBytes: Int): String =
        LogFile.tail(logDir(context), kind.fileName, maxBytes)

    /** 整份读出来（导出用）。上限就是 [limitBytes]，所以能一次塞进内存。 */
    fun readAll(context: Context, kind: LogKind): String =
        LogFile.tail(logDir(context), kind.fileName, limitBytes(kind).toInt())

    fun clear(context: Context, kind: LogKind) = LogFile.clear(logDir(context), kind.fileName)

    private fun logDir(context: Context): File = File(context.filesDir, "logs")

    /**
     * 一路进 logcat、一路进文件。文件那一路在 ROM 吞 `I` 级时仍然有效；
     * logcat 那一路在文件写不进去（没权限、磁盘满）时仍然有效。两条腿各断各的。
     *
     * logcat 的 tag 带上通道名（`NMoi/req/Http`），这样 `adb logcat -s NMoi/req:V`
     * 还能按通道过滤。
     */
    private fun emit(kind: LogKind, level: Char, tag: String, message: String) {
        if (!isEnabled(kind)) return
        val logcatTag = "NMoi/${kind.id}/$tag"
        when (level) {
            'W' -> Log.w(logcatTag, message)
            'E' -> Log.e(logcatTag, message)
            else -> Log.i(logcatTag, message)
        }
        LogFile.append(dir, kind.fileName, tag, level, message, limitBytes(kind))
    }
}
