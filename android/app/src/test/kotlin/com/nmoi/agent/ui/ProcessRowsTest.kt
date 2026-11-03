package com.nmoi.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 过程块分组与折叠（D229）。
 *
 * 这几条钉的都是**看着像没事、其实错了**的地方：分组断在哪、折叠保留哪两个、
 * 还在跑的时候能不能折。它们都不报错，只能靠对着屏幕数。
 */
class ProcessRowsTest {

    private fun unit(id: String, kind: String = "turn", source: String = "history", closed: Boolean = true): ChatUnit {
        val message = ChatMessage(
            id = "$id-m",
            seq = 0,
            role = if (source == "user") "user" else "assistant",
            source = source,
            content = "内容",
            tokens = 0,
            createdAt = 0,
            incomplete = false,
            locked = false,
            toolCalls = 0,
            toolCallId = null,
        )
        return ChatUnit(
            id = id,
            kind = kind,
            closed = closed,
            createdAt = 0,
            messages = listOf(message),
            broken = null,
        )
    }

    private fun thinking(id: String, closed: Boolean = true) = unit(id, source = "model_thinking", closed = closed)
    private fun tool(id: String, closed: Boolean = true) = unit(id, kind = "tool_pair", closed = closed)
    private fun text(id: String) = unit(id)

    @Test
    fun `连续的思考与工具合成一组`() {
        val rows = buildRows(listOf(thinking("a"), tool("b"), tool("c")))
        assertEquals(1, rows.size)
        val group = rows.single() as ChatRow.Process
        assertEquals(listOf("a", "b", "c"), group.items.map { it.id })
        assertEquals(ProcessItem.Kind.Thinking, group.items.first().kind)
        assertEquals(ProcessItem.Kind.Tool, group.items[1].kind)
    }

    @Test
    fun `正文把组断开`() {
        val rows = buildRows(listOf(thinking("a"), tool("b"), text("c"), tool("d"), tool("e")))
        assertEquals(3, rows.size)
        assertTrue(rows[0] is ChatRow.Process)
        assertTrue(rows[1] is ChatRow.Single)
        assertTrue(rows[2] is ChatRow.Process)
    }

    @Test
    fun `单独一项不分组`() {
        // 一次工具调用单独画一张卡就是现在这个样子，加竖线和缩进只是多一层壳。
        val rows = buildRows(listOf(text("a"), tool("b")))
        assertEquals(2, rows.size)
        assertTrue(rows[1] is ChatRow.Single)
        assertEquals("b", (rows[1] as ChatRow.Single).unit.id)
    }

    @Test
    fun `用户消息与断块不算过程项`() {
        assertNull(processItemOf(unit("u", source = "user")))
        // 正文（turn 单元里的普通消息）也不是过程项。
        assertNull(processItemOf(text("t")))
    }

    @Test
    fun `折叠保留最新两个`() {
        val items = listOf(thinking("a"), tool("b"), tool("c"), tool("d")).let { list ->
            list.mapNotNull { processItemOf(it) }
        }
        assertEquals(4, items.size)
        assertTrue(foldable(items))
        assertEquals(listOf("c", "d"), visibleItems(items, expanded = false).map { it.id })
        assertEquals(4, visibleItems(items, expanded = true).size)
        assertEquals(2, hiddenCount(items, expanded = false))
        assertEquals(0, hiddenCount(items, expanded = true))
    }

    @Test
    fun `两项不折`() {
        val items = listOf(thinking("a"), tool("b")).mapNotNull { processItemOf(it) }
        assertEquals(2, items.size)
        assertTrue(!foldable(items))
        assertEquals(2, visibleItems(items, expanded = false).size)
    }

    @Test
    fun `没闭合的单元算在跑`() {
        val items = listOf(thinking("a"), tool("b", closed = false)).mapNotNull { processItemOf(it) }
        assertTrue(items.last().running)
        assertTrue(!items.first().running)
    }

    @Test
    fun `折叠状态的键取第一项-后面追加不会把展开的大块收回去`() {
        val before = buildRows(listOf(thinking("a"), tool("b"))).single() as ChatRow.Process
        val after = buildRows(listOf(thinking("a"), tool("b"), tool("c"))).single() as ChatRow.Process
        assertEquals(before.key, after.key)
    }
}
