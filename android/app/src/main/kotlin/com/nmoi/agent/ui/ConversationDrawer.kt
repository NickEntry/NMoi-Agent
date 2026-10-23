package com.nmoi.agent.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 左侧抽屉：会话列表。
 *
 * 形状按 `docs/ui/overlays.md`（D109），本轮落地的与没落地的分开写清楚：
 *
 * 已做：
 * - 遮罩**纯黑 alpha 0.60**，点它关闭；
 * - 抽屉**只位移、不缩放不淡入**（`slideInHorizontally`，没有 `fadeIn`）；
 * - 抽屉从左边伸出，宽度 300dp；
 * - 系统返回键在**面板层**关闭它（调用方注册 `BackHandler(enabled = open)`）。
 *
 * 没做（下一轮，别以为已经有了）：
 * - 拖动关闭与 `dismissFraction 0.35` / `800dp/s` 速度阈值；
 * - 遮罩上滑动给 8dp 阻尼位移；
 * - 动画未落位前不可点 + `hideFromAccessibility()`；
 * - `panelEnterMs 220` / `panelExitMs 180` —— 现在用默认动画时长。
 *
 * 遮罩把内容盖住之后，下层的点击要真的落空，所以遮罩是"占满 + 可点"的，
 * 而不是只画一层颜色。
 */
@Composable
internal fun ConversationDrawer(
    open: Boolean,
    rows: List<ConversationRow>,
    currentTitle: String? = null,
    onOpen: () -> Unit,
    onOpenConversation: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onClose: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        content()

        if (!open) {
            // 左边缘唤出区。只做 20dp 宽、只认横向拖动：太宽会抢掉列表的竖向滚动，
            // 而"从边缘往右扒"这个手势本身就不该要求精准。
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(20.dp)
                    .align(Alignment.CenterStart)
                    .pointerInput(Unit) {
                        var dragged = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { dragged = 0f },
                            onHorizontalDrag = { _, amount ->
                                dragged += amount
                                if (dragged > 24.dp.toPx()) {
                                    dragged = 0f
                                    onOpen()
                                }
                            },
                        )
                    },
            )
        }

        if (open) {
            // 遮罩：纯黑 0.60，点击关闭。用没有涟漪的 clickable，
            // 否则点一下遮罩会闪一个灰圈，看着像"点到了东西"。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.60f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClose,
                    ),
            )
        }

        AnimatedVisibility(
            visible = open,
            enter = slideInHorizontally(initialOffsetX = { -it }),
            exit = slideOutHorizontally(targetOffsetX = { -it }),
            modifier = Modifier.align(Alignment.CenterStart),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.width(300.dp).fillMaxHeight(),
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Text(
                        text = "会话",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(start = 20.dp, top = 28.dp, bottom = 12.dp),
                    )

                    LazyColumn(modifier = Modifier.weight(1f)) {
                        if (rows.isEmpty()) {
                            item {
                                Text(
                                    text = "还没有会话",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                                )
                            }
                        }
                        items(rows) { row ->
                            // 当前正在看的那一条要有标记：抽屉里十行长得一样，
                            // 不标出来就没法回答"我现在在哪"这个最基本的可见性问题。
                            val current = row.title == currentTitle
                            Text(
                                text = if (current) "${row.title}  ·  当前" else row.title,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (current) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .clickable { onOpenConversation(row.title) }
                                    .padding(horizontal = 20.dp, vertical = 14.dp),
                            )
                        }
                    }

                    Text(
                        text = "设置",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .clickable { onOpenSettings() }
                            .padding(horizontal = 20.dp, vertical = 18.dp),
                    )
                }
            }
        }
    }
}
