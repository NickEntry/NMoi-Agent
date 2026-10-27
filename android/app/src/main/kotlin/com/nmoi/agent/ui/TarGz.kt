package com.nmoi.agent.ui

import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * 解 `.tar.gz`（D164 的 rootfs 用）。**不引依赖**：单个 tar 读取器，一百来行，
 * 比为了一个格式往依赖树里塞两个库划算——而且出问题时能一眼看出是谁的错。
 *
 * 支持的条目：普通文件、目录、符号链接、硬链接、GNU 长名（`L` / `K`）、
 * PAX 头（跳过）。**不支持的**：设备节点、FIFO、稀疏文件——Ubuntu Base 里没有它们，
 * 遇到了直接跳过并记一笔，不假装成功。
 *
 * 两条硬规矩（这条路径是"解别人的包到我们的私有目录"，出了问题就是穿透）：
 *
 * 1. **只准落在目标目录里面**：条目名是绝对路径、或者解析后跑出 dest 的，拒绝；
 *    写普通文件前先把同名旧链接删掉，绝不"顺着已有链接写到外面去"。
 * 2. **符号链接不许指向外面**：链接目标**按它所在目录做词法解析**，跑出 dest 的才拒绝。
 *    （早先的做法是"带 `..` 就拒"，实测会砍掉 Ubuntu Base 里 **171** 条正常链接：
 *    `usr/bin/cat -> ../lib/cargo/bin/coreutils/cat` 这种一发就没了，`ls`/`cat` 全废。）
 *
 * 另外两个实测结论（`ubuntu-base-26.04.1-base-arm64.tar.gz`，6564 条）：
 *
 * - 有 **19** 条符号链接的目标是**绝对路径**（`var/run -> /run`、`etc/alternatives/awk -> /usr/bin/mawk`）。
 *   它们只对"在 rootfs 里跑的进程"有意义：proot 会把这个 `/…` 解释成**容器里的** `/…`。
 *   所以 rootfs 场景要开 [Options.allowAbsoluteLinks]，原样存进去；别的场景保持拒绝。
 * - 有 **115** 条硬链接，其中一百多条是指向同一个 10 MB `usr/bin/coreutils` 的 applet。
 *   "复制一份内容"会把 35 MB 的 rootfs 撑到 1 GB 以上 → 默认用 `Files.createLink` 建真硬链接
 *   （同一个文件系统内成功），失败就记一笔跳过，不偷偷退化成复制。
 */
internal object TarGz {
    private const val BLOCK = 512
    private const val PROGRESS_STEP = 256
    private const val MAX_BYTES = 512L * 1024 * 1024

    /**
     * 解包策略。默认值 = "解一个来路不明的包"的口径（最严）；
     * 只有解 rootfs 这种"整棵树都归容器"的包才放开 [allowAbsoluteLinks]。
     */
    data class Options(
        /** 允许符号链接的目标是绝对路径（rootfs 需要；一般压缩包不需要）。 */
        val allowAbsoluteLinks: Boolean = false,
        /** 硬链接建成真硬链接，而不是复制内容。 */
        val hardLinksAsLinks: Boolean = true,
    )

    data class Report(
        val files: Int,
        val dirs: Int,
        val symlinks: Int,
        val hardLinks: Int,
        /** 硬链接退化成符号链接的条数（文件系统不许建硬链接时，比如 Android 的 app 私有目录）。 */
        val linkFallbacks: Int,
        val skipped: List<String>,
        val bytes: Long,
    )

