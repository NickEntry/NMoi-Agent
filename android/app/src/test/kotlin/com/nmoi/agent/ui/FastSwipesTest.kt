package com.nmoi.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 三次快滑的判据（D361）：半秒内挪过 1/8 屏算一次，三秒内三次算触发。
 *
 * 时钟是注入的，所以这里不睡——每一条都是"给定时间戳，看它记不记账"。
 */
class FastSwipesTest {
    private var clock = 0L
    private val triggers = ArrayList<Int>()
    private val swipes = FastSwipes(
        reachPx = 300f,
        now = { clock },
        onTrigger = { triggers.add(it) },
    )

    /** 一次完整的手势：每隔 50 ms 挪 [step] px，挪 [steps] 次，然后松手。 */
    private fun gesture(step: Float, steps: Int) {
        repeat(steps) {
            clock += 50
            swipes.feed(step)
        }
        swipes.end()
    }

    @Test
    fun three_quick_swipes_trigger_once() {
        gesture(-200f, 2)
        gesture(-200f, 2)
        gesture(-200f, 2)

        assertEquals(3, swipes.counts)
        assertEquals(listOf(-1), triggers)
    }

    @Test
    fun slow_drag_does_not_count() {
        // 每次 80 px、间隔 200 ms：挪过 300 px 用了 600 ms，超过半秒。
        repeat(5) {
            clock += 200
            swipes.feed(-80f)
        }
        swipes.end()

        assertEquals(0, swipes.counts)
        assertTrue(triggers.isEmpty())
    }

    @Test
    fun one_gesture_counts_at_most_once() {
        clock += 50
        swipes.feed(-400f)
        clock += 50
        swipes.feed(-400f)
        clock += 50
        swipes.feed(-400f)
        clock += 50
        swipes.feed(-400f)
        swipes.end()

        assertEquals(1, swipes.counts)
        assertTrue(triggers.isEmpty())
    }

    @Test
    fun three_swipes_spread_over_more_than_three_seconds_do_not_trigger() {
        gesture(-200f, 2)
        clock += 2_000
        gesture(-200f, 2)
        clock += 2_000
        gesture(-200f, 2)

        assertEquals(3, swipes.counts)
        assertTrue(triggers.isEmpty())
    }

    @Test
    fun flipping_direction_restarts_the_distance() {
        clock += 50
        swipes.feed(-250f)
        clock += 50
        swipes.feed(250f)
        swipes.end()

        // 换方向就从头算：两段各 250 px，加起来 500 也不会被当成一次 500 px 的快滑。
        assertEquals(0, swipes.counts)
    }

    @Test
    fun a_pause_longer_than_the_quiet_gap_starts_over() {
        clock += 50
        swipes.feed(-250f)
        clock += 400
        swipes.feed(-250f)
        swipes.end()

        assertEquals(0, swipes.counts)
    }

    @Test
    fun trigger_reports_the_direction_and_clears_the_window() {
        gesture(200f, 2)
        gesture(200f, 2)
        assertTrue(swipes.feed(0f).not())

        clock += 50
        swipes.feed(200f)
        clock += 50
        swipes.feed(200f)
        swipes.end()

        assertEquals(listOf(1), triggers)
        // 触发之后窗口清空：再来一次不算"第二次"。
        gesture(200f, 2)
        assertEquals(4, swipes.counts)
        assertEquals(listOf(1), triggers)
    }

    @Test
    fun a_single_gesture_below_the_reach_is_not_counted() {
        gesture(-100f, 2)
        assertEquals(0, swipes.counts)
        assertFalse(triggers.isNotEmpty())
    }
}