package com.nmoi.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「编辑整条消息」的范围（2026-11-26）。
 *
 * 这一层只有一个判据：**用户看到的红框里有什么，编辑器里就得有什么**。
 * 折行之后一轮回答在库里是几条消息，而屏幕上它们连成一片——只取锚点那一条，
 * 红框里另外几条的正文与工具参数就改不到，那正是他报的那个 bug。
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

    private fun tool(part: String, name: String = "System-Shell") = ToolCallArg(
        callId = "call-$part",
        part = part,
        name = name,
        arguments = """{"command":"ls"}""",
        output = "a.txt",
        status = "ok",
    )

    /** 用户消息之后的两条：工具那条 + 正文那条。点正文进来，两段都要在。 */
    @Test
    fun editing_the_text_message_takes_the_whole_round() {
        val units = listOf(
            unit("u1", message("s:1", role = "user", content = "跑一下")),
            unit("u2", message("s:2", tools = listOf(tool("p1")))),
            unit("u3", message("s:3", content = "跑完了")),
        )
        val turn = turnAround(units, "s:3")
        assertEquals(listOf("s:2", "s:3"), turn?.rounds?.map { it.messageId })
        // 工具那一段的块也在（这就是"红框里的工具卡"）。
        assertEquals(1, turn?.rounds?.first()?.doc?.tools?.size)
        assertEquals("跑完了", turn?.rounds?.last()?.doc?.output)
    }

    /** 点工具那条进来，拿到的范围与点正文进来**一样**：屏幕上那一片只有一份。 */
    @Test
    fun editing_the_tool_message_gives_the_same_round() {
        val units = listOf(
            unit("u1", message("s:1", role = "user", content = "跑一下")),
            unit("u2", message("s:2", tools = listOf(tool("p1")))),
            unit("u3", message("s:3", content = "跑完了")),
        )
        val fromText = turnAround(units, "s:3")?.rounds?.map { it.messageId }
        val fromTool = turnAround(units, "s:2")?.rounds?.map { it.messageId }
        assertEquals(fromText, fromTool)
    }

    /** 用户消息只取它自己：改他那句话不牵扯它引发的回答。 */
    @Test
    fun a_user_message_is_its_own_segment() {
        val units = listOf(
            unit("u1", message("s:1", role = "user", content = "跑一下")),
            unit("u2", message("s:2", content = "跑完了")),
        )
        val turn = turnAround(units, "s:1")
        assertEquals(listOf("s:1"), turn?.rounds?.map { it.messageId })
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
        val turn = turnAround(units, "s:4")
        assertEquals(listOf("s:4"), turn?.rounds?.map { it.messageId })
    }

    /** 空消息不占一段：它在屏幕上本来就不画，塞进编辑器只会多一段没内容的壳。 */
    @Test
    fun empty_messages_are_skipped() {
        val units = listOf(
            unit("u1", message("s:1", role = "user", content = "跑一下")),
            unit("u2", message("s:2", content = "")),
            unit("u3", message("s:3", content = "跑完了")),
        )
        val turn = turnAround(units, "s:3")
        assertEquals(listOf("s:3"), turn?.rounds?.map { it.messageId })
    }

    /** 纯工具那一轮没有正文：给占位，别让序列化把它判成"正文是空的"。 */
    @Test
    fun a_tool_only_message_gets_a_placeholder_body() {
        val units = listOf(
            unit("u1", message("s:1", role = "user", content = "跑一下")),
            unit("u2", message("s:2", content = "", tools = listOf(tool("p1")))),
        )
        val turn = turnAround(units, "s:2")
        val doc = turn?.rounds?.single()?.doc
        assertEquals(NO_OUTPUT_PLACEHOLDER, doc?.output)
        // 序列化能过（占位不是空串），这正是不挡住用户的原因。
        assertTrue(serializeEditTurn(turn!!).contains(NO_OUTPUT_PLACEHOLDER))
    }

    /** 找不到那条消息：返回 null，调用方去写"读不到"。 */
    @Test
    fun an_unknown_message_returns_null() {
        val units = listOf(unit("u1", message("s:1", content = "只有一条")))
        assertNull(turnAround(units, "s:99"))
    }

    // -----------------------------------------------------------------------
    // opsBetween：只有真的变了的块才写
    // -----------------------------------------------------------------------

    /** 文档里的一块工具：`part` 是库里的 part id，`input` / `stdout` 是两栏原文。 */
    private fun block(
        part: String = "p1",
        name: String = "System-Shell",
        input: String = """{"command":"ls"}""",
        stdout: String? = "a.txt",
    ) = EditToolBlock(index = 0, part = part, name = name, input = input, stdout = stdout, stderr = null)

    private fun doc(
        output: String = "正文",
        thinking: String? = null,
        tools: List<EditToolBlock> = emptyList(),
    ) = EditDocument(output = output, thinking = thinking, tools = tools)

    /** 一个字都没动：一条 op 都不该产生（不然每次保存都留一条"改了正文"的审计）。 */
    @Test
    fun an_unchanged_document_produces_no_ops() {
        val before = doc(output = "正文", thinking = "想", tools = listOf(block()))
        assertEquals(0, opsBetween(before, before).size)
    }

    /** 只改正文：一条 op，且落在 `output` 上。 */
    @Test
    fun changing_only_the_body_writes_one_output_op() {
        val ops = opsBetween(doc(output = "旧"), doc(output = "新"))
        assertEquals(1, ops.size)
        val op = ops.single() as EditOp.OnIndex
        assertEquals("output", op.block)
        assertEquals("新", op.content)
    }

    /** 工具参数改了：按 part id 定位（`p1`），不按序号——删一个调用不会让后面的错位。 */
    @Test
    fun a_tool_argument_change_targets_the_part_id() {
        val before = doc(tools = listOf(block()))
        val after = doc(
            tools = listOf(block(input = """{"command":"ls -la"}""")),
        )
        val ops = opsBetween(before, after)
        assertEquals(1, ops.size)
        val op = ops.single() as EditOp.OnPart
        assertEquals("part", op.block)
        assertEquals("p1", op.part)
        assertTrue("改参数不是改结果", !op.output)
    }

    /** 工具结果改了：同一块、`output = true`（结果那一栏），不是另起一块。 */
    @Test
    fun a_tool_result_change_marks_the_output_field() {
        val before = doc(tools = listOf(block()))
        val after = doc(tools = listOf(block(stdout = "b.txt")))
        val ops = opsBetween(before, after)
        assertEquals(1, ops.size)
        val op = ops.single() as EditOp.OnPart
        assertEquals("p1", op.part)
        assertTrue(op.output)
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

    /** 纯工具那一轮的占位正文：用户没动它，不该产生一条"正文"op。 */
    @Test
    fun the_placeholder_body_is_not_content() {
        val before = doc(output = NO_OUTPUT_PLACEHOLDER, tools = listOf(block()))
        assertEquals(0, opsBetween(before, before).size)
    }

    /** 用户把占位行删掉（正文变空）：不写 op——core 会当成空壳拒掉。 */
    @Test
    fun deleting_the_placeholder_body_writes_nothing() {
        val before = doc(output = NO_OUTPUT_PLACEHOLDER, tools = listOf(block()))
        val after = doc(output = "", tools = listOf(block()))
        assertEquals(0, opsBetween(before, after).size)
    }
}
