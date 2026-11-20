package com.nmoi.agent.ui

import android.os.Process
import android.os.SystemClock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

/**
 * 后台两条车道（D359）。
 *
 * 启动那十秒里抢 CPU 的不是一处在跑，是每一次读盘各自开一条 IO 线程、各自开一次库
 * （一次 `open` 500 ms 上下）。车道把"首屏要等的"和"看不见的"分开：
 *
 * - [io]：首屏要等的那几次读（助手列表 / 会话列表 / 消息首页）。两条线程，正常优先级。
 * - [low]：预热、代理接线、草稿、置顶、环境状态。一条线程 + `THREAD_PRIORITY_BACKGROUND`。
 *
 * UI 线程不动：Android 上把 UI 抬高需要 `CAP_SYS_NICE`，应用没有；能做的只有把后台压低。
 */
internal object Bg {
    val io: CoroutineDispatcher =
        Executors.newFixedThreadPool(2, worker("nmoi-io")).asCoroutineDispatcher()

    val low: CoroutineDispatcher =
        Executors.newSingleThreadExecutor(worker("nmoi-low")).asCoroutineDispatcher()

    private fun worker(name: String): ThreadFactory = ThreadFactory { runnable ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            runnable.run()
        }, name)
    }
}

/** 进程起到现在多少毫秒。`getStartElapsedRealtime` 就是这次进程被 fork 出来的时刻。 */
internal fun sinceProcessStartMs(): Long =
    SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()

/** 一段耗时的日志：`Start` 这一栏在真机上与 `Perf` 一起看（D359）。 */
internal object Start {
    fun mark(label: String, from: Long) {
        NLog.i("Start", "$label ${SystemClock.elapsedRealtime() - from}ms（进程起 +${sinceProcessStartMs()}ms）")
    }

    fun now(): Long = SystemClock.elapsedRealtime()
}