package com.nmoi.agent.ui.Files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 源码页的行级着色（D392）。
 *
 * 着色错起来不报错，只会"看着怪"——所以每条规矩都要有一句断言。
 * 尤其第三条：字符串里的注释符、注释里的引号，两个方向都试。
 */
class SourceLexerTest {

    private fun text(line: String, span: SourceSpan) = line.substring(span.start, span.end)

    @Test
    fun `rust 的行注释吃到行尾`() {
        val line = "let a = 1; // 这是注释"
        val spans = SourceLexer.spans(line, "rust")
        val comment = spans.single { it.token == SourceToken.Comment }
        assertEquals("// 这是注释", text(line, comment))
        assertEquals(line.length, comment.end)
    }

    @Test
    fun `字符串里的注释符不是注释`() {
        val line = """let url = "http://a.b/c"; // 真的注释"""
        val spans = SourceLexer.spans(line, "rust")
        val strings = spans.filter { it.token == SourceToken.String }
        assertEquals(1, strings.size)
        assertEquals("\"http://a.b/c\"", text(line, strings.single()))
        // 注释只有后面那一段。
        val comment = spans.single { it.token == SourceToken.Comment }
        assertEquals("// 真的注释", text(line, comment))
    }

    @Test
    fun `注释里的引号不开字符串`() {
        val line = "x = 1  # 他说\"你好\""
        val spans = SourceLexer.spans(line, "python")
        assertTrue(spans.none { it.token == SourceToken.String })
        assertEquals("# 他说\"你好\"", text(line, spans.single { it.token == SourceToken.Comment }))
    }

    @Test
    fun `转义引号不结束字符串`() {
        val line = """s = "a\"b"; // x"""
        val spans = SourceLexer.spans(line, "rust")
        val string = spans.single { it.token == SourceToken.String }
        assertEquals("\"a\\\"b\"", text(line, string))
    }

    @Test
    fun `没闭合的字符串吃到行尾`() {
        val line = """let a = "没闭合"""
        val spans = SourceLexer.spans(line, "rust")
        val string = spans.single { it.token == SourceToken.String }
        assertEquals(line.length, string.end)
        assertTrue(spans.none { it.token == SourceToken.Comment })
    }

    @Test
    fun `数字上色，标识符里的数字不上色`() {
        val line = "let a1 = 42; let b = 3.14"
        val spans = SourceLexer.spans(line, "rust")
        val numbers = spans.filter { it.token == SourceToken.Number }.map { text(line, it) }
        assertEquals(listOf("42", "3.14"), numbers)
    }

    @Test
    fun `不知道语言时只上字符串与数字，不猜注释`() {
        val line = "#include <stdio.h> // 看着像注释"
        val spans = SourceLexer.spans(line, null)
        assertTrue(spans.none { it.token == SourceToken.Comment })
        // 而同一个行在 sh 里，`#` 确实是注释。
        val shell = SourceLexer.spans(line, "sh")
        assertEquals(line, text(line, shell.single { it.token == SourceToken.Comment }))
    }

    @Test
    fun `着色段升序且不重叠`() {
        val line = """f("a", 1) # c"""
        val spans = SourceLexer.spans(line, "python")
        var cursor = 0
        for (span in spans) {
            assertTrue("段要升序：$span", span.start >= cursor)
            assertTrue("段要非空：$span", span.end > span.start)
            cursor = span.end
        }
    }

    @Test
    fun `空行没有段`() {
        assertTrue(SourceLexer.spans("", "rust").isEmpty())
        assertTrue(SourceLexer.spans("   ", "rust").isEmpty())
    }

    @Test
    fun `语言按后缀认`() {
        assertEquals("rust", SourceLexer.languageOf("a.rs"))
        assertEquals("kotlin", SourceLexer.languageOf("a.kt"))
        assertEquals("python", SourceLexer.languageOf("a.py"))
        assertEquals("sh", SourceLexer.languageOf("a.yaml"))
        assertEquals("sql", SourceLexer.languageOf("a.sql"))
        assertNull(SourceLexer.languageOf("a.txt"))
        assertNull(SourceLexer.languageOf("a.html"))
    }

    @Test
    fun `行号宽度与分页起点`() {
        assertEquals(1, SourceLexer.numberWidth(9))
        assertEquals(2, SourceLexer.numberWidth(10))
        assertEquals(4, SourceLexer.numberWidth(1000))
        assertEquals(1, SourceLexer.numberWidth(0))
        // 分页读到第二页时，行号要接着数，不能从 1 重来。
        assertEquals(201, SourceLexer.firstLineNumber(201))
        assertEquals(1, SourceLexer.firstLineNumber(0))
        assertEquals(1, SourceLexer.firstLineNumber(-5))
    }
}
