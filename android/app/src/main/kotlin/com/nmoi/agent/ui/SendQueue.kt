package com.nmoi.agent.ui

import kotlinx.coroutines.flow.MutableStateFlow

/** 排队里的一条。`id` 只在这台设备上有意义（用来排序、删除），不发给任何人。 */
internal data class QueuedSend(val id: String, val text: String, val at: Long)

/**
 * 每个会话一条**发送队列**（用户 2026-10-31 定的语义）。
 *
 * 为什么按会话分而不是全局一条：一个会话的下一句依赖它自己这轮的上下文，
 * 排到别的会话里就变成"发给错的人"了。会话之间**互不影响**，各排各的。
 *
 * 三种状态，别混：
 *
 * - **排队中**：会话正在跑，用户又发了一句 → 进队列，排第几位要当场告诉他；
 * - **自动继续**：这一轮**正常结束** → 取队首接着发，不需要再点一次；
 * - **阻塞**：这一轮**非正常结束**（失败 / 被停止）→ 队列停在原地，等用户处理。
 *
 * 阻塞不是"卡住了"：自动往下发等于替用户决定"失败也接着花他的钱"，
 * 而他此刻最想知道的恰恰是"上一句到底怎么了"。
 */
internal object SendQueue {
    private val queues = mutableMapOf<String, MutableList<QueuedSend>>()
    /** 非正常结束过的会话：队列停在这里，等用户手动处理。 */
    private val blocked = mutableSetOf<String>()
    private var sequence = 0L

    /** 任何变更都 +1：界面（按钮上的计数、队列窗口）订阅它。 */
    val version = MutableStateFlow(0)

    @Synchronized
    fun items(sessionId: String): List<QueuedSend> = queues[sessionId].orEmpty().toList()

    @Synchronized
    fun count(sessionId: String): Int = queues[sessionId]?.size ?: 0

    @Synchronized
    fun isBlocked(sessionId: String): Boolean = sessionId in blocked

    @Synchronized
    fun block(sessionId: String, on: Boolean) {
        if (on) blocked += sessionId else blocked -= sessionId
        bumpLocked()
    }

    /** 排进去，返回**排到第几位**（1 基，给提示行用）。 */
    @Synchronized
    fun enqueue(sessionId: String, text: String): Int {
        val row = QueuedSend(id = "q${sequence++}", text = text, at = System.currentTimeMillis())
        val list = queues.getOrPut(sessionId) { mutableListOf() }
        list += row
        bumpLocked()
        return list.size
    }

    @Synchronized
    fun peek(sessionId: String): QueuedSend? = queues[sessionId]?.firstOrNull()

    /** 取队首并移除。返回 null = 队列空了（自动继续的终止条件）。 */
    @Synchronized
    fun takeNext(sessionId: String): QueuedSend? {
        val list = queues[sessionId] ?: return null
        if (list.isEmpty()) return null
        val head = list.removeAt(0)
        if (list.isEmpty()) queues.remove(sessionId)
        bumpLocked()
        return head
    }

    @Synchronized
    fun remove(sessionId: String, id: String): Boolean {
        val list = queues[sessionId] ?: return false
        val removed = list.removeAll { it.id == id }
        if (list.isEmpty() && removed) queues.remove(sessionId)
        if (removed) bumpLocked()
        return removed
    }

    /** 上移（-1）/ 下移（+1）。到头了返回 false，界面据此把按钮灰掉。 */
    @Synchronized
    fun move(sessionId: String, id: String, delta: Int): Boolean {
        val list = queues[sessionId] ?: return false
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return false
        val target = index + delta
        if (target !in list.indices) return false
        val row = list.removeAt(index)
        list.add(target, row)
        bumpLocked()
        return true
    }

    @Synchronized
    fun clear(sessionId: String) {
        if (queues.remove(sessionId) != null) bumpLocked()
    }

    /** 会话被删掉时连带清掉，不留孤立数据（铁律 ②：界面指不出来的东西不该存）。 */
    @Synchronized
    fun forget(sessionId: String) {
        queues.remove(sessionId)
        blocked -= sessionId
        bumpLocked()
    }

    /** 已经在锁里了，别再拿一遍（`MutableStateFlow` 的写是线程安全的）。 */
    private fun bumpLocked() {
        version.value = version.value + 1
    }
}
