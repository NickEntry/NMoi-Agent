package com.nmoi.agent.ui

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 歌唱声库的安装与管理（0.17b）。
 *
 * 声库**不能随包发**：DiffSinger / UTAU 的声库都有各自的授权条款，多数明确禁止再分发。
 * 所以这里只做"装、查、选、卸"，不内置任何声库本体——跟容器那一套一样是可选下载，
 * 差别是**来源由用户给**（作者发布页的 zip 链接，或者本地文件）。
 *
 * 落地形状与 OpenUTAU / warble 一致：一个目录里有 `dsconfig.yaml`（DiffSinger）
 * 或 `oto.ini`（UTAU）。判据就是这两个文件——没有它们，装进去也用不了。
 */
internal object VoiceBankRepository {
    private const val PREFS = "nmoi.local"
    private const val KEY_SELECTED = "voice.svs.selected"

    /** 一次安装的进度。跟 `EnvirRepository.Phase` 一个形状，界面照抄那套渲染。 */
    sealed interface Phase {
        data class Download(val bytes: Long, val total: Long) : Phase
        data class Extract(val entries: Int) : Phase
        data object Verify : Phase
    }

    data class Installed(
        val id: String,
        val name: String,
        val languages: String,
        val bytes: Long,
        val kind: String,
        val source: String,
        val installedAt: Long,
        val dir: File,
    )

    fun root(context: Context): File = File(File(context.filesDir, "voice"), "svs")

    /** 装好的声库。只认带 `dsconfig.yaml` 或 `oto.ini` 的目录，别的一律不算。 */
    fun installed(context: Context): List<Installed> {
        val root = root(context)
        if (!root.isDir) return emptyList()
        return root.listFiles()
            .orEmpty()
            .filter { it.isDirectory }
            .mapNotNull { dir -> read(dir) }
            .sortedBy { it.name.lowercase() }
    }

