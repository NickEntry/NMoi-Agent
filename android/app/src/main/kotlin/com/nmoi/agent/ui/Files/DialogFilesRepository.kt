package com.nmoi.agent.ui.Files

import android.content.Context
import com.nmoi.agent.ui.NmoiFfi
import com.nmoi.agent.ui.NativeBridge
import com.nmoi.agent.ui.ToolingRepository
import com.nmoi.agent.ui.errorText
import com.nmoi.agent.ui.ok
import com.nmoi.agent.ui.withStorageHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 一条会话的「对话文件」三个页签的数据源（D393）。
 *
 * 前两个页签是**同一个目录的两种归属**：
 * - 附件 = 库里 `attachments`（`kind = 'file'`）有记录的那些；
 * - 输出 = 盘上有、库里没有的那些。
 *
 * 判据只在 `DialogFiles.split` 一处（单测钉住）。这里只负责**把两边的东西读出来**：
 * 盘上的走 `File.listFiles()`（`chat/` 是 App 自己的私有目录，不需要越界检查），
 * 库里的走 `list_attachments`（不另写 SQL）。
 *
 * D423 起用户发来的文件落在 `chat/attachments/` 那一层，所以这里**要递归**——
 * 只看根上那一层的话，`attachments/` 里的每一份都会变成"盘上有、两个页签都看不见"，
 * 那正是铁律② 要挡的东西。
 */
internal object DialogFilesRepository {

    /**
     * 盘上 `chat/` 里的全部文件（含 `attachments/` 那一层）。
     *
     * 递归到底而不设深度上限：会话目录由 App 自己管，正常不会有深树；
     * 设一个上限就得再决定"超了怎么办"，而那一步的结果通常是"悄悄不显示"。
     * 环由 `canonicalPath` 的已访问集合挡住（符号链接指回上层时会撞上）。
     *
     * 目录不存在时给空列表而不是报错：一条还没发过文件的会话本来就没有这个目录，
     * 那不是故障。
     */
    suspend fun onDisk(context: Context, sessionId: String): List<ChatFile> =
        withContext(Dispatchers.IO) {
            val root = ToolingRepository.chatRoot(context, sessionId)
            if (!root.isDirectory) return@withContext emptyList()
            val out = mutableListOf<ChatFile>()
            val seen = mutableSetOf<String>()
            fun walk(dir: File, prefix: String) {
                val key = runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath)
                if (!seen.add(key)) return
                for (file in dir.listFiles().orEmpty()) {
                    val relative = if (prefix.isEmpty()) file.name else "$prefix/${file.name}"
                    if (file.isDirectory) {
                        walk(file, relative)
                    } else if (file.isFile) {
                        out += ChatFile(
                            path = relative,
                            name = file.name,
                            sizeBytes = runCatching { file.length() }.getOrDefault(0L),
                            modifiedMs = runCatching { file.lastModified() }.getOrDefault(0L),
                        )
                    }
                }
            }
            walk(root, "")
            out
        }

    /** 库里的附件记录（判归属要用的字段，外加盘上那份的相对路径）。 */
    suspend fun attachments(
        context: Context,
        sessionId: String,
    ): Result<List<AttachmentRef>> {
        if (!NativeBridge.loaded) {
            return Result.failure(IllegalStateException("原生层没加载"))
        }
        val databasePath = File(context.filesDir, "nmoi.db").absolutePath
        // `attachments.path` 存的是绝对路径；判归属要的是相对 `chat/` 的那一段。
        val chatRoot = ToolingRepository.chatRoot(context, sessionId).absolutePath
            .trimEnd('/')
        return withStorageHandle(databasePath) { handle ->
            NmoiFfi.listAttachments(handle, sessionId).mapCatching { envelope ->
                if (!envelope.ok()) error(envelope.errorText())
                val items = envelope.optJSONArray("items") ?: org.json.JSONArray()
                buildList {
                    for (index in 0 until items.length()) {
                        val row = items.optJSONObject(index) ?: continue
                        val id = row.optString("id", "")
                        if (id.isEmpty()) continue
                        add(
                            AttachmentRef(
                                id = id,
                                name = row.optString("name", ""),
                                kind = row.optString("kind", "file"),
                                path = relativeTo(chatRoot, row),
                            ),
                        )
                    }
                }
            }
        }
    }

    /**
     * 库里那条绝对路径在 `chat/` 里的相对路径；读不出来就给 `null`。
     *
     * `org.json` 的 `optString` 对 JSON 的 `null` 会给回字面量 `"null"`，所以先 `isNull`。
     * 不在 `chat/` 底下的（老实现把图片写在 `images/`、或换了盘）一律 `null`——
     * 那时 `split` 退回到按名字判，而不是拿一条错路径去比。
     */
    private fun relativeTo(chatRoot: String, row: org.json.JSONObject): String? {
        if (row.isNull("path")) return null
        val absolute = row.optString("path", "")
        if (absolute.isBlank()) return null
        val prefix = "$chatRoot/"
        val rest = if (absolute.startsWith(prefix)) {
            absolute.removePrefix(prefix)
        } else {
            return null
        }
        return rest.takeIf { it.isNotBlank() }
    }

    /** 三个页签要的全部数据：两堆 + 各自读失败的原因。 */
    suspend fun load(
        context: Context,
        sessionId: String,
    ): DialogFilesSnapshot = withContext(Dispatchers.IO) {
        val disk = onDisk(context, sessionId)
        val refs = attachments(context, sessionId)
        val split = DialogFiles.split(disk, refs.getOrDefault(emptyList()))
        DialogFilesSnapshot(
            attachments = split[DialogFileSide.Attachment].orEmpty(),
            outputs = split[DialogFileSide.Output].orEmpty(),
            attachmentsError = refs.exceptionOrNull()?.message,
            sessionId = sessionId,
        )
    }
}

/** 一次读取的结果。`attachmentsError` 非空时「附件」页签要说明读不到库，而不是显示空。 */
internal data class DialogFilesSnapshot(
    val attachments: List<ChatFile>,
    val outputs: List<ChatFile>,
    val attachmentsError: String?,
    val sessionId: String,
)
