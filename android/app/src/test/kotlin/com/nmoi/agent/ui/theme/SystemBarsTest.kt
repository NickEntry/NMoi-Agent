package com.nmoi.agent.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 状态栏取色（D415）。
 *
 * 系统**不会**自己看底色决定图标深浅——`isAppearanceLightStatusBars` 是应用显式告诉它的。
 * 所以这两条必须自己算对：
 *
 * 1. 底色按抽屉的暗度压暗；
 * 2. 压暗之后的底色算不算"浅"，决定图标用深色还是浅色。
 *
 * 算错的症状是"状态栏和背景不适合"：浅色主题下拉出抽屉，底色暗了、图标还是深色，
 * 糊成一片。这种错编译能过、不崩，只有眼睛看得出来。
 */
class SystemBarsTest {

    private val dark = 0xFF101014.toInt()
    private val light = 0xFFF7F7FA.toInt()

    /** 不压暗时原样返回：0 是"不动"，不是"全黑"。 */
    @Test
    fun zero_dim_keeps_the_color() {
        assertEquals(light, dimBackground(light, 0f))
        assertEquals(dark, dimBackground(dark, 0f))
    }

    /** 压暗之后一定更暗，而且压到底就是黑。 */
    @Test
    fun dim_makes_it_darker() {
        val dimmed = dimBackground(light, 0.6f)
        assertTrue("压暗后应该更暗", luma(dimmed) < luma(light))
        assertTrue("0.6 不该压成纯黑", luma(dimmed) > 0)
        assertEquals(0xFF000000.toInt(), dimBackground(light, 1f))
    }

    /**
     * 关键那一条：浅色主题 + 抽屉压暗之后，底色已经不算"浅"了，图标要换浅色。
     *
     * 这正是之前"状态栏和背景不适合"的原因——按主题算的话这里会判成"浅底用深图标"。
     */
    @Test
    fun dimmed_light_background_wants_light_icons() {
        assertTrue("浅色底本身是浅的", isLight(light))
        val dimmed = dimBackground(light, 0.60f)
        assertFalse("压暗之后不该再按浅底算（否则图标糊在灰上）", isLight(dimmed))
    }

    /** 深色底本来就该用浅图标；压暗之后更是。 */
    @Test
    fun dark_background_always_wants_light_icons() {
        assertFalse(isLight(dark))
        assertFalse(isLight(dimBackground(dark, 0.60f)))
    }

    /**
     * 阈值两侧各试一次：分界在 luma 128。
     *
     * 用 0x81 / 0x7F 而不是 0x80：灰 0x80 的 luma 算出来是 127.99999999999999
     * （浮点累加误差），正好卡在 `>= 128` 的下面。拿它当边界会测出假失败。
     */
    @Test
    fun threshold_is_at_half_luma() {
        val justLight = 0xFF818181.toInt()   // luma ≈ 128.99
        val justDark = 0xFF7F7F7F.toInt()    // luma ≈ 126.99
        assertTrue("129 算浅", isLight(justLight))
        assertFalse("127 算深", isLight(justDark))
    }

    /**
     * 状态栏那一块按"最不利的那一半"算（D416）。
     *
     * 抽屉拉出来时左边那半是抽屉的白，所以浅色主题下图标必须转深色；
     * 抽屉收起来之后按页面底色算。
     */
    @Test
    fun drawer_open_makes_the_status_bar_light() {
        // 浅色主题 + 抽屉打开：按抽屉的白算 → 浅底 → 深色图标
        val open = statusBarBackdrop(dark = false, drawerCoverage = 1f, dim = 0.60f)
        assertTrue("抽屉打开时该按浅底算（深色图标）", isLight(open))

        // 浅色主题 + 抽屉关：按页面底色算 → 也是浅的
        val closed = statusBarBackdrop(dark = false, drawerCoverage = 0f, dim = 0f)
        assertTrue(isLight(closed))
    }

    /** 深色主题：抽屉打开时按抽屉的深色算，图标转浅色。 */
    @Test
    fun drawer_open_on_dark_theme_wants_light_icons() {
        val open = statusBarBackdrop(dark = true, drawerCoverage = 1f, dim = 0.60f)
        assertFalse("深色主题 + 抽屉：白图标", isLight(open))
        val closed = statusBarBackdrop(dark = true, drawerCoverage = 0f, dim = 0f)
        assertFalse(isLight(closed))
    }

    /**
     * 抽屉没盖到一半时仍按页面底色算：这时候状态栏大部分是页面那一块。
     *
     * 阈值 0.5 是"过半"的意思，两侧各试一次。
     */
    @Test
    fun coverage_threshold_decides_which_half_wins() {
        val mostlyPage = statusBarBackdrop(dark = false, drawerCoverage = 0.49f, dim = 0.3f)
        val mostlyDrawer = statusBarBackdrop(dark = false, drawerCoverage = 0.5f, dim = 0.3f)
        // 0.49 走页面底色（被压暗过），0.5 走抽屉的白——后者更亮
        assertTrue(
            "过半之后应该按抽屉的白算，比压暗过的页面底色更亮",
            luma(mostlyDrawer) > luma(mostlyPage),
        )
    }

    /**
     * 纯色底也要判对：用平均亮度会在纯蓝上判错（蓝的视觉亮度很低，但 R/G/B 平均不低）。
     */
    @Test
    fun pure_blue_is_dark_even_though_channels_average_high() {
        val blue = 0xFF0000FF.toInt()
        assertFalse("纯蓝该算深色底，图标用浅色", isLight(blue))
    }

}
