package com.nmoi.agent.ui

import kotlin.math.max
import kotlin.math.min

/**
 * 左滑删除区里那两样东西"露出多少"（D377）。
 *
 * 为什么不直接每帧算 alpha：这是纯逻辑，能脱离 Compose 单测。上一版把 alpha 写进
 * `graphicsLayer{}` 的 lambda 里，真机上它没被重算，冻在初值 0 —— 表现是
 * **"删除区能点，但是一片空白"**（D376）。同一类症状不值得再踩第二次，
 * 所以"什么时候露多少"单独抽出来钉住，Compose 那边只负责把这三个数画出去。
 *
 * 三个数都只跟**露出来的比例** `progress`（0 = 收起，1 = 88dp 全露）有关，
 * 跟时间无关：动画时长调成 0 也照样分得出来（V165 第 8 步）。
 */
internal object SwipeReveal {
    /** 图标在这一段进度内到齐。 */
    const val ICON_UNTIL = 0.35f

    /** 文字从这一段才开始出现，到 [LABEL_UNTIL] 落位。 */
    const val LABEL_FROM = 0.45f
    const val LABEL_UNTIL = 0.75f

    /** 文字还没落位时向右偏多少（dp）。 */
    const val LABEL_SHIFT_DP = 8f

    /** 图标未到齐时的缩放。 */
    const val ICON_SCALE_FROM = 0.72f

    /** 松手后停在打开的门槛（相对 88dp 的比例）。 */
    const val OPEN_AT = 0.5f

    /**
     * 露出来的比例。`offsetPx` 是内容行当前的位移（向左为负），`revealPx` 是删除区宽度。
     *
     * 三个边界都在这儿收口：0 位移 → 0；超过满宽 → 1；`revealPx` 是 0 时返回 0
     * （那一行没给删除区宽度，除以它会得到 NaN，而 NaN 传进 alpha 是"什么都不画"）。
     */
    fun progress(offsetPx: Float, revealPx: Float): Float {
        if (revealPx <= 0f) return 0f
        return (-offsetPx / revealPx).coerceIn(0f, 1f)
    }

    /** 图标的透明度与缩放：0 → [ICON_UNTIL] 内从 0 到 1、从 [ICON_SCALE_FROM] 到 1。 */
    fun iconAlpha(progress: Float): Float = ramp(progress, 0f, ICON_UNTIL)

    fun iconScale(progress: Float): Float =
        ICON_SCALE_FROM + (1f - ICON_SCALE_FROM) * ramp(progress, 0f, ICON_UNTIL)

    /** 文字的透明度与右偏：还没过 [LABEL_FROM] 时完全不出来。 */
    fun labelAlpha(progress: Float): Float = ramp(progress, LABEL_FROM, LABEL_UNTIL)

    fun labelShiftDp(progress: Float): Float =
        LABEL_SHIFT_DP * (1f - ramp(progress, LABEL_FROM, LABEL_UNTIL))

    /** 松手时该停在哪儿：过一半停在打开，否则弹回。 */
    fun openTarget(progress: Float): Boolean = progress > OPEN_AT

    /**
     * 把 `value` 映射到 `[from, to]` 这一段里的 0–1。
     *
     * `to <= from` 时返回 1：写错了区间的结果应该是"立刻到位"，不是 NaN 或者 0
     * ——后者会让删除区又变成一片空白，正是这条要防的东西。
     */
    fun ramp(value: Float, from: Float, to: Float): Float {
        if (to <= from) return 1f
        return min(1f, max(0f, (value - from) / (to - from)))
    }
}
