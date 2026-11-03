package com.nmoi.agent.ui

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/** 一轮在后台跑出来的三件事。界面只在自己正看着那个会话时才反应。 */
internal sealed interface TurnEvent {
    val sessionId: String

    data class Started(
        override val sessionId: String,
        val text: String,
        /** 随这一条一起发的图（还没从库里读回来，界面只能给数量）。 */
        val imageCount: Int = 0,
    ) : TurnEvent

    data class Finished(
        override val sessionId: String,
        val message: String,
        /** 队列里还有下一句 → 自动接着发（正常结束才有这一条）。 */
        val continued: Boolean,
    ) : TurnEvent

    data class Failed(
        override val sessionId: String,
        val text: String,
        /** 用户那句话有没有进库（进了就别把草稿还回去了）。 */
        val textSaved: Boolean,
        val message: String,
    ) : TurnEvent
}

/**
 * 进程级的"正在跑的那一轮"（用户 2026-10-31 要的多会话并发 + 发送队列）。
 *
 * 为什么不能再挂在 `ChatScreen` 的 `rememberCoroutineScope()` 上：
 *
 * - 离开聊天页（去设置、编辑消息）→ 组合销毁 → 协程取消。JNI 那条阻塞调用还在跑、
 *   还会把结果写进库，但**收尾那几行不执行**：`GeneratingTracker` 留着"在跑"，
 *   下次进来对账就只能说"上一轮没收尾，已接管"。真机就是这么撞上的。
 * - 页面只有一个：A 会话跑完时写的是"当前这个会话"的 `busy=false`，
 *   而那时页面可能正显示 B——于是 B 的按钮被点亮，一点就吃 `busy`。
 *
 * 所以：**job 归这里，页面只订阅事件**。事件里带 `sessionId`，页面按"自己现在显示的是谁"
 * 过滤——这样就没有"写到别人身上"这回事了。
 *
 * 并发：不同会话可以同时跑（原生那边按会话登记、每轮自己开一条存储连接）。
 * 这里**不设全局上限**：替用户决定"第三个会话不许发"比让他自己看到限流更糟；
 * 撞到上游 429 是上游的事，会以 `Failed` 的形式落到那个会话的提示行上。
 */
internal object TurnRunner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableMapOf<String, Job>()

    private val _events = MutableSharedFlow<TurnEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<TurnEvent> = _events

    /**
     * 每个会话**最后一轮的结果**（D242）。
     *
     * 为什么要有：事件是发给"正看着这个会话的那一页"的。用户去设置里转一圈，
     * 那一轮在这期间跑完/失败，事件就没人收——回来时只剩一句"已接管"，
     * 他既看不到回答也看不到失败原因。所以结果在进程里留一份，页面回来时补领。
     *
     * 只留最后一个，`take` 之后清掉：这是"补领"，不是历史。
     */
    private val recent = mutableMapOf<String, TurnEvent>()

    fun takeRecent(sessionId: String): TurnEvent? = synchronized(recent) { recent.remove(sessionId) }

    /** 哪些会话正在跑。抽屉里那几个点点、发送键的形态都订阅它。 */
    val running = MutableStateFlow<Set<String>>(emptySet())

    fun isRunning(sessionId: String): Boolean = sessionId in running.value

    /**
     * 开一轮。已经在跑就返回 false（调用方该做的是"排队"，不是再发一次）。
     *
     * 跑完之后：正常结束 → 自动取队首接着发；非正常结束 → 把这个会话的队列**阻塞**，
     * 等用户自己处理（重排 / 删掉 / 手动发送）。
     */
    fun start(
        context: Context,
        databasePath: String,
        sessionId: String,
        text: String,
        /** 这一轮带的图片（D208）：跟着用户那条消息一起落库。 */
        images: List<PendingImage> = emptyList(),
    ): Boolean {
        synchronized(jobs) {
            if (jobs.containsKey(sessionId)) return false
            val job = scope.launch { run(context, databasePath, sessionId, text, images) }
            jobs[sessionId] = job
            mark(sessionId, true)
        }
        return true
    }

    /** 停止：只是置取消标志，收尾仍走 `run` 那条路（这样状态不会半截）。 */
    fun cancel(sessionId: String) {
        scope.launch { TurnRepository.cancel(sessionId) }
    }

    private suspend fun run(
        context: Context,
        databasePath: String,
        sessionId: String,
        text: String,
        images: List<PendingImage> = emptyList(),
    ) {
        _events.emit(TurnEvent.Started(sessionId, text, images.size))
        val result = runCatching {
            TurnRepository.send(context, databasePath, sessionId, text, images)
        }
            .getOrElse { Result.failure(it) }
        val outcome = result.getOrNull()
        // "正常结束" = 有结果且不是被停掉的。`cancelled=true` 也走下面那条（阻塞队列）：
        // 用户按下停止是**有意的中断**，自动接着往下发等于把他的"停"当成"继续"。
        if (outcome != null && !outcome.cancelled) {
            remember(TurnEvent.Finished(sessionId, outcome.describe(), continued = false))
            _events.emit(TurnEvent.Finished(sessionId, outcome.describe(), continued = false))
            finish(sessionId)
            // 正常结束 → 队列往下走。**先清状态再发下一句**：下一句的 Started 会重新点亮按钮。
            val next = SendQueue.takeNext(sessionId)
            if (next != null) {
                SendQueue.block(sessionId, false)
                start(context, databasePath, sessionId, next.text)
            }
            return
        }
        // 到这儿都是"非正常"：失败，或者被用户停掉。
        SendQueue.block(sessionId, true)
        val saved = withRunCatchingFalse {
            TurnRepository.wasUserTextSaved(databasePath, sessionId, text)
        }
        val message = when {
            outcome != null -> "已停止（队列停在原地，处理完再发送）"
            else -> buildString {
                append("请求失败：")
                append(result.exceptionOrNull()?.message ?: "原因不明")
                append(if (saved) "（你这句话已经存进库里了）" else "（这句话没存进去）")
                append("。队列已暂停")
            }
        }
        remember(TurnEvent.Failed(sessionId, text, saved, message))
        _events.emit(TurnEvent.Failed(sessionId, text, saved, message))
        finish(sessionId)
    }

    private fun remember(event: TurnEvent) {
        synchronized(recent) { recent[event.sessionId] = event }
    }

    private fun finish(sessionId: String) {
        synchronized(jobs) {
            jobs.remove(sessionId)
            mark(sessionId, false)
        }
    }

    private fun mark(sessionId: String, on: Boolean) {
        GeneratingTracker.mark(sessionId, on)
        running.value = if (on) running.value + sessionId else running.value - sessionId
    }

    private inline fun withRunCatchingFalse(block: () -> Boolean): Boolean =
        runCatching { block() }.getOrDefault(false)
}
