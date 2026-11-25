package com.nmoi.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「编辑整条消息」的范围与逐块比对（2026-11-26）。
 *
 * 两条判据：
 * 1. **用户看到的红框里有什么，编辑器里就得有什么**——折行之后一轮回答在库里是几条消息，
 *    而屏幕上它们连成一片；只取锚点那一条，红框里另外几条就改不到。
 * 2. **只有真的变了的块才写**——不然每点一次保存都留一条"改了正文"的审计。
 */
class MessageEditRoundTest {

    private fun message(
        id: String,
        role: String = "assistant",
        content: String = "正文 $id",
        reasoning: String? = null,
        tools: List<ToolCallArg> = emptyList(),
    ) = ChatMessage(
        id = id,
        seq = id.substringAfter(':').toLongOrNull() ?: 0L,
        role = role,
        source = "history",
        content = content,
        tokens = 1,
        createdAt = 0L,
        incomplete = false,
        locked = false,
        toolCalls = tools.size,
        reasoning = reasoning,
        toolArgs = tools,
    )

    private fun unit(id: String, vararg messages: ChatMessage) = ChatUnit(
        id = id,
        kind = "turn",
        closed = true,
        createdAt = 0L,
        messages = messages.toList(),
        broken = null,
    )

    private fun call(part: String, name: String = "System-Shell") = ToolCallArg(
        callId = "call-$part",
        part = part,
        name = name,
        arguments = """{"command":"ls"}""",
        output = "a.txt",
        status = "ok",
    )

    // ---------------------------------------------------------------------
    // 范围
    // ---------------------------------------------------------------------

    /** 用户消息之后的两条：工具那条 + 正文那条。点正文进来，两段都要在。 */
    @Test
    fun editing_the_text_message_takes_the_whole_round() {
        val units = listOf(
            unit("u1", message("s:1", role = "user", content = "跑一下")),
            unit("u2", message("s:2", tools = listOf(call("p1")))),
            unit("u3", message("s:3", content = "跑完了")),
        )
        val loaded = turnAround(units, "s:3")!!
        assertEquals(listOf("s:2", "s:3"), loaded.messageIds)
        assertEquals(2, loaded.turn.docs.size)
        // 工具那一段的块也在（这就是"红框里的工具卡"）。
        assertEquals(1, loaded.turn.docs.first().tools.size)
        assertEquals("跑完了", loaded.turn.docs.last().output)
    }

    /** 点工具那条进来，拿到的范围与点正文进来**一样**：屏幕上那一片只有一份。 */
    @Test
    fun editing_the_tool_message_gives_the_same_round() {
        val units = listOf(
            unit("u1", message("s:1", role = "user", content = "跑一下")),
            unit("u2", message("s:2", tools = listOf(call("p1")))),
            unit("u3", message("s:3", content = "跑完了")),
        )
        assertEquals(
            turnAround(units, "s:3")!!.messageIds,
            turnAround(units, "s:2")!!.messageIds,
        )
    }

    /** 用户消息只取它自己：改他那句话不牵扯它引发的回答。 */
    @Test
    fun a_user_message_is_its_own_segment() {
        val units = listOf(
            unit("u1", message("s:1", role = "user", content = "跑一下")),
            unit("u2", message("s:2", content = "跑完了")),
        )
        assertEquals(listOf("s:1"), turnAround(units, "s:1")!!.messageIds)
    }

    /** 前一轮不能混进来：锚点前面那条用户消息是边界。 */
    @Test
    fun the_previous_round_stays_out() {
        val units = listOf(
            unit("u1", message("s:1", role = "user", content = "第一句")),
            unit("u2", message("s:2", content = "第一次回答")),
            unit("u3", message("s:3", role = "user", content = "第二句")),
            unit("u4", message("s:4", content = "第二次回答")),
        )
        assertEquals(listOf("s:4"), turnAround(units, "s:4")!!.messageIds)
    }

    /** 空消息不占一段：它在屏幕上本来就不画，塞进编辑器只会多一段没内容的壳。 */
    @Test
    fun empty_messages_are_skipped() {
        val units = listOf(
            unit("u1", message("s:1", role = "user", content = "跑一下")),
            unit("u2", message("s:2", content = "")),
            unit("u3", message("s:3", content = "跑完了")),
        )
        assertEquals(listOf("s:3"), turnAround(units, "s:3")!!.messageIds)
    }

