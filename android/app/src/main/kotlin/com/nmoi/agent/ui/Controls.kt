package com.nmoi.agent.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 返回箭头。左上角那个，二级页面统一用它。
 *
 * 按 D103 的三条：**视觉 22dp、热区 48dp**（分开算，不然不是看着太大就是点不着）、
 * `contentDescription` 必填、长按给一句人话提示。
 *
 * 长按用 `detectTapGestures` 而不是 `combinedClickable`：后者在不同 Compose 版本里
 * 还挂着 Experimental 注解，而这里只是"点一下 / 长按一下"，手势检测器够用且稳定。
 * 点击**不用** `IconButton`：那个默认热区只有 40dp，且不好叠加自己的手势。
 */
@Composable
internal fun BackButton(onBack: () -> Unit, modifier: Modifier = Modifier) {
    var hintVisible by remember { mutableStateOf(false) }

    LaunchedEffect(hintVisible) {
        if (hintVisible) {
            delay(1600)
            hintVisible = false
        }
    }

    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onBack() },
                        onLongPress = { hintVisible = true },
                    )
                }
                .semantics { contentDescription = "返回上一级" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(22.dp),
            )
        }

        if (hintVisible) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.inverseSurface,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 52.dp),
            ) {
                Text(
                    text = "返回上一级",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/**
 * 侧边栏入口（≡）。和返回按钮同一套尺寸规矩：热区 48dp / 视觉 22dp / contentDescription 必填。
 *
 * 单独抽出来是因为它要出现在多个页面（列表页、聊天页），
 * 复制粘贴三次之后一定会有一处忘了 `contentDescription`。
 */
@Composable
internal fun MenuButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) }
            .semantics { contentDescription = "打开侧边栏" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = ActionIcons.PanelLeft,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(22.dp),
        )
    }
}

/**
 * 通用图标按钮（D103 那三条：**视觉 22dp、热区 48dp** 分开算、`contentDescription` 必填、
 * 长按给一句人话提示）。
 *
 * 不跟返回键合成一个：返回键还要 AutoMirrored 方向翻转，合在一起就成了"通用里夹特例"，
 * 后面每加一个图标都得回头想清楚会不会影响返回键。这里只留最常用的一条。
 */
@Composable
internal fun IconActionButton(
    image: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** 灰掉：功能还没接。**仍然能长按看提示**——"为什么是灰的"要能问出来。 */
    enabled: Boolean = true,
    /** 消息下面那排动作按钮用它：热区 36 / 视觉 18，四五个并排才不显笨重。 */
    compact: Boolean = false,
) {
    var hintVisible by remember { mutableStateOf(false) }

    LaunchedEffect(hintVisible) {
        if (hintVisible) {
            delay(1600)
            hintVisible = false
        }
    }

    val hit = if (compact) 36.dp else 48.dp
    val visual = if (compact) 18.dp else 22.dp
    val tint = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
    }

    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .size(hit)
                .clip(CircleShape)
                .pointerInput(enabled) {
                    detectTapGestures(
                        onTap = { if (enabled) onClick() },
                        onLongPress = { hintVisible = true },
                    )
                }
                .semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = image,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(visual),
            )
        }

        if (hintVisible) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.inverseSurface,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 52.dp),
            ) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/**
 * 底部主按钮（原 ProviderScreens 里那颗，D201 第三片挪到这里）。
 *
 * 挪出来的理由只有一条：**同一个形状不能有两个实现**。资源更新页与供应商页是兄弟页面，
 * 两边各画一颗的话，圆角、高度、禁用色迟早对不上——而那种不对齐没人会报 bug，
 * 只会让页面看起来"不是一个人做的"。
 */
@Composable
internal fun WideButton(
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (enabled) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        modifier = modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 13.dp),
        )
    }
}

/**
 * 行线从文字起点开始（不是从卡片边缘），跟分组列表的规矩一致。
 *
 * 放在这里而不是某个页面里：供应商页、分组页、资源更新页都要用同一条。
 * 各画一条的话，缩进与透明度会一点点分叉，最后看起来像三个应用拼起来的。
 */
@Composable
internal fun RowDivider(leading: Dp) {
    androidx.compose.material3.HorizontalDivider(
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        modifier = Modifier.padding(start = leading),
    )
}

/**
 * 一行可以左滑露出删除的列表行。
 *
 * 红底铺在这个 Box 上、内容行自己不透明：换成"背后那一层铺红"会在真机上量成 0 高（D215）。
 * 松手不过一半就弹回，不留一条半开的红色待命。
 */
