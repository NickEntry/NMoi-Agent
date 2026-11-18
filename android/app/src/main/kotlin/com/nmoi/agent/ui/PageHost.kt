package com.nmoi.agent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * 页面宿主（D346）：给"这一页起不来"留一条出路。
 *
 * 用户 2026-11-18：「主要是我怕有那种页面加载失败导致卡住不能继续用的问题」。
 *
 * 能观测到的只有一件事：**根节点有没有完成过一次布局**。
 *
 * - 布局完成 = 这一页至少画出了一帧（可能内容是「读…」），正常。
 * - 若干秒都没有布局 = 这一页连第一帧都没画出来（组合卡住、首帧在等一个永远不来的东西）。
 *   这时把控制权还给用户：一颗「重试」（把这一页整个重建一次）、一颗「返回」。
 *
 * 抓不到的东西要说清：**组合抛异常不会走到这里**。Compose 没有 error boundary，
 * 异常一路冒到 `AndroidComposeView`，结果是整页崩。主线程死锁也不会走到这里
 * （计时器本身也在主线程）。这两种救不了，所以不假装能救——本组件只处理"卡在没画出来"。
 */
@Composable
internal fun PageHost(
    pageKey: Any,
    onLeave: () -> Unit,
    content: @Composable () -> Unit,
) {
    // 换页或重试时重建：`attempt` 一动，整棵子树重新组合。
    var attempt by remember(pageKey) { mutableStateOf(0) }
    var laidOut by remember(pageKey, attempt) { mutableStateOf(false) }
    var stuck by remember(pageKey, attempt) { mutableStateOf(false) }

    // 只在**没布局过**时才判"卡住"。这里不能重置 `laidOut`：布局会先于这个 effect 跑，
    // 在 effect 里清一遍正是把刚才那个 true 抹掉——所有页面 2.5 秒后都会误报。
    LaunchedEffect(pageKey, attempt) {
        delay(PAGE_STUCK_MS)
        if (!laidOut) stuck = true
    }

    Box(modifier = Modifier.fillMaxSize()) {
        key(pageKey, attempt) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { laidOut = true },
            ) {
                content()
            }
        }
        if (stuck) {
            StuckBar(
                attempt = attempt,
                onRetry = { attempt += 1 },
                onLeave = onLeave,
            )
        }
    }
}

/** 多久没画出来算"卡住"。给首帧留够时间（重页面要解码缩略图、读 JSON），又不至于让人干等。 */
private const val PAGE_STUCK_MS = 2_500L

/**
 * 卡住时那条横幅：贴在底部，不盖整页——上面可能已经画出了一半，用户还想看看。
 *
 * 第二次还卡就**不再自动重试**：把话说死（"这一页起不来"），只给返回。
 * 反复重试一个坏页面，等于让用户看着同一块地方闪两次。
 */
@Composable
private fun StuckBar(attempt: Int, onRetry: () -> Unit, onLeave: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Surface(
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 6.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    text = if (attempt == 0) {
                        "这一页还没画出来"
                    } else {
                        "这一页起不来（重试过一次还是这样）"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = if (attempt == 0) {
                        "可以先重试；还是不行就返回——别的页面不受影响。"
                    } else {
                        "先返回。原因在 设置 → 调试 → 日志 里。"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ChipButton("返回", primary = false, onClick = onLeave)
                    if (attempt == 0) {
                        Spacer(Modifier.size(8.dp))
                        ChipButton("重试", primary = true, onClick = onRetry)
                    }
                }
            }
        }
    }
}