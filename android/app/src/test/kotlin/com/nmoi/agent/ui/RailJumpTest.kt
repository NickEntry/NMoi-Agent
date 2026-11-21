package com.nmoi.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 位置键跳哪儿（D363）。
 *
 * 这几条钉的都是"错了也看不出来"的地方：它会停在一个看着也算合理的位置上。
 * `starts = [0, 3, 3+…]` 里的间隔就是"一条消息被拆成好几行"。
 */
class RailJumpTest {
    /** 三条消息：第 0 行、第 3 行、第 7 行（中间是过程卡 / 正文那些行）。 */
    private val starts = listOf(0, 3, 7)

    @Test
    fun `开头贴顶时往上一条`() {
        // 视口顶正好在第 3 行（第二条消息的开头）→ 去第一条。
        assertEquals(0, RailJump.up(topIndex = 3, topOffset = 0, starts = starts))
    }

    @Test
    fun `露一半时回到这一条的开头`() {
        // 第 4 行（第二条消息的第二行）→ 回第 3 行，不是上一条。
        assertEquals(3, RailJump.up(topIndex = 4, topOffset = 0, starts = starts))
        // 同一行但滚了几个像素：仍然算"这一条"。
        assertEquals(3, RailJump.up(topIndex = 3, topOffset = 24, starts = starts))
    }

    @Test
    fun `已经在第一条的开头时不动`() {
        assertNull(RailJump.up(topIndex = 0, topOffset = 0, starts = starts))
    }

    @Test
    fun `停在提示行上时上下都往第一条消息对齐`() {
        // starts 从第 2 行开始（上面是「还有更早的消息」那行）：视口顶比第一条消息还靠上。
        assertEquals(2, RailJump.down(topIndex = 0, starts = listOf(2, 5), canScrollForward = true))
        assertEquals(2, RailJump.up(topIndex = 0, topOffset = 0, starts = listOf(2, 5)))
    }

    @Test
    fun `往下一条`() {
        assertEquals(7, RailJump.down(topIndex = 3, starts = starts, canScrollForward = true))
        // 露一半也算在这一条里：下一条是 7，不是 3。
        assertEquals(7, RailJump.down(topIndex = 5, starts = starts, canScrollForward = true))
    }

    @Test
    fun `最后一条时到底-已经在底就什么都不做`() {
        assertEquals(RailJump.END, RailJump.down(topIndex = 7, starts = starts, canScrollForward = true))
        assertNull(RailJump.down(topIndex = 7, starts = starts, canScrollForward = false))
    }

    @Test
    fun `没有消息时两个方向都不动`() {
        assertNull(RailJump.up(topIndex = 0, topOffset = 0, starts = emptyList()))
        assertNull(RailJump.down(topIndex = 0, starts = emptyList(), canScrollForward = true))
    }

    @Test
    fun `rowOf 给的是落在哪一条`() {
        assertEquals(0, RailJump.rowOf(0, starts))
        assertEquals(0, RailJump.rowOf(2, starts))
        assertEquals(1, RailJump.rowOf(3, starts))
        assertEquals(2, RailJump.rowOf(9, starts))
        assertEquals(-1, RailJump.rowOf(0, listOf(2, 5)))
    }
}