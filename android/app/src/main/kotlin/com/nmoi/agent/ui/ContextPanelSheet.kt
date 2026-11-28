package com.nmoi.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONObject

/**
 * 上下文管理（D443）。用户 2026-11-26 给的那张图。
 *
 * # 它跟"压缩"是两件事
 *
 * 这个面板回答"窗口里现在都是什么"，压缩只是其中一项动作。所以「＋」里那一行
 * 从「压缩上下文」改成「上下文管理」，点开是这一页。
 *
 * # 不放「设置上下文窗口」
 *
 * 窗口在模型配置里改（`ModelFields` 已经有那一格）。同一件事两个入口，
 * 迟早给出两个答案。
 *
 * # 数字从哪来
 *
 * 八行由 core 算（`nmoi_context_breakdown_json`）：历史 / 工具来自库里，
 * 系统提示词 / 技能 / 世界书 / 工作区 / 草稿由界面把**原文**递进去，
 * 工具声明按与真实请求同一份算。**一处只算一次**——各算一遍就是两种口径。
 *
 * 每一行都标（预估）：这些数来自本地估算，不是端点的账单。端点回了 usage 的
 * 那条消息才标（实际），那在消息尾部，不在这里。
 */
@Composable
internal fun ContextPanelSheet(
    databasePath: String,
    sessionId: String,
    provider: ProviderEntry?,
    modelId: String,
    /** 这一轮会发出去的图（其他模态）：只数张数，字节不进这一页。 */
    imageCount: Int,
    imageLimit: Int,
    onCompress: () -> Unit,
    onOpenImages: () -> Unit,
    onClearContext: () -> Unit,
    onRestore: () -> Unit,
    /** 压过没有：有的话「清空上下文」那一行旁边要能还原。 */
    compacted: Boolean,
    covered: Int,
    onDismiss: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    var rows by remember { mutableStateOf<List<ContextRow>>(emptyList()) }
    var total by remember { mutableStateOf(0L) }
    var level by remember { mutableStateOf("l0") }
    var loading by remember { mutableStateOf(true) }
    var failure by remember { mutableStateOf<String?>(null) }
    var autoCompress by remember { mutableStateOf(AppSettings.autoCompress(context)) }

    LaunchedEffect(sessionId) {
        loading = true
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val extra = buildExtra(context)
                withStorageHandle(databasePath) { handle ->
                    NmoiFfi.contextBreakdown(handle, sessionId, extra)
                }.getOrThrow()
            }
        }
        result.fold(
            onSuccess = { envelope ->
                rows = parseRows(envelope)
                total = envelope.optLong("total", 0L)
                level = envelope.optString("level", "l0")
                failure = null
            },
            onFailure = { failure = "算不出来：${it.message}" },
        )
        loading = false
        PageReady.notifyReadyAfterFrame()
    }

    SheetFrame(title = "上下文管理", onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
        ) {
            // ---- 顶部总数 + 分段条 ----
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = if (loading) "—" else formatTokens(total),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "（预估）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = if (level == "l1") {
                    "本地词表 + 校准；不是端点的账单"
                } else {
                    "字符近似（还没跑过一轮，或没编本地词表）"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            Spacer(Modifier.height(10.dp))
            SegmentBar(rows = rows, total = total)
            Spacer(Modifier.height(12.dp))

            // ---- 八行图例 ----
            if (failure != null) {
                Text(
                    text = failure.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            for (row in rows) {
                if (row.tokens <= 0L) continue
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(9.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(row.color),
                    )
                    Text(
                        text = row.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f).padding(start = 10.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = formatTokens(row.tokens),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            RowDivider(leading = 0.dp)

            // ---- 动作 ----
            PanelAction(
                icon = ActionIcons.RotateCcw,
                label = "上下文压缩",
                detail = "把上面一段换成摘要，原文一条不删",
                onClick = {
                    onDismiss()
                    onCompress()
                },
            )
            if (compacted) {
                PanelAction(
                    icon = ActionIcons.ChevronUp,
                    label = "还原压缩",
                    detail = "删掉那条线，上面 $covered 条重新进请求",
                    onClick = {
                        onDismiss()
                        onRestore()
                    },
                )
            }
            PanelAction(
                icon = ActionIcons.Image,
                label = "图片上下文",
                detail = "这一轮发 $imageCount / $imageLimit 张",
                onClick = {
                    onDismiss()
                    onOpenImages()
                },
            )
            PanelAction(
                icon = ActionIcons.Close,
                label = "清空上下文",
                detail = "把这一轮之前的对话全部移出请求（消息还在库里，能还原）",
                danger = true,
                enabled = provider != null && modelId.isNotBlank(),
                onClick = onClearContext,
            )
            if (provider == null || modelId.isBlank()) {
                Text(
                    text = "清空上下文要一个能写摘要的模型：先在「模型 → 压缩」里指定一个，或者选一个当前模型。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }

            Spacer(Modifier.height(8.dp))
            RowDivider(leading = 0.dp)

            // ---- 自动压缩：全局开关 ----
            //
            // 用户 2026-11-26：「自动压缩后面也分离出来开关，压缩是压缩，落盘是落盘，
            // 上下文那个档位 F1~F4 管的是"落盘/编辑"这些，这里是自动开关」。
            // 所以它单独一格，与上面那几个动作不在一组——那两个是"现在做什么"，
            // 这一个"以后要不要自己动手"。
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "自动压缩",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "到了绝对线自己压一次。与设置 → 上下文里那个是同一个开关",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ChipButton(
                    label = if (autoCompress) "开" else "关",
                    primary = autoCompress,
                ) {
                    autoCompress = !autoCompress
                    AppSettings.setAutoCompress(context, autoCompress)
                }
            }
            Text(
                text = "压缩是压缩，落盘是落盘：上面那个档位管的是「原文留多少」，不是这一格。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
            )
        }
    }
}

/** 面板上一行：[tokens] 为 0 的行不画（一段 0 宽的颜色块看着像缝隙）。 */
internal data class ContextRow(val key: String, val label: String, val tokens: Long, val color: Color)

/**
 * 八行的颜色。与根页那套**不共用**：这里按"上下文里的位置"分，那边按"占了多少空间"分，
 * 两套语义混用会让同一个颜色在两个页面指两件事。
 */
private fun colorOf(key: String): Color = when (key) {
    "history" -> Color(0xFF4C8DFF)
    "builtin_tools" -> Color(0xFFE8A33D)
    "mcp_tools" -> Color(0xFFE8643D)
    "skills" -> Color(0xFFE8C33D)
    "system_prompt" -> Color(0xFF8C8C8C)
    "world_book" -> Color(0xFF3DC46A)
    "workspace" -> Color(0xFF5B6B7C)
    "draft" -> Color(0xFFB06BE8)
    else -> Color(0xFF9AA0A6)
}

private fun parseRows(envelope: JSONObject): List<ContextRow> {
    val array = envelope.optJSONArray("rows") ?: return emptyList()
    return buildList {
        for (index in 0 until array.length()) {
            val row = array.optJSONObject(index) ?: continue
            val key = row.optString("key")
            add(
                ContextRow(
                    key = key,
                    label = row.optString("label").ifBlank { key },
                    tokens = row.optLong("tokens", 0L),
                    color = colorOf(key),
                ),
            )
        }
    }
}

/**
 * 库里看不见的那几行的**原文**。
 *
 * 递原文而不是数：core 那边用同一个估算器算，面板上八行才是同一口径。
 * 界面自己估一遍的话，"系统提示词 3.9k"与"历史 382.1k"会来自两套算法。
 */
private fun buildExtra(context: android.content.Context): JSONObject {
    val doc = JSONObject()
    val system = PromptVars.compose(
        null,
        AppSettings.globalSystem(context),
        // 助手那一层这里拿不到（面板只关心"这一轮送了什么"），全局那段是最稳的那一半。
        null,
    )
    system?.let { doc.put("system_prompt_text", it) }
    return doc
}

/** 分段条：按 token 数分权重，零的行不画。 */
@Composable
private fun SegmentBar(rows: List<ContextRow>, total: Long) {
    val empty = MaterialTheme.colorScheme.surfaceContainerHighest
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(empty),
    ) {
        if (total <= 0L) {
            Spacer(Modifier.fillMaxWidth().fillMaxHeight())
            return@Row
        }
        for (row in rows) {
            if (row.tokens <= 0L) continue
            Box(
                modifier = Modifier
                    .weight(row.tokens.toFloat().coerceAtLeast(0.0001f))
                    .fillMaxHeight()
                    .background(row.color),
            )
        }
    }
}

/** 面板里的一行动作：图标 + 标题 + 一句说明。 */
@Composable
private fun PanelAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    detail: String,
    danger: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = when {
                !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                danger -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.size(18.dp),
        )
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = when {
                    !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                    danger -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
    }
}

/**
 * token 数写成 `435.6k` 这种。
 *
 * 与 `formatBytes` 分开：那个是 KB/MB，这里是 k/M，混用会让人以为在看磁盘。
 * 小于 1000 原样给数字——`0.3k` 不如 `325` 好用。
 */
internal fun formatTokens(tokens: Long): String = when {
    tokens < 1_000 -> tokens.toString()
    tokens < 1_000_000 -> "%.1fk".format(tokens / 1_000.0)
    else -> "%.2fM".format(tokens / 1_000_000.0)
}
