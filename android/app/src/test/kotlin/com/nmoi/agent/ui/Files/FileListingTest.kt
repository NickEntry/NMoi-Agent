package com.nmoi.agent.ui.Files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工作区列表的排序与显示规则（D392）。
 *
 * 三条容易写错的：目录永远在前、名字大小写不敏感、时间/大小为 0 的排最后
 * （0 不是"很小"，是"不知道"）。
 */
class FileListingTest {

    private fun dir(name: String, modified: Long = 1_000L) =
        FileRow(name = name, path = name, isDir = true, sizeBytes = 4096, modifiedMs = modified)

    private fun file(name: String, size: Long = 10, modified: Long = 1_000L) =
        FileRow(name = name, path = name, isDir = false, sizeBytes = size, modifiedMs = modified)

    private fun names(rows: List<FileRow>) = rows.map { it.name }

    @Test
    fun `目录永远在文件前面，升降序都一样`() {
        val rows = listOf(file("a.txt", size = 999), dir("z"), file("b.txt"))
        for (order in listOf(
            FileOrder(FileSort.Name),
            FileOrder(FileSort.Name, desc = true),
            FileOrder(FileSort.Size),
            FileOrder(FileSort.Size, desc = true),
            FileOrder(FileSort.Modified),
            FileOrder(FileSort.Modified, desc = true),
        )) {
            val first = FileListing.sorted(rows, order).first()
            assertEquals(order.toString(), "z", first.name)
        }
    }

    @Test
    fun `名字排序大小写不敏感`() {
        val rows = listOf(file("b.txt"), file("A.txt"), file("a.txt"), file("C.txt"))
        val sorted = FileListing.sorted(rows, FileOrder(FileSort.Name))
        assertEquals(listOf("A.txt", "a.txt", "b.txt", "C.txt"), names(sorted))
    }

    @Test
    fun `大小排序时目录不按大小散开`() {
        val rows = listOf(
            dir("big", modified = 5),
            file("huge.bin", size = 9_000),
            dir("small", modified = 5),
            file("tiny.txt", size = 1),
        )
        val sorted = names(FileListing.sorted(rows, FileOrder(FileSort.Size)))
        assertEquals(listOf("big", "small", "tiny.txt", "huge.bin"), sorted)
    }

    @Test
    fun `时间排序把不知道时间的排最后`() {
        val rows = listOf(
            file("unknown", modified = 0),
            file("old", modified = 100),
            file("new", modified = 900),
        )
        assertEquals(
            listOf("old", "new", "unknown"),
            names(FileListing.sorted(rows, FileOrder(FileSort.Modified))),
        )
        // 降序时"不知道"也不该跑到最前面。
        assertEquals(
            listOf("new", "old", "unknown"),
            names(FileListing.sorted(rows, FileOrder(FileSort.Modified, desc = true))),
        )
    }

    @Test
    fun `再点一次同一个键翻转方向，换键回到升序`() {
        val start = FileOrder(FileSort.Name, desc = true)
        assertEquals(FileOrder(FileSort.Name, desc = false), start.toggled(FileSort.Name))
        assertEquals(FileOrder(FileSort.Size, desc = false), start.toggled(FileSort.Size))
        assertEquals("名称 ↑", FileOrder(FileSort.Name).label)
        assertEquals("大小 ↓", FileOrder(FileSort.Size, desc = true).label)
    }

    @Test
    fun `隐藏文件按开头那一个点判`() {
        assertFalse(FileListing.visible(file(".git"), showHidden = false))
        assertFalse(FileListing.visible(file(".nmoi-workspace.json"), showHidden = false))
        assertTrue(FileListing.visible(file(".git"), showHidden = true))
        assertTrue(FileListing.visible(file("a.txt"), showHidden = false))
    }

    @Test
    fun `统计与副标题`() {
        assertEquals("空目录", FileListing.summary(emptyList()))
        assertEquals("2 个文件", FileListing.summary(listOf(file("a"), file("b"))))
        assertEquals("1 个文件夹", FileListing.summary(listOf(dir("d"))))
        assertEquals(
            "3 项 · 1 个文件夹",
            FileListing.summary(listOf(dir("d"), file("a"), file("b"))),
        )
        // 目录不给大小：那 4096 是 inode 自己的字节数，对用户没意义。
        assertFalse(FileListing.subtitle(dir("d")).contains("KB"))
        assertTrue(FileListing.subtitle(file("a", size = 2048)).startsWith("2.0 KB"))
        assertEquals("空文件", FileListing.subtitle(file("a", size = 0, modified = 0)))
        assertTrue(FileListing.subtitle(file("a", size = 0, modified = 0, )).isNotEmpty())
    }

    @Test
    fun `符号链接在副标题里说出来`() {
        val link = FileRow("l", "l", isDir = false, sizeBytes = 0, modifiedMs = 0, isSymlink = true)
        assertTrue(FileListing.subtitle(link).contains("链接"))
    }

    @Test
    fun `空列表排序不崩`() {
        assertTrue(FileListing.sorted(emptyList(), FileOrder()).isEmpty())
    }

    @Test
    fun `字节数换算`() {
        assertEquals("512 B", humanFileSize(512))
        assertEquals("1.0 KB", humanFileSize(1024))
        assertEquals("1.5 KB", humanFileSize(1536))
        assertEquals("1.0 MB", humanFileSize(1024L * 1024))
        assertTrue(humanFileSize(5L * 1024 * 1024 * 1024).endsWith("GB"))
    }
}
