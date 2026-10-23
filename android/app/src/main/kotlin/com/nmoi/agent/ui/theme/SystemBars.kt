package com.nmoi.agent.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * 窗口外围（状态栏底色 + 系统栏图标深浅 + 窗口底色）跟着主题走。
 *
 * 这是真机踩出来的：主题切到浅色之后**状态栏图标还是白的**，白底白字，时间电量和返回箭头全看不见。
 * 原因不是主题没生效，而是系统那一层的"图标用深色还是浅色"要另外告诉它——
 * 我们只换了应用内的颜色，没管系统栏。
 *
 * 三件事必须一起做，少一件就会"看起来没生效"：
 *
 * 1. **窗口底色**（`window` 的 background）：没开 edge-to-edge 时，状态栏那块区域显示的
 *    就是它。只在 `onCreate` 设一次的话，应用内切主题后那块仍旧是启动时的颜色——
 *    深色主题下状态栏还是亮的，真机上就是这么被看出来的。
 * 2. **状态栏底色**：显式给成同一个颜色，别指望各家 ROM 对 transparent 的处理一致。
 * 3. **图标深浅**：`isAppearanceLightStatusBars = true` 表示系统栏底色是浅的、图标要画深色，
 *    所以传的是 `!dark`——这个 API 的语义最容易读反。
 *
 * 两处都要调：`onCreate` 一次（第一帧之前，否则会闪一下白图标/亮底色），
 * 主题运行中切换时再调一次（[SystemBarsForTheme]）。
 */
internal fun applySystemBars(window: Window, dark: Boolean) {
    val background = if (dark) DARK_BACKGROUND else LIGHT_BACKGROUND
    window.setBackgroundDrawable(ColorDrawable(background))
    window.statusBarColor = background
    // 导航栏保持透明：手势条下面就是窗口底色，三键导航时也是同一个颜色。
    window.navigationBarColor = Color.TRANSPARENT

    val controller = WindowCompat.getInsetsController(window, window.decorView)
    controller.isAppearanceLightStatusBars = !dark
    controller.isAppearanceLightNavigationBars = !dark
}

/** 与 `values/colors.xml` 里的两个值保持一致（那边给冷启动那一帧用）。 */
private const val DARK_BACKGROUND = 0xFF101014.toInt()
private const val LIGHT_BACKGROUND = 0xFFF7F7FA.toInt()

/** 主题变化时重设系统栏。[dark] 是应用当前用深色还是浅色。 */
@Composable
internal fun SystemBarsForTheme(dark: Boolean) {
    val view = LocalView.current
    val activity = LocalContext.current.findActivity()
    // view 进 key：换窗口（如旋转重建）时要重新设置一次。
    LaunchedEffect(dark, view, activity) {
        activity?.window?.let { applySystemBars(it, dark) }
    }
}

/** `LocalContext` 可能是 `ContextWrapper`，一层层剥到 Activity。 */
private fun Context.findActivity(): Activity? {
    var context: Context = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}
