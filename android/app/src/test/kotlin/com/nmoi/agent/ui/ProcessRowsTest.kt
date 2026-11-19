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

    private fun unit(
        id: String,
        kind: String = "turn",
        source: String = "history",
        closed: Boolean = true,
        reasoning: String? = null,
        // 工具单元默认没有正文：有正文的那种（折行之后的一轮）由专门的用例造。
        content: String = if (kind == "tool_pair") "" else "内容",
    ): ChatUnit {
        val message = ChatMessage(
            id = "$id-m",
            seq = 0,
            role = if (source == "user") "user" else "assistant",
            source = source,
            content = content,
            tokens = 0,
            createdAt = 0,
            incomplete = false,
            locked = false,
            toolCalls = 0,
            reasoning = reasoning,
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
    private fun textWithReasoning(id: String) = unit(id, reasoning = "想了一会儿")

    /** 工具那一轮（折行之后一轮 = 一条消息：思考 + 正文 + 工具）。 */
    private fun toolWithBody(
        id: String,
        content: String,
        reasoning: String? = null,
    ): ChatUnit {
        val message = ChatMessage(
            id = "$id-m",
            seq = 0,
            role = "assistant",
            source = "tool_call",
            content = content,
            tokens = 0,
            createdAt = 0,
            incomplete = false,
            locked = false,
            toolCalls = 1,
            reasoning = reasoning,
            toolArgs = listOf(
                ToolCallArg(
                    callId = "c1",
                    part = "p3",
                    name = "read_file",
                    arguments = "{}",
                    output = "内容",
                    status = "ok",
                ),
            ),
        )
        return ChatUnit(
            id = id,
            kind = "tool_pair",
            closed = true,
            createdAt = 0,
            messages = listOf(message),
            broken = null,
        )
    }

    /**
     * 工具那一轮的正文**永远在卡外面**：卡一行、正文一行（2026-11-06 真机）。
     *
     * 以前一句短正文会吸进卡里（D264），可生成中那条路径一直把正文画在卡外面——
     * 于是"跑的时候在外面、一落库就跳进大块"。同一个语义只能有一条渲染路径。
     */
    @Test
    fun `工具轮次里的正文单独一行-卡在前`() {
        val rows = buildRows(listOf(toolWithBody("t", "我先看看", reasoning = "想一想")))
        assertEquals(2, rows.size)
        val group = rows[0] as ChatRow.Process
        assertEquals(listOf("t#reasoning", "t"), group.items.map { it.id })
        val body = rows[1] as ChatRow.Single
        assertEquals(UnitSlice.TextOnly, body.slice)
        assertTrue(body.hideReasoning)
    }

    /** 正文长短不影响形状：短句也是一行，不吸进卡里。 */
    @Test
    fun `工具轮次里的长正文也是同样两行`() {
        val long = "这一轮我先把目录列出来，再看看里面那个文件的前几行，然后决定要不要改。".repeat(2)
        val rows = buildRows(listOf(toolWithBody("t", long, reasoning = "想一想")))
        assertEquals(2, rows.size)
        assertEquals(listOf("t#reasoning", "t"), (rows[0] as ChatRow.Process).items.map { it.id })
        assertEquals(UnitSlice.TextOnly, (rows[1] as ChatRow.Single).slice)
    }

    /** 没有正文就什么都不画——「（空消息）」不该出现（2026-11-06 用户）。 */
    @Test
    fun `工具轮次没有正文时不产生正文行`() {
        val rows = buildRows(listOf(toolWithBody("t", "", reasoning = "想一想")))
        assertEquals(1, rows.size)
        val group = rows.single() as ChatRow.Process
        assertEquals(listOf("t#reasoning", "t"), group.items.map { it.id })
    }

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

    /**
     * 带工具调用的那一轮（0.15ax 起正文那条也存思考）。
     *
     * 用户 2026-11-04 报的就是这个：屏幕上是「工具调用」一张卡、「思考」一张卡，
     * 明明是同一次回合。现在思考并进同一个大块，正文自己一行、思考不再画第二遍。
     */
    @Test
    fun `工具后面那条带思考的正文-思考并进大块`() {
        val rows = buildRows(listOf(tool("a"), textWithReasoning("b")))
        assertEquals(2, rows.size)
        val group = rows[0] as ChatRow.Process
        assertEquals(listOf("a", "b#reasoning"), group.items.map { it.id })
        assertEquals(ProcessItem.Kind.Thinking, group.items.last().kind)
        val body = rows[1] as ChatRow.Single
        assertEquals("b", body.unit.id)
        assertTrue("思考已经在大块里了，正文这边不该再画", body.hideReasoning)
    }

    /** 只有正文、前面没有过程项：思考留在气泡里，不为了凑一组硬拆。 */
    @Test
    fun `没有过程项时思考留在气泡里`() {
        val rows = buildRows(listOf(textWithReasoning("a")))
        assertEquals(1, rows.size)
        val only = rows.single() as ChatRow.Single
        assertEquals("a", only.unit.id)
        assertTrue(!only.hideReasoning)
    }

    @Test
    fun `折叠状态的键取第一项-后面追加不会把展开的大块收回去`() {
        val before = buildRows(listOf(thinking("a"), tool("b"))).single() as ChatRow.Process
        val after = buildRows(listOf(thinking("a"), tool("b"), tool("c"))).single() as ChatRow.Process
        assertEquals(before.key, after.key)
    }

    /**
     * 动作行原来是**每一行现扫一遍后面**（`rows.drop(index+1)…`）：长会话里可见的
     * 八行每帧各扫半张表，是 O(n²) 的分配（D354）。现在整张表一次算完。
     *
     * 判据一个字没改，所以这里拿老写法逐格对一遍——不然"优化"和"改坏"看起来一样。
     */
    private fun legacyShowActions(rows: List<ChatRow>, index: Int): Boolean =
        rows.drop(index + 1).takeWhile { !it.alignsRight() }
            .none { it is ChatRow.Single && it.slice != UnitSlice.ToolsOnly }

    @Test
    fun `预计算的动作行与逐行扫描完全一致`() {
        val shapes = listOf(
            listOf(text("a")),
            listOf(text("a"), text("b")),
            listOf(unit("u", source = "user"), text("a")),
            listOf(text("a"), unit("u", source = "user"), text("b")),
            listOf(tool("t"), text("a")),
            listOf(toolWithBody("t", "正文"), text("a")),
            listOf(tool("x"), tool("y"), text("a"), unit("u", source = "user"), text("b"), tool("z")),
            listOf(unit("u1", source = "user"), text("a"), tool("t"), unit("u2", source = "user")),
        )
        for ((shape, units) in shapes.withIndex()) {
            val rows = buildRows(units)
            val flags = showActionsByRow(rows)
            assertEquals("形状 $shape 的行数", rows.size, flags.size)
            for (index in rows.indices) {
                assertEquals("形状 $shape 第 $index 行", legacyShowActions(rows, index), flags[index])
            }
        }
    }

    /** 空列表不该炸（真机上"这个会话还空着"那一屏就是它）。 */
    @Test
    fun `没有行时动作行表是空的`() {
        assertEquals(0, showActionsByRow(emptyList()).size)
    }

    /**
     * 稳定 key：同一条单元的行，key 不跟着位置走；不同 slice / 过程块要分得开。
     *
     * 不给 key 的时候行身份是"第几个"，前面插进一行后面全体错位——Markdown 要重新
     * 解析、展开状态要重新挂（D354）。
     */
    @Test
    fun `行 key 跟着单元走-不跟着位置走`() {
        val alone = buildRows(listOf(text("a"))).single()
        val shifted = buildRows(listOf(unit("u", source = "user"), text("a"))).last()
        assertEquals(rowKey(alone), rowKey(shifted))
        // 同一个单元的两半（整条 / 只有正文）不能撞 key。
        val textOnly = ChatRow.Single(unit("a"), hideReasoning = true, slice = UnitSlice.TextOnly)
        assertTrue(rowKey(textOnly) != rowKey(alone))
        // 过程块用 `g:` 前缀，与单元行不会撞。
        val group = buildRows(listOf(thinking("t1"), tool("t2"))).single()
        assertTrue(rowKey(group).startsWith("g:"))
        assertEquals("process", rowContentType(group))
        assertEquals("user", rowContentType(buildRows(listOf(unit("u", source = "user"))).single()))
        assertEquals("assistant", rowContentType(alone))
    }
}
