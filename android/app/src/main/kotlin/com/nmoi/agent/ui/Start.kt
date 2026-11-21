package com.nmoi.agent.ui

import android.os.Process
import android.os.SystemClock

/**
 * 启动分段的尺子（D359）。
 *
 * `Process.getStartElapsedRealtime()` 就是这次进程被 fork 出来的时刻，全部时间都相对它报，
 * 所以"进程起 +N ms"这一栏在几处日志里能直接相加对比（onCreate → 首帧 → 各段数据读完）。
 */
internal fun sinceProcessStartMs(): Long =
    SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()

/** 一段耗时的日志：`Start` 这一栏在真机上与 `Perf` 一起看（D359）。 */
internal object Start {
    fun mark(label: String, from: Long) {
        NLog.i(
            "Start",
            "$label ${SystemClock.elapsedRealtime() - from}ms（进程起 +${sinceProcessStartMs()}ms）",
        )
    }

    fun now(): Long = SystemClock.elapsedRealtime()
}