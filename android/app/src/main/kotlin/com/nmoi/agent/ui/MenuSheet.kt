package com.nmoi.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * 从底部升起的操作面板（自己画的，不用 `DropdownMenu`）。
 *
 * 为什么不用 `DropdownMenu`：那个是"贴着某个锚点的小菜单"，一行只有文字，
 * 而这里要的是**带图标、带头像、带右侧动作（铅笔）的行**，还要盖住整屏当一层用。
 * 用户 2026-10-24 给的助手列表与消息「更多」两张参考图都是这种。
 *
 * 形状：遮罩 0.32、面板上圆角 20、顶部一个抓手、每行 52dp 高（图标/头像 24 + 文字 + 可选右侧动作）。
 *
 * 还没做：升起的动画（现在是直接出现），拖动关闭也没有——这两条写在这里，免得被当成"已经会动"。
 */
internal data class SheetItem(
    val label: String,
    /** 左侧图标；与 [avatar] 二选一。 */
    val icon: ImageVector? = null,
    /** 左侧首字圆标（助手列表那种）。 */
    val avatar: String? = null,
    /** 右侧的动作图标（铅笔 = 编辑）。 */
    val trailing: ImageVector? = null,
    val onTrailing: (() -> Unit)? = null,
    /** 直接执行的动作。 */
    val onClick: (() -> Unit)? = null,
    /** 还没接的功能：点了给一句话，而不是让整行不能点。 */
    val hint: String? = null,
)

@Composable
internal fun MenuSheet(
    title: String,
    items: List<SheetItem>,
    onHint: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // 遮罩：比抽屉那层淡，面板本身是"临时的一层"，不该看着像换了页面。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.32f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )

        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Surface(
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(bottom = 24.dp)) {
                    // 抓手：告诉人"这一层是可以往下收的"（收的方式现在只有点遮罩/返回）。
                    Box(
                        modifier = Modifier
                            .padding(top = 10.dp, bottom = 8.dp)
                            .size(width = 36.dp, height = 4.dp)
                            .background(
                                MaterialTheme.colorScheme.outlineVariant,
                                RoundedCornerShape(999.dp),
                            )
                            .align(Alignment.CenterHorizontally),
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 6.dp),
                    )
                    items.forEach { item ->
                        SheetRow(item = item, onHint = onHint, onDismiss = onDismiss)
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetRow(item: SheetItem, onHint: (String) -> Unit, onDismiss: () -> Unit) {
    // 三态：能执行 / 还没接（点了给原因）/ 既没动作也没提示（灰着）。
    val usable = item.onClick != null || item.hint != null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable {
                when {
                    item.onClick != null -> {
                        onDismiss()
                        item.onClick.invoke()
                    }

                    item.hint != null -> {
                        onDismiss()
                        onHint(item.hint)
                    }
                }
            }
            .padding(start = 20.dp, end = 12.dp)
            .alpha(if (usable) 1f else 0.42f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val icon = item.icon
        val avatar = item.avatar
        when {
            avatar != null -> Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.size(28.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = avatar,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }

            icon != null -> Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Text(
            text = item.label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        val trailing = item.trailing
        if (trailing != null) {
            IconActionButton(
                image = trailing,
                description = "编辑",
                onClick = { item.onTrailing?.invoke() ?: onHint("编辑待接") },
                compact = true,
            )
        }
    }
}