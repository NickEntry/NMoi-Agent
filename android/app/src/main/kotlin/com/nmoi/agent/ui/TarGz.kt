package com.nmoi.agent.ui

import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * 解 `.tar.gz`（D164 的 rootfs 用）。**不引依赖**：单个 tar 读取器，一百来行，
 * 比为了一个格式往依赖树里塞两个库划算——而且出问题时能一眼看出是谁的错。
 *
 * 支持的条目：普通文件、目录、符号链接、硬链接（复制一份）、GNU 长名（`L` / `K`）、
 * PAX 头（跳过）。**不支持的**：设备节点、FIFO、稀疏文件——Ubuntu Base 里没有它们，
 * 遇到了直接跳过并记一笔，不假装成功。
 *
 * 两条硬规矩（这条路径是"解别人的包到我们的私有目录"，出了问题就是穿透）：
 *
 * 1. **只准落在目标目录里面**：绝对路径、`..`、解析后跑出 dest 的，一律拒绝。
 * 2. **符号链接不许指向外面**：链接目标是绝对路径、或者带 `..` 的，拒绝。
 *    否则解出来的 `/etc/passwd -> /data/data/…/nmoi.db` 就成了读文件的跳板。
 */
internal object TarGz {
    private const val BLOCK = 512
    private const val MAX_BYTES = 512L * 1024 * 1024

    data class Report(
        val files: Int,
        val dirs: Int,
        val links: Int,
        val skipped: List<String>,
        val bytes: Long,
    )

    fun extract(archive: File, dest: File): Report {
        dest.mkdirs()
        val root = dest.canonicalFile
        var files = 0
        var dirs = 0
        var links = 0
        var bytes = 0L
        val skipped = mutableListOf<String>()

        GZIPInputStream(archive.inputStream().buffered(64 * 1024)).use { stream ->
            var longName: String? = null
            var longLink: String? = null
            while (true) {
                val header = readBlock(stream) ?: break
                if (header.all { it == 0.toByte() }) break

                val magicOk = String(header, 257, 5, Charsets.US_ASCII) == "ustar"
                if (!magicOk) {
                    // 不是 tar（或者已经错位）：停下来，不猜。
                    skipped += "头部不像 tar，停止解析"
                    break
                }
                val type = header[156].toInt().toChar()
                val size = octal(header, 124, 12)
                if (size < 0 || size > MAX_BYTES) {
                    skipped += "条目大小异常（$size）"
                    break
                }
                val rawName = name(header)
                val path = longName ?: rawName
                val link = longLink ?: linkName(header)
                longName = null
                longLink = null

                // PAX / GNU 扩展头：本身不落盘，只带下一项的元信息。
                when (type) {
                    'x', 'g' -> {
                        skip(stream, size)
                        continue
                    }
                    'L' -> {
                        longName = readString(stream, size).trimEnd('\u0000', '\n')
                        skip(stream, 0)
                        continue
                    }
                    'K' -> {
                        longLink = readString(stream, size).trimEnd('\u0000', '\n')
                        skip(stream, 0)
                        continue
                    }
                }

                // `./` 本身：GNU tar 会带一条空名条目，它就是 dest 目录，不是越界。
                if (path.isEmpty() || path == ".") {
                    target0(skipped, stream, size)
                    continue
                }

                val target = resolve(root, path)
                if (target == null) {
                    skipped += "拒绝越界路径：$path"
                    skip(stream, size)
                    continue
                }

                when (type) {
                    '5' -> {
                        target.mkdirs()
                        dirs += 1
                        skip(stream, size)
                    }
                    '2' -> {
                        if (!safeLink(root, link)) {
                            skipped += "拒绝可疑符号链接：$path -> $link"
                            skip(stream, size)
                            continue
                        }
                        target.parentFile?.mkdirs()
                        target.delete()
                        java.nio.file.Files.createSymbolicLink(
                            target.toPath(),
                            File(link).toPath(),
                        )
                        links += 1
                        skip(stream, size)
                    }
                    '1' -> {
                        // 硬链接：在别的实现里是指向同一个 inode；我们复制一份内容。
                        val source = resolve(root, link)
                        target.parentFile?.mkdirs()
                        if (source != null && source.isFile) {
                            source.copyTo(target, overwrite = true)
                            links += 1
                        } else {
                            skipped += "硬链接源不存在：$link"
                        }
                        skip(stream, size)
                    }
                    '0', '\u0000' -> {
                        target.parentFile?.mkdirs()
                        val written = copy(stream, target, size)
                        bytes += written
                        files += 1
                        applyMode(target, header)
                    }
                    else -> {
                        skipped += "跳过条目类型 '$type'：$path"
                        skip(stream, size)
                    }
                }
            }
        }
        return Report(files = files, dirs = dirs, links = links, skipped = skipped, bytes = bytes)
    }

