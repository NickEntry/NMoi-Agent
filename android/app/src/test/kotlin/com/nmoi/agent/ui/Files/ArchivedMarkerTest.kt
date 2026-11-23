package com.nmoi.agent.ui.Files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具输出的归档标记（D398）。
 *
 * 认不出来的表现是"那颗「摘要化」永远不出现"——用户以为没归档，其实是没认出来。
 * 三种形态各试一次，外加两个不该认的反例。
 */
class ArchivedMarkerTest {

    @Test
    fun `截断形态认得出路径与 token`() {
        val text = "build started\n[… 8.2k tok 已归档 ref#a3f9c1 → /archive/a3f9c1.txt | 失败那次的构建日志]\nbuild done"
        val parsed = ArchivedMarker.parse(text)!!
        assertEquals("/archive/a3f9c1.txt", parsed.path)
        assertEquals(8_200, parsed.tokens)
        assertEquals("失败那次的构建日志", parsed.summary)
        assertEquals("摘要化 · 8.2k tok", parsed.label)
        // 头尾还有内容：不是整体替换。
        assertFalse(ArchivedMarker.replacedWhole(text))
    }

    @Test
    fun `masked 形态认得出`() {
        val text = "[output masked: 8.2k tok, ref#a3f9c1 → /archive/a3f9c1.txt]"
        val parsed = ArchivedMarker.parse(text)!!
        assertEquals("/archive/a3f9c1.txt", parsed.path)
        assertEquals(8_200, parsed.tokens)
        // 没有摘要那一句时给空串，不是 "output masked" 那段。
        assertEquals("", parsed.summary)
        assertTrue(ArchivedMarker.replacedWhole(text))
    }

    @Test
    fun `pointer 形态认得出`() {
        val text = "[archived ref#a3f9c1 | 8200 tok | tool_result | m-1#0 | /archive/a3f9c1.txt | 构建日志]"
        val parsed = ArchivedMarker.parse(text)!!
        assertEquals("/archive/a3f9c1.txt", parsed.path)
        assertEquals(8_200, parsed.tokens)
        assertEquals("构建日志", parsed.summary)
        assertTrue(ArchivedMarker.replacedWhole(text))
    }

    @Test
    fun `没有归档路径就不认`() {
        assertNull(ArchivedMarker.parse(""))
        assertNull(ArchivedMarker.parse("exit 0 · 202ms"))
        assertNull(ArchivedMarker.parse("[… 8.2k tok 已归档，但路径丢了]"))
        assertNull(ArchivedMarker.parse("/archive 只是提到这个名字，不是文件"))
    }

    @Test
    fun `token 数只在含路径那一行里找`() {
        // 整段里还有 `exit 0 · 202ms`：拿整段找会把 202 当成 token 数。
        val text = "exit 0 · 202ms\n[… 1.5k tok 已归档 → /archive/bb.txt]"
        assertEquals(1_500, ArchivedMarker.parse(text)!!.tokens)
    }

    @Test
    fun `没有 token 数时也认`() {
        val text = "[archived → /archive/cc.txt]"
        val parsed = ArchivedMarker.parse(text)!!
        assertEquals("/archive/cc.txt", parsed.path)
        assertEquals(0, parsed.tokens)
        assertEquals("摘要化", parsed.label)
    }

    @Test
    fun `m 单位`() {
        val text = "[… 1.2m tok 已归档 → /archive/dd.txt]"
        assertEquals(1_200_000, ArchivedMarker.parse(text)!!.tokens)
    }

    @Test
    fun `头尾留了内容就不算整体替换`() {
        val text = "head\n[… 3k tok 已归档 → /archive/ee.txt]\ntail"
        assertFalse(ArchivedMarker.replacedWhole(text))
        // 只有标记行（加空白）才算整体替换。
        assertTrue(ArchivedMarker.replacedWhole("  [… 3k tok 已归档 → /archive/ee.txt]  \n"))
    }

    @Test
    fun `pathOf 给点开用的路径`() {
        assertEquals("/archive/ff.txt", ArchivedMarker.pathOf("[… 2k tok 已归档 → /archive/ff.txt]"))
        assertNull(ArchivedMarker.pathOf("nothing here"))
    }

    @Test
    fun `formatK 与 core 同口径`() {
        assertEquals("999", formatK(999))
        assertEquals("8.2k", formatK(8_200))
        assertEquals("1.2m", formatK(1_200_000))
    }
}
