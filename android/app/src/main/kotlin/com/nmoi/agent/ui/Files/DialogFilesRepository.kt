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
 */
internal object DialogFilesRepository {

    /**
     * 盘上 `chat/` 里的文件。**不递归**：附件与输出都落在这一层
     * （`put_file_attachment` 用的是 `chat_dir.join(relative)`，没有子目录）。
     *
     * 目录不存在时给空列表而不是报错：一条还没发过文件的会话本来就没有这个目录，
     * 那不是故障。
     */
    suspend fun onDisk(context: Context, sessionId: String): List<ChatFile> =
        withContext(Dispatchers.IO) {
            val dir = ToolingRepository.chatRoot(context, sessionId)
            if (!dir.isDirectory) return@withContext emptyList()
            dir.listFiles().orEmpty()
                .filter { it.isFile }
                .map { file ->
                    ChatFile(
                        path = file.name,
                        name = file.name,
                        sizeBytes = runCatching { file.length() }.getOrDefault(0L),
                        modifiedMs = runCatching { file.lastModified() }.getOrDefault(0L),
                    )
                }
        }

    /** 库里的附件记录（只要判归属要用的三个字段）。 */
    suspend fun attachments(
        context: Context,
        sessionId: String,
    ): Result<List<AttachmentRef>> {
        if (!NativeBridge.loaded) {
            return Result.failure(IllegalStateException("原生层没加载"))
        }
        val databasePath = File(context.filesDir, "nmoi.db").absolutePath
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
                            ),
                        )
                    }
                }
            }
        }
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
