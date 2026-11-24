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
 * 窗口外围（窗口兜底底色 + 系统栏图标深浅）跟着主题走。
 *
 * edge-to-edge 之后状态栏那一块由**应用内容**决定，窗口不能给它涂色：
 * `statusBarColor` 一设就把那块盖住，抽屉铺到状态栏下面这件事等于没发生。
 *
 * 所以这里只剩两件事：
 *
 * 1. **窗口底色**：冷启动第一帧的兜底（那时 Compose 还没画），切主题时也要跟着换。
 * 2. **图标深浅**：`isAppearanceLightStatusBars = true` 表示底色浅、图标要画深色。
 *
 * 两处都要调：`onCreate` 一次（第一帧之前），主题/抽屉变化时再调一次（[SystemBarsForTheme]）。
 */
internal fun applySystemBars(
    window: Window,
    dark: Boolean,
    dim: Float = 0f,
    /** 抽屉盖住了屏幕多少（0 = 没开，1 = 全屏）。 */
    drawerCoverage: Float = 0f,
) {
    // 那一块由内容画：左边可能是抽屉的白、右边是页面的暗。窗口只负责没被盖住的兜底。
    val background = statusBarBackdrop(dark, drawerCoverage, dim)
    window.setBackgroundDrawable(ColorDrawable(background))
    window.statusBarColor = Color.TRANSPARENT
    window.navigationBarColor = Color.TRANSPARENT

    val controller = WindowCompat.getInsetsController(window, window.decorView)
    // 系统不会自己看底色，图标深浅只能按实际盖住状态栏的那一块算——不按主题算。
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
