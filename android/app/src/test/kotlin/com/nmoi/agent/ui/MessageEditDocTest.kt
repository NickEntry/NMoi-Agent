package com.nmoi.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 单框编辑器的解析器（0.15g / D179；2026-11-26 去掉包装）。
 *
 * 这一层的判据只有一条：**用户手里的那段文本，能原样变成库里的几块，再原样回到文本**。
 * 凡是做不到的，必须报出行号——"格式不对"这四个字在这里等于没报。
 *
 * 2026-11-26 去掉的三样（都是为了少打字）：
 * - 文件头尾 `[□{<nmoi-edit v="3">` / `</nmoi-edit>}□]`；
 * - 消息 id（`<nmoi:message id="…">` 换成一行 `---`）；
 * - 工具块的 `part="p1"` 与 `stderr` 那一栏。
 */
class MessageEditDocTest {

    private fun tool(
        index: Int = 0,
        name: String = "System-Shell",
        input: String = """{"command":"ls"}""",
        stdout: String? = "a.txt",
    ) = EditToolBlock(index = index, name = name, input = input, stdout = stdout)

    private fun doc(
        output: String = "正文第一段\n\n第二段",
        thinking: String? = "先想想",
        tools: List<EditToolBlock> = emptyList(),
    ) = EditDocument(output = output, thinking = thinking, tools = tools)

    @Test
    fun round_trip_keeps_every_block() {
        val original = EditTurn(
            listOf(
                EditDocument(
                    output = "改过的正文",
                    thinkings = listOf("换个思路"),
                    tools = listOf(tool(input = """{"path":"a.txt"}""", stdout = "文件内容")),
                ),
            ),
        )
        assertEquals(original, parseEditTurn(serializeEditTurn(original)))
    }

    /** 正文不包标签：写出来的就是那几行字，一个 `<nmoi:output>` 都没有。 */
    @Test
    fun the_body_carries_no_tags() {
        val text = serializeEditTurn(EditTurn(listOf(doc(output = "就这一行", thinking = null))))
        assertEquals("就这一行\n", text)
        assertTrue("不该有 output 标签", !text.contains("<nmoi:output>"))
    }

    /** 整份文档没有头尾：第一行就是内容，最后一行也是内容。 */
    @Test
    fun there_is_no_header_or_footer() {
        val text = serializeEditTurn(EditTurn(listOf(doc(output = "正文", thinking = "想"))))
        assertTrue(text, !text.contains("nmoi-edit"))
        assertTrue(text, !text.contains("[□{"))
        assertEquals(doc(output = "正文", thinking = "想"), parseEditTurn(text).docs.single())
    }

    /** 工具块：名字写在标签上，没有 `part=`，也没有 `stderr`。 */
    @Test
    fun a_tool_block_is_one_tag_plus_two_sections() {
        val text = serializeEditTurn(
            EditTurn(listOf(doc(output = "正文", thinking = null, tools = listOf(tool())))),
        )
        assertTrue(text, text.contains("""<nmoi:tool name="System-Shell">"""))
        assertTrue(text, !text.contains("part="))
        assertTrue(text, !text.contains("stderr"))
        assertEquals(doc(output = "正文", thinking = null, tools = listOf(tool())), parseEditTurn(text).docs.single())
    }

    /** 两段之间一行 `---`：段数就是消息条数。 */
    @Test
    fun the_separator_splits_messages() {
        val turn = EditTurn(
            listOf(
                doc(output = "第一条", thinking = null),
                doc(output = "第二条", thinking = null),
            ),
        )
        val text = serializeEditTurn(turn)
        assertTrue(text, text.contains("---\n"))
        assertEquals(2, parseEditTurn(text).docs.size)
        assertEquals(turn, parseEditTurn(text))
    }

    /** 正文里出现 `---` **不是**分隔：只有独占一行的才算。 */
    @Test
    fun a_separator_inside_a_line_is_just_text() {
        val text = "上面\n--- 这一行不是分隔\n下面\n"
        val docs = parseEditTurn(text).docs
        assertEquals(1, docs.size)
        assertEquals("上面\n--- 这一行不是分隔\n下面", docs.single().output)
    }

    // ---------------------------------------------------------------------
    // 思考块：想写几个写几个（2026-11-26 用户提的）
    // ---------------------------------------------------------------------

    /** 一条消息里两个思考块：解析出来是两块，序列化再写回两块。 */
    @Test
    fun two_thinking_blocks_are_allowed() {
        val text = """
            <nmoi:thinking>
            先想想
            </nmoi:thinking>
            <nmoi:thinking>
            再想想
            </nmoi:thinking>
            正文
        """.trimIndent()
        val doc = parseEditTurn(text).docs.single()
        assertEquals(listOf("先想想", "再想想"), doc.thinkings)
        assertEquals("正文", doc.output)
        assertEquals(doc, parseEditTurn(serializeEditTurn(EditTurn(listOf(doc)))).docs.single())
    }

