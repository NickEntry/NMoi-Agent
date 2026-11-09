package com.nmoi.agent.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 一个待发的文件附件（D304）。
 *
 * 只有**读得出文本**的文件进来：模型收到的是一段文字，界面上看到的也是同一段文字
 * （它会被拼进用户那条消息的正文）。二进制进来只会得到一个看不清的框，所以直接挡在门外，
 * 并说清是哪种类型挡的。
 */
internal data class PendingFile(
    val name: String,
    val mime: String,
    val bytes: Int,
    val text: String,
    val truncated: Boolean,
) {
    val sizeLabel: String
        get() = when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "${(bytes / 1024.0 / 1024.0 * 10).toInt() / 10.0} MB"
        }
}

internal object FileAttach {
    const val MAX_FILES = 4
    const val MAX_TEXT_BYTES = 64 * 1024

    private val TEXT_MIME_PREFIX = listOf("text/")
    private val TEXT_MIME = setOf(
        "application/json",
        "application/xml",
        "application/yaml",
        "application/x-yaml",
        "application/toml",
        "application/javascript",
        "application/x-sh",
        "application/sql",
    )
    private val TEXT_EXT = setOf(
        "txt", "md", "markdown", "json", "jsonl", "yaml", "yml", "toml", "ini", "csv", "tsv",
        "log", "xml", "html", "htm", "css", "scss", "sass", "less", "sql",
        "kt", "kts", "java", "rs", "py", "js", "jsx", "ts", "tsx", "c", "h", "cc", "cpp", "hpp",
        "go", "rb", "php", "sh", "bash", "zsh", "lua", "dart", "swift", "gradle", "properties",
        "cfg", "conf", "env", "gitignore", "dockerfile", "makefile",
    )

    /**
     * 这个文件能不能当文本读。
     *
     * 先看 MIME（系统给的那种），再看扩展名——有些来源给的 MIME 是
     * `application/octet-stream`，光看它会把 `.md` 也挡在门外。
     */
    fun looksText(name: String, mime: String): Boolean {
        val type = mime.trim().lowercase()
        if (TEXT_MIME_PREFIX.any { type.startsWith(it) }) return true
        if (type in TEXT_MIME) return true
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isNotEmpty() && ext in TEXT_EXT) return true
        // 没有扩展名的那种（`Dockerfile` / `Makefile`）：整名小写比一比。
        return name.lowercase() in TEXT_EXT
    }

    suspend fun read(context: Context, uri: Uri): Result<PendingFile> =
        withContext(Dispatchers.IO) {
            runCatching {
                val resolver = context.contentResolver
                var shown = ""
                var size = 0
                resolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex >= 0) shown = cursor.getString(nameIndex).orEmpty()
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getInt(sizeIndex)
                    }
                }
                val mime = resolver.getType(uri).orEmpty()
                val name = shown.ifBlank { uri.lastPathSegment?.substringAfterLast('/') ?: "文件" }
                if (!looksText(name, mime)) {
                    error("「$name」不是文本文件（${mime.ifBlank { "类型不明" }}），这一版只收能读成文字的文件")
                }
                val raw = resolver.openInputStream(uri)?.use { input ->
                    val buffer = ByteArray(MAX_TEXT_BYTES + 1)
                    var read = 0
                    while (read < buffer.size) {
                        val step = input.read(buffer, read, buffer.size - read)
                        if (step <= 0) break
                        read += step
                    }
                    buffer.copyOf(read)
                } ?: error("打不开「$name」")
                if (raw.isEmpty()) error("「$name」是空的")
                if (raw.any { it == 0.toByte() }) {
                    error("「$name」看着是二进制（里面有 0 字节），这一版只收文本")
                }
                val truncated = raw.size > MAX_TEXT_BYTES
                val body = if (truncated) raw.copyOf(MAX_TEXT_BYTES) else raw
                PendingFile(
                    name = name,
                    mime = mime,
                    bytes = if (size > 0) size else raw.size,
                    text = String(body, Charsets.UTF_8),
                    truncated = truncated,
                )
            }
        }

    /** 拼成用户那条消息里的一段。界面上看到的就是这一段，不是藏在别处的元数据。 */
    fun render(files: List<PendingFile>): String = files.joinToString("\n\n") { file ->
        buildString {
            append("【附件：")
            append(file.name)
            if (file.truncated) append("（只取了前 ${MAX_TEXT_BYTES / 1024} KB）")
            append("】\n```\n")
            append(file.text.trimEnd())
            append("\n```")
        }
    }
}
