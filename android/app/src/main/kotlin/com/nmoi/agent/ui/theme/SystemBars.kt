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
internal fun applySystemBars(
    window: Window,
    dark: Boolean,
    dim: Float = 0f,
    /** 抽屉盖住了屏幕多少（0 = 没开，1 = 全屏）。 */
    drawerCoverage: Float = 0f,
) {
    // edge-to-edge 之后状态栏那一块是**应用自己画的**（D416）：
    // 左边可能是抽屉的白、右边是页面的暗。窗口底色只是"没被盖住那部分"的兜底，
    // 图标深浅按 `statusBarBackdrop` 算出来的那一块定。
    val background = statusBarBackdrop(dark, drawerCoverage, dim)
    window.setBackgroundDrawable(ColorDrawable(background))
    window.statusBarColor = background
    // 导航栏保持透明：手势条下面就是窗口底色，三键导航时也是同一个颜色。
    window.navigationBarColor = Color.TRANSPARENT

    val controller = WindowCompat.getInsetsController(window, window.decorView)
    // 图标深浅按**压暗之后**的底色算，不按主题算。
    //
    // 系统不会自己看底色：`isAppearanceLightStatusBars` 是应用显式告诉它的。
    // 抽屉把浅色底压到 #636364 时，如果还按"浅色主题 → 深色图标"来，图标就糊在
    // 那块灰上了——这正是"状态栏和背景不太适合"的来源。
    val light = isLight(background)
    controller.isAppearanceLightStatusBars = light
    controller.isAppearanceLightNavigationBars = light
}

/** 主题变化时重设系统栏。[dark] 是应用当前用深色还是浅色。 */
@Composable
internal fun SystemBarsForTheme(dark: Boolean, dim: Float = 0f, drawerCoverage: Float = 0f) {
    val view = LocalView.current
    val activity = LocalContext.current.findActivity()
    // view 进 key：换窗口（如旋转重建）时要重新设置一次。
    // `dim` / `drawerCoverage` 进 key：抽屉开合要跟着改状态栏底色与图标深浅。
    LaunchedEffect(dark, dim, drawerCoverage, view, activity) {
        activity?.window?.let { applySystemBars(it, dark, dim, drawerCoverage) }
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
