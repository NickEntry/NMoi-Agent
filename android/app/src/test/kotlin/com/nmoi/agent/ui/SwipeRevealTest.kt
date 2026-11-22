package com.nmoi.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 左滑删除区的露出曲线（D377）。
 *
 * 钉的是"错了会变成一片空白或者全是空白"的那些点，以及预览里那三个手感数字。
 * 不测 Compose：这一层是纯函数，能在这儿跑就说明数值本身对。
 */
class SwipeRevealTest {
    private val reveal = 88f * 3f

    @Test
    fun `收起时什么都不露`() {
        assertEquals(0f, SwipeReveal.progress(0f, reveal), 1e-6f)
        assertEquals(0f, SwipeReveal.iconAlpha(0f), 1e-6f)
        assertEquals(0f, SwipeReveal.labelAlpha(0f), 1e-6f)
    }

    @Test
    fun `滑到五分之一时图标已经出现、文字还没有`() {
        val p = SwipeReveal.progress(-reveal * 0.2f, reveal)
        assertEquals(0.2f, p, 1e-5f)
        assertTrue("图标该看得见了", SwipeReveal.iconAlpha(p) > 0.5f)
        assertEquals("文字还不该出来", 0f, SwipeReveal.labelAlpha(p), 1e-6f)
    }

    @Test
    fun `滑到五分之二时图标全亮、文字仍然没有`() {
        val p = SwipeReveal.progress(-reveal * 0.4f, reveal)
        assertEquals(1f, SwipeReveal.iconAlpha(p), 1e-6f)
        assertEquals(1f, SwipeReveal.iconScale(p), 1e-6f)
        assertEquals(0f, SwipeReveal.labelAlpha(p), 1e-6f)
        assertEquals(SwipeReveal.LABEL_SHIFT_DP, SwipeReveal.labelShiftDp(p), 1e-6f)
    }

    @Test
    fun `滑到底时两样都到位`() {
        val p = SwipeReveal.progress(-reveal, reveal)
        assertEquals(1f, p, 1e-6f)
        assertEquals(1f, SwipeReveal.iconAlpha(p), 1e-6f)
        assertEquals(1f, SwipeReveal.labelAlpha(p), 1e-6f)
        assertEquals(0f, SwipeReveal.labelShiftDp(p), 1e-6f)
    }

    @Test
    fun `文字在区间中点是一半`() {
        val mid = (SwipeReveal.LABEL_FROM + SwipeReveal.LABEL_UNTIL) / 2f
        assertEquals(0.5f, SwipeReveal.labelAlpha(mid), 1e-6f)
    }

    @Test
    fun `过一半才停在打开`() {
        assertFalse(SwipeReveal.openTarget(0.49f))
        assertFalse("正好一半算没过", SwipeReveal.openTarget(0.5f))
        assertTrue(SwipeReveal.openTarget(0.51f))
        assertTrue(SwipeReveal.openTarget(1f))
    }

    @Test
    fun `删除区宽度是 0 时不产生 NaN`() {
        val p = SwipeReveal.progress(-40f, 0f)
        assertEquals(0f, p, 0f)
        // NaN 传进 alpha 的表现是"什么都不画"，正是这条要防的
        assertFalse(p.isNaN())
        assertFalse(SwipeReveal.iconAlpha(p).isNaN())
    }

    @Test
    fun `往右滑过头不会超过三分之一以外的值`() {
        // offset 为正 = 内容被往右拖（不该发生，但要收口）
        assertEquals(0f, SwipeReveal.progress(30f, reveal), 0f)
        // 拖过满宽
        assertEquals(1f, SwipeReveal.progress(-reveal * 2f, reveal), 0f)
    }

    @Test
    fun `区间写反时立刻到位而不是变成空白`() {
        // to <= from 是写错，结果该是"看得见"，不是 0
        assertEquals(1f, SwipeReveal.ramp(0.3f, 0.6f, 0.4f), 0f)
        assertEquals(1f, SwipeReveal.ramp(0.3f, 0.5f, 0.5f), 0f)
    }

    @Test
    fun `曲线单调：滑得越多露得越多`() {
        var lastIcon = -1f
        var lastLabel = -1f
        for (step in 0..100) {
            val p = step / 100f
            val i = SwipeReveal.iconAlpha(p)
            val l = SwipeReveal.labelAlpha(p)
            assertTrue("图标不该回落：p=$p", i >= lastIcon - 1e-6f)
            assertTrue("文字不该回落：p=$p", l >= lastLabel - 1e-6f)
            lastIcon = i
            lastLabel = l
        }
    }
}