    /** 落库时接起来：空行分隔，一个字都不丢（库里 `reasoning` 只有一个字符串）。 */
    @Test
    fun thinking_blocks_are_joined_on_save() {
        val doc = EditDocument(output = "正文", thinkings = listOf("一", "二"), tools = emptyList())
        assertEquals("一\n\n二", doc.thinking)
    }

    /** 全空 = 没有思考块（不是"空字符串"）。 */
    @Test
    fun empty_thinking_means_no_block() {
        val doc = EditDocument(output = "正文", thinkings = listOf(""), tools = emptyList())
        assertEquals(null, doc.thinking)
    }

    /** 空思考块也要写出来：写出来才看得见"删掉这一块"这个动作。 */
    @Test
    fun an_empty_thinking_block_survives_as_empty() {
        val doc = EditDocument(output = "正文", thinkings = listOf(""), tools = emptyList())
        val text = serializeEditTurn(EditTurn(listOf(doc)))
        assertTrue(text, text.contains("<nmoi:thinking>\n\n</nmoi:thinking>"))
        assertEquals(listOf(""), parseEditTurn(text).docs.single().thinkings)
    }

    // ---------------------------------------------------------------------
    // 报错：带行号
    // ---------------------------------------------------------------------

    @Test
    fun an_unclosed_block_names_the_opening_line() {
        val text = """
            <nmoi:thinking>
            想
        """.trimIndent()
        val error = assertThrows(EditDocException::class.java) { parseEditTurn(text) }
        assertEquals(1, error.line)
        assertTrue(error.reason.contains("没闭合"))
    }

    @Test
    fun an_unknown_tag_reports_the_line() {
        val text = """
            正文
            <nmoi:note>
            这是什么
            </nmoi:note>
        """.trimIndent()
        val error = assertThrows(EditDocException::class.java) { parseEditTurn(text) }
        assertEquals(2, error.line)
        assertTrue(error.reason.contains("不认"))
    }

    /** 连续两个 `---`：中间那一段是空的，说清楚是哪一段。 */
    @Test
    fun an_empty_segment_is_refused() {
        val error = assertThrows(EditDocException::class.java) {
            parseEditTurn("一\n---\n---\n二\n")
        }
        assertTrue(error.message, error.message.orEmpty().contains("这一段是空的"))
    }

    @Test
    fun an_empty_document_says_so() {
        val error = assertThrows(EditDocException::class.java) { parseEditTurn("   ") }
        assertTrue(error.message, error.message.orEmpty().contains("文档是空的"))
    }

    /** 工具块没有名字：报开头那一行，别让它静默成"工具 #0"。 */
    @Test
    fun a_tool_block_without_a_name_is_refused() {
        val text = """
            正文
            <nmoi:tool name="">
            <nmoi:input>{}</nmoi:input>
            </nmoi:tool>
        """.trimIndent()
        val error = assertThrows(EditDocException::class.java) { parseEditTurn(text) }
        assertEquals(2, error.line)
        assertTrue(error.reason.contains("没有名字"))
    }

    /** 工具块没有参数：调用发不出去，当场拒。 */
    @Test
    fun a_tool_block_without_input_is_refused() {
        val text = """
            <nmoi:tool name="System-Shell">
            </nmoi:tool>
        """.trimIndent()
        val error = assertThrows(EditDocException::class.java) { parseEditTurn(text) }
        assertTrue(error.reason.contains("没有参数"))
    }

    // ---------------------------------------------------------------------
    // 单行形：手写短块时很常见
    // ---------------------------------------------------------------------

    @Test
    fun one_line_blocks_are_accepted() {
        val text = """
            <nmoi:thinking>一行思考</nmoi:thinking>
            一行正文
            <nmoi:tool name="read_file">
            <nmoi:input>{"path":"a.txt"}</nmoi:input>
            <nmoi:stdout>结果</nmoi:stdout>
            </nmoi:tool>
        """.trimIndent()
        val doc = parseEditTurn(text).docs.single()
        assertEquals("一行正文", doc.output)
        assertEquals(listOf("一行思考"), doc.thinkings)
        assertEquals("read_file", doc.tools.single().name)
        assertEquals("结果", doc.tools.single().stdout)
    }

    /** 生成一份自己都解析不回来的文档，等于把问题推给用户。 */
    @Test
    fun serialize_refuses_content_that_looks_like_a_tag() {
        val error = assertThrows(EditDocException::class.java) {
            serializeEditTurn(EditTurn(listOf(doc(output = "看到 </nmoi:thinking> 就结束了"))))
        }
        assertTrue(error.reason.contains("分块标记撞"))
    }

    /** 纯工具那一轮：没有正文也合法（屏幕上只有工具卡）。 */
    @Test
    fun a_tool_only_message_has_no_body() {
        val doc = EditDocument(output = "", thinkings = emptyList(), tools = listOf(tool()))
        val text = serializeEditTurn(EditTurn(listOf(doc)))
        assertTrue(text, text.startsWith("""<nmoi:tool name="System-Shell">"""))
        assertEquals(doc, parseEditTurn(text).docs.single())
    }
}
