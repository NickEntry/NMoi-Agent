package com.nmoi.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
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
            imageVector = Icons.Filled.Menu,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(22.dp),
        )
    }
}
