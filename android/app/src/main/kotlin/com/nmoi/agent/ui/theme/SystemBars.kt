package com.nmoi.agent.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * 状态栏 / 导航栏的图标颜色跟着主题走。
 *
 * 这是真机踩出来的：主题切到浅色之后**状态栏图标还是白的**，白底白字，时间电量和返回箭头全看不见。
 * 原因不是主题没生效，而是系统那一层的"图标用深色还是浅色"要另外告诉它——
 * 我们只换了应用内的颜色，没管系统栏。
 *
 * API 的语义容易读反：`isAppearanceLightStatusBars = true` 表示**系统栏的底色是浅的**，
 * 于是图标要画成深色。所以要传的是 `!dark`，不是 `dark`。
 *
 * 两处都要调：`onCreate` 里一次（第一帧之前，否则会闪一下白图标），
 * 主题运行中切换时再调一次（[SystemBarsForTheme]）。
 */
internal fun applySystemBars(window: Window, lightBackground: Boolean) {
    val controller = WindowCompat.getInsetsController(window, window.decorView)
    controller.isAppearanceLightStatusBars = lightBackground
    controller.isAppearanceLightNavigationBars = lightBackground
}

/** 主题变化时重设系统栏。[dark] 是应用当前用深色还是浅色。 */
@Composable
internal fun SystemBarsForTheme(dark: Boolean) {
    val view = LocalView.current
    val activity = LocalContext.current.findActivity()
    // view 进 key：换窗口（如旋转重建）时要重新设置一次。
    LaunchedEffect(dark, view, activity) {
        activity?.window?.let { applySystemBars(it, lightBackground = !dark) }
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
