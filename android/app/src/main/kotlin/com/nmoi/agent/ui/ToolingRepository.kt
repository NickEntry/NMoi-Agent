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
    private const val KEY_ENVIR_SHELL = "tool.envir_shell"
    private const val KEY_CROSS_SESSION = "tool.cross_session"

    /** 默认工作区（`files/workspace`）。**先建好再绑**：FFI 那边不替用户建目录。 */
    fun sharedRoot(context: Context): File = WorkspaceRepository.defaultRoot(context)

    /**
     * 一个会话自己的工作区（D231 / 0.15ao）：`files/sessions/<会话>/chat`。
     *
     * 为什么是 `chat` 而不是直接 `sessions/<id>`：同一个会话目录下还要放图片（`images/`），
     * 模型能看到的只有 `chat/` 这一层——它不该顺手看见自己的附件原图。
     */
    fun chatRoot(context: Context, sessionId: String): File =
        File(File(File(context.filesDir, "sessions"), sessionId), "chat").apply { mkdirs() }

    /**
     * 这个会话的附件目录（`sessions/<id>/images`，D216）。
     *
     * 路径由 Rust 侧 `attachment_dir` 决定，这里只是**同一个位置**的另一处引用：
     * 容器里要把它绑到 `/chat/images`（D234），模型才读得到自己的附件。
     */
    fun sessionImagesRoot(context: Context, sessionId: String): File =
        File(File(File(context.filesDir, "sessions"), sessionId), "images")

    /**
     * 这个会话自己的暂存目录（`sessions/<id>/tmp`，D294）。
     *
     * 文件工具里的 `/tmp` 落到这儿，容器里的 `/tmp` 挂的也是它——**两边是同一份**。
     * 系统 shell 不做这个映射：它跑在宿主上，`/tmp` 是宿主自己的。
     *
     * 这里不 `mkdirs()`：谁用谁建（工具第一次写时建、容器起之前 `EnvirSpec` 建），
     * 光打开一条会话不该在盘上多出一个空目录。
     */
    fun sessionTmpRoot(context: Context, sessionId: String): File =
        File(File(File(context.filesDir, "sessions"), sessionId), "tmp")

    /**
     * 删掉这个会话自己的目录（`sessions/<id>/`，含 `chat/`、`images/` 与 `tmp/`）。
     *
     * 只在**库里的会话真的删掉之后**调：先删库再删文件，顺序反了会出现
     * "消息还在、图没了"——那比留着垃圾目录糟得多。
     * 删不掉不报错（目录可能本来就不存在），但要留一行日志。
     */
    fun removeSessionDir(context: Context, sessionId: String) {
        val dir = File(File(context.filesDir, "sessions"), sessionId)
        if (!dir.exists()) return
        val removed = runCatching { dir.deleteRecursively() }.getOrDefault(false)
        if (removed) {
            NLog.i("Tools", "已删掉会话目录：sessions/$sessionId")
        } else {
            NLog.w("Tools", "会话目录没删干净：sessions/$sessionId（下次清理时会再试）")
        }
    }

    /** 没有会话上下文的地方（设置页显示路径）用：默认工作区。 */
    fun workspaceRoot(context: Context): File = sharedRoot(context)

    /**
     * 开一个句柄，根目录由调用方给（D234：助手绑哪个工作区就用哪个）。
     *
     * 失败给原因，不返回 0 顶数。
     */
    fun open(context: Context, root: File = sharedRoot(context)): Result<Long> {
        if (!NativeBridge.loaded) {
            return Result.failure(NmoiFfiException("not_loaded", "原生层没加载"))
        }
        root.mkdirs()
        val path = root.absolutePath
        val envelope = runCatching { JSONObject(NativeBridge.openWorkspaceJson(path)) }
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
            NLog.i("Tools", "工作区已绑定：${envelope.optString("root", path)}（handle=$handle）")
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

    /**
     * 本机开关：容器里的命令（`WorkSpace-EnvirShell`，D172）。默认关。
     *
     * 跟 `native_shell` 一样**只存本机**：它描述的是"这台设备上允不允许"，换机导入一个 `true`
     * 只会得到"看着开着、其实没有 rootfs"。真正的可用性由 `EnvirRepository.installed()` 说话，
     * 这里只表示用户的意愿。
     */
    fun envirShell(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENVIR_SHELL, false)

    /**
     * 本机开关：跨会话只读（`Session-Files`，D295）。默认关。
     *
     * 关着的时候这个工具**不声明**（不是灰的、不是声明了再拒绝）——不声明，模型就不会去试。
     * 与 `native_shell` 一样只存本机：它描述的是"这台设备允不允许"。
     */
    fun crossSession(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_CROSS_SESSION, false)

    fun setCrossSession(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_CROSS_SESSION, enabled)
            .apply()
        NLog.i("Tools", "跨会话读取开关：${if (enabled) "开" else "关"}")
    }

    fun setEnvirShell(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENVIR_SHELL, enabled)
            .apply()
        NLog.i("Tools", "WorkSpace-EnvirShell 开关：${if (enabled) "开" else "关"}")
    }

    fun setNativeShell(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_NATIVE_SHELL, enabled)
            .apply()
        NLog.i("Tools", "System-Shell 开关：${if (enabled) "开" else "关"}")
    }
}