@Composable
internal fun SwipeDeleteRow(
    resetKey: Any?,
    rowColor: Color,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    swipeEnabled: Boolean = true,
    revealWidth: Dp = 88.dp,
    deleteLabel: String = "删除",
    deleteDescription: String = "删除",
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
    content: @Composable RowScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    val reveal = with(LocalDensity.current) { revealWidth.toPx() }
    val offset = remember(resetKey) { Animatable(0f) }

    LaunchedEffect(swipeEnabled) {
        if (!swipeEnabled) offset.snapTo(0f)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clipToBounds()
            .background(MaterialTheme.colorScheme.error),
    ) {
        Box(modifier = Modifier.matchParentSize()) {
            Row(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .width(revealWidth)
                    .fillMaxHeight()
                    .clickable {
                        scope.launch { offset.snapTo(0f) }
                        onDelete()
                    },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = ActionIcons.Trash2,
                    contentDescription = deleteDescription,
                    tint = MaterialTheme.colorScheme.onError,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = deleteLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onError,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }

        Row(
            modifier = Modifier
                .background(rowColor)
                .fillMaxWidth()
                .offset { IntOffset(offset.value.roundToInt(), 0) }
                .pointerInput(resetKey, swipeEnabled) {
                    if (!swipeEnabled) return@pointerInput
                    detectHorizontalDragGestures(
                        onHorizontalDrag = { _, drag ->
                            scope.launch {
                                offset.snapTo((offset.value + drag).coerceIn(-reveal, 0f))
                            }
                        },
                        onDragEnd = {
                            val target = if (offset.value < -reveal / 2f) -reveal else 0f
                            scope.launch { offset.animateTo(target) }
                        },
                        onDragCancel = { scope.launch { offset.animateTo(0f) } },
                    )
                }
                .clickable {
                    if (offset.value < -1f) scope.launch { offset.animateTo(0f) } else onClick()
                }
                .padding(contentPadding),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/**
 * 多选时那块顶栏：勾了几项、还能不能全选、怎么退出去。
 *
 * 抽屉和世界书用同一份：两处各写一条，改手感时总有一处落后。
 */
@Composable
internal fun SelectionBar(
    count: Int,
    unit: String,
    allSelected: Boolean,
    onToggleAll: () -> Unit,
    onClose: () -> Unit,
    topPadding: Dp = 8.dp,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = topPadding, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "已勾 $count $unit",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = if (allSelected) "取消全选" else "全选",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clickable { onToggleAll() }
                .padding(horizontal = 8.dp, vertical = 8.dp),
        )
        IconActionButton(
            image = ActionIcons.Close,
            description = "退出多选",
            onClick = onClose,
            compact = true,
        )
    }
}

/**
 * 多选时的底部动作条：一项都没勾时只写一句，动作由调用方摆。
 *
 * 三个按不动的字摆在那儿，用户会去点，然后怀疑是不是坏了。
 */
@Composable
internal fun BulkBar(
    count: Int,
    emptyLabel: String = "还没勾：点一行开始",
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (count == 0) {
            Text(
                text = emptyLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Row
        }
        content()
    }
}

/** 底部那条里的一个文字动作。删除用 error 色；红色实心只出现在确认卡里。 */
@Composable
internal fun BulkText(
    label: String,
    onClick: () -> Unit,
    danger: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = when {
            !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            danger -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.onSurface
        },
        modifier = modifier
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    )
}

/**
 * 按住 [millis] 再触发：默认 2 秒，用户在真机上量过。
 *
 * 计时放在外面的 `LaunchedEffect` 里：`awaitEachGesture` 那块是受限挂起作用域，
 * 里面既不能 `delay` 也不能 `launch`。
 * 手指移动超过 slop 就取消，否则滚一下列表就会误进多选；触发之后这一串事件都被吞掉，
 * 行自己的点击不会跟着再响一次。
 */
internal fun Modifier.onHold(
    key: Any?,
    millis: Long = 2000L,
    onHold: () -> Unit,
): Modifier = composed {
    var armed by remember { mutableStateOf(false) }
    var held by remember { mutableStateOf(false) }
    val latest = rememberUpdatedState(onHold)

    LaunchedEffect(armed, millis) {
        if (!armed) return@LaunchedEffect
        delay(millis)
        held = true
        latest.value()
    }

    pointerInput(key) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            held = false
            armed = true
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (held) change.consume()
                if (change.changedToUpIgnoreConsumed()) break
                if (!held && (change.position - down.position).getDistance() > viewConfiguration.touchSlop) break
            }
            armed = false
        }
    }
}

/** 多选时左边那个小方框：勾上是主色实心 + 白勾。 */
@Composable
internal fun SelectBox(checked: Boolean) {
    val stroke = MaterialTheme.colorScheme.outline
    val fill = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .size(20.dp)
            .border(1.5.dp, if (checked) fill else stroke, RoundedCornerShape(5.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(
                imageVector = ActionIcons.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}


/**
 * 小胶囊按钮（取消 / 确定 / 清除这一档）。
 *
 * 与 [WideButton] 的分工：那颗是"这一屏的主动作"，一屏只有一颗填色的；
 * 这一颗是行内的小动作，成对出现（取消 + 确定）。两者形状不一样，别互相顶替。
 */
@Composable
internal fun ChipButton(
    label: String,
    primary: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = when {
            !enabled -> MaterialTheme.colorScheme.surfaceContainerLow
            primary -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.surfaceContainerHigh
        },
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = when {
                !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                primary -> MaterialTheme.colorScheme.onPrimary
                else -> MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
        )
    }
}

/**
 * 收不回来的动作的确认卡：标题 + 会没掉什么 + 红色实心确认。
 *
 * 遮罩点一下 = 取消（这类确认不是"必须处理"的那种，取消就是回到原样）。
 * 标题与确认按钮上的字都由调用方写：写成"确定"的话，用户得回头再看一遍正文才知道要确认什么。
 */
@Composable
internal fun ConfirmDialog(
    title: String,
    detail: String,
    confirmLabel: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.32f))
                .clickable(onClick = onCancel),
        )
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ChipButton(label = "取消", primary = false, onClick = onCancel)
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.clickable(onClick = onConfirm),
                        ) {
                            Text(
                                text = confirmLabel,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onError,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