    /** 纯工具那一轮没有正文：空正文是合法的（只要它有工具块），序列化能过。 */
    @Test
    fun a_tool_only_message_has_no_body() {
        val units = listOf(
            unit("u1", message("s:1", role = "user", content = "跑一下")),
            unit("u2", message("s:2", content = "", tools = listOf(call("p1")))),
        )
        val loaded = turnAround(units, "s:2")!!
        assertEquals("", loaded.turn.docs.single().output)
        assertTrue(serializeEditTurn(loaded.turn).contains("<nmoi:tool name="))
    }

    /** 找不到那条消息：返回 null，调用方去写"读不到"。 */
    @Test
    fun an_unknown_message_returns_null() {
        val units = listOf(unit("u1", message("s:1", content = "只有一条")))
        assertNull(turnAround(units, "s:99"))
    }

    // ---------------------------------------------------------------------
    // opsBetween：只有真的变了的块才写
    // ---------------------------------------------------------------------

    private fun block(
        index: Int = 0,
        name: String = "System-Shell",
        input: String = """{"command":"ls"}""",
        stdout: String? = "a.txt",
    ) = EditToolBlock(index = index, name = name, input = input, stdout = stdout)

    private fun doc(
        output: String = "正文",
        thinking: String? = null,
        tools: List<EditToolBlock> = emptyList(),
    ) = EditDocument(output = output, thinking = thinking, tools = tools)

    @Test
    fun an_unchanged_document_produces_no_ops() {
        val before = doc(output = "正文", thinking = "想", tools = listOf(block()))
        assertEquals(0, opsBetween(before, before).size)
    }

    @Test
    fun changing_only_the_body_writes_one_output_op() {
        val ops = opsBetween(doc(output = "旧"), doc(output = "新"))
        assertEquals(1, ops.size)
        assertEquals("output", ops.single().block)
        assertEquals("新", ops.single().content)
    }

    /** 工具参数改了：按**序号**定位（工具块不能增删，序号就是身份）。 */
    @Test
    fun a_tool_argument_change_targets_the_index() {
        val ops = opsBetween(
            doc(tools = listOf(block())),
            doc(tools = listOf(block(input = """{"command":"ls -la"}"""))),
        )
        assertEquals(1, ops.size)
        assertEquals("tool", ops.single().block)
        assertEquals(0, ops.single().index)
    }

    /** 工具结果改了：同一块、`tool_result` 那一栏。 */
    @Test
    fun a_tool_result_change_targets_the_result_slot() {
        val ops = opsBetween(
            doc(tools = listOf(block())),
            doc(tools = listOf(block(stdout = "b.txt"))),
        )
        assertEquals(1, ops.size)
        assertEquals("tool_result", ops.single().block)
    }

    /** 删掉一整块工具：说清"不能增删"，不是静默少写一块。 */
    @Test
    fun removing_a_tool_block_is_refused() {
        val error = assertThrows(EditDocException::class.java) {
            opsBetween(doc(tools = listOf(block())), doc(tools = emptyList()))
        }
        assertTrue(error.message, error.message.orEmpty().contains("不能增删"))
    }

    /** 改工具名：拒。名字决定这一块怎么解释。 */
    @Test
    fun renaming_a_tool_is_refused() {
        val error = assertThrows(EditDocException::class.java) {
            opsBetween(
                doc(tools = listOf(block(name = "System-Shell"))),
                doc(tools = listOf(block(name = "read_file"))),
            )
        }
        assertTrue(error.message, error.message.orEmpty().contains("工具名不能改"))
    }

    /** 把正文删空：core 会当成空壳拒掉，这里先说清楚。 */
    @Test
    fun clearing_the_body_is_refused_with_a_reason() {
        val error = assertThrows(EditDocException::class.java) {
            opsBetween(doc(output = "有正文"), doc(output = ""))
        }
        assertTrue(error.message, error.message.orEmpty().contains("不能删空"))
    }

    /** 纯工具那一轮（正文本来就空）改工具参数：只出一条 tool op，不掺一条空正文。 */
    @Test
    fun a_tool_only_round_does_not_write_an_empty_body_op() {
        val ops = opsBetween(
            doc(output = "", tools = listOf(block())),
            doc(output = "", tools = listOf(block(input = """{"command":"pwd"}"""))),
        )
        assertEquals(1, ops.size)
        assertEquals("tool", ops.single().block)
    }

    /** 两个思考块接起来之后和一块不同：写一条 thinking op。 */
    @Test
    fun adding_a_second_thinking_block_writes_one_op() {
        val before = EditDocument(output = "正文", thinkings = listOf("一"), tools = emptyList())
        val after = EditDocument(output = "正文", thinkings = listOf("一", "二"), tools = emptyList())
        val ops = opsBetween(before, after)
        assertEquals(1, ops.size)
        assertEquals("thinking", ops.single().block)
        assertEquals("一\n\n二", ops.single().content)
    }
}
