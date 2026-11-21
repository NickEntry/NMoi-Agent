package com.nmoi.agent.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalView
import kotlin.math.roundToInt

/**
 * 把窗口钉在屏幕能给的最高刷新率上（D360）。
 *
 * 用户 2026-11-21：「有时候50多帧，有时候100多帧，理论上应该120帧，而且线程负载没满，
 * 核心负载没满」「短会话滑动的帧率不够…长会话滑动速度上去了帧率不错」。
 * 一句没写的地方就在这里——**这个应用从来没有申请过高帧率**（`grep setFrameRate` 一条都没有）。
 * 不申请的时候系统按自己的判断给：静止降到 60，动得"多"才抬上来。于是同一个列表在两个场景
 * 跑出两个帧率，而 CPU 一直是闲的。这不是渲染性能问题。
 *
 * 用 `WindowManager.LayoutParams.preferredRefreshRate`（API 21 就有，compileSdk 34 里编得过）。
 * **不是** `View#setRequestedFrameRate`：那个方法 API 35 才进公开 `android.jar`，
 * compileSdk 34 编不过（本机类型检查用的是 android-36 的 jar，把它放过去了——CI 才红）；
 * 也不是 `Window#setFrameRate`，那个是 `@hide`。
 *
 * 只在**启动时**设一次，不跟着手势开关：切显示模式要走一次窗口 relayout，
 * 在"手指刚按下那一帧"切，用户看到的就是一次卡顿——那正是我们要修的东西。
 * 代价是静止时也停在 120 Hz（面板不会自己降档），这一档由用户拍板。
 */
@Composable
internal fun PinHighRefreshRate() {
    val view = LocalView.current
    LaunchedEffect(view) {
        val window = view.context.nmoiWindow() ?: return@LaunchedEffect
        val display = view.display ?: return@LaunchedEffect
        val maxHz = display.supportedModes.maxOfOrNull { it.refreshRate } ?: 0f
        if (maxHz <= 0f) return@LaunchedEffect
        val before = display.refreshRate
        val asked = runCatching {
            val attributes = window.attributes
            attributes.preferredRefreshRate = maxHz
            window.attributes = attributes
        }.isSuccess
        // 切模式要几帧才落地，读回来才是证据（`display.refreshRate` 是当前模式）。
        withFrameNanos { }
        withFrameNanos { }
        val after = runCatching { view.display?.refreshRate ?: 0f }.getOrDefault(0f)
        NLog.i(
            "Perf",
            if (!asked) {
                "帧率：屏幕最高 ${maxHz.roundToInt()}Hz，但系统不接受这个请求"
            } else {
                "帧率：屏幕最高 ${maxHz.roundToInt()}Hz，请求前 ${before.roundToInt()}Hz，" +
                    "现在 ${after.roundToInt()}Hz"
            },
        )
    }
}

/** 从任意 context 往上找到那个 Activity 的 Window（Compose 里拿到的是 ContextThemeWrapper）。 */
private fun Context.nmoiWindow(): Window? {
    var context: Context? = this
    while (context is ContextWrapper) {
        if (context is Activity) return context.window
        context = context.baseContext
    }
    return null
}