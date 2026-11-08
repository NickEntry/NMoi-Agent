package com.nmoi.agent.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 一条会话的"详细信息"（2026-11-08）。
 *
 * 存在的理由不是好看：**跨会话访问要一个能复制出去的 ID**。上下文里不会出现会话 id，
 * 用户得自己把 id 交给模型，所以这里每一项都能长按复制。
 *
 * 数字都是**当前版本**的（不是所有版本加起来）：切过版本之后，这里看到的就是屏幕上那一份。
 */
internal data class SessionStats(
    /** 库里这一会话的单元数 = 总请求数（一次回答 = 一个单元，工具往返仍在同一单元里）。 */
    val units: Int,
    val messages: Int,
    val user: Int,
    val assistant: Int,
) {
    companion object {
        /** 读不出来（会话被删了、库打不开）返回 `null`，界面写"读不出来"，不给 0 冒充。 */
        suspend fun of(databasePath: String, sessionId: String): SessionStats? {
            val snapshot = ChatRepository.load(databasePath, sessionId, limit = 10_000)
            val page = snapshot.page ?: return null
            var messages = 0
            var user = 0
            var assistant = 0
            page.units.forEach { unit ->
                unit.messages.forEach { message ->
                    messages += 1
                    if (message.isUser) user += 1 else assistant += 1
                }
            }
            return SessionStats(
                units = page.total,
                messages = messages,
                user = user,
                assistant = assistant,
            )
        }
    }
}

/** 会话信息面板：一项一行，**长按复制那一项的值**。 */
@Composable
internal fun SessionInfoSheet(
    sessionId: String,
    title: String,
    databasePath: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var stats by remember(sessionId) { mutableStateOf<SessionStats?>(null) }
    var loaded by remember(sessionId) { mutableStateOf(false) }
    var copied by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(sessionId) {
        stats = SessionStats.of(databasePath, sessionId)
        loaded = true
    }

    fun copy(label: String, value: String) {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        manager?.setPrimaryClip(ClipData.newPlainText(label, value))
        copied = "已复制：$label"
    }

    SheetFrame(title = "会话信息", onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 460.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            InfoRow("会话 ID", sessionId) { copy("会话 ID", sessionId) }
            InfoRow("会话名称", title.ifBlank { "（没有标题）" }) {
                copy("会话名称", title)
            }
            InfoRow("当前版本消息总数", stats?.messages?.toString() ?: "…") {
                stats?.let { copy("消息总数", it.messages.toString()) }
            }
            InfoRow("当前版本总请求数", stats?.units?.toString() ?: "…") {
                stats?.let { copy("总请求数", it.units.toString()) }
            }
            InfoRow("当前版本用户消息数", stats?.user?.toString() ?: "…") {
                stats?.let { copy("用户消息数", it.user.toString()) }
            }
            InfoRow("当前版本助手消息数", stats?.assistant?.toString() ?: "…") {
                stats?.let { copy("助手消息数", it.assistant.toString()) }
            }
            if (loaded && stats == null) {
                Text(
                    text = "这个会话读不出来（可能已经被删了）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            copied?.let { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Spacer(Modifier.heightIn(min = 16.dp))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InfoRow(label: String, value: String, onCopy: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = {}, onLongClick = onCopy)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(132.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}
