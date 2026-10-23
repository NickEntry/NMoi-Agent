package com.nmoi.agent.ui

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * 应用内日志文件的落盘与轮转。
 *
 * 为什么不在 logcat 里就够了：2026-10-23 真机上整段 `NMoi/` 前缀的日志消失了——
 * `app_log/enabled=true`、`force-stop` 之后是新 pid、`App 启动入口` 是 `onCreate`
 * 的第一句，`logcat -d` 里一条都没有。同一台机器更早出现过的只有 `E NMoi/NMoiIcon`。
 * 也就是说 `I` 级输出被 ROM 吞了。**证据不能只放在别人手里能关掉的那根线上。**
 *
 * 形状：单个文件、追写、封顶。超过上限时保留**最新的一半**（不是全清）——
 * 全清会让"刚复现完去翻日志"正好翻到空文件。
 *
 * 线程：日志可能从任意线程进来（原生回调、Compose、IO 线程），全部串到一把锁里。
 * 每次追写都独立 open/close：丢掉一点点吞吐，换"进程被砍也不丢已经写进去的行"。
 */
internal object LogFile {
    const val FILE_NAME = "nmoi.log"

    private val lock = Any()
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    fun append(dir: File?, tag: String, level: Char, message: String, limitBytes: Long) {
        val folder = dir ?: return
        val line = "${LocalTime.now().format(timeFormat)} $level/$tag: $message\n"
        val bytes = line.toByteArray(Charsets.UTF_8)
        synchronized(lock) {
            // 写日志失败不能让调用方跟着崩：这条路径在崩溃排查里最常被用到。
            runCatching {
                folder.mkdirs()
                val file = File(folder, FILE_NAME)
                if (limitBytes > 0 && file.length() + bytes.size > limitBytes) {
                    trim(file, limitBytes / 2)
                }
                FileOutputStream(file, true).use { it.write(bytes) }
            }
        }
    }

    fun size(dir: File?): Long {
        val folder = dir ?: return 0L
        return synchronized(lock) { runCatching { File(folder, FILE_NAME).length() }.getOrDefault(0L) }
    }

    /** 末尾最多 [maxBytes] 字节。切在多字节字符中间时，头一行可能是替换字符——不修，说明在注释里。 */
    fun tail(dir: File?, maxBytes: Int): String {
        val folder = dir ?: return ""
        synchronized(lock) {
            return runCatching {
                val file = File(folder, FILE_NAME)
                if (!file.exists() || maxBytes <= 0) return ""
                val size = file.length()
                val start = maxOf(0L, size - maxBytes)
                val buffer = ByteArray((size - start).toInt())
                RandomAccessFile(file, "r").use { handle ->
                    handle.seek(start)
                    handle.readFully(buffer)
                }
                var offset = 0
                // 不是从头开始的话，先跳过被截断的那半行。
                if (start > 0) {
                    while (offset < buffer.size && buffer[offset] != '\n'.code.toByte()) offset++
                    if (offset < buffer.size) offset++
                }
                String(buffer, offset, buffer.size - offset, Charsets.UTF_8)
            }.getOrDefault("")
        }
    }

    fun clear(dir: File?) {
        val folder = dir ?: return
        synchronized(lock) {
            runCatching {
                File(folder, FILE_NAME).delete()
                File(folder, "$FILE_NAME.tmp").delete()
            }
        }
    }

    /**
     * 砍到只剩最新 [keepBytes]。先 `.tmp` 再 rename（D48）：读到半个文件比读不到更坏，
     * 而读它的人正是在排障。
     */
    private fun trim(file: File, keepBytes: Long) {
        val size = file.length()
        if (size <= keepBytes || keepBytes <= 0) return
        val start = size - keepBytes
        val buffer = ByteArray((size - start).toInt())
        RandomAccessFile(file, "r").use { handle ->
            handle.seek(start)
            handle.readFully(buffer)
        }
        var offset = 0
        while (offset < buffer.size && buffer[offset] != '\n'.code.toByte()) offset++
        if (offset < buffer.size) offset++
        val temporary = File(file.parentFile, "$FILE_NAME.tmp")
        FileOutputStream(temporary).use { it.write(buffer, offset, buffer.size - offset) }
        if (!temporary.renameTo(file)) {
            file.delete()
            temporary.renameTo(file)
        }
    }
}
