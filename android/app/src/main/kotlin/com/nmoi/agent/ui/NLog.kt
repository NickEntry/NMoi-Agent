package com.nmoi.agent.ui

import android.content.Context
import android.util.Log
import java.io.File

/**
 * 日志门面。**默认开**，设置里可关；**同时落一份到应用内文件**（`files/logs/nmoi.log`）。
 *
 * 为什么要包一层：`Log.*` 散在各处之后就没法统一关掉了，用户装个正式版还得看我们的调试输出。
 * 现在只有三个入口（i / w / e），开关一拉全部静默。
 *
 * 为什么还要落盘：logcat 不是我们能保证的通道——2026-10-23 在一台真机上，
 * 开关是开的、进程是新的，`logcat -d` 里 `NMoi/` 前缀的日志一条都没有（`I` 级被 ROM 吞了，
 * 同一台机器上更早只出现过 `E` 级）。排查时"日志拿不到"比"日志少"更糟，
 * 所以证据另存一份，用户在应用内就能看、能复制、能导出。
 *
 * 关掉之后**连 e 也不打、也不写文件**——那是用户的意愿，不是我们能替他决定的。
 * 崩溃堆栈由系统打（`AndroidRuntime`），不受这个开关影响，所以关日志不等于关诊断。
 *
 * 用 SharedPreferences 而不是 settings 表（D86）：开关与上限必须在**第一行日志之前**
 * 就读到，而 settings 表要开库、要线程，够不上。
 */
internal object NLog {
    private const val PREFS = "app_log"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_LIMIT = "limit_bytes"

    private const val MIB = 1024L * 1024L

    /** 上限默认 8 MiB。手机上一份纯文本日志能到这个量级已经很长了。 */
    const val DEFAULT_LIMIT_BYTES = 8L * MIB

    /** 界面给用户选的几档（MB）。不给任意输入：没人真的需要 7.3 MB。 */
    val LIMIT_CHOICES_MB = listOf(2, 4, 8, 16, 32)

    /** 缓存一份，避免每条日志都读一次 SharedPreferences。 */
    @Volatile
    var enabled: Boolean = true
        private set

    @Volatile
    var limitBytes: Long = DEFAULT_LIMIT_BYTES
        private set

    /** 日志目录，`init` 之前是 null（那时候的日志只进 logcat）。 */
    @Volatile
    private var dir: File? = null

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        enabled = prefs.getBoolean(KEY_ENABLED, true)
        limitBytes = prefs.getLong(KEY_LIMIT, DEFAULT_LIMIT_BYTES).coerceIn(1 * MIB, 64 * MIB)
        dir = File(context.filesDir, "logs")
    }

    fun setEnabled(context: Context, value: Boolean) {
        enabled = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, value).apply()
    }

    fun setLimitBytes(context: Context, value: Long) {
        limitBytes = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY_LIMIT, value).apply()
    }

    fun i(tag: String, message: String) = emit('I', tag, message)

    fun w(tag: String, message: String) = emit('W', tag, message)

    fun e(tag: String, message: String) = emit('E', tag, message)

    // ---- 给日志页用的读接口（调用方放 IO 线程） ----------------------------

    fun fileSize(context: Context): Long = LogFile.size(logDir(context))

    fun readTail(context: Context, maxBytes: Int): String = LogFile.tail(logDir(context), maxBytes)

    /** 整份读出来（导出用）。上限就是 [limitBytes]，所以能一次塞进内存。 */
    fun readAll(context: Context): String = LogFile.tail(logDir(context), limitBytes.toInt())

    fun clear(context: Context) = LogFile.clear(logDir(context))

    private fun logDir(context: Context): File = File(context.filesDir, "logs")

    /**
     * 一路进 logcat、一路进文件。文件那一路在 ROM 吞 `I` 级时仍然有效；
     * logcat 那一路在文件写不进去（没权限、磁盘满）时仍然有效。两条腿各断各的。
     */
    private fun emit(level: Char, tag: String, message: String) {
        if (!enabled) return
        when (level) {
            'I' -> Log.i("NMoi/$tag", message)
            'W' -> Log.w("NMoi/$tag", message)
            else -> Log.e("NMoi/$tag", message)
        }
        LogFile.append(dir, tag, level, message, limitBytes)
    }
}