    private fun read(dir: File): Installed? {
        val manifest = File(dir, "bank.json")
        val meta = runCatching { JSONObject(manifest.readText()) }.getOrNull()
        val kind = when {
            File(dir, "dsconfig.yaml").isFile -> "diffsinger"
            File(dir, "oto.ini").isFile || dir.walkTopDown().any { it.name.equals("oto.ini", true) } -> "utau"
            else -> return null
        }
        return Installed(
            id = meta?.optString("id").orEmpty().ifBlank { dir.name },
            name = meta?.optString("name").orEmpty().ifBlank { dir.name },
            languages = meta?.optString("languages").orEmpty(),
            bytes = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() },
            kind = kind,
            source = meta?.optString("source").orEmpty(),
            installedAt = meta?.optLong("installed_at", 0L) ?: 0L,
            dir = dir,
        )
    }

    fun selected(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SELECTED, "").orEmpty()

    fun select(context: Context, id: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SELECTED, id)
            .apply()
    }

    /**
     * 从一条 URL 装一个声库 zip。
     *
     * 三条硬规矩：
     * 1. 先落到 `.part`，校验通过才改名——中途断掉不会留下半个"装好了"的目录；
     * 2. 解包防穿越：条目名里出现 `..` 或绝对路径直接拒绝，不"跳过继续"；
     * 3. 装完检查 `dsconfig.yaml` / `oto.ini`，没有就整条回滚（那种 zip 是别的东西）。
     *
     * `sha256` 可空：作者页通常不给校验和，硬要求等于不让装。
     */
    suspend fun install(
        context: Context,
        name: String,
        url: String,
        sha256: String,
        languages: String,
        onPhase: (Phase) -> Unit,
    ): Result<Installed> = withContext(Dispatchers.IO) {
        runCatching {
            val id = safeId(name)
            val root = root(context)
            if (!root.isDir && !root.mkdirs()) error("建不了目录：${root.absolutePath}")
            val target = File(root, id)
            if (target.exists()) error("已经装过一个叫「$name」的声库了，先删掉再装")

            val part = File(root, "$id.part")
            val staging = File(root, "$id.tmp")
            part.delete()
            staging.deleteRecursively()

            try {
                download(url, part) { done, total -> onPhase(Phase.Download(done, total)) }
                if (sha256.isNotBlank()) {
                    onPhase(Phase.Verify)
                    val actual = sha256(part)
                    if (!actual.equals(sha256.trim(), ignoreCase = true)) {
                        error("校验和不符：期望 ${sha256.take(12)}…，实际 ${actual.take(12)}…")
                    }
                }
                if (!staging.mkdirs()) error("建不了临时目录：${staging.absolutePath}")
                var count = 0
                unzip(part, staging) { seen ->
                    count = seen
                    onPhase(Phase.Extract(seen))
                }
                flatten(staging)
                val kind = when {
                    File(staging, "dsconfig.yaml").isFile -> "diffsinger"
                    staging.walkTopDown().any { it.name.equals("oto.ini", true) } -> "utau"
                    else -> error("这个 zip 里没有 dsconfig.yaml 也没有 oto.ini——不是声库")
                }
                File(staging, "bank.json").writeText(
                    JSONObject()
                        .put("id", id)
                        .put("name", name)
                        .put("languages", languages)
                        .put("kind", kind)
                        .put("source", url)
                        .put("installed_at", System.currentTimeMillis())
                        .toString(2),
                )
                if (!staging.renameTo(target)) error("装不进去：改名失败（${target.absolutePath}）")
                part.delete()
                if (selected(context).isBlank()) select(context, id)
                read(target) ?: error("装完读不出来：目录结构不对")
            } catch (error: Throwable) {
                part.delete()
                staging.deleteRecursively()
                throw error
            }
        }
    }

    fun uninstall(context: Context, id: String): Result<Unit> = runCatching {
        val dir = File(root(context), id)
        if (!dir.exists()) return@runCatching
        val gone = dir.deleteRecursively()
        if (!gone) error("删不掉：${dir.absolutePath}")
        if (selected(context) == id) select(context, "")
        NLog.i("Voice", "删了声库 $id")
    }

    private fun safeId(name: String): String {
        val base = name.trim().ifBlank { "bank" }
        val cleaned = base.map { ch ->
            if (ch.isLetterOrDigit() || ch == '-' || ch == '_') ch else '_'
        }.joinToString("")
        return cleaned.take(48).ifBlank { "bank" }
    }

    /**
     * 有些 zip 把声库套了一层目录（`声库名/dsconfig.yaml`）。这里把它压平一层，
     * 否则后面按路径找不到 dsconfig。只压一层：多层的 zip 本身就不合格式。
     */
    private fun flatten(dir: File) {
        val children = dir.listFiles().orEmpty()
        if (children.size == 1 && children[0].isDirectory) {
            val inner = children[0]
            inner.listFiles().orEmpty().forEach { file ->
                if (!file.renameTo(File(dir, file.name))) {
                    file.copyRecursively(File(dir, file.name), overwrite = true)
                }
            }
            inner.deleteRecursively()
        }
    }

    private fun download(url: String, target: File, onProgress: (Long, Long) -> Unit) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "NMoi-Agent")
        }
        connection.connect()
        try {
            if (connection.responseCode !in 200..299) {
                error("下载失败：HTTP ${connection.responseCode}")
            }
            val total = connection.contentLengthLong.coerceAtLeast(0L)
            connection.inputStream.use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    var last = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        done += read
                        if (done - last > 512 * 1024 || done == total) {
                            last = done
                            onProgress(done, total)
                        }
                    }
                    output.flush()
                    onProgress(done, total)
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun unzip(zip: File, into: File, onEntries: (Int) -> Unit) {
        var count = 0
        var budget = 4L * 1024 * 1024 * 1024
        ZipInputStream(zip.inputStream().buffered()).use { stream ->
            while (true) {
                val entry = stream.nextEntry ?: break
                count += 1
                if (count > 20_000) error("zip 条目太多（>20000），不像是一个声库")
                val name = entry.name.replace('\\', '/')
                if (name.startsWith("/") || name.split('/').any { it == ".." }) {
                    error("zip 里有越界条目：$name")
                }
                val out = File(into, name)
                if (!out.canonicalPath.startsWith(into.canonicalPath + File.separator)) {
                    error("zip 里有越界条目：$name")
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                    continue
                }
                out.parentFile?.mkdirs()
                out.outputStream().use { sink ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = stream.read(buffer)
                        if (read <= 0) break
                        sink.write(buffer, 0, read)
                        budget -= read
                        if (budget < 0) error("解包超过 4 GB，停下")
                    }
                }
                if (count % 25 == 0) onEntries(count)
            }
        }
        onEntries(count)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
