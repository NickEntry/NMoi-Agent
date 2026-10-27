package com.nmoi.agent.ui

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 增强编辑（D163）：让模型按你的要求改写**一条消息**，正文 / 思考 / 工具参数一起改。
 *
 * 三件事分开落地，各有各的落点：
 *
 * | 块 | 落到哪 | 为什么 |
 * |---|---|---|
 * | 正文 `output` | 版本那条路（`tooledit_message`，红/蓝） | 用户已经会用红蓝切了，改到别处等于再造一套 |
 * | 思考 `thinking` | `messages.reasoning`（块级编辑） | 它本来就在这一列 |
 * | 工具参数 `tool:N` | `messages.tool_calls[N].arguments` | **只能改参数**：名字与 call_id 决定这一块怎么解释、跟谁配对 |
 *
 * 模型只回一份带围栏的东西（见 [SYSTEM_PROMPT]）。**围栏外的字一律不写进库里**——
 * 模型爱加"好的，我改好了"这种前言，把它塞进正文就是脏数据。
 */
internal object EditRepository {
    /** 单块上限，跟 FFI 那边的 200 KB 对齐。 */
    private const val MAX_BLOCK = 200_000

    private const val SYSTEM_PROMPT = """你是一个只做一件事的文本编辑器。用户给你一条消息的当前内容（正文、思考、某些工具调用的参数），以及一句修改要求。

你只输出修改后的块，不解释、不寒暄、不评论。格式如下（每个块一个围栏，行首三个反引号+标签）：

```output
（新的正文）
```

```thinking
（新的思考）
```

```tool:0
（第 0 个工具调用的新参数；原来是 JSON 就仍然写 JSON）
```

规则：
- 只输出**需要改**的块；不改的块整块不要出现。出现空块视为不改。
- 正文是主体：改了正文就输出 output。
- tool:N 里的 N 是工具调用序号（从 0 起，见用户给的清单）。你只能改参数，不能改工具名。
- 围栏外不要写任何字。"""

    data class Plan(
        val output: String?,
        val thinking: String?,
        val tools: Map<Int, String>,
        val notes: List<String>,
    )

    /** 改完之后给用户看的一句话 + 改了哪几块。 */
    data class Report(val changed: List<String>, val notes: List<String>) {
        fun describe(): String = when {
            changed.isEmpty() -> "模型没给出可用的改动" + notes.takeIf { it.isNotEmpty() }
                ?.joinToString("；", prefix="（", postfix="）").orEmpty()
            else -> "已改：" + changed.joinToString("、") +
                if (notes.isEmpty()) "" else "（${notes.joinToString("；")}）"
        }
    }

    /**
     * 跑一次增强编辑。
     *
     * 顺序是**先解析再落库**：解析不出东西就什么都不写，不会留下"改了一半"的消息。
     * 正文走版本、思考与参数走块级编辑，两段各自失败都只影响自己那一块（并写进 notes）。
     */
    suspend fun enhance(
        context: Context,
        databasePath: String,
        message: ChatMessage,
        instruction: String,
    ): Result<Report> = withContext(Dispatchers.IO) {
        val instruction = instruction.trim()
        if (instruction.isEmpty()) {
            return@withContext Result.failure(NmoiFfiException("bad_argument", "先写一句要改什么"))
        }

        // 现读：改的是屏幕上此刻显示的那一份（铁律 ①）。版号也用它，不用面板点开时的副本。
        val fresh = ChatRepository.versions(databasePath, message.id)
            .getOrElse { return@withContext Result.failure(it) }
        val shown = fresh.shown.ifEmpty { message.content }

        val assistant = AssistantRepository.current(databasePath)
            .getOrElse { return@withContext Result.failure(it) }
        if (!assistant.isRunnable) {
            return@withContext Result.failure(
                NmoiFfiException("no_model", "还没选模型：聊天页顶部的模型条里挑一个"),
            )
        }
        val provider = ProviderRepository.load(databasePath, assistant.providerId)
            .getOrElse { return@withContext Result.failure(it) }
        val key = Vault.load(context, provider.id)

        val config = JSONObject()
            .put(
                "provider",
                JSONObject()
                    .put("id", provider.id)
                    .put("kind", provider.kind)
                    .put("base_url", provider.baseUrl.trim())
                    .also { row -> if (key != null) row.put("api_key", key) },
            )
            .put("model", assistant.modelId)
            .put("system", SYSTEM_PROMPT)
            .put("prompt", buildPrompt(instruction, shown, message.reasoning, message.toolArgs))

        val answer = NmoiFfi.complete(config)
            .getOrElse { return@withContext Result.failure(it) }
            .optString("text", "")
        val plan = parseFences(answer)
        if (plan.output == null && plan.thinking == null && plan.tools.isEmpty()) {
            return@withContext Result.success(Report(emptyList(), plan.notes))
        }

        val changed = mutableListOf<String>()
        val notes = plan.notes.toMutableList()

        // 正文走版本那条路：这样红/蓝、`n/n` 都是现成的。
        plan.output?.let { text ->
            if (text != shown) {
                ChatRepository.toolEditVersion(
                    databasePath,
                    message.id,
                    fresh.active.coerceAtLeast(1).toLong(),
                    text,
                ).onSuccess { changed += "正文" }
                    .onFailure { notes += "正文没写成：${it.message}" }
            }
        }
        // 思考与工具参数走块级编辑：它们各自在自己的列里。
        plan.thinking?.let { text ->
            if (text != message.reasoning.orEmpty()) {
                withStorageHandle(databasePath) { handle ->
                    NmoiFfi.applyBlockEdit(handle, message.id, "thinking", text)
                }.onSuccess { changed += "思考" }
                    .onFailure { notes += "思考没写成：${it.message}" }
            }
        }
        plan.tools.forEach { (index, arguments) ->
            val current = message.toolArgs.getOrNull(index)
            if (current == null) {
                notes += "工具块 #$index 不存在，跳过"
                return@forEach
            }
            if (arguments != current.arguments) {
                withStorageHandle(databasePath) { handle ->
                    NmoiFfi.applyBlockEdit(handle, message.id, "tool:$index", arguments)
                }.onSuccess { changed += "工具参数 #$index" }
                    .onFailure { notes += "工具参数 #$index 没写成：${it.message}" }
            }
        }

        NLog.i("Edit", "增强编辑：改了 ${changed.size} 块（${changed.joinToString("、")}）")
        Result.success(Report(changed, notes))
    }

