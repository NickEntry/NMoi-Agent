package com.nmoi.agent.ui

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * 工作区与本地开关（D159）。
 *
 * 工作区根：`/data/user/<uid>/com.nmoi.agent/files/workspace`（就是 `filesDir/workspace`）。
 * **不走 `/sdcard`**：那要额外权限、会被别的 App 看到，而这里的六件文件工具 + 原生命令
 * 本来就是"一个自己的目录"。用户 2026-10-27 明确要的是这个位置。
 *
 * 为什么开关不放在库里：`System-Shell` 这一项关乎"这台设备允不允许跑命令"，
 * 跟着数据走的话，换台机器导入一个 `true` 就成了一句空话。所以它只存本地
 * （SharedPreferences），**不进库、不进导出/导入**。
 */
internal object ToolingRepository {
    private const val PREFS = "nmoi.local"
    private const val KEY_NATIVE_SHELL = "tool.native_shell"

    /** 工作区根目录。**先建好再绑**：FFI 那边不替用户建目录（绑错路径要当场知道）。 */
    fun workspaceRoot(context: Context): File =
        File(context.filesDir, "workspace").apply { mkdirs() }

    /** 开一个句柄。失败给原因，不返回 0 顶数。 */
    fun open(context: Context): Result<Long> {
        if (!NativeBridge.loaded) {
            return Result.failure(NmoiFfiException("not_loaded", "原生层没加载"))
        }
        val root = workspaceRoot(context).absolutePath
        val envelope = runCatching { JSONObject(NativeBridge.openWorkspaceJson(root)) }
            .getOrElse {
                return Result.failure(NmoiFfiException("native_call", "绑工作区时报错：${it.message}"))
            }
        if (!envelope.ok()) {
            return Result.failure(NmoiFfiException(envelope.errorKind(), envelope.errorText()))
        }
        val handle = envelope.optLong("handle", 0L)
        return if (handle == 0L) {
            Result.failure(NmoiFfiException("bad_envelope", "绑工作区没拿到句柄"))
        } else {
            NLog.i("Tools", "工作区已绑定：${envelope.optString("root", root)}（handle=$handle）")
            Result.success(handle)
        }
    }

    fun close(handle: Long) {
        runCatching { NativeBridge.closeWorkspaceJson(handle) }
            .onFailure { NLog.w("Tools", "解绑工作区失败：${it.message}") }
    }

    /** 本机开关：原生命令（`System-Shell`）。默认关。 */
    fun nativeShell(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_NATIVE_SHELL, false)

    fun setNativeShell(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_NATIVE_SHELL, enabled)
            .apply()
        NLog.i("Tools", "System-Shell 开关：${if (enabled) "开" else "关"}")
    }
}
