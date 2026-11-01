package com.nmoi.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 发送队列那一层小窗（用户 2026-10-31 指定：入口在**发送键的长按菜单**里）。
 *
 * 它回答四件事：
 *
 * 1. 还有几条、各是什么（两行截断，点开不展开——队列里不需要读长文）；
 * 2. **顺序**：↑ ↓ 手动调（用户的顺序就是真实的顺序，不替他排）；
 * 3. **删掉哪条**：× 只删这一条；
 * 4. **继续 / 手动发**：上一轮非正常结束时队列是停着的，这里给一颗「发送下一条」。
 *
 * 为什么"手动发"要求会话没在请求：一轮还没收尾时再发，不管在哪个队列里都是
 * 给同一段上下文插两条并行的回复，落库会打架。所以按钮在跑的时候是灰的，
 * 而且**写着为什么灰**。
 */
@Composable
internal fun SendQueueSheet(
    sessionId: String,
    running: Boolean,
    onSendNow: (String) -> Unit,
    onClose: () -> Unit,
) {
    // 队列的任何变更都会让 version +1：界面据此重读（队列是进程级单例，不是 Compose state）。
    val tick by SendQueue.version.collectAsState()
    val items = remember(tick, sessionId) { SendQueue.items(sessionId) }
    val blocked = remember(tick, sessionId) { SendQueue.isBlocked(sessionId) }

    Box(modifier = Modifier.fillMaxSize()) {
        // 遮罩：点它关掉这一层（和别处的面板一致，0.32 的黑）。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.32f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClose,
                ),
        )
        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.Bottom) {
            Surface(
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(top = 10.dp, bottom = 12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (items.isEmpty()) "发送队列" else "发送队列 · ${items.size} 条",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        IconActionButton(
                            image = ActionIcons.Close,
                            description = "关闭发送队列",
                            onClick = onClose,
                        )
                    }

                    if (running) {
                        QueueHint("这个会话正在跑：新发的会排到队尾；要发下一条得等这一轮收尾。")
                    } else if (blocked) {
                        QueueHint("上一轮不是正常结束，队列停在这里。确认没问题就点「发送下一条」继续。")
                    }

                    if (items.isEmpty()) {
                        Text(
                            text = "队列是空的。会话正在跑的时候，直接按发送就会排到这里。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .heightIn(max = 340.dp)
                                .verticalScroll(rememberScrollState()),
                        ) {
                            items.forEachIndexed { index, item ->
                                if (index > 0) RowDivider(leading = 20.dp)
                                QueueRow(
                                    index = index,
                                    item = item,
                                    canUp = index > 0,
                                    canDown = index < items.size - 1,
                                    onUp = { SendQueue.move(sessionId, item.id, -1) },
                                    onDown = { SendQueue.move(sessionId, item.id, 1) },
                                    onRemove = { SendQueue.remove(sessionId, item.id) },
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (items.isNotEmpty()) {
                            TextAction(
                                label = "清空",
                                danger = true,
                                onClick = { SendQueue.clear(sessionId) },
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        WideButton(
                            label = when {
                                running -> "正在跑，等它收尾"
                                items.isEmpty() -> "没有排队的消息"
                                else -> "发送下一条"
                            },
                            enabled = !running && items.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth(0.62f),
                            onClick = {
                                val head = SendQueue.peek(sessionId)
                                if (head != null) {
                                    SendQueue.takeNext(sessionId)
                                    SendQueue.block(sessionId, false)
                                    onSendNow(head.text)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueRow(
    index: Int,
    item: QueuedSend,
    canUp: Boolean,
    canDown: Boolean,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${index + 1}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 10.dp),
        )
        Text(
            text = item.text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconActionButton(
            image = ActionIcons.ChevronUp,
            description = "往上一条",
            compact = true,
            enabled = canUp,
            onClick = onUp,
        )
        IconActionButton(
            image = ActionIcons.ChevronDown,
            description = "往下一条",
            compact = true,
            enabled = canDown,
            onClick = onDown,
        )
        IconActionButton(
            image = ActionIcons.Close,
            description = "从队列里删掉这一条",
            compact = true,
            onClick = onRemove,
        )
    }
}
/** 本文件里的一行说明（`Hint` 在供应商页里是 private，不该为一个提示去改它的可见性）。 */
@Composable
private fun QueueHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
    )
}
