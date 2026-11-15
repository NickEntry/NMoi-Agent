package com.nmoi.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolGroupsTest {
    private val all = listOf(
        "read_file", "write_file", "edit_file", "list_dir", "find_files", "search",
        "System-Shell", "View-Image", "WorkSpace-EnvirShell", "edit_unit",
        "Session-Files", "Voice-Caps", "Speak",
    )

    @Test
    fun 十三个工具分成读写在语音命令四组() {
        val groups = toolGroups(all)
        assertEquals(listOf("读", "写", "语音", "命令"), groups.map { it.title })
        assertEquals(listOf(5, 3, 2, 3), groups.map { it.names.size })
        assertEquals(13, groups.sumOf { it.names.size })
    }

    @Test
    fun 读在最前命令在最后() {
        val names = toolGroups(all).flatMap { it.names }
        assertEquals("read_file", names.first())
        assertEquals("System-Shell", names[names.size - 3])
    }

    @Test
    fun 注册表里多出来的名字进其他并且不丢() {
        val groups = toolGroups(all + "mcp__Exa__web_search_exa")
        assertEquals("其他", groups.last().title)
        assertEquals(listOf("mcp__Exa__web_search_exa"), groups.last().names)
    }

    @Test
    fun 空清单没有分组() {
        assertEquals(emptyList<ToolGroup>(), toolGroups(emptyList()))
    }

    @Test
    fun 重复的名字只出现一次() {
        val groups = toolGroups(listOf("search", "search", "search"))
        assertEquals(1, groups.size)
        assertEquals(listOf("search"), groups.first().names)
    }

    @Test
    fun 空串不占一行() {
        assertEquals(emptyList<ToolGroup>(), toolGroups(listOf("", " ")))
    }
}
