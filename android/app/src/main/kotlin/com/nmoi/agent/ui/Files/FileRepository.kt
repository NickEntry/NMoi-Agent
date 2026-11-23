package com.nmoi.agent.ui.Files

import com.nmoi.agent.ui.NmoiFfiException
import com.nmoi.agent.ui.NativeBridge
import com.nmoi.agent.ui.NLog
import com.nmoi.agent.ui.ToolingRepository
import com.nmoi.agent.ui.errorKind
import com.nmoi.agent.ui.errorText
import com.nmoi.agent.ui.ok
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * 工作区文件的读写（D392）。
 *
 * 走的是**同一条** FFI（`nmoi_workspace_*`），不是 Kotlin 自己 `File.listFiles()`：
 * 越界检查、符号链接、忽略清单、截断口径都在 core 那一份实现里。
 * 界面这边再写一套，两套判据迟早对不上——那时"模型看见的"和"用户看见的"就是两份东西。
 *
 * 每次调用开一次句柄再关掉：`Workspace::open` 只做 `create_dir_all` + `canonicalize`，
 * 不是 SQLite 那种几百毫秒的开库，所以不必为了省它去持有一份长句柄
 * （长句柄还要管"工作区被删了"、"切了工作区"这些失效时刻）。
 */
internal object FileRepository {

    /** 一页读多少行。与 `ReadOptions` 的默认上限同量级，界面上滚一屏够用。 */
    const val PAGE_LINES = 2_000L

    private suspend fun <T> withRoot(
        root: File,
        block: suspend (Long) -> Result<T>,
    ): Result<T> {
        if (!NativeBridge.loaded) {
            return Result.failure(NmoiFfiException("not_loaded", "原生层没加载"))
        }
        root.mkdirs()
        val opened = runCatching { JSONObject(NativeBridge.openWorkspaceJson(root.absolutePath)) }
            .getOrElse {
                return Result.failure(
                    NmoiFfiException("native_call", "绑工作区时报错：${it.message}"),
                )
            }
        if (!opened.ok()) {
            return Result.failure(NmoiFfiException(opened.errorKind(), opened.errorText()))
        }
        val handle = opened.optLong("handle", 0L)
        if (handle == 0L) {
            return Result.failure(NmoiFfiException("bad_envelope", "绑工作区没拿到句柄"))
        }
        return try {
            block(handle)
        } finally {
            runCatching { NativeBridge.closeWorkspaceJson(handle) }
        }
    }

    private fun call(envelope: JSONObject, what: String): JSONObject {
        if (!envelope.ok()) {
            throw NmoiFfiException(envelope.errorKind(), "$what：${envelope.errorText()}")
        }
        return envelope
    }

    /**
     * 列一个目录。
     *
     * `depth = 1`：界面上一次只看一层。递归列出来的是"整棵树摊平"，而列表要的是
     * "进哪个文件夹"——摊平之后用户反而找不到层级。
     */
    suspend fun list(
        root: File,
        path: String,
        showHidden: Boolean,
    ): Result<List<FileRow>> = withContext(Dispatchers.IO) {
        withRoot(root) { handle ->
            runCatching {
                val envelope = JSONObject(
                    NativeBridge.workspaceListJson(handle, path, 1L, 2_000L, showHidden),
                )
                call(envelope, "列目录失败")
                val items = envelope.optJSONArray("entries") ?: org.json.JSONArray()
                buildList {
                    for (index in 0 until items.length()) {
                        val row = items.optJSONObject(index) ?: continue
                        val name = row.optString("name", "")
                        if (name.isEmpty()) continue
                        add(
                            FileRow(
                                name = name,
                                path = row.optString("path", name),
                                isDir = row.optBoolean("is_dir", false),
                                sizeBytes = row.optLong("size_bytes", 0L),
                                modifiedMs = row.optLong("modified_ms", 0L),
                                isSymlink = row.optBoolean("is_symlink", false),
                            ),
                        )
                    }
                }
            }
        }
    }

    /** 读一页文本。三种回答分得开：文本 / 太大 / 不是文本（`kind` 各不相同）。 */
    suspend fun read(
        root: File,
        path: String,
        startLine: Long = 1L,
        maxLines: Long = PAGE_LINES,
    ): Result<FileText> = withContext(Dispatchers.IO) {
        withRoot(root) { handle ->
            runCatching {
                val envelope = JSONObject(
                    NativeBridge.workspaceReadJson(handle, path, startLine, maxLines, false),
                )
                call(envelope, "读文件失败")
                val truncation = envelope.optJSONObject("truncation")
                FileText(
                    path = envelope.optString("path", path),
                    content = envelope.optString("content", ""),
                    totalLines = envelope.optInt("total_lines", 0),
                    totalBytes = envelope.optLong("total_bytes", 0L),
                    returnedLines = envelope.optInt("returned_lines", 0),
                    truncated = envelope.optBoolean("truncated", false),
                    remainingLines = truncation?.optInt("remaining_lines", 0) ?: 0,
                    lossy = envelope.optBoolean("lossy", false),
                    startLine = startLine.coerceAtLeast(1).toInt(),
                )
            }
        }
    }