    /**
     * @param onEntries 每处理 [PROGRESS_STEP] 条报一次已处理条数——解 35 MB 的 rootfs 要一分多钟，
     *   界面上没有这个数就只剩一个转圈，用户不知道是卡住了还是在干活。
     */
    fun extract(
        archive: File,
        dest: File,
        options: Options = Options(),
        onEntries: ((Int) -> Unit)? = null,
    ): Report {
        dest.mkdirs()
        val root = dest.canonicalFile
        var files = 0
        var dirs = 0
        var symlinks = 0
        var hardLinks = 0
        var linkFallbacks = 0
        var bytes = 0L
        val skipped = mutableListOf<String>()
        // 硬链接的源还没解出来的：等整条流走完再补（tar 不保证顺序，不能假设源在前）。
        val pendingLinks = mutableListOf<Pair<String, String>>()
        var processed = 0

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
                processed += 1
                if (onEntries != null && processed % PROGRESS_STEP == 0) onEntries(processed)
                val rawName = name(header)
                val path = longName ?: rawName
                val link = longLink ?: linkName(header)
                longName = null
                longLink = null

                // PAX / GNU 扩展头：本身不落盘，只带下一项的元信息。
                when (type) {
                    'x', 'g' -> {
                        skipEntry(stream, size)
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
                    skipEntry(stream, size)
                    continue
                }

                val target = resolve(root, path)
                if (target == null) {
                    skipped += "拒绝越界路径：$path"
                    skipEntry(stream, size)
                    continue
                }

                when (type) {
                    '5' -> {
                        // 同名旧条目是链接的话先删掉：后面往这个"目录"里写，就会顺着链接跑到外面。
                        if (java.nio.file.Files.isSymbolicLink(target.toPath())) target.delete()
                        target.mkdirs()
                        dirs += 1
                        skipEntry(stream, size)
                    }
                    '2' -> {
                        if (linkEscapes(path, link, options)) {
                            skipped += "拒绝越界符号链接：$path -> $link"
                            skipEntry(stream, size)
                            continue
                        }
                        target.parentFile?.mkdirs()
                        target.delete()
                        java.nio.file.Files.createSymbolicLink(
                            target.toPath(),
                            File(link).toPath(),
                        )
                        symlinks += 1
                        skipEntry(stream, size)
                    }
                    '1' -> {
                        // 硬链接：**建真硬链接**。复制内容会把 coreutils 那一百多份 10 MB applet
                        // 变成 1 GB 以上的磁盘占用，rootfs 一装就爆。
                        target.parentFile?.mkdirs()
                        when (linkInto(target, resolve(root, link))) {
                            LinkKind.Hard -> hardLinks += 1
                            LinkKind.Relative -> {
                                symlinks += 1
                                linkFallbacks += 1
                            }
                            LinkKind.None -> pendingLinks += path to link
                        }
                        skipEntry(stream, size)
                    }
                    '0', '\u0000' -> {
                        target.parentFile?.mkdirs()
                        // 先删掉同名旧条目：否则会顺着一个已存在的符号链接写到 dest 外面去。
                        if (!clearBeforeWrite(target, skipped)) {
                            skipEntry(stream, size)
                            continue
                        }
                        val written = copy(stream, target, size)
                        bytes += written
                        files += 1
                        applyMode(target, header)
                    }
                    else -> {
                        skipped += "跳过条目类型 '$type'：$path"
                        skipEntry(stream, size)
                    }
                }
            }
        }

        onEntries?.invoke(processed)

        // 流走完了再补硬链接：tar 不保证"源在前"，但**到这一步源一定都在磁盘上**。
        for ((path, link) in pendingLinks) {
            val target = resolve(root, path)
            when (linkInto(target, resolve(root, link))) {
                LinkKind.Hard -> hardLinks += 1
                LinkKind.Relative -> {
                    symlinks += 1
                    linkFallbacks += 1
                }
                LinkKind.None -> skipped += "硬链接源不存在：$path -> $link"
            }
        }

