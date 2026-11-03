package com.nmoi.agent.ui

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 容器垫片的清理（D237 / 0.15bb）。
 *
 * 这一条钉的是"只摘我们自己写的那一行"：`/etc/ld.so.preload` 是**别人的文件**，
 * 上面可能还有用户或别的工具写的行。整份删掉 = 顺手拆了别人的东西；
 * 一行都不摘 = 容器继续起不来。真机上撞过一次（每个程序都报 `libdl.so` 找不到）。
 */
class ProxyContainerTest {

    private fun rootfs(): File = Files.createTempDirectory("rootfs").toFile()

    private fun writePreload(root: File, text: String): File {
        val file = File(root, "etc/ld.so.preload")
        file.parentFile?.mkdirs()
        file.writeText(text)
        return file
    }

    private fun writeShim(root: File): File {
        val shim = File(root, "usr/local/lib/nmoi-netshim.so")
        shim.parentFile?.mkdirs()
        shim.writeText("ELF")
        return shim
    }

    @Test
    fun `只摘我们那一行-别人的留着`() {
        val root = rootfs()
        val preload = writePreload(root, "/usr/local/lib/other.so\n/usr/local/lib/nmoi-netshim.so\n")
        writeShim(root)
        assertEquals(2, ProxyContainer.cleanStale(root))
        assertEquals("/usr/local/lib/other.so\n", preload.readText())
        assertFalse("垫片本身也该删掉", File(root, "usr/local/lib/nmoi-netshim.so").exists())
    }

    @Test
    fun `摘空了就把文件删掉`() {
        val root = rootfs()
        val preload = writePreload(root, "/usr/local/lib/nmoi-netshim.so\n")
        writeShim(root)
        assertEquals(2, ProxyContainer.cleanStale(root))
        assertFalse("空 preload 也是文件，留着只会让人以为这里配过什么", preload.exists())
    }

    @Test
    fun `没有残留就什么都不动`() {
        val root = rootfs()
        val preload = writePreload(root, "/usr/local/lib/other.so\n")
        assertEquals(0, ProxyContainer.cleanStale(root))
        assertTrue(preload.exists())
        assertEquals("/usr/local/lib/other.so\n", preload.readText())
    }

    @Test
    fun `没有 preload 文件也不报错`() {
        val root = rootfs()
        assertEquals(0, ProxyContainer.cleanStale(root))
    }
}
