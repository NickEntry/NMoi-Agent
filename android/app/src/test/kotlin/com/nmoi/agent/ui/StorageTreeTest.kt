package com.nmoi.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 存储树的纯逻辑（D434）。
 *
 * 这三条判据错起来**不报错、不编译错**，只会让用户看见一个说不通的数字或者一个
 * 删掉就坏的入口，所以全部钉在这里：
 *
 * 1. 上一层报的数 == 这一层条目之和（少算一处，就是"300 MB 的目录点进去只有 2 MB"）。
 * 2. `标题 · 短id` 能分开两条同名会话。
 * 3. 哪一层给删是按规矩来的：沙箱只读、技能只在一级删、会话内部只删文件。
 */
class StorageTreeTest {

    private fun dir(root: File, path: String): File {
        val target = File(root, path)
        target.mkdirs()
        return target
    }

    private fun file(root: File, path: String, bytes: Int): File =
        File(root, path).apply {
            parentFile.mkdirs()
            writeBytes(ByteArray(bytes))
        }

    @Test
    fun `会话名字带短 id，两条同名会话能分开`() {
        val a = StorageTree.sessionLabel("AI客户端开发-37", "0199f0c3-2a1b-4c3d-9e8f-0123456789ab")
        val b = StorageTree.sessionLabel("AI客户端开发-37", "0199f0d1-2a1b-4c3d-9e8f-0123456789ab")
        assertEquals("AI客户端开发-37 · 0199f0c3", a)
        assertEquals("AI客户端开发-37 · 0199f0d1", b)
        assertTrue(a != b)
    }

    @Test
    fun `标题为空写没有标题，不编名字`() {
        assertEquals(
            "（没有标题） · 0199f0c3",
            StorageTree.sessionLabel(null, "0199f0c3-2a1b-4c3d-9e8f-0123456789ab"),
        )
        assertEquals(
            "（没有标题） · 0199f0c3",
            StorageTree.sessionLabel("   ", "0199f0c3-2a1b-4c3d-9e8f-0123456789ab"),
        )
    }

    @Test
    fun `量目录是递归的，子项之和等于父项`() {
        val root = File.createTempFile("tree", "").apply { delete(); mkdirs() }
        try {
            file(root, "a/b/c.txt", 100)
            file(root, "a/d.txt", 300)
            file(root, "e.txt", 7)

            val all = StorageTree.measure(root)
            assertEquals(407L, all.bytes)
            assertEquals(3, all.files)

            // 判据 1：这一层的条目大小之和 == 上一级报的数。
            val level = StorageTree.children(root, StorageKind.Workspace, 2)
            assertEquals(all.bytes, level.sumOf { it.bytes })
            assertEquals(all.files, level.sumOf { it.files })

            val nested = StorageTree.children(File(root, "a"), StorageKind.Workspace, 3)
            assertEquals(400L, nested.sumOf { it.bytes })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `列一层时目录在前、名字大小写不敏感`() {
        val root = File.createTempFile("tree", "").apply { delete(); mkdirs() }
        try {
            file(root, "zebra.txt", 1)
            file(root, "Apple.txt", 1)
            dir(root, "amber")
            dir(root, "Zeta")

            val names = StorageTree.children(root, StorageKind.Skills, 1).map { it.name }
            // 大小写不敏感：`amber` 在 `Zeta` 前面（按字节比会把所有大写排到前面）。
            assertEquals(listOf("amber", "Zeta", "Apple.txt", "zebra.txt"), names)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `不存在的目录量出来是零，不抛`() {
        val measure = StorageTree.measure(File("这一层根本不在/下面的目录"))
        assertEquals(0L, measure.bytes)
        assertEquals(0, measure.files)
        assertTrue(StorageTree.children(File("这一层根本不在/下面的目录"), StorageKind.Skills, 1)
            .isEmpty())
    }

    @Test
    fun `沙箱环境只读`() {
        for (depth in 1..4) {
            for (isDir in listOf(true, false)) {
                assertFalse(StorageTree.canDelete(StorageKind.Envir, depth, isDir))
            }
        }
    }

    @Test
    fun `技能只在一级删，进去之后不给删`() {
        assertTrue(StorageTree.canDelete(StorageKind.Skills, 1, isDir = true))
        assertFalse(StorageTree.canDelete(StorageKind.Skills, 1, isDir = false))
        assertFalse(StorageTree.canDelete(StorageKind.Skills, 2, isDir = true))
        assertFalse(StorageTree.canDelete(StorageKind.Skills, 3, isDir = false))
    }

    @Test
    fun `工作区只删里面的东西，不删工作区本身`() {
        assertFalse(StorageTree.canDelete(StorageKind.Workspace, 1, isDir = true))
        assertTrue(StorageTree.canDelete(StorageKind.Workspace, 2, isDir = true))
        assertTrue(StorageTree.canDelete(StorageKind.Workspace, 2, isDir = false))
    }

    @Test
    fun `会话内部只删文件，会话本身与两个子目录都不删`() {
        assertFalse(StorageTree.canDelete(StorageKind.SessionFiles, 1, isDir = true))
        assertFalse(StorageTree.canDelete(StorageKind.SessionFiles, 2, isDir = true))
        assertTrue(StorageTree.canDelete(StorageKind.SessionFiles, 2, isDir = false))
        assertTrue(StorageTree.canDelete(StorageKind.SessionFiles, 3, isDir = false))
    }

    @Test
    fun `十项都归到三种形状，没有漏掉的那一项`() {
        val shapes = StorageKind.entries.map { it.shape }
        assertEquals(StorageKind.entries.size, shapes.size)
        // 形状是分发的开关：少一项没归形状，它在界面上就没有落点。
        assertTrue(shapes.all { it == StorageShape.Tree || it == StorageShape.Cards || it == StorageShape.Flat })
        // 十项都有形状、每种形状都至少有一项——形状有一项没挂上，这一页点进去是空白。
        assertEquals(3, shapes.toSet().size)
        assertEquals(
            4,
            shapes.filter { it == StorageShape.Tree }.size,
        )
        assertEquals(
            listOf(
                StorageKind.Cache,
                StorageKind.Logs,
                StorageKind.Other,
                StorageKind.Chat,
            ).toSet(),
            StorageKind.entries.filter { it.shape == StorageShape.Cards }.toSet(),
        )
        assertEquals(
            listOf(StorageKind.Images, StorageKind.Files).toSet(),
            StorageKind.entries.filter { it.shape == StorageShape.Flat }.toSet(),
        )
    }
}
