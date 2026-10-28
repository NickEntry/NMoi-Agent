package com.nmoi.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `EditRepository.parseFences` 的边界（D163 / PLAN 0.14 里那个证据缺口）。
 *
 * 为什么这组边界要紧：解析错了不是"少改一块"，而是**把围栏语法本身写进消息正文**——
 * 用户看到的是 ```output 和三行解释，而他以为模型只改了正文。这类错在界面上不显眼，
 * 只有把真输出喂进来才看得见。
 *
 * 同一组用例也在本机用 kotlinc + JVM 直接跑过（2026-10-28），改动前那四条全是坏的。
 */
internal class EditFencesTest {

    private fun parse(raw: String) = EditRepository.parseFences(raw)

    @Test
    fun a_normal_output_fence_is_the_body_and_the_text_outside_is_dropped() {
        val plan = parse("好的，我改好了\n```output\n新正文\n```\n以上。")
        assertEquals("新正文", plan.output)
        assertNull(plan.thinking)
        assertTrue(plan.tools.isEmpty())
        assertTrue("围栏外的客套不该产生笔记：${plan.notes}", plan.notes.isEmpty())
    }

    @Test
    fun an_empty_output_block_writes_nothing_and_says_so() {
        val plan = parse("```output\n```")
        // 以前这里会把 "```output\n```" 整段当正文写进去。
        assertNull("空块不能回退成整段", plan.output)
        assertTrue("空块要留下一条笔记：${plan.notes}", plan.notes.any { it.contains("空") })
    }

    @Test
    fun an_unclosed_fence_writes_nothing_instead_of_dumping_the_raw_text() {
        val plan = parse("```output\nA")
        assertNull("没闭合时不能把半截围栏当正文", plan.output)
        assertTrue("没闭合要点名：${plan.notes}", plan.notes.any { it.contains("没闭合") })
    }

    @Test
    fun a_second_output_block_wins_and_is_announced() {
        val plan = parse("```output\nA\n```\n```output\nB\n```")
        assertEquals("B", plan.output)
        assertTrue("用了第二个要说明：${plan.notes}", plan.notes.any { it.contains("用了后面") })
    }

    @Test
    fun an_oversized_block_is_skipped_with_a_reason() {
        val plan = parse("```output\n" + "x".repeat(200_001) + "\n```")
        assertNull(plan.output)
        assertTrue("超限要写进笔记：${plan.notes}", plan.notes.any { it.contains("200 KB") })
    }

    @Test
    fun text_without_any_fence_is_taken_as_the_body() {
        val plan = parse("就是一段普通文字")
        assertEquals("就是一段普通文字", plan.output)
        assertTrue(plan.notes.isEmpty())
    }

    @Test
    fun tool_blocks_keep_their_index_and_bad_indices_are_named() {
        val ok = parse("```tool:3\n{\"a\":1}\n```")
        assertEquals(mapOf(3 to "{\"a\":1}"), ok.tools)

        val tooBig = parse("```tool:99\n{}\n```")
        assertTrue(tooBig.tools.isEmpty())
        assertTrue("越界编号要点名：${tooBig.notes}", tooBig.notes.any { it.contains("tool:99") })

        val nonsense = parse("```tool:x\n{}\n```")
        assertTrue(nonsense.tools.isEmpty())
        assertTrue("看不懂的编号要点名：${nonsense.notes}", nonsense.notes.any { it.contains("tool:x") })
    }

    @Test
    fun thinking_and_output_can_come_together() {
        val plan = parse("```output\n正文\n```\n```thinking\n想了想\n```")
        assertEquals("正文", plan.output)
        assertEquals("想了想", plan.thinking)
    }
}
