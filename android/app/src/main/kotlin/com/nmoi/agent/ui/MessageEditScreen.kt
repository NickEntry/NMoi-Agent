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
 * **一整轮**的编辑器（0.15g / D179；2026-11-26 扩到整轮）。
 *
 * 折行（D258）之后一轮回答是**几条消息**（每轮一条：工具那轮、正文那轮…），
 * 而屏幕上看到的是连成一片的一个整体。用户长按那段正文点「编辑整条消息」时，
 * 他指的是**屏幕上那一片**——工具卡、思考、正文都在里面。
 *
 * 所以这一页读的是「这一轮」而不是「那一条」：从目标消息往前找到这一轮的第一条
 * （最后一条用户消息之后的全部），整段放进**一个文本框**，靠 `<nmoi:*>` 标记分块
 * （语法与解析在 [MessageEditDoc]，v3 的 `<nmoi:message>` 是那几条消息的边界）。
 *
 * 为什么换掉卡片页：用户要的是"直接改数据"的那种手感。几张卡各带一个保存按钮时，
 * "我改了三块、只保存了两块"是必然会出现的；单框只有一次保存，也是一个事务。
 *
 * 四条这一页必须守住的：
 *
 * 1. **只有真的改过的块才写**（[opsBetween] 逐块比），没改的不产生一条审计；
 * 2. **工具块不能增删、名字不能改**——调用与结果按 `call_id` 配对，
 *    凭空加一条会让下一轮请求直接 400；这类"点了会报错"的入口，这里选择**说清楚**；
 * 3. **段数不能变**：每一段对应库里一条消息，多一段少一段就不知道该写回哪一条；
 * 4. **解析失败整页不写**，并滚到出错那一行（[MessageEditField] 的 `revealLine`）。
 */
@Composable
internal fun MessageEditScreen(
    databasePath: String,
    sessionId: String,
    messageId: String,
    onBack: () -> Unit,
    /** 存好了：外层要重读这一轮（铁律 ①：以库里那份为准）。 */
    onSaved: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    var loaded by remember(messageId) { mutableStateOf(false) }
    var original by remember(messageId) { mutableStateOf<EditTurn?>(null) }
    var text by remember(messageId) { mutableStateOf("") }
    var notice by remember(messageId) { mutableStateOf<String?>(null) }
    var noticeIsError by remember(messageId) { mutableStateOf(false) }
    var busy by remember(messageId) { mutableStateOf(false) }
    var errorLine by remember(messageId) { mutableStateOf<Int?>(null) }
    /** 拼不出文档（内容里有跟分块标记撞的串）时置真：这时保存必然失败，按钮要灰着。 */
    var blocked by remember(messageId) { mutableStateOf(false) }

    /**
     * 框里到底有哪几块。写成一行"整轮 2 条消息：正文 · 思考 · 运行命令 #0 · 正文"，
     * 用户不用先往下翻才知道工具块也在里面（他 2026-11-26 的判据是"红框那一片"）。
     */
    val scopeLine = original?.let { turn ->
        val blocks = turn.rounds.flatMap { round ->
            buildList {
                add("正文")
                if (round.doc.thinking != null) add("思考")
                round.doc.tools.forEachIndexed { index, tool ->
                    add("${tool.name.ifEmpty { "工具" }} #$index")
                }
            }
        }
        "整轮 ${turn.rounds.size} 条消息：${blocks.joinToString(" · ")}"
    }

    LaunchedEffect(messageId) {
        val snapshot = withContext(Dispatchers.IO) {
            ChatRepository.load(databasePath, sessionId, limit = 400)
        }
        val turn = turnAround(snapshot.page?.units.orEmpty(), messageId)
        if (turn == null) {
            loaded = true
            blocked = true
            notice = "读不到这条消息（${snapshot.note ?: "可能已经被删了"}）"
            noticeIsError = true
            return@LaunchedEffect
        }
        original = turn
        try {
            text = serializeEditTurn(turn)
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
            parseEditTurn(text)
        } catch (error: EditDocException) {
            notice = error.message
            noticeIsError = true
            errorLine = error.line.takeIf { it > 0 }
            return
        }
        // 段与库里那几条一一对应：段数变了就说不清"这一段的正文该写回哪一条"。
        val beforeIds = before.rounds.map { it.messageId }
        val afterIds = parsed.rounds.map { it.messageId }
        if (beforeIds != afterIds) {
            notice = "这一轮有 ${beforeIds.size} 条消息，框里是 ${afterIds.size} 段：" +
                "段不能增删（每段对应库里一条消息）。\n" +
                "只改内容就好，别删掉整段或复制一段。"
            noticeIsError = true
            errorLine = null
            return
        }

        val edits = JSONArray()
        val failed = try {
            before.rounds.zip(parsed.rounds).forEach { (was, now) ->
                val ops = opsBetween(was.doc, now.doc)
                if (ops.isNotEmpty()) {
                    val array = JSONArray()
                    ops.forEach { array.put(it.toJson()) }
                    edits.put(JSONObject().put("message_id", was.messageId).put("ops", array))
                }
            }
            null
        } catch (error: EditDocException) {
            error
        }
        if (failed != null) {
            notice = failed.message
            noticeIsError = true
            errorLine = null
            return
        }
        if (edits.length() == 0) {
            notice = "什么都没改"
            noticeIsError = false
            return
        }

        busy = true
        notice = null
        errorLine = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                ChatRepository.applyTurnEdits(databasePath, edits)
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
                    text = "编辑整条消息",
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
                text = "这一轮的全部内容都在这一个框里（工具卡、思考、正文）。" +
                    "改完点保存；只有真的改过的块会落库。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )

            scopeLine?.let { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 6.dp),
                )
            }

            MessageEditField(
                value = text,
                onValueChange = { text = it },
                revealLine = errorLine,
                enabled = loaded && !busy,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp),
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
