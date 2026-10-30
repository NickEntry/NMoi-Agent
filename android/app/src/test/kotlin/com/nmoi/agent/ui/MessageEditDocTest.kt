package com.nmoi.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 单框编辑器的解析器（0.15g / D179）。
 *
 * 这一层的判据只有一条：**用户手里的那段文本，能原样变成库里的几块，再原样回到文本**。
 * 凡是做不到的，必须报出行号——"格式不对"这四个字在这里等于没报。
 */
class MessageEditDocTest {

    private fun doc(
        output: String = "正文第一段\n\n第二段",
        thinking: String? = "先想想",
        tools: List<EditToolBlock> = emptyList(),
    ) = EditDocument(output = output, thinking = thinking, tools = tools)

    @Test
    fun round_trip_keeps_every_block() {
        val original = EditDocument(
            output = "改过的正文",
            thinking = "换个思路",
            tools = listOf(
                EditToolBlock(
                    index = 0,
                    name = "read_file",
                    input = """{"path":"a.txt"}""",
                    stdout = "文件内容",
                    stderr = null,
                ),
            ),
        )
        val text = serializeEditDocument(original)
        val parsed = parseEditDocument(text)
        assertEquals(original, parsed)
    }

    @Test
    fun round_trip_keeps_blank_lines_and_trailing_newline() {
        // 正文尾随的空行是用户写的，不能因为"看起来多余"被 trim 掉。
        val original = doc(output = "第一行\n\n\n最后一行\n", thinking = null)
        val parsed = parseEditDocument(serializeEditDocument(original))
        assertEquals(original.output, parsed.output)
    }

    @Test
    fun thinking_absent_round_trips() {
        val original = doc(thinking = null)
        val text = serializeEditDocument(original)
        assertTrue("没有思考块时不该写出 thinking 段", !text.contains("<nmoi:thinking>"))
        assertNull(parseEditDocument(text).thinking)
    }

    @Test
    fun empty_thinking_block_survives_as_empty_string() {
        // 空串 ≠ 没有：空串是"这一块在，但内容为空"，落库时才算删掉。
        val parsed = parseEditDocument(serializeEditDocument(doc(thinking = "")))
        assertEquals("", parsed.thinking)
    }

    @Test
    fun unknown_tag_reports_the_line() {
        val text = """
            [□{<nmoi-edit v="1">
            <nmoi:output>
            正文
            </nmoi:output>
            <nmoi:note>
            这是什么
            </nmoi:note>
            </nmoi-edit>}□]
        """.trimIndent()
        val error = assertThrows(EditDocException::class.java) { parseEditDocument(text) }
        assertEquals(5, error.line)
        assertTrue(error.reason.contains("不是能认的块开头"))
    }

    @Test
    fun missing_close_names_the_opening_line() {
        val text = """
            [□{<nmoi-edit v="1">
            <nmoi:output>
            正文
            </nmoi-edit>}□]
        """.trimIndent()
        val error = assertThrows(EditDocException::class.java) { parseEditDocument(text) }
        assertEquals(2, error.line)
        assertTrue(error.reason.contains("没闭合"))
    }

    @Test
    fun duplicate_output_is_rejected() {
        val text = """
            [□{<nmoi-edit v="1">
            <nmoi:output>
            一
            </nmoi:output>
            <nmoi:output>
            二
            </nmoi:output>
            </nmoi-edit>}□]
        """.trimIndent()
        val error = assertThrows(EditDocException::class.java) { parseEditDocument(text) }
        assertEquals(5, error.line)
        assertTrue(error.reason.contains("只能有一块"))
    }

    @Test
    fun tool_index_must_be_continuous() {
        val text = """
            [□{<nmoi-edit v="1">
            <nmoi:output>
            正文
            </nmoi:output>
            <nmoi:tool index="1">
            <nmoi:name>read_file</nmoi:name>
            <nmoi:input>{}</nmoi:input>
            </nmoi:tool>
            </nmoi-edit>}□]
        """.trimIndent()
        val error = assertThrows(EditDocException::class.java) { parseEditDocument(text) }
        assertEquals(5, error.line)
        assertTrue(error.reason.contains("要从 0 连续排"))
    }

    @Test
    fun text_outside_blocks_is_rejected() {
        val text = """
            [□{<nmoi-edit v="1">
            这句话在块外面
            <nmoi:output>
            正文
            </nmoi:output>
            </nmoi-edit>}□]
        """.trimIndent()
        val error = assertThrows(EditDocException::class.java) { parseEditDocument(text) }
        assertEquals(2, error.line)
    }

    @Test
    fun empty_output_is_rejected() {
        val text = """
            [□{<nmoi-edit v="1">
            <nmoi:output>
            </nmoi:output>
            </nmoi-edit>}□]
        """.trimIndent()
        val error = assertThrows(EditDocException::class.java) { parseEditDocument(text) }
        assertTrue(error.reason.contains("正文是空的"))
    }

    @Test
    fun serialize_refuses_content_that_looks_like_a_tag() {
        // 生成一份自己都解析不回来的文档，等于把问题推给用户。
        val error = assertThrows(EditDocException::class.java) {
            serializeEditDocument(doc(output = "看到 </nmoi:output> 就结束了"))
        }
        assertTrue(error.reason.contains("分块标记撞"))
    }

    @Test
    fun tool_subsections_are_parsed_and_joined() {
        val text = """
            [□{<nmoi-edit v="1">
            <nmoi:output>
            正文
            </nmoi:output>
            <nmoi:tool index="0">
            <nmoi:name>System-Shell</nmoi:name>
            <nmoi:input>{"command":"uname -a"}</nmoi:input>
            <nmoi:stdout>
            Linux
            </nmoi:stdout>
            <nmoi:stderr>
            warning: x
            </nmoi:stderr>
            </nmoi:tool>
            </nmoi-edit>}□]
        """.trimIndent()
        val parsed = parseEditDocument(text)
        assertEquals(1, parsed.tools.size)
        val tool = parsed.tools[0]
        assertEquals("System-Shell", tool.name)
        assertEquals("""{"command":"uname -a"}""", tool.input)
        assertEquals("Linux", tool.stdout)
        assertEquals("warning: x", tool.stderr)
        assertEquals("Linux\nwarning: x", tool.result())
    }

    @Test
    fun one_line_blocks_are_accepted() {
        // 手写短块时几乎都会写成一行：`<nmoi:output>正文</nmoi:output>`。
        // 要求"结束标签必须单独一行"会把这种写法直接判死——那是难用，不是严格。
        val text = """
            [□{<nmoi-edit v="1">
            <nmoi:output>一行正文</nmoi:output>
            <nmoi:thinking>一行思考</nmoi:thinking>
            <nmoi:tool index="0">
            <nmoi:name>read_file</nmoi:name>
            <nmoi:input>{"path":"a.txt"}</nmoi:input>
            <nmoi:stdout>结果</nmoi:stdout>
            </nmoi:tool>
            </nmoi-edit>}□]
        """.trimIndent()
        val parsed = parseEditDocument(text)
        assertEquals("一行正文", parsed.output)
        assertEquals("一行思考", parsed.thinking)
        assertEquals(1, parsed.tools.size)
        assertEquals("read_file", parsed.tools[0].name)
        assertEquals("结果", parsed.tools[0].stdout)
    }

    @Test
    fun header_line_must_come_first() {
        val error = assertThrows(EditDocException::class.java) {
            parseEditDocument("<nmoi:output>\n正文\n</nmoi:output>")
        }
        assertTrue(error.reason.contains("开头必须是"))
        assertEquals(1, error.line)
    }
}
