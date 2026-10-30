package com.nmoi.agent.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一条消息的**单框编辑器**（0.15g / D179，替代 0.14x 的卡片页）。
 *
 * 一条消息在库里是几块（正文 / 思考 / 每对工具的参数与结果），这一页把它们放进
 * **一个文本框**，靠 `<nmoi:*>` 标记分块（语法与解析在 [MessageEditDoc]）。
 *
 * 为什么换掉卡片页：用户要的是"直接改数据"的那种手感。几张卡各带一个保存按钮时，
 * "我改了三块、只保存了两块"是必然会出现的；单框只有一次保存，也是一个事务。
 *
 * 三条这一页必须守住的：
 *
 * 1. **只有真的改过的块才写**（[opsBetween] 逐块比），没改的不产生一条审计；
 * 2. **工具块不能增删、名字不能改**——调用与结果按 `call_id` 配对，
 *    凭空加一条会让下一轮请求直接 400；这类"点了会报错"的入口，这里选择**说清楚**；
 * 3. **解析失败整页不写**，并滚到出错那一行（[MessageEditField] 的 `revealLine`）。
 */
@Composable
internal fun MessageEditScreen(
    databasePath: String,
    sessionId: String,
    messageId: String,
    onBack: () -> Unit,
    /** 存好了：外层要重读这一条消息（铁律 ①：以库里那份为准）。 */
    onSaved: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    var loaded by remember(messageId) { mutableStateOf(false) }
    var original by remember(messageId) { mutableStateOf<EditDocument?>(null) }
    var text by remember(messageId) { mutableStateOf("") }
    var notice by remember(messageId) { mutableStateOf<String?>(null) }
    var noticeIsError by remember(messageId) { mutableStateOf(false) }
    var busy by remember(messageId) { mutableStateOf(false) }
    var errorLine by remember(messageId) { mutableStateOf<Int?>(null) }
    /** 拼不出文档（内容里有跟分块标记撞的串）时置真：这时保存必然失败，按钮要灰着。 */
    var blocked by remember(messageId) { mutableStateOf(false) }

    LaunchedEffect(messageId) {
        val snapshot = withContext(Dispatchers.IO) {
            ChatRepository.load(databasePath, sessionId, limit = 400)
        }
        val unit = snapshot.page?.units
            ?.firstOrNull { unit -> unit.messages.any { it.id == messageId } }
        val message = unit?.messages?.firstOrNull { it.id == messageId }
        if (message == null) {
            loaded = true
            blocked = true
            notice = "读不到这条消息（${snapshot.note ?: "可能已经被删了"}）"
            noticeIsError = true
            return@LaunchedEffect
        }
        val document = buildEditDocument(message, unit.messages)
        original = document
        try {
            text = serializeEditDocument(document)
        } catch (error: EditDocException) {
            // 不假装成功：把原因写出来，并把按钮灰掉。放一份"半截文档"进去更糟——
            // 用户会以为这条消息本来就只剩这些。
            blocked = true
            notice = error.message
            noticeIsError = true
        }
        loaded = true
    }

    fun save() {
        val before = original ?: return
        val parsed = try {
            parseEditDocument(text)
        } catch (error: EditDocException) {
            notice = error.message
            noticeIsError = true
            errorLine = error.line.takeIf { it > 0 }
            return
        }
        val ops = try {
            opsBetween(before, parsed)
        } catch (error: EditDocException) {
            notice = error.message
            noticeIsError = true
            errorLine = null
            return
        }
        if (ops.length() == 0) {
            notice = "什么都没改"
            noticeIsError = false
            return
        }

        busy = true
        notice = null
        errorLine = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                ChatRepository.applyUnitEdits(databasePath, messageId, ops)
            }
            busy = false
            result.onSuccess { envelope ->
                val written = envelope.optInt("written", 0)
                val removed = envelope.optInt("removed", 0)
                val blocks = envelope.optJSONArray("blocks")
                val names = (0 until (blocks?.length() ?: 0))
                    .mapNotNull { blocks?.optString(it)?.takeIf { name -> name.isNotEmpty() } }
                notice = buildString {
                    append("已保存：")
                    append(names.joinToString("、").ifEmpty { "$written 块" })
                    if (removed > 0) append("（删掉 $removed 条消息）")
                }
                noticeIsError = false
                onSaved()
                onBack()
            }.onFailure { failure ->
                notice = "没写进去：${failure.message}"
                noticeIsError = true
            }
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
                EditChip(
                    label = if (busy) "保存中…" else "保存",
                    primary = true,
                    enabled = loaded && !busy && !blocked,
                ) { save() }
            }

            Text(
                text = "这一条消息的所有块都在这一个框里。改完点保存；只有真的改过的块会落库。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )

            MessageEditField(
                value = text,
                onValueChange = { text = it },
                revealLine = errorLine,
                enabled = loaded && !busy,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
            )

            notice?.let { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (noticeIsError) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

/**
 * 库里的一条消息 → 文档。
 *
 * 工具结果在**另一条消息**上（`role=tool`），按 `call_id` 配对——不按顺序：
 * 并行调用时结果的先后不保证，按下标猜会把第 0 个调用的结果挂到第 1 个上。
 */
private fun buildEditDocument(message: ChatMessage, siblings: List<ChatMessage>): EditDocument {
    val results = siblings.filter { it.isToolResult }
    val tools = message.toolArgs.mapIndexed { index, call ->
        EditToolBlock(
            index = index,
            name = call.name,
            input = call.arguments,
            // 库里只有一份结果原文，先放在 stdout 这一栏；stderr 留空。
            stdout = results.firstOrNull { it.toolCallId == call.callId }?.content,
            stderr = null,
        )
    }
    return EditDocument(
        output = message.content,
        thinking = message.reasoning,
        tools = tools,
    )
}

/**
 * 两份文档之间**真的变了**的那几块，变成 `ops`。
 *
 * 这里抛 [EditDocException] 的三件事都是"界面上不该允许"的，必须说清而不是静默忽略：
 * 工具块增删（配对会断）、改工具名（决定这一块怎么解释）、把结果安到别的调用上。
 */
private fun opsBetween(before: EditDocument, after: EditDocument): JSONArray {
    val ops = JSONArray()

    if (after.output != before.output) {
        ops.put(
            JSONObject()
                .put("action", "set")
                .put("block", "output")
                .put("content", after.output),
        )
    }

    // 思考块：整段删掉与清空内容都等于"删掉这一块"（D184），不必逼用户留一对空标签。
    if (after.thinking != before.thinking) {
        ops.put(
            JSONObject()
                .put("action", "set")
                .put("block", "thinking")
                .put("content", after.thinking.orEmpty()),
        )
    }

    if (after.tools.size != before.tools.size) {
        throw EditDocException(
            0,
            "工具块不能增删：这条消息有 ${before.tools.size} 个工具调用，框里有 ${after.tools.size} 个。\n" +
                "一条调用必须和它的结果按 call_id 配对，凭空多一条会让下一轮请求直接 400。",
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
            ops.put(
                JSONObject()
                    .put("action", "set")
                    .put("block", "tool")
                    .put("index", index)
                    .put("content", tool.input),
            )
        }
        if (tool.result() != was.result()) {
            ops.put(
                JSONObject()
                    .put("action", "set")
                    .put("block", "tool_result")
                    .put("index", index)
                    .put("content", tool.result()),
            )
        }
    }
    return ops
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
