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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * 页面宿主（D346 / D347 / D348）：把下一页画好再换上去；画不出来给出口。
 *
 * # 范围（用户 2026-11-18 划定）
 *
 * 只对**聊天以外的整页**做预渲染——设置、供应商、模型、存储、日志、安全这些。
 * 聊天页不预渲染（`preview = false`）：它第一帧的响应性最要紧，输入框、键盘、
 * 滚动位置都在那一帧里；为了"不闪一下"让它晚 90 ms 才响应点击，是拿最常用的页面
 * 去换最少用的那点收益。
 *
 * # 预渲染怎么做的
 *
 * 两层叠着、位置固定：
 *
 * - 位置 0 永远是"当前页"（`pageKey` 那一份）。换页时它先以 `alpha(0f)` 组合、布局、
 *   跑完 `LaunchedEffect` 里那一轮本地读，只是被上面的旧页完全盖住、也拿不到输入。
 * - 位置 1 是旧页（`shownContent`）。窗口里它压在上面，用户看到的、点到的是它，
 *   所以"点了导航还没换过去"这一小段里界面不僵。
 * - 窗口结束：`shownKey = pageKey`，同一帧里旧页消失、新页 `alpha` 变 1。
 *
 * 位置固定是全部要点：把两页分在两个 `if` 分支里各画一次，Compose 会认为换页是
 * 一次跨位置的移动，`remember` 与 `LaunchedEffect` 整棵重来——等于切过去又重建一遍。
 *
 * # 窗口多长（D348）
 *
 * 两帧（组合 + 布局）之后**不再死等**：每 8 ms 采一次新页的高度，连续两次一样就认为
 * 布局稳了（数据填进来高度一定会变，稳定就等于变化结束），立刻切；最多等到
 * [PAGE_PREVIEW_MS]。轻页面四十毫秒上下换完，还在长的重页面才用满窗口。
 *
 * # 抓不到的，不假装能救
 *
 * - **组合抛异常**不会走到这里。Compose 没有 error boundary，异常冒到 `AndroidComposeView`
 *   的结果是整页崩，不是"空白"。
 * - **主线程死锁**也救不了：看门狗那条计时器本身在主线程上排队。
 */
@Composable
internal fun PageHost(
    pageKey: Any,
    preview: Boolean,
    onLeave: () -> Unit,
    content: @Composable () -> Unit,
) {
    // 重试 = 整棵子树重建（D346）。
    var attempt by remember(pageKey) { mutableStateOf(0) }
    var laidOut by remember(pageKey, attempt) { mutableStateOf(false) }
    var stuck by remember(pageKey, attempt) { mutableStateOf(false) }
    // 这一页现在多高（D348）。窗口里靠它判断"布局稳了没有"——稳定就够了，
    // 不需要知道数据是不是真到了：内容一填进来高度一定变，稳定意味着变化结束。
    var height by remember(pageKey, attempt) { mutableStateOf(0) }

    LaunchedEffect(pageKey, attempt) {
        delay(PAGE_STUCK_MS)
        if (!laidOut) stuck = true
    }

    val probe = Modifier
        .fillMaxSize()
        .onGloballyPositioned { coords ->
            laidOut = true
            height = coords.size.height
        }

    if (!preview) {
        Box(modifier = Modifier.fillMaxSize()) {
            key(pageKey, attempt) {
                Box(modifier = probe) { content() }
            }
            if (stuck) {
                StuckBar(attempt = attempt, onRetry = { attempt += 1 }, onLeave = onLeave)
            }
        }
        return
    }

    var shownKey by remember { mutableStateOf(pageKey) }
    // 旧页那一份渲染函数：只在窗口里用。每帧同步，窗口一开就停——不然窗口里画的会变成
    // "新页的旧帧"，看着像闪了一下。引用比较：同一个 lambda 不重复写状态。
    var shownContent by remember { mutableStateOf(content) }
    val pending = shownKey != pageKey
    if (!pending) {
        SideEffect { if (shownContent !== content) shownContent = content }
    }

    LaunchedEffect(pageKey, attempt) {
        withFrameNanos { }
        withFrameNanos { }
        // 每 8 ms 采一次高度，**两次采样一样**就认为布局稳了（数据填完、该撑开也撑开了），
        // 立刻换。轻页面四十毫秒上下就换完；还在长的重页面才用满窗口。
        var last = height
        var waited = 0L
        while (waited < PAGE_PREVIEW_MS) {
            delay(PREVIEW_STEP_MS)
            waited += PREVIEW_STEP_MS
            if (height > 0 && height == last) break
            last = height
        }
        shownKey = pageKey
        shownContent = content
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 位置 0：当前页。窗口里透明、被旧页盖住。
        Box(modifier = Modifier.fillMaxSize().alpha(if (pending) 0f else 1f)) {
            key(pageKey, attempt) {
                Box(modifier = probe) { content() }
            }
        }
        // 位置 1：旧页，压在上面（后画的在上层）——输入归它。
        if (pending) {
            key(shownKey) {
                Box(modifier = Modifier.fillMaxSize()) { shownContent() }
            }
        }
        if (stuck) {
            StuckBar(attempt = attempt, onRetry = { attempt += 1 }, onLeave = onLeave)
        }
    }
}

/** 预渲染窗口上限：两帧之后的这一小段，最多等这么久。 */
private const val PAGE_PREVIEW_MS = 90L

/** 窗口里多久看一次"数据到了没有"。8 ms 约半帧，够快又不会空转。 */
private const val PREVIEW_STEP_MS = 8L

/** 多久还没画出来算"卡住"。 */
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