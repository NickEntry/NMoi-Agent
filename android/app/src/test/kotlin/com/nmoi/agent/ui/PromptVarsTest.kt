package com.nmoi.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 提示词站位字符串（D316）。这几条都是"错了会很难查"的那一类：
 * 替换多了会让模型的提示词被吃掉，替换少了会让 `<ContextMode>` 原样发给上游。
 */
class PromptVarsTest {
    private val values = mapOf(PromptVars.CONTEXT_MODE to "F2")

    @Test
    fun declared_variable_is_replaced() {
        assertEquals(
            "现在是 F2 档：可以少写一点。",
            PromptVars.fill("现在是 <ContextMode> 档：可以少写一点。", values),
        )
    }

    @Test
    fun undeclared_variable_stays_as_is() {
        // 写错一个字母就让它显形，别静默变成空串。
        assertEquals("现在是 <Contextmode> 档", PromptVars.fill("现在是 <Contextmode> 档", values))
    }

    @Test
    fun replacement_is_not_rescanned() {
        // 值里再出现一个占位符，也不该被二次替换（递归替换是注入入口）。
        val tricky = mapOf(PromptVars.CONTEXT_MODE to "<ContextMode>F2")
        assertEquals("<ContextMode>F2", PromptVars.fill("<ContextMode>", tricky))
    }

    @Test
    fun empty_values_leave_the_text_alone() {
        assertEquals("没有变量", PromptVars.fill("没有变量", emptyMap()))
    }

    @Test
    fun every_declared_name_gets_filled() {
        // 声明的名字必须真的能被填——否则界面上那行「变量：…」就是假开关。
        ModelRole.entries.forEach { role ->
            val names = PromptVars.names(role)
            if (names.isEmpty()) return@forEach
            val filled = PromptVars.fill(names.joinToString(" "), names.associateWith { "V" })
            assertEquals("$role 的变量没被替换：$filled", false, filled.contains('<'))
        }
    }
}
