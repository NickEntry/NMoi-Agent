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
    private const val KEY_SHELL_ASK = "tool.shell_ask"
    private const val KEY_SHELL_BLACKLIST = "tool.shell_blacklist"
    private const val PREFIX_SHELL_GROUP = "tool.shell_group."
    private const val KEY_SING = "tool.sing"
    private const val PREFIX_TOOL_OFF = "tool.off."

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
     * 这个会话的归档目录（`sessions/<id>/archive`，D317）。
     *
     * F2–F4 换掉的原文物化在这儿，标记行里写的是 `/archive/<hex>.txt`；
     * 文件工具与容器里的 `/archive` 都是这一份。
     *
     * 同样不 `mkdirs()`：`put_archive` 落文件时会建，容器起之前 `EnvirSpec` 也会建。
     */
    fun sessionArchiveRoot(context: Context, sessionId: String): File =
        File(File(File(context.filesDir, "sessions"), sessionId), "archive")

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

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * 面板上被**逐个关掉**的工具名（0.17e）。
     *
     * 关掉 = 这一轮**不声明**它，不是声明了再拒绝：声明了再拒绝，模型会一直试，
     * 用户看到的是一串"被拒绝"。默认一个都没关。
     */
    fun toolsOff(context: Context): List<String> =
        prefs(context).all
            .filter { (key, value) -> key.startsWith(PREFIX_TOOL_OFF) && value == true }
            .keys
            .map { it.removePrefix(PREFIX_TOOL_OFF) }
            .sorted()

    fun setToolOff(context: Context, name: String, off: Boolean) {
        prefs(context).edit().putBoolean(PREFIX_TOOL_OFF + name, off).apply()
        NLog.i("Tools", "$name：${if (off) "不声明" else "声明"}")
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

    /**
     * 本机开关：命令执行前询问（D329）。默认**开**。
     *
     * 能力开关（`native_shell` / `envir_shell`）的字面意思只是"能用命令"，
     * 不是"随便跑"。这一项才是"这一条要不要先问一句"。跟那两个一样只存本机。
     */
    fun shellAsk(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHELL_ASK, true)

    fun setShellAsk(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SHELL_ASK, enabled)
            .apply()
        NLog.i("Tools", "命令执行前询问：${if (enabled) "开" else "关"}")
    }

    /** 本机开关：黑名单模式（D329）。默认**开**——只问命中清单的，不是"安全模式"。 */
    fun shellBlacklist(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHELL_BLACKLIST, true)

    fun setShellBlacklist(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SHELL_BLACKLIST, enabled)
            .apply()
        NLog.i("Tools", "黑名单模式：${if (enabled) "开" else "关"}")
    }

    /**
     * 某一组规则还问不问。默认**问**（真值），只有显式关过的那一组是 false。
     *
     * 为什么按"关过哪些组"存、不按"开着哪些组"存：以后加了新组，
     * 老用户的默认还是"问"——按"开着哪些组"存的话，新组会静默变成不问。
     */
    fun shellGroupAsks(context: Context, group: String): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(PREFIX_SHELL_GROUP + group, true)

    fun setShellGroupAsks(context: Context, group: String, asks: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PREFIX_SHELL_GROUP + group, asks)
            .apply()
        NLog.i("Tools", "规则组 $group：${if (asks) "问" else "不问"}")
    }

    /** 被关掉的那些组，原样递给 core（它只认组 id）。 */
    fun shellOffGroups(context: Context): List<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .all
            .filter { (key, value) -> key.startsWith(PREFIX_SHELL_GROUP) && value == false }
            .keys
            .map { it.removePrefix(PREFIX_SHELL_GROUP) }
            .sorted()

    /**
     * 本机开关：唱歌（`Sing`，0.17b）。默认关。
     *
     * 开着也还要有引擎：`svsEngine(context)` 为空时这一行是灰的——
     * "拨了也不会生效"的开关比没有更糟，所以灰的判据是"有没有东西可跑"。
     */
    fun sing(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SING, false)

    fun setSing(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SING, enabled)
            .apply()
        NLog.i("Tools", "唱歌开关：${if (enabled) "开" else "关"}")
    }

    /**
     * 歌唱引擎（运行时）装好没有。
     *
     * 声库装在 `voice/svs/<id>/`（见 `VoiceBankRepository`），但**运行时还没接**——
     * 这里认的是运行时自己写的 `runtime.json`，它现在不存在，所以恒为 null。
     * 判据用"有没有运行时"而不是"有没有声库"：声库装上不能唱，跟没装一样。
     */
    fun svsEngine(context: Context): File? {
        val runtime = File(File(File(context.filesDir, "voice"), "svs"), "runtime.json")
        return if (runtime.isFile) runtime.parentFile else null
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
