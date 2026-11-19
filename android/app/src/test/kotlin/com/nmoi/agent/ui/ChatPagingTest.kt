package com.nmoi.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分页拼接（D355）。
 *
 * 这几条钉的是"看着连续、其实断了"的地方：两段按行号拼、按 id 去重拼、接缝处缺了几条。
 * 它们都不报错——屏幕上只是少了一两句，或者一句变回旧的内容。
 */
class ChatPagingTest {

    private fun unit(seq: Long, text: String = "第 $seq 条"): ChatUnit {
        val message = ChatMessage(
            id = "s:$seq",
            seq = seq,
            role = "assistant",
            source = "history",
            content = text,
            tokens = 1,
            createdAt = 0,
            incomplete = false,
            locked = false,
            toolCalls = 0,
        )
        return ChatUnit(
            id = "u:$seq",
            kind = "turn",
            closed = true,
            createdAt = 0,
            messages = listOf(message),
            broken = null,
        )
    }

    @Test
    fun `没有往上翻过的就原样用最新那一页`() {
        val newest = listOf(unit(3), unit(4))
        assertEquals(newest, mergeUnits(emptyList(), newest))
    }

    @Test
    fun `同一条单元以新的那一份为准`() {
        // 用户改过这一条：旧的那份写着"旧"，最新的那份写着"新"。
        val older = listOf(unit(1), unit(2, "旧"))
        val newest = listOf(unit(2, "新"), unit(3))
        val merged = mergeUnits(older, newest)
        assertEquals(listOf("u:1", "u:2", "u:3"), merged.map { it.id })
        assertEquals("新", merged[1].messages.single().content)
    }

    @Test
    fun `两段按首条 seq 排序-不按谁先来`() {
        // 中间来过新消息：最新那一页整体后移，接缝处 older 比 newest 还"新"是不该发生的，
        // 但真发生时顺序要按 seq 排，不能按拼接顺序。
        val merged = mergeUnits(listOf(unit(5), unit(6)), listOf(unit(3), unit(4)))
        assertEquals(3L, firstSeqOf(merged.first()))
        assertEquals(6L, firstSeqOf(merged.last()))
    }

    @Test
    fun `接缝连续就没有空档`() {
        assertFalse(hasGap(listOf(unit(1), unit(2)), listOf(unit(3), unit(4))))
        // 同一段重复了也不算空档（去重之后接上）。
        assertFalse(hasGap(listOf(unit(1), unit(2)), listOf(unit(2), unit(3))))
    }

    @Test
    fun `接缝缺了几条就是空档`() {
        assertTrue(hasGap(listOf(unit(1), unit(2)), listOf(unit(4), unit(5))))
    }

    @Test
    fun `两段有一边是空的就不判空档`() {
        assertFalse(hasGap(emptyList(), listOf(unit(9))))
        assertFalse(hasGap(listOf(unit(1)), emptyList()))
    }

    @Test
    fun `分页字段与旧的 truncated 是两件事`() {
        // `truncated` 只说"上面还有没读的"；能不能继续要由 `hasMore` + 游标决定。
        // 老接口没有 `has_more` 时这两个值由 `parseUnits` 兜底，这里钉的是默认值本身：
        // 一个从头到尾读完的会话，`hasMore` 必须是 false，否则界面会一直去要下一页。
        val page = ChatPage(
            sessionId = "s",
            units = emptyList(),
            total = 0,
            truncated = false,
        )
        assertFalse(page.hasMore)
        assertEquals(null, page.nextBefore)
    }
}
