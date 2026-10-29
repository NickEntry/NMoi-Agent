package com.nmoi.agent.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 一条消息的**手动块编辑器**（D177）。
 *
 * 与"让模型改这一条"是两件事：这里改的人是你，点保存就落库；模型改消息要走工具调用，
 * 在这一轮结束时生效。所以这一页不碰网络，也没有"再问一次模型"的按钮。
 *
 * 一条消息在库里是**几个独立的块**，各自在自己的列里：
 *
 * | 块 | 落到哪 | 能不能加 / 删 |
 * |---|---|---|
 * | 正文 | `messages.content` | 恒有，不能删（删了这条消息就没内容了） |
 * | 思考 | `messages.reasoning` | **能加也能删**（空串落库就是删掉这一块） |
 * | 工具参数 #N | `tool_calls[N].arguments` | 只能改参数；不能凭空加、不能删 |
 *
 * 为什么工具块不能加：一条工具调用必须与一条结果按 `call_id` 配对，凭空加一条会让
 * **下一轮请求直接 400**（上游说"tool_calls 没配对"）。名字与 `call_id` 也不许改——
 * 它们决定了这一块怎么解释、跟谁配对（D158）。这一页把这条写在工具块旁边，
 * 而不是给一个点了会报错的按钮。
 */
@Composable
internal fun MessageEditScreen(
    databasePath: String,
    sessionId: String,
    messageId: String,
    onBack: () -> Unit,
    /** 存好了：外层要重读这一条消息。 */
    onSaved: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    // 块的可编辑副本。`thinking == null` 表示"这条消息现在没有思考块"，
    // 按一下「＋ 添加思考块」就变成空串（空串保存 = 删掉）。
    var output by remember(messageId) { mutableStateOf("") }
    var thinking by remember(messageId) { mutableStateOf<String?>(null) }
    var tools by remember(messageId) { mutableStateOf<List<ToolBlock>>(emptyList()) }
    var loaded by remember(messageId) { mutableStateOf(false) }
    var notice by remember(messageId) { mutableStateOf<String?>(null) }
    var noticeIsError by remember(messageId) { mutableStateOf(false) }
    var busy by remember(messageId) { mutableStateOf(false) }

    // 原始值：只把**真的改了**的块写回去（没改的块不产生一条审计记录）。
    var originalOutput by remember(messageId) { mutableStateOf("") }
    var originalThinking by remember(messageId) { mutableStateOf<String?>(null) }
    var originalTools by remember(messageId) { mutableStateOf<List<ToolBlock>>(emptyList()) }

    LaunchedEffect(messageId) {
        val snapshot = withContext(Dispatchers.IO) {
            ChatRepository.load(databasePath, sessionId, limit = 400)
        }
        val message = snapshot.page?.units
            ?.flatMap { unit -> unit.messages }
            ?.firstOrNull { it.id == messageId }
        if (message == null) {
            loaded = true
            notice = "读不到这条消息（${snapshot.note ?: "可能已经被删了"}）"
            noticeIsError = true
            return@LaunchedEffect
        }
        output = message.content
        originalOutput = message.content
        thinking = message.reasoning
        originalThinking = message.reasoning
        val blocks = message.toolArgs.map { call ->
            ToolBlock(callId = call.callId, name = call.name, arguments = call.arguments)
        }
        tools = blocks
        originalTools = blocks
        loaded = true
    }

    fun save() {
        // 工具参数先全量校验：**一条不合法就整页不写**，免得"改了三块、坏了一块"。
        val badJson = tools.firstOrNull { block ->
            val text = block.arguments.trim()
            text.isNotEmpty() && runCatching { JSONObject(text) }.isFailure
        }
        if (badJson != null) {
            notice = "工具参数 #${tools.indexOf(badJson)} 不是合法 JSON，先改好再保存"
            noticeIsError = true
            return
        }
        busy = true
        notice = null
        scope.launch {
            val changed = buildList {
                if (output != originalOutput) add("正文" to ("output" to output))
                if (thinking != originalThinking) {
                    add("思考" to ("thinking" to thinking.orEmpty()))
                }
                tools.forEachIndexed { index, block ->
                    val before = originalTools.getOrNull(index)?.arguments
                    if (before != block.arguments) {
                        add("工具参数 #$index" to ("tool:$index" to block.arguments))
                    }
                }
            }
            if (changed.isEmpty()) {
                busy = false
                notice = "什么都没改"
                noticeIsError = false
                return@launch
            }
            for ((label, spec) in changed) {
                val (block, text) = spec
                val result = withContext(Dispatchers.IO) {
                    ChatRepository.applyBlock(databasePath, messageId, block, text)
                }
                if (result.isFailure) {
                    busy = false
                    notice = "$label 没写成：${result.exceptionOrNull()?.message}"
                    noticeIsError = true
                    return@launch
                }
            }
            busy = false
            notice = "已保存：${changed.joinToString("、") { it.first }}"
            noticeIsError = false
            // 铁律 ①：存完以库里那份为准，不在界面上叠一层"我刚改的"。
            withContext(Dispatchers.IO) {
                ChatRepository.load(databasePath, sessionId, limit = 400)
            }
            onSaved()
            onBack()
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackButton(onBack = onBack)
                Text(
                    text = "编辑消息",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
                EditChip(label = "保存", primary = true, enabled = loaded && !busy) { save() }
            }

            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Text(
                    text = "这条消息的每一块都能单独改；只有真的改过的块会落库。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )

                // —— 正文（恒有）——
                EditCard(title = "正文", note = "Markdown 原文；不能删") {
                    EditArea(
                        value = output,
                        hint = "这条消息的正文",
                        minLines = 6,
                        onChange = { output = it },
                    )
                }

                // —— 思考（可加可删）——
                EditCard(
                    title = "思考",
                    note = if (thinking == null) {
                        "这条消息现在没有思考块"
                    } else {
                        "思考块；清空并保存等于删掉它"
                    },
                ) {
                    val current = thinking
                    if (current == null) {
                        EditChip(label = "＋ 添加思考块") { thinking = "" }
                    } else {
                        EditArea(
                            value = current,
                            hint = "思考内容",
                            minLines = 4,
                            onChange = { thinking = it },
                        )
                        Row(modifier = Modifier.padding(top = 8.dp)) {
                            EditChip(label = "清空这一块") { thinking = "" }
                        }
                    }
                }

                // —— 工具参数（只改不增删）——
                if (tools.isEmpty()) {
                    EditCard(title = "工具参数", note = "这条消息没有工具调用") {
                        Text(
                            text = "工具块不能凭空加：一条调用必须和一条结果按 call_id 配对，" +
                                "加成孤儿会让下一轮请求直接 400。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    tools.forEachIndexed { index, block ->
                        EditCard(title = "工具参数 #$index", note = block.name) {
                            EditArea(
                                value = block.arguments,
                                hint = "{…}",
                                minLines = 4,
                                mono = true,
                                onChange = { text ->
                                    tools = tools.toMutableList().also { list ->
                                        list[index] = block.copy(arguments = text)
                                    }
                                },
                            )
                            Text(
                                text = "只能改参数：名字与 call_id 决定了这一块怎么解释、跟谁配对。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }

                notice?.let { text ->
                    Text(
                        text = text,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (noticeIsError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** 一条工具调用的可编辑副本（`callId` / `name` 只读，只用于显示与定位）。 */
private data class ToolBlock(val callId: String, val name: String, val arguments: String)

@Composable
private fun EditCard(
    title: String,
    note: String,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = note,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box(modifier = Modifier.padding(top = 8.dp)) { content() }
        }
    }
}

@Composable
private fun EditArea(
    value: String,
    hint: String,
    minLines: Int,
    mono: Boolean = false,
    onChange: (String) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
                    fontSize = if (mono) 12.sp else 14.sp,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth().height((minLines * 22).dp),
            )
            if (value.isBlank()) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EditChip(
    label: String,
    primary: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = when {
            !enabled -> MaterialTheme.colorScheme.surfaceContainer
            primary -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.surfaceContainerHighest
        },
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (primary) FontWeight.Medium else FontWeight.Normal,
            color = when {
                !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
                primary -> MaterialTheme.colorScheme.onPrimary
                else -> MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}
