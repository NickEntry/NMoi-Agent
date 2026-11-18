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
 * 页面宿主（D346 / D347）：**先在后台把下一页渲染好，再换上去**。
 *
 * 用户 2026-11-18：「我想的就是能在切换瞬间看到渲染好的页面，而不是切换过去了才进行渲染」。
 *
 * 做法是两层叠着，位置固定：
 *
 * - 位置 0 永远是"当前页"（`pageKey` 那一份）。换页时它先以 `alpha(0f)` 组合、布局、
 *   跑完 `LaunchedEffect` 里那一轮读取，只是被上面的旧页完全盖住、也拿不到输入。
 * - 位置 1 是旧页（`shownContent`）：预渲染窗口里它压在上面，用户看到的、点到的是它——
 *   所以"点了导航但还没换过去"这一小段里，界面不会僵。
 * - 窗口结束：`shownKey = pageKey`，同一帧里旧页消失、新页 `alpha` 变 1。
 *   因为位置 0 的 `key` 没变，**页面不会被重建**（重建会再闪一次，等于白做）。
 *
 * 位置固定这一点是全部要点：把"当前页"和"旧页"分在两个 `if` 分支里各画一次，
 * Compose 会认为换页是一次跨位置的移动，`remember` 与 `LaunchedEffect` 全部重来。
 *
 * 窗口多长：两帧（组合 + 布局）+ [PAGE_PREVIEW_MS]（给 `LaunchedEffect` 里那一轮本地读）。
 * 读完还没布局的页面（重页面首帧要解码）就再多等一会儿，但不超过窗口上限。
 *
 * 抓不到的东西写在下面，不假装能救：
 * - **组合抛异常**不会走到这里。Compose 没有 error boundary，异常冒到 `AndroidComposeView`
 *   的结果是整页崩，不是"空白"。
 * - **主线程死锁**也救不了：看门狗那条计时器本身在主线程上排队。
 */
@Composable
internal fun PageHost(
    pageKey: Any,
    onLeave: () -> Unit,
    content: @Composable () -> Unit,
) {
    var shownKey by remember { mutableStateOf(pageKey) }
    // 旧页那一份渲染函数：只在预渲染窗口里用。每帧同步，窗口一开就不再更新——
    // 不然窗口里画的会变成"新页的旧帧"，看着像闪了一下。
    var shownContent by remember { mutableStateOf(content) }
    // 重试 = 整棵子树重建（D346）。
    var attempt by remember(pageKey) { mutableStateOf(0) }
    var laidOut by remember(pageKey, attempt) { mutableStateOf(false) }
    var stuck by remember(pageKey, attempt) { mutableStateOf(false) }

    val pending = shownKey != pageKey
    if (!pending) {
        // 引用比较：同一个 lambda 不重复写。写了虽然没人订阅（`pending` 分支才读它），
        // 但每一帧都写一次状态，迟早有一天会有人在别的层级读它，那时就是无限重组。
        SideEffect { if (shownContent !== content) shownContent = content }
    }

    LaunchedEffect(pageKey, attempt) {
        // 两帧：组合完成 + 布局完成。少了这两帧，新页还没量出尺寸就切，等于没预渲染。
        withFrameNanos { }
        withFrameNanos { }
        // 还没布局的重页面多给一点时间；已经布局好的（多半数据也到了）立刻换。
        if (!laidOut) delay(PAGE_PREVIEW_MS)
        shownKey = pageKey
        shownContent = content
        // 换过去之后仍然没画出来 → 判定卡住，把出口给用户。
        delay(PAGE_STUCK_MS)
        if (!laidOut) stuck = true
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 位置 0：当前页。预渲染期间透明、被旧页盖住。
        Box(modifier = Modifier.fillMaxSize().alpha(if (pending) 0f else 1f)) {
            key(pageKey, attempt) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .onGloballyPositioned { laidOut = true },
                ) {
                    content()
                }
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

/** 预渲染窗口：两帧之后的这一小段，用来等 `LaunchedEffect` 里那一轮本地读。 */
private const val PAGE_PREVIEW_MS = 90L

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