package com.nmoi.agent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
 * 页面自己报告"这一屏的内容决定了"（D349）。
 *
 * 没接的页面照旧靠"布局稳定"那条兜底（D348），行为不变；接了的页面在数据读完之后
 * 调一次 [notifyReady]，预渲染窗口就能**等到真数据**，而不是猜那 90 ms。
 *
 * 为什么是一个进程级对象而不是 `CompositionLocal`：`.current` 只能在 composable 上下文中读，
 * 而页面里能拿到"读完了"这个时机的地方是 `LaunchedEffect`（挂起上下文）。用一个对象，
 * 页面侧只要一行、不需要在 composable 作用域里先取出回调。宿主同一时刻只有一个待渲染页，
 * 所以"后装的覆盖前装的"在这里不是问题；换页时宿主会重新装。
 */
internal object PageReady {
    private val hook = java.util.concurrent.atomic.AtomicReference<(() -> Unit)?>(null)

    fun install(callback: (() -> Unit)?) {
        hook.set(callback)
    }

    fun notifyReady() {
        hook.get()?.invoke()
    }
}

/**
 * 页面宿主（D346 / D347 / D348 / D349）：把下一页画好再换上去；画不出来给出口。
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
    /** 这一页会不会报 `ready`（D352）。**不报的页面两帧就走**，否则每次都要空等 600 ms。 */
    waitsForData: Boolean,
    onLeave: () -> Unit,
    content: @Composable () -> Unit,
) {
    // 重试 = 整棵子树重建（D346）。
    var attempt by remember(pageKey) { mutableStateOf(0) }
    var laidOut by remember(pageKey, attempt) { mutableStateOf(false) }
    var stuck by remember(pageKey, attempt) { mutableStateOf(false) }
    // 页面自己说"我读完了"（D349）。比高度稳不稳定的判定准，接了就用它。
    var ready by remember(pageKey, attempt) { mutableStateOf(false) }
    // 等不起了（D353）：切上去之后又等了一整段还没 ready，就把遮罩撤掉。
    // 没有这一格，一个永远不报 ready 的页面会被一块板永久盖住。
    var gaveUp by remember(pageKey, attempt) { mutableStateOf(false) }
    val markReady: () -> Unit = remember(pageKey, attempt) { { ready = true } }
    SideEffect { PageReady.install(markReady) }
    androidx.compose.runtime.DisposableEffect(pageKey, attempt) {
        onDispose { PageReady.install(null) }
    }

    LaunchedEffect(pageKey, attempt) {
        delay(PAGE_STUCK_MS)
        if (!laidOut) stuck = true
    }

    val probe = Modifier
        .fillMaxSize()
        .onGloballyPositioned { laidOut = true }

    if (!preview) {
        Box(modifier = Modifier.fillMaxSize()) {
            key(pageKey, attempt) {
                Box(modifier = probe) {
                    content()
                }
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
        // 会报 ready 的页面：等它，或等满窗口。**不看"布局稳不稳"**——那个判据会在
        // "正在读…"那一行上判稳（那一行本来就不动），提前切过去正好把它露出来（D352）。
        // 不报 ready 的页面：它们的首帧就是最终内容，两帧足够。
        var waited = 0L
        if (waitsForData) {
            while (waited < PAGE_PREVIEW_MS && !ready) {
                delay(PREVIEW_STEP_MS)
                waited += PREVIEW_STEP_MS
            }
        }
        // 一行日志：真机上"还会不会看到正在读"要靠它判断（ready 到没到、等了多少）。
        NLog.i("Page", "$pageKey ready=$ready waited=${waited}ms")
        shownKey = pageKey
        shownContent = content
        // 已经切上去了，数据还没到（或者这一页根本不报 ready）：再遮一小段就撤。
        // 用户 2026-11-18 的原话：「设计一个 0.3 秒的加载动画先叠加着」。
        if (waitsForData) {
            var covered = 0L
            while (covered < PAGE_SCREEN_MS && !ready) {
                delay(PREVIEW_STEP_MS)
                covered += PREVIEW_STEP_MS
            }
        }
        gaveUp = true
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 位置 0：当前页。窗口里透明、被旧页盖住。
        Box(modifier = Modifier.fillMaxSize().alpha(if (pending) 0f else 1f)) {
            key(pageKey, attempt) {
                Box(modifier = probe) {
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
        // 切换已经发生、数据还没到：一块与背景同色的板 + 一个小圈，盖住页面的
        // 「正在读库…」。**板的下面是这一页本身**（它自己在读），盖住只是不让人看见那行字。
        if (!pending && waitsForData && !ready && !gaveUp) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (stuck) {
            StuckBar(attempt = attempt, onRetry = { attempt += 1 }, onLeave = onLeave)
        }
    }
}

/**
 * 预渲染窗口上限（D350）：两帧之后最多等这么久。
 *
 * 从 90 ms 提到 600 ms：用户要的是「加载完再渲染」，不是「等一会儿就切」。
 * 慢页面（模型快照那一页解析 1.2 MB JSON 要几百毫秒）在 90 ms 里根本读不完，
 * 切过去还是「正在读…」——那正是他不想要的东西。窗口期间旧页还撑着、还能点，
 * 所以等久一点不会出现空白。
 */
private const val PAGE_PREVIEW_MS = 600L



/** 窗口里多久看一次"数据到了没有"。8 ms 约半帧，够快又不会空转。 */
private const val PREVIEW_STEP_MS = 8L

/** 多久还没画出来算"卡住"。 */
private const val PAGE_STUCK_MS = 2_500L

/**
 * 切换之后还要遮多久（D353）。
 *
 * 窗口那边等 600 ms 是为了"切过去时数据已经在"；这一条是**兜底**：万一没等到
 * （页面没报 ready、或者报晚了），切上去之后再遮这一段，别让「正在读库…」露出来。
 */
private const val PAGE_SCREEN_MS = 300L

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