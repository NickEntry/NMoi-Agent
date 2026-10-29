package com.nmoi.agent.ui

import android.view.inputmethod.InputMethodManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.time.LocalTime
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * 左侧抽屉：用户块 / 搜索 / 会话列表 / 助手切换（D139）。
 *
 * 手势（D136）：按下的前一小段路径与水平夹角 ≤ 25° 才算"在滑抽屉"，之后跟手；
 * 松手看位移或速度（开 350 dp/s · 25%，关 160 dp/s · 15%），并补一段带初速的动画。
 *
 * 展开时**主动收键盘**（用户 2026-10-24 提）：抽屉盖住输入区之后，输入框还顶着焦点，
 * 安卓的输入法就不会自己收回去。这里清焦点 + 直接让 IME 收窗（`clearFocus` 对
 * `AndroidView` 里的原生 `EditText` 不一定够，所以两件事都做）。
 */
@Composable
internal fun ConversationDrawer(
    open: Boolean,
    rows: List<ConversationRow>,
    currentId: String? = null,
    /** 全部助手（D174）。底部那一行显示当前那个，点它弹出切换面板。 */
    assistants: List<AssistantEntry> = emptyList(),
    activeAssistantId: String = AssistantRepository.DEFAULT_ID,
    onOpen: () -> Unit,
    onOpenConversation: (ConversationRow) -> Unit,
    onNewConversation: () -> Unit,
    /**
     * 能不能"从边缘拖出来"。
     *
     * 子页面（助手页、设置页…）上不该能拖出抽屉：那会让用户以为自己在另一层里滑，
     * 也让这些页面的返回手势和抽屉手势打架（2026-10-28 用户报的"这页为什么能划出侧边栏"）。
     * 抽屉打开状态本身仍由外层控制——聊天页用得上。
     */
    canOpenByDrag: Boolean = true,
    /** 切到某个助手：只改"当前是哪个"，会话与配置一个都不搬。 */
    onSwitchAssistant: (String) -> Unit = {},
    onNewAssistant: () -> Unit = {},
    onEditAssistant: (AssistantEntry) -> Unit = {},
    /** 把某条会话移到某个助手（D174）：改归属，不复制。 */
    onMoveConversation: (ConversationRow, String) -> Unit = { _, _ -> },
    onOpenSettings: () -> Unit,
    onClose: () -> Unit,
    content: @Composable () -> Unit,
) {
    val width = 300.dp
    val widthPx = with(LocalDensity.current) { width.toPx() }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val view = LocalView.current

    // 抽屉一开就收键盘：这是用户要的手感，不是可选项。
    LaunchedEffect(open) {
        if (open) {
            focusManager.clearFocus(force = true)
            val imm = view.context.getSystemService(InputMethodManager::class.java)
            imm?.hideSoftInputFromWindow(view.windowToken, 0)
        }
    }

    // 拖动位移（px）。拖动阶段只能写普通 state：`awaitEachGesture` 是受限协程，
    // 里面挂起不了 Animatable 的方法（CI 会报 Restricted suspending functions...）。
    // 提示行与助手面板的状态放在最外层：面板要盖住整屏，不能缩在 300dp 的抽屉里。
    var hint by remember { mutableStateOf<String?>(null) }
    var assistantMenu by remember { mutableStateOf(false) }
    // 当前助手：面板与底部那一行都读它。找不到就退回一份默认（老库没有 assistant 文档）。
    val activeAssistant = assistants.firstOrNull { it.id == activeAssistantId } ?: AssistantEntry(id = activeAssistantId)
    var dragPx by remember { mutableStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val inertia = remember { Animatable(0f) }
    var animating by remember { mutableStateOf(false) }
    val manual = dragging || animating
    val shownPx = if (animating) inertia.value else dragPx

    // 助手面板盖在抽屉上面，返回键要先把这一层收掉，而不是把抽屉一起关掉（2026-10-26 报的同类问题）。
    // 这里后于 MainActivity 的 drawerOpen 处理器注册，所以内层先轮到。
    BackHandler(enabled = assistantMenu) { assistantMenu = false }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(open, canOpenByDrag) {
                // 关门的手势永远留着（抽屉开着时总得能收）；**开门**只在允许的页面上认。
                if (!open && !canOpenByDrag) return@pointerInput
                // 两个方向两套阈值（D136）：开门 350 dp/s / 25%，关门 160 dp/s / 15%。
                val openFling = 350.dp.toPx()
                val closeFling = 160.dp.toPx()
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
                        // 列表/输入框已经把这次事件拿走了：立刻退出，绝不抢。
                        if (change.isConsumed) break

                        dx += change.positionChange().x
                        dy += change.positionChange().y
                        tracker.addPosition(change.uptimeMillis, change.position)

                        if (!decided) {
                            if (hypot(dx.toDouble(), dy.toDouble()) > viewConfiguration.touchSlop) {
                                decided = true
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
                            progress = (progress + change.positionChange().x / widthPx).coerceIn(0f, 1f)
                            dragPx = progress * widthPx
                            change.consume()
                        }
                        if (!change.pressed) break
                    }

                    if (!decided || !horizontal) return@awaitEachGesture

                    val velocityX = tracker.calculateVelocity().x
                    val targetOpen = if (open) {
                        when {
                            velocityX < -closeFling -> false
                            velocityX > openFling -> true
                            else -> progress > 0.85f
                        }
                    } else {
                        when {
                            velocityX > openFling -> true
                            velocityX < -openFling -> false
                            else -> progress >= 0.25f
                        }
                    }

                    if (targetOpen) {
                        if (!open) onOpen()
                    } else {
                        if (open) onClose()
                    }

                    val start = dragPx
                    val target = if (targetOpen) widthPx else 0f
                    if (abs(start - target) < 1f) {
                        dragging = false
                        dragPx = 0f
                        return@awaitEachGesture
                    }
                    val speed = abs(velocityX).coerceAtLeast(1f)
                    val duration = ((abs(target - start) / speed) * 1000f).coerceIn(80f, 260f).toInt()
                    scope.launch {
                        inertia.snapTo(start)
                        animating = true
                        dragging = false
                        inertia.animateTo(
                            targetValue = target,
                            animationSpec = tween(
                                durationMillis = duration,
                                easing = LinearOutSlowInEasing,
                            ),
                        )
                        animating = false
                        dragPx = 0f
                        inertia.snapTo(0f)
                    }
                }
            },
    ) {
        content()

        if (manual) {
            val progress = (shownPx / widthPx).coerceIn(0f, 1f)
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
                    .offset { IntOffset((shownPx - widthPx).roundToInt(), 0) },
            ) {
                DrawerPanel(
                    rows = rows,
                    currentId = currentId,
                    hint = hint,
                    onHint = { hint = it },
                    activeAssistant = activeAssistant,
                    onOpenAssistantMenu = { assistantMenu = true },
                    onOpenConversation = onOpenConversation,
                    onNewConversation = onNewConversation,
                    onMoveConversation = onMoveConversation,
                    assistants = assistants,
                    onOpenSettings = onOpenSettings,
                )
            }
        } else {
            if (open) {
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
                        hint = hint,
                        onHint = { hint = it },
                        activeAssistant = activeAssistant,
                        onOpenAssistantMenu = { assistantMenu = true },
                        onOpenConversation = onOpenConversation,
                        onNewConversation = onNewConversation,
                        onMoveConversation = onMoveConversation,
                        assistants = assistants,
                        onOpenSettings = onOpenSettings,
                    )
                }
            }
        }

        // 助手列表：自己画的底部面板（头像 + 名称 + 铅笔），不是下拉菜单。
        // D174：真数据。**当前那个写「（当前）」**——不写的话，两个助手名字相像时
        // 用户不知道点下去有没有变化。
        if (assistantMenu) {
            val items = assistants.map { entry ->
                val current = entry.id == activeAssistantId
                SheetItem(
                    label = entry.name + if (current) "（当前）" else "",
                    avatar = entry.name.take(1).ifBlank { "助" },
                    trailing = ActionIcons.Pencil,
                    onTrailing = { onEditAssistant(entry) },
                    onClick = {
                        assistantMenu = false
                        if (!current) onSwitchAssistant(entry.id)
                    },
                )
            } + listOf(
                SheetItem(
                    label = "新建助手",
                    icon = ActionIcons.Plus,
                    onClick = {
                        assistantMenu = false
                        onNewAssistant()
                    },
                ),
            )
            MenuSheet(
                title = "切换助手",
                items = items,
                onHint = { hint = it },
                onDismiss = { assistantMenu = false },
            )
        }
    }
}