        return Report(
            files = files,
            dirs = dirs,
            symlinks = symlinks,
            hardLinks = hardLinks,
            linkFallbacks = linkFallbacks,
            skipped = skipped,
            bytes = bytes,
        )
    }

    // -----------------------------------------------------------------------

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

    /** 解析成 dest 里的真实路径；跑出去（或者在解析时炸了）就返回 null。 */
    private fun resolve(root: File, path: String): File? {
        if (path.isEmpty()) return null
        if (path.startsWith("/")) return null
        val candidate = runCatching { File(root, path).canonicalFile }.getOrNull() ?: return null
        return if (candidate.path == root.path || candidate.path.startsWith(root.path + File.separator)) {
            candidate
        } else {
            null
        }
    }

    /**
     * 符号链接会不会跑出 dest：**按"链接所在目录"做词法解析**，`..` 弹空了才算越界。
     *
     * 绝对路径目标：默认拒绝（来路不明的包）；rootfs 场景由 [Options.allowAbsoluteLinks] 放行，
     * 因为在容器里那个 `/…` 指的是 guest 的 `/…`。
     */
    private fun linkEscapes(path: String, link: String, options: Options): Boolean {
        if (link.isEmpty()) return true
        if (link.startsWith("/")) return !options.allowAbsoluteLinks
        val stack = ArrayDeque(path.split('/').dropLast(1).filter { it.isNotEmpty() && it != "." })
        for (part in link.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> if (stack.isEmpty()) return true else stack.removeLast()
                else -> stack.addLast(part)
            }
        }
        return false
    }

    private enum class LinkKind { Hard, Relative, None }

    /**
     * 硬链接条目落地：**先建真硬链接，不行退成符号链接，绝不复制内容**。
     *
     * Android 的 app 私有目录不许建硬链接（`link(2)` 被 SELinux 挡：真机上 Ubuntu Base 那
     * 115 条硬链接**全部**失败，界面上的 `链接 287+0` 就是这么来的），而复制一份内容意味着
     * coreutils 那一百多条 10 MB applet 变成 1 GB 以上。符号链接两头都躲开了：
     * 容器里 `cat` 一样能跑，因为它本来只关心 `argv[0]`。
     */
    private fun linkInto(target: File?, source: File?): LinkKind {
        if (target == null || source == null || !source.isFile) return LinkKind.None
        val hard = runCatching {
            java.nio.file.Files.deleteIfExists(target.toPath())
            java.nio.file.Files.createLink(target.toPath(), source.toPath())
        }.isSuccess
        if (hard) return LinkKind.Hard
        // 相对路径：容器里也成立（绝对路径得指到宿主机的 /data/...，在 guest 里不存在）。
        val relative = runCatching {
            target.toPath().parent.relativize(source.toPath()).toString()
        }.getOrNull() ?: return LinkKind.None
        val linked = runCatching {
            java.nio.file.Files.deleteIfExists(target.toPath())
            java.nio.file.Files.createSymbolicLink(target.toPath(), java.nio.file.Paths.get(relative))
        }.isSuccess
        return if (linked) LinkKind.Relative else LinkKind.None
    }

    /**
     * 写普通文件之前先把 dest 里的同名条目删掉：`FileOutputStream` 会**跟着符号链接写**，
     * 一个 `x -> /data/data/…/nmoi.db` 的链接加上一条同名文件条目，就能把库盖掉。
     * （`resolve` 用的是 canonical 路径，本身也会拦住这一手；这里是第二道。）
     */
    private fun clearBeforeWrite(target: File, skipped: MutableList<String>): Boolean {
        val path = target.toPath()
        val noFollow = arrayOf(java.nio.file.LinkOption.NOFOLLOW_LINKS)
        if (!java.nio.file.Files.exists(path, *noFollow)) return true
        if (java.nio.file.Files.isDirectory(path, *noFollow)) {
            skipped += "同名目录挡住了文件：${target.name}"
            return false
        }
        if (!target.delete()) {
            skipped += "同名条目删不掉：${target.name}"
            return false
        }
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

    /**
     * 跳过一个条目：**数据区 + 512 对齐的补白**。
     *
     * 只跳 `size` 是个安静的大坑：一个 7 字节的文件条目之后还剩 505 字节补白没吃，
     * 下一次读头就从半个块开始，读到的是文件内容而不是 `ustar` 魔数 → 整包"停止解析"，
     * 而调用方只看到"解出来很少几个文件"。本机拿真 rootfs 撞出来的（见 Harness 那条记录）。
     */
    private fun skipEntry(stream: InputStream, size: Long) {
        skip(stream, size)
        skip(stream, (BLOCK - (size % BLOCK)) % BLOCK)
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
