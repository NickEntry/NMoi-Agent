package com.nmoi.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

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

