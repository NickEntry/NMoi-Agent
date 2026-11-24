package com.nmoi.agent.ui.theme

/**
 * 这个底色算不算"浅"：亮度过半就用深色图标。
 *
 * 系数是 Rec.601 的 luma（人眼对绿最敏感）。用平均亮度会在纯蓝/纯红底上判错。
 */
internal fun isLight(color: Int): Boolean = luma(color) >= 128.0

/**
 * 这个底色的视觉亮度（Rec.601）。
 *
 * 自己按位取通道，不用 `android.graphics.Color`：那个类在 JVM 单测里是桩，
 * 一调就抛 `RuntimeException: Stub!`——而这两个函数正是最该被测的（算错了只有眼睛看得见）。
 */
internal fun luma(color: Int): Double {
    val r = (color shr 16) and 0xFF
    val g = (color shr 8) and 0xFF
    val b = color and 0xFF
    return 0.299 * r + 0.587 * g + 0.114 * b
}

/**
 * 把底色按 [dim] 压暗（0 = 不动，0.6 = 抽屉那种暗度）。
 *
 * 与遮罩的算法一致：黑底按 alpha 叠上去。这样状态栏和内容区的暗度看起来是同一层。
 */
internal fun dimBackground(color: Int, dim: Float): Int {
    val a = dim.coerceIn(0f, 1f)
    if (a <= 0f) return color
    fun mix(channel: Int) = (channel * (1f - a)).toInt().coerceIn(0, 255)
    val r = mix((color shr 16) and 0xFF)
    val g = mix((color shr 8) and 0xFF)
    val b = mix(color and 0xFF)
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}

