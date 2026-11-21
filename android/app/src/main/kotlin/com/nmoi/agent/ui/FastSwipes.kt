package com.nmoi.agent.ui

import android.os.SystemClock
import kotlin.math.abs

/**
 * 三次快滑 → 巡游（D361）。判据是用户 2026-11-21 自己给的：
 *
 * > 从滑动开始计时，半秒内滑动距离超过屏幕的八分之一，就算一次成功快滑；三秒内有三次这样的快滑就触发。
 *
 * 为什么不是"看速度"：速度只在松手那一刻才有值（`VelocityTracker`），而用户描述的是**滑动过程**
 * 本身——手指在半秒里挪了屏幕的 1/8，那就是一次快滑，跟松手多快无关。这样慢拖也不会误触发：
 * 拖够 1/8 屏要花的时间超过半秒，那条路走不到计数上去。
 *
 * 记账分两层：一次**手势**最多记一次（`counted`），三次**手势**要在三秒的滑动窗口里凑齐。
 * 换方向、停顿超过 [quietMs]、松手，都从下一条重新算距离——否则来回搓两下也会连上三次。
 */
internal class FastSwipes(
    private val reachPx: Float,
    private val now: () -> Long = { SystemClock.elapsedRealtime() },
    /** 半秒：一次快滑的时间上限。 */
    private val windowMs: Long = 500,
    /** 三秒：三次快滑之间的总跨度。 */
    private val spanMs: Long = 3_000,
    /** 两次事件之间超过这么久的停顿，算新的一次手势。 */
    private val quietMs: Long = 250,
    private val onTrigger: (Int) -> Unit = {},
) {
    private var startedAt = 0L
    private var distance = 0f
    private var lastAt = 0L
    private var counted = false
    private var direction = 0
    private val stamps = ArrayDeque<Long>()

    /** 已经记上的快滑次数（只给单测读）。 */
    var counts = 0
        private set

    /**
     * 一次滑动事件（手指位移，px；正数 = 往下拖 = 往更早的消息去）。
     *
     * 返回这一下是不是"第三次"，便于调用方立刻接管。
     */
    fun feed(dy: Float): Boolean {
        if (dy == 0f) return false
        val at = now()
        val flipped = direction != 0 && ((dy > 0f) != (direction > 0))
        if (startedAt == 0L || at - lastAt > quietMs || flipped) {
            // 新手势（或换了方向）：距离从头算，这一次还没记过。
            startedAt = at
            distance = 0f
            counted = false
            direction = if (dy > 0f) 1 else -1
        }
        lastAt = at
        distance += abs(dy)
        if (counted) return false
        if (at - startedAt > windowMs) return false
        if (distance < reachPx) return false

        counted = true
        counts += 1
        stamps.addLast(at)
        while (stamps.isNotEmpty() && at - stamps.first() > spanMs) stamps.removeFirst()
        if (stamps.size < 3) return false
        stamps.clear()
        onTrigger(direction)
        return true
    }

    /** 松手或者停下来了：下一次从零开始算（三秒窗口里的那几次仍然留着）。 */
    fun end() {
        startedAt = 0L
        distance = 0f
        lastAt = 0L
        counted = false
        direction = 0
    }
}