    /** 大小与修改时间（详情页、图片页用）。 */
    suspend fun stat(root: File, path: String): Result<FileStat> = withContext(Dispatchers.IO) {
        withRoot(root) { handle ->
            runCatching {
                val envelope = JSONObject(NativeBridge.workspaceStatJson(handle, path))
                call(envelope, "读文件信息失败")
                FileStat(
                    isDir = envelope.optBoolean("is_dir", false),
                    isFile = envelope.optBoolean("is_file", false),
                    sizeBytes = envelope.optLong("size_bytes", 0L),
                    modifiedMs = envelope.optLong("modified_ms", 0L),
                )
            }
        }
    }

    suspend fun makeDir(root: File, path: String): Result<String> = withContext(Dispatchers.IO) {
        withRoot(root) { handle ->
            runCatching {
                val envelope = JSONObject(NativeBridge.workspaceMkdirJson(handle, path))
                call(envelope, "新建文件夹失败")
                envelope.optString("path", path)
            }
        }
    }

    suspend fun rename(root: File, path: String, to: String): Result<String> =
        withContext(Dispatchers.IO) {
            withRoot(root) { handle ->
                runCatching {
                    val envelope = JSONObject(NativeBridge.workspaceRenameJson(handle, path, to))
                    call(envelope, "改名失败")
                    envelope.optString("to", to)
                }
            }
        }

    /** 删一个文件或一整棵目录，回删掉的条目数。 */
    suspend fun delete(root: File, path: String): Result<Int> = withContext(Dispatchers.IO) {
        withRoot(root) { handle ->
            runCatching {
                val envelope = JSONObject(NativeBridge.workspaceDeleteJson(handle, path))
                call(envelope, "删除失败")
                envelope.optInt("removed", 0)
            }
        }
    }

    /**
     * 写一段文本（新建文件 / 覆盖）。
     *
     * 界面上"新建文件"先建一个空文件，正文由预览页的编辑进去——不在新建那一刻
     * 就要内容，那会让"新建"变成一张必须填的表单。
     */
    suspend fun write(root: File, path: String, content: String): Result<Long> =
        withContext(Dispatchers.IO) {
            withRoot(root) { handle ->
                runCatching {
                    val envelope = JSONObject(NativeBridge.workspaceWriteJson(handle, path, content))
                    call(envelope, "写入失败")
                    envelope.optLong("bytes", 0L)
                }
            }
        }

    /** 这个工作区在盘上的绝对路径（预览页拼 `file://` 用）。 */
    fun absolute(root: File, path: String): File = if (path.isEmpty()) root else File(root, path)

    /** 一句话说明为什么这块是空的——比"加载失败"有用。 */
    fun describe(error: Throwable): String = when {
        error is NmoiFfiException && error.kind == "workspace" -> error.message ?: "工作区报错"
        error is NmoiFfiException -> error.message ?: "原生层报错"
        else -> error.message ?: error.javaClass.simpleName
    }

    /** 只有它自己会用到的一条日志：界面上看不出来的失败，logcat 里要留痕。 */
    fun warn(what: String, error: Throwable) {
        NLog.w("Files", "$what：${describe(error)}")
    }
}

/** 一页文本。`startLine` 是这一页的第一行（1-based），行号从它接着数。 */
internal data class FileText(
    val path: String,
    val content: String,
    val totalLines: Int,
    val totalBytes: Long,
    val returnedLines: Int,
    val truncated: Boolean,
    val remainingLines: Int,
    val lossy: Boolean,
    val startLine: Int,
) {
    /** 这一页的最后一行（1-based）。 */
    val endLine: Int get() = startLine + returnedLines - 1

    /** 分页读到第几页了，界面上要能看见。 */
    fun rangeLabel(): String =
        if (returnedLines <= 0) "空文件" else "第 $startLine–$endLine 行 · 共 $totalLines 行"

    /** 底部那一行"还有多少"。读完了就是 `null`，不画。 */
    fun moreLabel(): String? =
        if (!truncated || remainingLines <= 0) null else "还有 $remainingLines 行"
}

/** 文件信息。 */
internal data class FileStat(
    val isDir: Boolean,
    val isFile: Boolean,
    val sizeBytes: Long,
    val modifiedMs: Long,
)
