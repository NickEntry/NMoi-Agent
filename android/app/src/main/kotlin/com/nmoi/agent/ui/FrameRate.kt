package com.nmoi.agent.ui

import android.os.Build
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import java.util.concurrent.atomic.AtomicInteger

/**
 * 滑动期间能到多高（D360）：120 帧。
 *
 * 用户 2026-11-21：「有时候50多帧，有时候100多帧，理论上应该120帧，而且线程负载没满」。
 * 一句没写的地方就在这里——**我们从没告诉系统这一屏要 120 帧**。不写的时候系统按自己的
 * 判断给（静止降到 60，动得"多"才抬上来），于是同一个列表在不同会话里跑出两个帧率：
 * 短会话与侧边栏 50 多帧，长会话猛甩 100 多帧。这跟 CPU 有没有满没有关系。
 *
 * 用的是 `View#setRequestedFrameRate`（API 31）。**不是** `Window#setFrameRate`——那个方法
 * 在 AOSP 里是 `@hide`，公开的 `android.jar` 里根本编不过（本机类型检查就是这么发现的）。
 * 0 = 交回系统自己判断，滑动结束就还回去，免得整屏静止时也把面板钉在 120 上耗电。
 */
private const val SCROLL_FRAME_RATE = 120f

/**
 * 计数的是**还有几处在动**。
 *
 * 抽屉滑出与消息区滚动会同时发生（抽屉盖着聊天页那半秒），一处结束就写回 0 的话，
 * 另一处正滑到一半帧率就掉下去。所以按引用数进出：0→1 抬到 120，1→0 还回去。
 */
@Composable
internal fun RequestFrameRate(active: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, active) {
        if (active) FrameRateHolders.acquire(view)
        onDispose { if (active) FrameRateHolders.release(view) }
    }
}

private object FrameRateHolders {
    private val holders = AtomicInteger(0)
    private var applied: Float = -1f
    private var view: View? = null

    fun acquire(target: View) {
        view = target
        if (holders.incrementAndGet() == 1) apply(SCROLL_FRAME_RATE)
    }

    fun release(target: View) {
        view = target
        if (holders.decrementAndGet() <= 0) {
            holders.set(0)
            apply(0f)
        }
    }

    private fun apply(frameRate: Float) {
        val target = view ?: return
        if (applied == frameRate) return
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // 有的 ROM 会直接抛（厂商魔改），那不是我们的事：界面照常动。
            runCatching { target.setRequestedFrameRate(frameRate) }.isSuccess
        } else {
            false
        }
        applied = frameRate
        val what = if (frameRate > 0f) "${frameRate.toInt()}fps" else "交回系统"
        // 这一行必须写：帧率对不上的时候，第一件事就是确认它到底有没有生效。
        NLog.i("Perf", if (ok) "帧率请求 $what" else "帧率请求没生效（$what，系统版本或 ROM 不让）")
    }
}