/** 用户名字：装饰性字段，编辑是下一层的事。 */
private const val USER_NAME = "我"

/** 问候语按系统时间选，**文案一字不改**（用户 2026-10-24 指定）。 */
private fun greeting(): String = when (LocalTime.now().hour) {
    in 5..10 -> "早上好🤡"
    in 11..12 -> "中午好🤡"
    in 13..17 -> "下午好🤡"
    in 18..22 -> "晚上好🤡"
    else -> "夜猫子好😋"
}

/** 抽屉里那一列。拖动与动画两条路径共用同一份，免得两处慢慢长歪。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DrawerPanel(
    rows: List<ConversationRow>,
    currentId: String?,
    hint: String?,
    onHint: (String) -> Unit,
    activeAssistant: AssistantEntry,
    assistants: List<AssistantEntry>,
    onOpenAssistantMenu: () -> Unit,
    onOpenConversation: (ConversationRow) -> Unit,
    onNewConversation: () -> Unit,
    /**
     * 能不能"从边缘拖出来"。
     *
     * 子页面（助手页、设置页…）上不该能拖出抽屉：那会让用户以为自己在另一层里滑，
     * 也让这些页面的返回手势和抽屉手势打架（2026-10-28 用户报的"这页为什么能划出侧边栏"）。
     * 抽屉打开状态本身仍由外层控制——聊天页用得上。
     */
    canOpenByDrag: Boolean = true,
    onMoveConversation: (ConversationRow, String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    var searchAll by remember { mutableStateOf(false) }
    // 长按某一行弹出的"移动到其他助手"（D174）。存这一行本身，不存索引——
    // 沙盒页面刷新之后索引会指到别的会话上去。
    var moveTarget by remember { mutableStateOf<ConversationRow?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        // —— 顶部：用户块（头像 + 名字上排 / 问候语下排）——
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 44.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DrawerAvatar(label = "我", size = 40.dp, mine = true)
            Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                Text(
                    text = USER_NAME,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    modifier = Modifier.clickable { onHint("编辑用户名字/头像待接：要头像存储与编辑对话框（D140）") },
                )
                Text(
                    text = greeting(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }

        // —— 搜索：两态，在框上向右滑切换 ——
        SearchPill(
            searchAll = searchAll,
            onSwitchToAll = { searchAll = true },
            onHint = { onHint("搜索待接：storage 还没有 search 动词（P4 / P10）") },
        )

        // —— 会话列表（新对话在最上面）——
        LazyColumn(modifier = Modifier.weight(1f)) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNewConversation() }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = ActionIcons.Plus,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 10.dp).size(18.dp),
                    )
                    Text(
                        text = "新对话",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "草稿",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (rows.isEmpty()) {
                item {
                    Text(
                        text = "还没有会话",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }

            items(rows) { row ->
                // 当前行按 **id** 高亮，不按标题：两个会话重名是常事。
                val current = currentId != null && row.id == currentId
                Surface(
                    color = if (current) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        Color.Transparent
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 1.dp)
                        // 点 = 打开；长按 = 这一条会话的更多动作（现在是"移动到其他助手"）。
                        // 长按比每行挂一个"更多"图标省地方，也和消息区的长按选择同一个手势语言。
                        .combinedClickable(
                            onClick = { onOpenConversation(row) },
                            onLongClick = { moveTarget = row },
                        ),
                ) {
                    Text(
                        text = row.title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (current) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                    )
                }
            }
        }

        // —— 长按一条会话：移动到其他助手（D174）——
        // 只列**别的**助手：列当前那个，点下去等于什么都没发生。
        moveTarget?.let { target ->
            val others = assistants.filter { it.id != activeAssistant.id }
            // 只有一个助手时给一句实话，而不是一个空面板：这条动作现在做不了，
            // 缺的是"另一个助手"，不是这个功能坏了。
            val items = if (others.isEmpty()) {
                listOf(SheetItem(label = "还没有别的助手：先用「新建助手」建一个"))
            } else {
                others.map { entry ->
                    SheetItem(
                        label = entry.name,
                        avatar = entry.name.take(1).ifBlank { "助" },
                        onClick = {
                            moveTarget = null
                            onMoveConversation(target, entry.id)
                        },
                    )
                }
            }
            MenuSheet(
                title = "把「${target.title}」移到",
                items = items,
                onHint = onHint,
                onDismiss = { moveTarget = null },
            )
        }

        // —— 提示行（点搜索/助手头像这类"还没接"的地方，原因写在这里）——
        val currentHint = hint
        if (currentHint != null) {
            Text(
                text = currentHint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        // —— 底部：助手切换 + 设置 ——
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 点头像 = 助手设置（待接）；点名字/箭头 = 助手列表。
            Row(
                modifier = Modifier
                    .clickable { onOpenAssistantMenu() }
                    .padding(end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                    Box(
                        modifier = Modifier.clickable {
                            onHint("改助手的名字要等助手页（P3.6）；现在能在聊天页顶部换模型")
                        },
                    ) {
                        DrawerAvatar(
                            label = activeAssistant.name.take(1).ifBlank { "助" },
                            size = 30.dp,
                            mine = false,
                        )
                    }
                    Text(
                        text = activeAssistant.name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        modifier = Modifier.padding(start = 10.dp),
                    )
                Icon(
                    imageVector = ActionIcons.ChevronDown,
                    contentDescription = "切换助手",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp).size(18.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            // 设置图标是**自绘**的齿轮（D139），不用参考图里那个。
            IconActionButton(
                image = ActionIcons.GearSelf,
                description = "设置",
                onClick = onOpenSettings,
                compact = true,
            )
        }
    }
}

/**
 * 搜索框：两态（搜索当前助手 / 搜索全部会话）。
 *
 * - 左图标跟着状态换：当前助手 = 细长尾巴的搜索（`SearchAssistant`），全部会话 = 数据库（`Database`），
 *   两个都是自绘的（D139）。
 * - 在框上**向右滑**切到"全部会话"；左滑切回来。点框本身给出"搜索还没接"的原因。
 */
@Composable
private fun SearchPill(searchAll: Boolean, onSwitchToAll: () -> Unit, onHint: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .pointerInput(searchAll) {
                var acc = 0f
                detectHorizontalDragGestures(
                    onDragStart = { acc = 0f },
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        acc += amount
                        if (acc > 44.dp.toPx()) {
                            acc = 0f
                            if (!searchAll) onSwitchToAll()
                        } else if (acc < -44.dp.toPx()) {
                            acc = 0f
                        }
                    },
                )
            }
            .clickable { onHint() },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (searchAll) ActionIcons.Database else ActionIcons.SearchAssistant,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = if (searchAll) "搜索全部会话" else "搜索当前助手",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 10.dp).weight(1f),
                maxLines = 1,
            )
        }
    }
}

/**
 * 头像占位：首字母/首字圆标。
 *
 * 真头像要等 D140 那套存储（内容哈希去重 + 引用计数）落地；现在用圆标是**诚实的占位**，
 * 它明确地不是一张脸，也不会假装自己是谁。
 */
@Composable
private fun DrawerAvatar(label: String, size: Dp, mine: Boolean) {
    Surface(
        shape = CircleShape,
        color = if (mine) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        modifier = Modifier.size(size),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (mine) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
            )
        }
    }
}