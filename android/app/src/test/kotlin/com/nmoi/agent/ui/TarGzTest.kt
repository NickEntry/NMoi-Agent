package com.nmoi.agent.ui

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 解包器（`TarGz`）。
 *
 * 这里钉的是**覆盖安装**那条路（D235）：同一份包解第二遍时，目标树里已经有链接了。
 * 老实现用 `canonicalFile` 解析条目名，于是 `bin -> usr/bin` 这一条被算成"往 `usr/bin` 上建链接"，
 * 直接抛 `FileAlreadyExistsException`，整次覆盖安装挂掉——真机上就是这么挂的。
 */
class TarGzTest {

    // ---------------------------------------------------------------- 造包

    private fun entry(
        name: String,
        type: Char,
        link: String = "",
        content: String = "",
    ): ByteArray {
        val data = content.toByteArray(Charsets.UTF_8)
        val header = ByteArray(512)
        fun put(offset: Int, length: Int, text: String) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            bytes.copyInto(header, offset, 0, minOf(bytes.size, length))
        }
        put(0, 100, name)
        put(100, 8, "0000644\u0000")
        put(108, 8, "0000000\u0000")
        put(116, 8, "0000000\u0000")
        put(124, 12, String.format("%011o", data.size) + "\u0000")
        put(136, 12, "00000000000\u0000")
        header[156] = type.code.toByte()
        put(157, 100, link)
        put(257, 6, "ustar\u0000")
        put(263, 2, "00")
        for (i in 148 until 156) header[i] = ' '.code.toByte()
        var sum = 0
        for (b in header) sum += b.toInt() and 0xff
        put(148, 8, String.format("%06o", sum) + "\u0000 ")
        val padding = ByteArray((512 - data.size % 512) % 512)
        return header + data + padding
    }

    private fun archive(dir: File, blocks: List<ByteArray>): File {
        val file = File(dir, "test.tar.gz")
        ByteArrayOutputStream().use { out ->
            GZIPOutputStream(out).use { gz ->
                for (block in blocks) gz.write(block)
                gz.write(ByteArray(1024))
            }
            file.writeBytes(out.toByteArray())
        }
        return file
    }

    /** 一份最小的 rootfs 形状：`bin -> usr/bin`，`usr/bin/cat -> ../bin/coreutils`。 */
    private fun rootfsLike(dir: File): File = archive(
        dir,
        listOf(
            entry("usr", '5'),
            entry("usr/bin", '5'),
            entry("usr/bin/coreutils", '0', content = "ELF"),
            entry("usr/bin/cat", '2', link = "../bin/coreutils"),
            entry("bin", '2', link = "usr/bin"),
        ),
    )

    // ---------------------------------------------------------------- 用例

    @Test
    fun `第一次解包-链接与文件都到位`() {
        val dir = Files.createTempDirectory("targz1").toFile()
        val dest = File(dir, "rootfs")
        val report = TarGz.extract(rootfsLike(dir), dest)
        assertEquals(0, report.skipped.size)
        assertTrue(Files.isSymbolicLink(File(dest, "bin").toPath()))
        assertEquals("usr/bin", Files.readSymbolicLink(File(dest, "bin").toPath()).toString())
        assertTrue(File(dest, "usr/bin/coreutils").isFile)
        assertFalse(Files.isSymbolicLink(File(dest, "usr/bin/coreutils").toPath()))
    }

    /**
     * 覆盖安装：**同一份包解第二遍**。
     *
     * 老实现在这里抛 `FileAlreadyExistsException: …/usr/bin`——因为 `bin` 已经被算成了 `usr/bin`。
     */
    @Test
    fun `解第二遍不跟随已有链接-也不把 coreutils 变成环`() {
        val dir = Files.createTempDirectory("targz2").toFile()
        val dest = File(dir, "rootfs")
        val archive = rootfsLike(dir)
        TarGz.extract(archive, dest)
        // 第二遍：这正是"覆盖安装"。
        val again = TarGz.extract(archive, dest)
        assertEquals("第二遍不该跳过任何条目", 0, again.skipped.size)
        assertEquals("usr/bin", Files.readSymbolicLink(File(dest, "bin").toPath()).toString())
        assertTrue("coreutils 必须还是那个普通文件", File(dest, "usr/bin/coreutils").isFile)
        assertFalse(
            "coreutils 不能变成指回自己的链接",
            Files.isSymbolicLink(File(dest, "usr/bin/coreutils").toPath()),
        )
        // 链接链照样能走到底：cat → usr/bin/coreutils
        val cat = File(dest, "usr/bin/cat")
        assertTrue(Files.isSymbolicLink(cat.toPath()))
        assertEquals("ELF", File(cat.canonicalPath).readText())
    }

    @Test
    fun `越界条目被拒-不会写到 dest 外面`() {
        val dir = Files.createTempDirectory("targz3").toFile()
        val dest = File(dir, "rootfs")
        val escape = archive(
            dir,
            listOf(
                entry("../escaped.txt", '0', content = "nope"),
                entry("/etc/passwd", '0', content = "nope"),
                entry("ok.txt", '0', content = "fine"),
            ),
        )
        val report = TarGz.extract(escape, dest)
        assertFalse(File(dir, "escaped.txt").exists())
        assertEquals("fine", File(dest, "ok.txt").readText())
        assertEquals(2, report.skipped.size)
    }

    /** 同名目录挡住链接时跳过并记一笔：删目录等于删用户的东西。 */
    @Test
    fun `目录挡住链接-跳过并写进报告`() {
        val dir = Files.createTempDirectory("targz4").toFile()
        val dest = File(dir, "rootfs")
        File(dest, "var/run").mkdirs()
        File(dest, "var/run/keep.txt").writeText("用户的东西")
        val report = TarGz.extract(
            archive(dir, listOf(entry("var/run", '2', link = "../run"))),
            dest,
        )
        assertTrue(File(dest, "var/run").isDirectory)
        assertEquals("用户的东西", File(dest, "var/run/keep.txt").readText())
        assertEquals(1, report.skipped.size)
    }
}
