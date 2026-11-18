package com.nmoi.agent.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 一个待发的文件附件（D337）。
 *
 * 只拿"名字 / 大小 / 类型 / 字节"，**不在这里读成文本**：正文里一个字都不加。
 * 文件落到会话自己的 `chat/`，界面上是一块芯片；模型有文件工具就自己按
 * `/chat/<文件名>` 读，没有工具时由 core 把内容内联进发出去的那一份。
 *
 * `data` 是 base64（`Base64.NO_WRAP`）：FFI 那条路只认字符串。
 */
internal data class PendingFile(
    val name: String,
    val mime: String,
    val bytes: Long,
    val data: String,
) {
    /** 是不是一段录音。听觉覆盖只挑这一类。 */
    val isAudio: Boolean
        get() = mime.startsWith("audio/", ignoreCase = true)

    val sizeLabel: String
        get() = when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "${(bytes / 1024.0 / 1024.0 * 10).toInt() / 10.0} MB"
        }
}

/** 扩展名 → mime。只认这几个：认不出的留给 `application/octet-stream`（当文档处理）。 */
private fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "mp3" -> "audio/mpeg"
    "wav" -> "audio/wav"
    "m4a" -> "audio/mp4"
    "ogg", "opus" -> "audio/ogg"
    "flac" -> "audio/flac"
    "aac" -> "audio/aac"
    "mp4" -> "video/mp4"
    "webm" -> "video/webm"
    "mov" -> "video/quicktime"
    "pdf" -> "application/pdf"
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "webp" -> "image/webp"
    "gif" -> "image/gif"
    else -> ""
}

internal object FileAttach {
    const val MAX_FILES = 4

    /** 与 `nmoi_storage::repo::MAX_FILE_BYTES` 同值：超了 native 也会拒，这里先给一句人话。 */
    const val MAX_BYTES = 16 * 1024 * 1024

    /**
     * 读一个文件。任何类型都收——文件的价值在于"模型能自己读"，
     * 而不是"我们能把它转成文字"；二进制由 core 在没工具时如实说一句。
     */
    suspend fun read(context: Context, uri: Uri): Result<PendingFile> =
        withContext(Dispatchers.IO) {
            runCatching {
                val resolver = context.contentResolver
                var shown = ""
                var size = 0L
                resolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex >= 0) shown = cursor.getString(nameIndex).orEmpty()
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                            size = cursor.getLong(sizeIndex)
                        }
                    }
                }
                val name = shown.ifBlank { uri.lastPathSegment?.substringAfterLast('/') ?: "文件" }
                // 选择器常常给不出 mime（尤其从"最近使用"里点进来的）。按扩展名补一把：
                // 听觉覆盖靠 `audio/` 这个前缀认音频，补不上就等于那个开关对这个文件是哑的。
                val mime = resolver.getType(uri).orEmpty().trim().ifBlank { guessMime(name) }
                if (size > MAX_BYTES) {
                    error("「$name」${size / 1024 / 1024} MB，超过 ${MAX_BYTES / 1024 / 1024} MB 上限")
                }
                val raw = resolver.openInputStream(uri)?.use { input ->
                    val buffer = ByteArray(MAX_BYTES + 1)
                    var read = 0
                    while (read < buffer.size) {
                        val step = input.read(buffer, read, buffer.size - read)
                        if (step <= 0) break
                        read += step
                    }
                    buffer.copyOf(read)
                } ?: error("打不开「$name」")
                if (raw.isEmpty()) error("「$name」是空的")
                if (raw.size > MAX_BYTES) {
                    error("「$name」超过 ${MAX_BYTES / 1024 / 1024} MB 上限")
                }
                PendingFile(
                    name = name,
                    mime = mime.ifBlank { "application/octet-stream" },
                    bytes = if (size > 0) size else raw.size.toLong(),
                    data = android.util.Base64.encodeToString(raw, android.util.Base64.NO_WRAP),
                )
            }
        }
}
