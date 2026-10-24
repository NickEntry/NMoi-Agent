package com.nmoi.agent.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * 左侧抽屉：会话列表 + 唤出手势（D136）。
 *
 * 手势规则（用户 2026-10-24 定，这一版按真机反馈调过）：
 *
 * 1. 按下后先看**前一小段路径的方向**：与水平线夹角 **≤ 25°** 才算"在滑抽屉"。
 *    判据是角度，不是 `|dx| > |dy|`——斜着划时人以为自己在滚列表。
 * 2. 判定为横滑后**跟手**：面板位移 = 起点进度 + 位移 / 面板宽，遮罩 α 同步 0 → 0.60。
 * 3. 松手看两样：**位移 ≥ 25%** 或 **速度 ≥ 350 dp/s**（带方向）。
 *    真机上 800 dp/s 那次太苛刻，快速甩也常常不过线——阈值定得比"系统认为在甩"更松一点，
 *    因为这里的目标不是精确判定，是"我想开就开"。
 * 4. 松手之后**补一段带初速的动画**（`Animatable.animateTo` + tween）：
 *    面板沿原来方向继续走完，而不是啪一下归位。时长按"剩余距离 / 速度"算，夹在 80–260ms。
 *
 * 竖向滚动不受影响：只有判定为横滑**之后**才 consume；发现 `change.isConsumed`
 * （列表已经拿走这次事件）立刻退出。
 *
 * 还没做：只认第一根手指（双指不处理）；遮罩没有单独的缓动曲线，跟着同一条 tween 走。
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
    val scope = rememberCoroutineScope()

    // 面板位移（px，0 = 收起，widthPx = 展开）。拖动和松手后的惯性动画都写它。
    val panel = remember { Animatable(0f) }
    var dragging by remember { mutableStateOf(false) }
    // 松手之后的惯性动画期间也要走"手动画"这条路径，否则会先弹一下再交给 AnimatedVisibility。
    var animating by remember { mutableStateOf(false) }
    val manual = dragging || animating

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(open) {
                val flingThreshold = 350.dp.toPx()
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
                                val degrees = Math.toDegrees(
                                    atan2(abs(dy).toDouble(), abs(dx).toDouble()),
                                )
                                horizontal = degrees <= 25.0
                                if (!horizontal) break
                                dragging = true
                                panel.snapTo(progress * widthPx)
                            }
                        }

                        if (decided && horizontal) {
                            progress = (progress + change.positionChange().x / widthPx).coerceIn(0f, 1f)
                            panel.snapTo(progress * widthPx)
                            change.consume()
                        }
                        if (!change.pressed) break
                    }

                    if (!decided || !horizontal) return@awaitEachGesture

                    val velocityX = tracker.calculateVelocity().x
                    val flung = abs(velocityX) > flingThreshold
                    val targetOpen = when {
                        // 快速甩：方向说了算（向右开、向左关）。
                        flung -> velocityX > 0f
                        else -> progress >= 0.25f
                    }

                    // 先落状态，再播动画：这样动画结束时 AnimatedVisibility 已经是"到位"的，
                    // 不会再滑一次。
                    dragging = false
                    if (targetOpen) {
                        if (!open) onOpen()
                    } else {
                        if (open) onClose()
                    }

                    val start = panel.value
                    val target = if (targetOpen) widthPx else 0f
                    if (abs(start - target) < 1f) {
                        panel.snapTo(0f)
                        return@awaitEachGesture
                    }
                    // 时长按"剩余距离 / 速度"估：甩得越快越短，松手时几乎没速度就用慢一点的 260ms。
                    val speed = abs(velocityX).coerceAtLeast(1f)
                    val duration = ((abs(target - start) / speed) * 1000f)
                        .coerceIn(80f, 260f)
                        .toInt()
                    animating = true
                    scope.launch {
                        panel.animateTo(
                            targetValue = target,
                            animationSpec = tween(
                                durationMillis = duration,
                                easing = LinearOutSlowInEasing,
                            ),
                        )
                        animating = false
                        panel.snapTo(0f)
                    }
                }
            },
    ) {
        content()

        if (manual) {
            // 手动渲染这条路径：遮罩透明度跟着进度走，别在手指还在动的时候突然全黑。
            val progress = (panel.value / widthPx).coerceIn(0f, 1f)
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
                    .offset { IntOffset((panel.value - widthPx).roundToInt(), 0) },
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