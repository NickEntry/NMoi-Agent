package com.nmoi.agent.ui

/**
 * 位置键"跳到哪一行"的算法（D363）。
 *
 * 抽出来是因为**它错了看不出来**：屏幕上只是停在了一个"也算合理"的位置，而真机上又没法
 * 逐条对着日志数。这里是纯函数，`RailJumpTest` 把四种边界都钉住了。
 *
 * 术语：`starts` = 每条消息在行列表里的起点（`messageStarts`，已含顶部提示行的偏移）；
 * 「当前位置」= `starts` 里最后一个 ≤ 视口顶的那一条。
 */
internal object RailJump {
    /** 「到底」：滚到最后一条消息的结尾（不是某个消息的开头）。 */
    const val END = -1

    /**
     * ⌃：「上一条消息 / 这一条的开头」。
     *
     * - 视口顶正好在某条消息的开头（第 0 行像素且行号等于起点）→ 前一条；
     * - 否则 → 这一条的开头；
     * - 已经在第一条的开头、或者根本没有消息 → `null`（不动）。
     */
    fun up(topIndex: Int, topOffset: Int, starts: List<Int>): Int? {
        if (starts.isEmpty()) return null
        val at = rowOf(topIndex, starts)
        val exact = at >= 0 && starts[at] == topIndex && topOffset == 0
        val target = if (exact) at - 1 else at.coerceAtLeast(0)
        return if (target < 0) null else starts[target]
    }

    /**
     * ⌄：「下一条消息的开头」；已经是最后一条 → [END]（到结尾）。
     *
     * - 还停在提示行上（比第一条消息还靠上）→ 第一条消息的开头；
     * - 在最后一条上、而且已经滚到底 → `null`（不动）。
     */
    fun down(topIndex: Int, starts: List<Int>, canScrollForward: Boolean): Int? {
        if (starts.isEmpty()) return null
        val at = rowOf(topIndex, starts)
        if (at < 0) return starts.first()
        if (at + 1 < starts.size) return starts[at + 1]
        return if (canScrollForward) END else null
    }

    /** 视口顶落在第几条消息上（`-1` = 比第一条还靠上，比如停在顶部提示行）。 */
    fun rowOf(topIndex: Int, starts: List<Int>): Int = starts.indexOfLast { it <= topIndex }
}