package com.nmoi.agent.ui

/**
 * 骨架阶段的占位数据。**不是真实会话**，没有任何一条来自数据库。
 *
 * 接上 Rust 核心之后，这里应该整个被替换掉：列表数据来自
 * `nmoi_storage_list_conversations_json`（契约见 docs/PLAN.md §3.11），
 * 字段顺序与这里列的这几样一一对应（含 D101 的 `model`，NULL 表示这一轮还没跑过）。
 */
internal data class ConversationRow(
    /** 库里的会话 id。占位数据给空串：空串 = "这一行不是真的"，聊天页据此不假装能读。 */
    val id: String,
    val title: String,
    val preview: String,
    val model: String?,
    val updatedAt: String,
    val incomplete: Boolean,
)

internal object PlaceholderData {
    val conversations: List<ConversationRow> = listOf(
        ConversationRow(
            id = "",
            title = "这一行是写死的",
            preview = "列表的形状先定下来：标题、最后一条预览、模型、时间、断流标记。",
            model = "骨架 · 无",
            updatedAt = "—",
            incomplete = false,
        ),
        ConversationRow(
            id = "",
            title = "排序按 updated_at DESC, id DESC",
            preview = "D61 定的顺序，列表页照这个来，分页用 has_more 而不是总数。",
            model = "骨架 · 无",
            updatedAt = "—",
            incomplete = false,
        ),
        ConversationRow(
            id = "",
            title = "断流的那一轮会带 incomplete",
            preview = "这一行模拟「写了一半就断」的状态，将来由 messages.incomplete 决定。",
            model = "骨架 · 无",
            updatedAt = "—",
            incomplete = true,
        ),
    )
}
