package com.nmoi.agent.ui

/**
 * 消息区的一行（D229）。
 *
 * 为什么要有这一层：一次回合里「深度思考 → 运行命令 → 写入文件」是**连着**发生的，
 * 以前每个单元各画一张卡，一屏能冒出五六张一样的小卡，看不出它们属于同一轮。
 * 现在连续的思考/工具合成一行，外面一个大块、左侧一条线串起来。
 *
 * 分组只影响**渲染**：`ChatUnit` 与库里的东西一一对应，这里不新增也不合并数据。
 */
internal sealed interface ChatRow {
    /** 单独一条（正文、用户消息、断块，或者"只跑了一次工具"这种不值得分组的）。 */
    data class Single(val unit: ChatUnit) : ChatRow

    /** 连续的过程项（思考 / 工具），至少两个。 */
    data class Process(val items: List<ProcessItem>) : ChatRow {
        /**
         * 折叠状态的键：**取第一项**，不取整组的 id。
         *
         * 取整组的话，这一轮又多跑了一次工具、组里多了一项，键就变了，
         * 用户刚展开的大块会自己收回去。
         */
        val key: String get() = items.first().id
    }
}

/** 过程项：一个思考块，或一次工具往返。 */
internal data class ProcessItem(
    val id: String,
    val kind: Kind,
    val unit: ChatUnit,
    /** 思考块的那条消息（`kind == Thinking` 时有）。 */
    val thinking: ChatMessage? = null,
) {
    enum class Kind { Thinking, Tool }

    /**
     * 还在跑：单元没闭合（工具结果还没回来）。
     *
     * 正在跑的过程块**永远展开**——折起来就看不见它在动，
     * 而"它还在动"恰恰是用户此刻唯一想知道的事。
     */
    val running: Boolean get() = !unit.closed
}

/**
 * 把一个会话的单元切成行。
 *
 * 三条规矩（用户 2026-11-03 口述，逐条落）：
 *
 * 1. **连续的**思考与工具合成一组；中间夹了正文就断开（正文是回答，不是过程）。
 * 2. 组里只有一项时**不分组**——一次工具调用单独画一张卡就是现在的样子，
 *    给它加一条竖线和一层缩进只是多一层壳。
 * 3. 用户消息、断块、正文一律是单独一行。
 */
internal fun buildRows(units: List<ChatUnit>): List<ChatRow> {
    val rows = mutableListOf<ChatRow>()
    var pending = mutableListOf<ProcessItem>()

    fun flush() {
        if (pending.isEmpty()) return
        rows += if (pending.size == 1) ChatRow.Single(pending.first().unit) else ChatRow.Process(pending)
        pending = mutableListOf()
    }

    for (unit in units) {
        val item = processItemOf(unit)
        if (item != null) {
            pending += item
        } else {
            flush()
            rows += ChatRow.Single(unit)
        }
    }
    flush()
    return rows
}

/** 这个单元算不算"过程项"；不算就返回 `null`（正文 / 用户消息 / 断块）。 */
internal fun processItemOf(unit: ChatUnit): ProcessItem? {
    if (unit.isToolPair) {
        return ProcessItem(id = unit.id, kind = ProcessItem.Kind.Tool, unit = unit)
    }
    // 一个 turn 单元只有一条消息（`AtomicUnit::turn`）。思考那条单独成单元。
    val only = unit.messages.singleOrNull() ?: return null
    if (!only.isThinking) return null
    return ProcessItem(
        id = unit.id,
        kind = ProcessItem.Kind.Thinking,
        unit = unit,
        thinking = only,
    )
}

/**
 * 折叠之后看得见哪几项。
 *
 * 规矩：**至少三个**才折；折起来保留**最新两个**（用户要的是"最近发生了什么"，
 * 最早那几步只是过程）。正在跑的组不折——`foldable` 由调用方按 [ProcessItem.running] 判。
 */
internal fun visibleItems(items: List<ProcessItem>, expanded: Boolean): List<ProcessItem> {
    if (!foldable(items)) return items
    return if (expanded) items else items.takeLast(2)
}

/** 这一组够不够折叠的门槛（≥ 3）。 */
internal fun foldable(items: List<ProcessItem>): Boolean = items.size >= 3

/** 折叠时被收起来的步数（给"展开更多 N 步"那句用）。 */
internal fun hiddenCount(items: List<ProcessItem>, expanded: Boolean): Int =
    (items.size - visibleItems(items, expanded).size).coerceAtLeast(0)
