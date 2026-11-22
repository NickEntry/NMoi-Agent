package com.nmoi.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 变更文件列表的展示逻辑（D381）。
 *
 * 钉的都是"错了看不出来"的地方：排序、`+N` 是几、点一个已经不存在的文件会怎样。
 */
class ChangeListTest {

    private fun row(
        path: String,
        created: Boolean = false,
        added: Int? = 1,
        removed: Int? = 0,
        diff: String? = "+x\n",
        exists: Boolean = true,
    ) = ChangeRow(path, created, added, removed, diff, exists)

    @Test
    fun `按路径 A 到 Z 排，大小写不敏感`() {
        val rows = listOf(
            row("/w/b.rs"),
            row("/w/A.rs"),
            row("/w/c.rs"),
            row("/w/a.rs"),
        )
        val sorted = ChangeList.sorted(rows).map { ChangeList.name(it.path) }
        assertEquals(listOf("A.rs", "a.rs", "b.rs", "c.rs"), sorted)
    }

    @Test
    fun `气泡只铺三行`() {
        val rows = (1..10).map { row("/w/f$it.txt") }
        assertEquals(3, ChangeList.inline(rows).size)
        // 铺的是排好序的前三条
        assertEquals(
            listOf("f1.txt", "f10.txt", "f2.txt"),
            ChangeList.inline(rows).map { ChangeList.name(it.path) },
        )
    }

    @Test
    fun `只有两条时不显示加号`() {
        val rows = (1..2).map { row("/w/f$it.txt") }
        assertEquals(0, ChangeList.overflow(rows))
    }

    @Test
    fun `正好三条时不显示加号`() {
        assertEquals(0, ChangeList.overflow((1..3).map { row("/w/f$it.txt") }))
    }

    @Test
    fun `四条时加号是一`() {
        assertEquals(1, ChangeList.overflow((1..4).map { row("/w/f$it.txt") }))
    }

    @Test
    fun `十条时加号是七`() {
        assertEquals(7, ChangeList.overflow((1..10).map { row("/w/f$it.txt") }))
    }

    @Test
    fun `二进制没有差异，标无法预览`() {
        val binary = row("/w/out/a.bin", diff = null, added = null, removed = null)
        assertFalse(ChangeList.previewable(binary))
        assertTrue(ChangeList.previewable(row("/w/a.rs")))
    }

    @Test
    fun `文件不在了只弹提示，不打开面板`() {
        val gone = row("/w/deleted.rs", exists = false)
        assertTrue(ChangeList.onTap(gone) is ChangeList.TapAction.Missing)
    }

    @Test
    fun `文件还在就打开详情`() {
        val alive = row("/w/a.rs")
        val action = ChangeList.onTap(alive)
        assertTrue(action is ChangeList.TapAction.Open)
        assertEquals("/w/a.rs", (action as ChangeList.TapAction.Open).row.path)
    }

    @Test
    fun `路径中段省略保留头尾`() {
        val long = "/data/user/0/com.nmoi.agent/files/sessions/abc123/workspace/deep/nested/file.rs"
        val shown = ChangeList.middleEllipsis(long, max = 40)
        assertTrue("长度不该超过上限：${shown.length}", shown.length <= 40)
        assertTrue("要保留开头", shown.startsWith("/data/user"))
        assertTrue("要保留结尾", shown.endsWith("file.rs"))
        assertTrue("省略号在中间", shown.contains("…"))
    }

    @Test
    fun `短路径不省略`() {
        assertEquals("/w/a.rs", ChangeList.middleEllipsis("/w/a.rs", max = 48))
    }

    @Test
    fun `太短的 max 不省略，免得省成一个省略号`() {
        val path = "/w/some/file.rs"
        assertEquals(path, ChangeList.middleEllipsis(path, max = 8))
    }

    @Test
    fun `计数与差异文本对得上`() {
        val diff = """
            --- a/x
            +++ b/x
            @@ -1,2 +1,3 @@
             keep
            -gone
            +new1
            +new2
        """.trimIndent()
        assertEquals(2 to 1, ChangeList.count(diff))
    }

    @Test
    fun `计数不把文件头当成增删`() {
        val diff = "--- a/x\n+++ b/x\n"
        assertEquals(0 to 0, ChangeList.count(diff))
    }

    @Test
    fun `只有文件名时不崩`() {
        assertEquals("a.rs", ChangeList.name("a.rs"))
        assertEquals("", ChangeList.name(""))
    }
}
