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
    /**
     * 单独一条（正文、用户消息、断块，或者"只跑了一次工具"这种不值得分组的）。
     *
     * `hideReasoning`：这条消息的思考**已经并进上面那个大块**了（见 [buildRows]），
     * 这里就不要再画一遍——同一段思考出现两次，用户会以为模型想了两遍。
     */
    data class Single(
        val unit: ChatUnit,
        val hideReasoning: Boolean = false,
        /** 这一行画单元的哪一半（D258 折行之后，工具那一轮里也有正文与思考）。 */
        val slice: UnitSlice = UnitSlice.Whole,
    ) : ChatRow

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

/** 一个单元被画成哪一半：整条 / 只有正文与思考 / 只有工具。 */
internal enum class UnitSlice { Whole, TextOnly, ToolsOnly }

/** 过程项：一个思考块，或者一次工具往返。 */
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
        rows += if (pending.size == 1) {
            val only = pending.first()
            // 单独一项工具只画工具；单独一项思考照旧画整条。
            ChatRow.Single(
                only.unit,
                slice = when (only.kind) {
                    ProcessItem.Kind.Tool -> UnitSlice.ToolsOnly
                    ProcessItem.Kind.Thinking -> UnitSlice.Whole
                },
            )
        } else {
            ChatRow.Process(pending)
        }
        pending = mutableListOf()
    }

    for (unit in units) {
        val item = processItemOf(unit)
        if (item != null) {
            if (unit.isToolPair) {
                // 折行之后一轮 = 一条消息（思考 + 正文 + 工具）。画法只有两种：
                // 1. 有正文：这一轮的过程卡一行、正文一行，正文**永远在卡外面**；
                // 2. 没有正文：并进上面那张卡（连着几步都属于同一段过程）。
                //
                // 以前短正文会吸进卡里（D264），而生成中那条路径一直把正文画在卡外面：
                // "跑的时候在外面、一落库就跳进大块"（2026-11-06 真机）。
                // 同一个语义只能有一条渲染路径——现在两条都是"卡 → 正文"。
                reasoningItemOf(unit)?.let { pending += it }
                pending += item
                if (unitBodyText(unit).isNotBlank()) {
                    flush()
                    rows += ChatRow.Single(unit, hideReasoning = true, slice = UnitSlice.TextOnly)
                }
                continue
            }
            pending += item
            continue
        }
        // 正文那条身上带着思考（0.15ax 起，带工具调用的那条消息也存思考）。
        // 前面已经有过程项时，这段思考属于**同一轮的过程**：并进那个大块，
        // 正文自己单独一行，思考卡不再在气泡里画第二遍。
        val reasoning = reasoningItemOf(unit)
        if (reasoning != null && pending.isNotEmpty()) {
            pending += reasoning
            flush()
            rows += ChatRow.Single(unit, hideReasoning = true)
            continue
        }
        flush()
        rows += ChatRow.Single(unit)
    }
    flush()
    return rows
}

/** 工具那一轮的正文（一条消息一轮，所以取第一条非思考消息）。 */
internal fun unitBodyText(unit: ChatUnit): String =
    unit.messages.firstOrNull { !it.isThinking }?.content.orEmpty()

/**
 * 正文那条消息的思考，当成一个过程项。
 *
 * `id` 加后缀：它和"思考单独成单元"那种（[processItemOf]）在界面上是两种东西，
 * 键撞在一起会让折叠状态串到另一条消息上。
 */
internal fun reasoningItemOf(unit: ChatUnit): ProcessItem? {
    val only = unit.messages.singleOrNull() ?: return null
    if (only.isThinking) return null
    if (only.reasoning.isNullOrBlank()) return null
    return ProcessItem(
        id = unit.id + "#reasoning",
        kind = ProcessItem.Kind.Thinking,
        unit = unit,
        thinking = only,
    )
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

/** 这一组够不够折叠的门槛（≥ 3 步）。 */
internal fun foldable(items: List<ProcessItem>): Boolean = items.size >= 3

internal fun ChatUnit.alignsRight(): Boolean =
    !isToolPair && messages.isNotEmpty() && messages.all { it.isUser }

/** 这一行整体靠哪边——只用来算"换人"的间距，不参与别的判断。 */
internal fun ChatRow.alignsRight(): Boolean = when (this) {
    is ChatRow.Single -> unit.alignsRight()
    // 过程块永远是助手那边的东西。
    is ChatRow.Process -> false
}

/** 折叠时被收起来的步数（给"展开更多 N 步"那句用）。 */
internal fun hiddenCount(items: List<ProcessItem>, expanded: Boolean): Int =
    (items.size - visibleItems(items, expanded).size).coerceAtLeast(0)

/**
 * 每一行有没有动作行（重试 / 复制 / 分享 / 更多）。
 *
 * 判据（与以前逐行现算的一样）：**这一轮助手的话里，只有在最后一段正文上**——
 * 后面还有别的正文段就不画，一律留给最后那段。分界线是下一条用户消息：
 * 扫描到它就停（它自己不属于"这一轮助手的话"）。
 *
 * 为什么改成从后往前扫一遍：原来在每一行的组合里写
 * `rows.drop(index + 1).takeWhile { … }`——可见的八行每帧各扫半张表，
 * 长会话里是 O(n²) 的分配，而且每来一个字就重来一次（D354）。
 * 这里 O(n)，只在 `rows` 变了的时候算一次。
 */
internal fun showActionsByRow(rows: List<ChatRow>): BooleanArray {
    val flags = BooleanArray(rows.size) { true }
    // 从下往上：`textAhead` = 这一行之后（到下一个用户消息之前）还有没有别的正文段。
    var textAhead = false
    for (index in rows.indices.reversed()) {
        flags[index] = !textAhead
        when (val row = rows[index]) {
            // 用户消息是分界线，但它自己不算"后面的正文段"。
            is ChatRow.Process -> Unit
            is ChatRow.Single -> textAhead = if (row.alignsRight()) false else {
                textAhead || row.slice != UnitSlice.ToolsOnly
            }
        }
    }
    return flags
}

/**
 * 列表项的稳定 key（D354）。
 *
 * 不给 key 的时候行身份是"第几个"，中间多出一行（分组从 1 项涨到 2 项、
 * 或者流式过程块挤进来一条）后面所有行的 `remember` 状态都跟着挪一个位置：
 * Markdown 要重新解析、展开状态要重新挂。key 跟着行自己的 id 走就没这回事。
 */
internal fun rowKey(row: ChatRow): String = when (row) {
    is ChatRow.Process -> "g:${row.key}"
    is ChatRow.Single -> "u:${row.unit.id}:${row.slice}:${row.hideReasoning}"
}

/** 列表项的复用类型：形状一样的行才能互相复用（Compose 的 contentType）。 */
internal fun rowContentType(row: ChatRow): String = when (row) {
    is ChatRow.Process -> "process"
    is ChatRow.Single -> if (row.alignsRight()) "user" else "assistant"
}
