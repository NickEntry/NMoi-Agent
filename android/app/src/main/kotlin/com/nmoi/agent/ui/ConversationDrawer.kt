package com.nmoi.agent.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * 左侧抽屉：会话列表。
 *
 * 形状按 `docs/ui/overlays.md`（D109）。这一版把**唤出手势**改成了"任意位置横滑"（D136）：
 *
 * - 按下后先看**前一小段路径的方向**：与水平线夹角 ≤ 25° 才算"在滑抽屉"，
 *   其它方向（更陡的斜滑、竖滑）一律让给列表自己滚——判据是角度，不是"横向分量更大"。
 *   这条很重要：斜着划的时候人以为自己在滚列表，抢过来会很恼火。
 * - 判定为横滑之后**跟手**：面板随手指位移、遮罩同步变深；
 * - 松手时看两样：**位移过了 35%** 或者 **速度过了 800 dp/s**（带方向）。快速甩一下就开，
 *   慢慢拖到一半松手也开；往回甩则关。都不满足就回弹到原状态。
 * - 竖向滚动不受影响：我们**只在判定为横滑之后**才 consume 事件。
 *
 * 还没做（别以为已经有）：回弹动画是"直接归位"而不是插值动画；拖动期间没有跟手的遮罩渐变曲线；
 * 双指手势没处理（只认第一个按下的手指）。
 */
@Composable
internal fun ConversationDrawer(
    open: Boolean,
    rows: List<ConversationRow>,
    currentId: String? = null,
    onOpen: () -> Unit,
    onOpenConversation: (ConversationRow) -> Unit,
    onNewConversation: () -> Unit,
    onOpenSettings: () -> Unit,
    onClose: () -> Unit,
    content: @Composable () -> Unit,
) {
    val width = 300.dp
    val widthPx = with(LocalDensity.current) { width.toPx() }
    // 跟手位移（px）。0 = 完全收起，widthPx = 完全展开。
    var dragPx by remember { mutableStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }

    // 手势挂在**最外层**：父节点在子节点之后收到事件，所以列表先滚；
    // 我们只在判定为横滑之后才 consume，竖向/斜向一律不抢。
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(open) {
                val flingThreshold = 800.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)

                    var dx = 0f
                    var dy = 0f
                    var decided = false
                    var horizontal = false
                    var progress = if (open) 1f else 0f

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        // 列表已经把这次事件拿走了（竖向滚动）：立刻退出，绝不抢。
                        if (change.isConsumed) break

                        dx += change.positionChange().x
                        dy += change.positionChange().y
                        tracker.addPosition(change.uptimeMillis, change.position)

                        if (!decided) {
                            val moved = hypot(dx.toDouble(), dy.toDouble())
                            if (moved > viewConfiguration.touchSlop) {
                                decided = true
                                // 角度判据：与水平线夹角 ≤ 25° 才当横滑。
                                val degrees = Math.toDegrees(
                                    atan2(abs(dy).toDouble(), abs(dx).toDouble()),
                                )
                                horizontal = degrees <= 25.0
                                if (!horizontal) break
                                dragging = true
                                dragPx = progress * widthPx
                            }
                        }

                        if (decided && horizontal) {
                            // 跟手：起点进度 + 位移占宽的比例。
                            progress = (progress + change.positionChange().x / widthPx).coerceIn(0f, 1f)
                            dragPx = progress * widthPx
                            change.consume()
                        }
                        if (!change.pressed) break
                    }

                    if (decided && horizontal) {
                        val velocityX = tracker.calculateVelocity().x
                        val flung = abs(velocityX) > flingThreshold
                        val targetOpen = when {
                            // 快速甩：方向说了算（向右开、向左关）。
                            flung -> velocityX > 0f
                            else -> progress >= 0.35f
                        }
                        dragging = false
                        dragPx = 0f
                        if (targetOpen) {
                            if (!open) onOpen()
                        } else {
                            if (open) onClose()
                        }
                    }
                }
            },
    ) {
        content()

        if (dragging) {
            // 拖动中：遮罩透明度跟着进度走，别在手指还在动的时候突然全黑。
            val progress = (dragPx / widthPx).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.60f * progress)),
            )
            Surface(
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .width(width)
                    .fillMaxHeight()
                    .offset { IntOffset((dragPx - widthPx).roundToInt(), 0) },
            ) {
                DrawerPanel(
                    rows = rows,
                    currentId = currentId,
                    onOpenConversation = onOpenConversation,
                    onNewConversation = onNewConversation,
                    onOpenSettings = onOpenSettings,
                )
            }
        } else {
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
                    modifier = Modifier.width(width).fillMaxHeight(),
                ) {
                    DrawerPanel(
                        rows = rows,
                        currentId = currentId,
                        onOpenConversation = onOpenConversation,
                        onNewConversation = onNewConversation,
                        onOpenSettings = onOpenSettings,
                    )
                }
            }
        }
    }

}


/** 抽屉里那一列：新建 + 会话行 + 设置。拖动与动画两条路径共用同一份，免得两处慢慢长歪。 */
@Composable
private fun DrawerPanel(
    rows: List<ConversationRow>,
    currentId: String?,
    onOpenConversation: (ConversationRow) -> Unit,
    onNewConversation: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "会话",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 20.dp, top = 28.dp, bottom = 8.dp),
        )

        // 新建放在列表顶上：抽屉是"换会话"的地方，那就得能在这里开一个新的。
        // 它只是切到一个空白会话（D125），落库发生在第一次存消息时。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onNewConversation() }
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = ActionIcons.Plus,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 10.dp).size(18.dp),
            )
            Text(
                text = "新会话",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

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
                // 按 **id** 比，不按标题：两个会话重名是常事，重名会让两行同时亮。
                val current = currentId != null && row.id == currentId
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
                        .clickable { onOpenConversation(row) }
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