    /** 把模型回的那段文字解析成 [Plan]。 */
    internal fun parseFences(raw: String): Plan {
        val lines = raw.lines()
        var output: String? = null
        var thinking: String? = null
        val tools = mutableMapOf<Int, String>()
        val notes = mutableListOf<String>()
        var sawFence = false
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            val trimmed = line.trim()
            if (!trimmed.startsWith("```")) {
                index += 1
                continue
            }
            val lang = trimmed.removePrefix("```").trim().lowercase()
            val body = StringBuilder()
            var cursor = index + 1
            var closed = false
            while (cursor < lines.size) {
                if (lines[cursor].trim() == "```") {
                    closed = true
                    break
                }
                body.append(lines[cursor])
                if (cursor != lines.size - 1) body.append('\n')
                cursor += 1
            }
            if (!closed) {
                notes += "有一个围栏没闭合，后面没解析"
                break
            }
            val content = body.toString()
            if (content.length > MAX_BLOCK) {
                notes += "有一块超过 200 KB，跳过"
                index = cursor + 1
                continue
            }
            when {
                lang == "output" && content.isNotBlank() -> {
                    output = content.trimEnd()
                    sawFence = true
                }
                lang == "thinking" && content.isNotBlank() -> {
                    thinking = content
                    sawFence = true
                }
                lang.startsWith("tool:") -> {
                    val number = lang.removePrefix("tool:").toIntOrNull()
                    if (number == null || number < 0 || number > 63) {
                        notes += "工具块编号看不懂：$lang"
                    } else if (content.isNotBlank()) {
                        tools[number] = content.trim()
                    }
                    sawFence = true
                }
                lang.isEmpty() || lang == "text" || lang == "markdown" -> {
                    notes += "忽略了一个没有标签的围栏"
                }
                else -> notes += "忽略了不认识的围栏「$lang」"
            }
            index = cursor + 1
        }
        // 一个围栏都没有 = 模型没按格式来：整段当正文（总比什么都不改强）。
        if (!sawFence) {
            val all = raw.trim()
            return Plan(
                output = all.takeIf { it.isNotEmpty() && it.length <= MAX_BLOCK },
                thinking = null,
                tools = emptyMap(),
                notes = if (all.isEmpty()) listOf("模型回了空") else emptyList(),
            )
        }
        return Plan(output = output, thinking = thinking, tools = tools, notes = notes)
    }

    /** 拼给模型的那段话：现状 + 要求。 */
    internal fun buildPrompt(
        instruction: String,
        shown: String,
        reasoning: String?,
        tools: List<ToolCallArg>,
    ): String = buildString {
        append("修改要求：").append(instruction).append("\n\n")
        append("当前正文：\n").append(shown.ifBlank { "（空）" }).append("\n\n")
        append("当前思考：\n").append(reasoning?.ifBlank { "（空）" } ?: "（没有思考块）").append("\n\n")
        if (tools.isEmpty()) {
            append("工具调用：这条消息没有工具调用。\n")
        } else {
            append("工具调用：\n")
            tools.forEachIndexed { index, call ->
                append("[$index] ").append(call.name).append(" 参数=").append(call.arguments).append('\n')
            }
        }
    }
}
