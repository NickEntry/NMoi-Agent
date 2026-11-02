package com.nmoi.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 流式拼接（0.15ah）。
 *
 * 这几条钉的都是**真机上看着像 bug 的样子**：回答开头凭空消失、同一段出现两遍、
 * 表情符号每隔几个字吞一个。它们都不报错，只能靠对着屏幕猜。
 */
class LiveStreamTest {

    @Test
    fun `新长出来的那一段接在已有正文后面`() {
        // turn_busy 给的是尾部（这里假设只有 2 个字）和总量 4。
        assertEquals("你好世界", growText("你好", 4, "世界"))
        // 尾部里还带着手里的旧字：只取末尾那截新的，别把"好"再写一遍。
        assertEquals("你好世界", growText("你好", 4, "好世界"))
    }

    @Test
    fun `总量没变就不重复追加`() {
        assertEquals("你好", growText("你好", 2, "你好"))
        // 尾部比手里还短，但总量没涨：一个字都不该加。
        assertEquals("你好世界", growText("你好世界", 4, "世界"))
    }

    @Test
    fun `界面卡了一下只拿到尾部时-不假装完整`() {
        // 手里 2 个字，总量 10，尾部只有 3 个字的窗口：中间那 5 个字确实没拿到。
        // 这时候给尾部（而不是把尾部硬接在后面），缺的那截看得见。
        assertEquals("八九十", growText("一二", 10, "八九十"))
    }

    @Test
    fun `表情符号按码点数-不会吞字`() {
        // "😀" 在 Kotlin 里 length=2，在 Rust 的 chars().count() 里是 1。
        val emoji = "😀"
        assertEquals(2, emoji.length)
        assertEquals(1, emoji.codePointCount(0, emoji.length))
        // 手里有一个表情（1 个码点），总量 2，尾部是"表情 + 好"：要接上"好"。
        assertEquals("${emoji}好", growText(emoji, 2, "${emoji}好"))
    }

    @Test
    fun `还没收到任何增量时保持原样`() {
        assertEquals("", growText("", 0, ""))
        assertEquals("已有", growText("已有", 0, "已有"))
    }
}
