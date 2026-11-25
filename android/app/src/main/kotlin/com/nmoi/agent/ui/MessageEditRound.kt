package com.nmoi.agent.ui

import org.json.JSONObject

/**
 * 「这一轮是哪几条消息」——「编辑整条消息」的范围（2026-11-26）。
 *
 * 为什么要有这一层：折行（D258）之后一轮回答在库里是**几条消息**（工具那条、正文那条），
 * 而屏幕上看到的是连成一片的一个整体。用户长按那段正文点「编辑整条消息」时，
 * 他指的是屏幕上那一片（红框）——工具卡、思考、正文都在里面。
 *
 * 只改锚点那一条的话，红框里另外几条的正文和工具参数在编辑器里根本不存在，
 * 那就是他报的那个 bug。
 *
 * 边界用"最后一条用户消息"切：用户在界面上看到的那一片正好也是这个范围，
 * 所以点哪一条进来看到的都是同一份。
 *
 * 这个文件不 import 任何 Android / Compose 类：单测要能在本机用 kotlinc + junit 跑。
 */

/** 读出来的一轮：文档 + 每段对应哪条消息（按下标对齐，保存时按它写回）。 */
internal data class LoadedTurn(
    val turn: EditTurn,
    val messageIds: List<String>,
)

internal fun turnAround(units: List<ChatUnit>, messageId: String): LoadedTurn? {
    val flat = units.flatMap { it.messages }
    val at = flat.indexOfFirst { it.id == messageId }
    if (at < 0) return null
    val target = flat[at]
    // 用户消息自成一段：改他那句话，不牵扯它引发的回答。
    if (target.isUser) {
        return LoadedTurn(
            turn = EditTurn(listOf(buildEditDocument(target))),
            messageIds = listOf(target.id),
        )
    }
    // 往前找这一轮的开头：最后一条用户消息之后的第一条。
    var start = at
    while (start > 0 && !flat[start - 1].isUser) start--
    val rounds = flat.subList(start, flat.size)
        .takeWhile { !it.isUser }
        .filter { it.content.isNotBlank() || it.reasoning != null || it.toolArgs.isNotEmpty() }
    if (rounds.isEmpty()) {
        return LoadedTurn(
            turn = EditTurn(listOf(buildEditDocument(target))),
            messageIds = listOf(target.id),
        )
    }
    return LoadedTurn(
        turn = EditTurn(rounds.map { buildEditDocument(it) }),
        messageIds = rounds.map { it.id },
    )
}

/**
 * 库里的一条消息 → 文档。
 *
 * 参数与结果在**同一块**里（D258）：编辑这一块就是编辑这一次调用，
 * 不用再跨消息按 `call_id` 找结果。
 */
internal fun buildEditDocument(message: ChatMessage): EditDocument {
    val tools = message.toolArgs.mapIndexed { index, call ->
        EditToolBlock(
            index = index,
            name = call.name,
            input = call.arguments,
            // 库里只有一份结果原文（D258），没有第二栏。
            stdout = call.output.ifEmpty { null },
        )
    }
    return EditDocument(
        // 纯工具那一轮本来就没有正文：空正文是允许的（只要它有工具块）。
        output = message.content,
        thinkings = listOfNotNull(message.reasoning?.takeIf { it.isNotEmpty() }),
        tools = tools,
    )
}

/**
 * 一块要写回去的动作。**纯数据**，不带 JSON：本机单测跑不了 `org.json`
 * （android.jar 里那是个 `Stub!`），而这一段的判据全在"哪几块真的变了"上，
 * 不该因为一个序列化库就跑不了测试。转 JSON 在 [MessageEditScreen] 那一层做。
 */
internal data class EditOp(
    /** `output` / `thinking` / `tool` / `tool_result`。 */
    val block: String,
    /** `block=tool` / `tool_result` 时是第几个调用；其余给 0。 */
    val index: Int,
    val content: String,
)

/**
 * 两份文档之间**真的变了**的那几块。
 *
 * 这里抛 [EditDocException] 的三件事都是"界面上不该允许"的，必须说清而不是静默忽略：
 * 工具块增删（配对会断）、改工具名（决定这一块怎么解释）、把正文删空。
 *
 * 只有真的变了的块才产生一条：没改的不该留下一条审计，也不该让"改了 3 处"
 * 跟实际写进去的对不上。
 */
internal fun opsBetween(before: EditDocument, after: EditDocument): List<EditOp> {
    val ops = mutableListOf<EditOp>()

    // 思考块：库里只有一个字符串，多块按顺序接起来（[EditDocument.thinking]）。
    // 整段删掉与清空内容都等于"删掉这一块"（D184），不必逼用户留一对空标签。
    if (after.thinking != before.thinking) {
        ops += EditOp(block = "thinking", index = 0, content = after.thinking.orEmpty())
    }

    // 正文：空串在 core 那边等于"把这条消息变成空壳"，会被拒。
    // 纯工具那一轮本来就没有正文，所以"空 → 空"不产生动作；"有 → 空"要说清楚。
    if (after.output != before.output) {
        if (after.output.isBlank() && before.output.isNotBlank()) {
            throw EditDocException(
                0,
                "正文不能删空（删掉正文等于把这条消息变成空壳）：要换内容就直接改写这一段。",
            )
        }
        if (after.output.isNotEmpty()) {
            ops += EditOp(block = "output", index = 0, content = after.output)
        }
    }

    if (after.tools.size != before.tools.size) {
        throw EditDocException(
            0,
            "工具块不能增删：这一条有 ${before.tools.size} 个工具调用，框里有 ${after.tools.size} 个。\n" +
                "一条调用必须和它的结果配对，凭空多一条会让下一轮请求直接 400。",
        )
    }

    after.tools.forEachIndexed { index, tool ->
        val was = before.tools[index]
        if (tool.name != was.name) {
            throw EditDocException(
                0,
                "工具名不能改（第 ${index + 1} 个：${was.name} → ${tool.name}）：名字决定这一块怎么解释。",
            )
        }
        if (tool.input != was.input) {
            ops += EditOp(block = "tool", index = index, content = tool.input)
        }
        if (tool.result() != was.result()) {
            ops += EditOp(block = "tool_result", index = index, content = tool.result())
        }
    }
    return ops
}

/**
 * 一条动作 → 界面上那封信封里的一项（`{"action":"set","block":…}`）。
 *
 * 放在这里（而不是 `MessageEditDoc`）是因为它要 `org.json`：那份文件是纯逻辑，
 * 本机单测要能编过。这一份只被界面调用，不参与本机单测。
 */
internal fun EditOp.toJson(): JSONObject = JSONObject()
    .put("action", "set")
    .put("block", block)
    .apply { if (block == "tool" || block == "tool_result") put("index", index) }
    .put("content", content)