    // -----------------------------------------------------------------------

    private fun target0(skipped: MutableList<String>, stream: InputStream, size: Long) {
        skip(stream, size)
    }

    private fun readBlock(stream: InputStream): ByteArray? {
        val block = ByteArray(BLOCK)
        var read = 0
        while (read < BLOCK) {
            val count = stream.read(block, read, BLOCK - read)
            if (count < 0) return if (read == 0) null else block.copyOf(read)
            read += count
        }
        return block
    }

    private fun name(header: ByteArray): String {
        val base = text(header, 0, 100)
        val prefix = text(header, 345, 155)
        val joined = if (prefix.isEmpty()) base else "$prefix/$base"
        return joined.removePrefix("./")
    }

    private fun linkName(header: ByteArray): String = text(header, 157, 100)

    private fun text(header: ByteArray, offset: Int, length: Int): String =
        String(header, offset, length, Charsets.UTF_8).substringBefore('\u0000').trim()

    private fun octal(header: ByteArray, offset: Int, length: Int): Long {
        val raw = String(header, offset, length, Charsets.US_ASCII)
            .trim { it == ' ' || it == '\u0000' }
        if (raw.isEmpty()) return 0
        return raw.toLongOrNull(8) ?: -1
    }

    /** 解析成 dest 里的真实路径；跑出去就返回 null。 */
    private fun resolve(root: File, path: String): File? {
        if (path.isEmpty()) return null
        if (path.startsWith("/")) return null
        val candidate = File(root, path).canonicalFile
        return if (candidate.path == root.path || candidate.path.startsWith(root.path + File.separator)) {
            candidate
        } else {
            null
        }
    }

    /** 符号链接：绝对路径、或者带 `..` 的，一律拒绝。 */
    private fun safeLink(root: File, link: String): Boolean {
        if (link.isEmpty()) return false
        if (link.startsWith("/")) return false
        if (link.split('/').any { it == ".." }) return false
        return true
    }

    private fun copy(stream: InputStream, target: File, size: Long): Long {
        var left = size
        var written = 0L
        target.outputStream().buffered().use { out ->
            val buffer = ByteArray(64 * 1024)
            while (left > 0) {
                val count = stream.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                if (count < 0) break
                out.write(buffer, 0, count)
                left -= count
                written += count
            }
        }
        // 数据块补齐到 512 的倍数。
        skip(stream, (BLOCK - (size % BLOCK)) % BLOCK)
        return written
    }

    private fun readString(stream: InputStream, size: Long): String {
        val bytes = ByteArray(size.toInt())
        var read = 0
        while (read < bytes.size) {
            val count = stream.read(bytes, read, bytes.size - read)
            if (count < 0) break
            read += count
        }
        skip(stream, (BLOCK - (size % BLOCK)) % BLOCK)
        return String(bytes, 0, read, Charsets.UTF_8)
    }

    private fun skip(stream: InputStream, count: Long) {
        var left = count
        while (left > 0) {
            val skipped = stream.skip(left)
            if (skipped <= 0) {
                // 有的流 skip 会返回 0：退化成读一字节，别死循环。
                if (stream.read() < 0) return
                left -= 1
            } else {
                left -= skipped
            }
        }
    }

    /** 可执行位：rootfs 里的 `/bin/sh` 没有 x 位就是一堆死文件。 */
    private fun applyMode(target: File, header: ByteArray) {
        val mode = octal(header, 100, 8)
        if (mode < 0) return
        if (mode and 0b001_000_000L != 0L) target.setReadable(true, true)
        if (mode and 0b000_100_000L != 0L) target.setWritable(true, true)
        if (mode and 0b000_001_000L != 0L) target.setExecutable(true, true)
    }
}
