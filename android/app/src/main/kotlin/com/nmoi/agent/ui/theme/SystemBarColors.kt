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

/**
 * 状态栏那一块实际是什么颜色（D416）。
 *
 * edge-to-edge 之后，状态栏那块区域是**应用自己画的**：左边可能是抽屉的白，
 * 右边是页面的暗。图标只有一个开关，所以只能按"最不利的那一半"来定——
 * 抽屉占了大半个屏时按抽屉的底色算，否则按页面底色算。
 *
 * 返回压暗之后的窗口底色（抽屉没开时给窗口铺的那个）。
 */
internal fun statusBarBackdrop(dark: Boolean, drawerCoverage: Float, dim: Float): Int {
    // 抽屉拉出来时它自己铺的是 `surface`（浅色主题近白、深色主题近黑），
    // 覆盖超过一半屏之后，状态栏左半基本就是它。
    if (drawerCoverage >= 0.5f) {
        return if (dark) DARK_SURFACE else LIGHT_SURFACE
    }
    return dimBackground(if (dark) DARK_BACKGROUND else LIGHT_BACKGROUND, dim)
}

/** 与 `values/colors.xml` 里的两个值保持一致（那边给冷启动那一帧用）。 */
internal const val DARK_BACKGROUND = 0xFF101014.toInt()
internal const val LIGHT_BACKGROUND = 0xFFF7F7FA.toInt()

/** 与主题里的 `surface` 对齐（浅色主题近白、深色主题近黑）。 */
private const val LIGHT_SURFACE = 0xFFFFFFFF.toInt()
private const val DARK_SURFACE = 0xFF14141A.toInt()
