package com.nmoi.agent.ui

/**
 * 哪个会话正在生成（0.15c）。
 *
 * 为什么不能只放在 `ChatScreen` 的 state 里：切到别的会话再切回来，页面重组过一次，
 * 那个布尔就回到初值了——而那一轮其实还在跑。用户看到按钮变回"发送"，一点就撞
 * `busy: 会话 … 已经有一轮在跑了`，而且**他没有任何办法停下来**。
 *
 * 两层：
 *
 * - 这一份是进程内缓存，负责"切回来立刻显示对的状态"（不用等一次 FFI）；
 * - 进页面时问一次原生（`turnBusy`），**以原生为准**：那一轮崩在 `run_json` 之外时，
 *   这里的缓存反而会留着 `true`（0.15i 的接管就靠这一次核对）。
 */
internal object GeneratingTracker {
    private val running = linkedSetOf<String>()

    @Synchronized
    fun mark(sessionId: String, on: Boolean) {
        if (on) running.add(sessionId) else running.remove(sessionId)
    }

    @Synchronized
    fun isRunning(sessionId: String): Boolean = running.contains(sessionId)

    /** 切回某个会话时，"那一轮挂在谁身上"。没在跑就是 `null`。 */
    @Synchronized
    fun sessionFor(sessionId: String): String? = if (running.contains(sessionId)) sessionId else null

    /**
     * 用原生那张表校准一次。返回 `true` 表示"本地以为在跑，其实早就不在跑了"——
     * 调用方要把状态收干净，并且**说一句**（用户已经点了发送才会知道界面是旧的）。
     */
    @Synchronized
    fun reconcile(sessionId: String, nativeRunning: Boolean): Boolean {
        val thought = running.contains(sessionId)
        if (nativeRunning) running.add(sessionId) else running.remove(sessionId)
        return thought && !nativeRunning
    }
}
