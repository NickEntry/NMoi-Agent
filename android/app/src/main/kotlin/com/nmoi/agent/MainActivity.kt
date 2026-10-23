package com.nmoi.agent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.nmoi.agent.ui.AppIconSwitcher
import com.nmoi.agent.ui.AppearanceScreen
import com.nmoi.agent.ui.DebugScreen
import com.nmoi.agent.ui.ChatScreen
import com.nmoi.agent.ui.ConversationListScreen
import com.nmoi.agent.ui.ConversationDrawer
import com.nmoi.agent.ui.ConversationRepository
import com.nmoi.agent.ui.ConversationSnapshot
import com.nmoi.agent.ui.GroupScreen
import com.nmoi.agent.ui.LogChannelScreen
import com.nmoi.agent.ui.LogKind
import com.nmoi.agent.ui.LogListScreen
import com.nmoi.agent.ui.LogSettingsScreen
import com.nmoi.agent.ui.NLog
import com.nmoi.agent.ui.NativeBridge
import com.nmoi.agent.ui.PromptsScreen
import com.nmoi.agent.ui.SettingsGroup
import com.nmoi.agent.ui.SettingsIndexScreen
import com.nmoi.agent.ui.theme.NMoiTheme
import com.nmoi.agent.ui.theme.SystemBarsForTheme
import com.nmoi.agent.ui.theme.ThemeStore
import com.nmoi.agent.ui.theme.applySystemBars
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 三个页面，够用就行。导航还不值得引 Navigation-Compose：多一个依赖只为 3 个页面不值。 */
private sealed interface Route {
    data object Conversations : Route

    /** 标题给顶栏，`sessionId` 给 `load_units`；占位数据没有 id，所以可空。 */
    data class Chat(val title: String, val sessionId: String?) : Route

    /** 设置索引页：六行分组入口，本身不摆开关。 */
    data object Settings : Route

    /** 设置的分组子页（外观 / 调试 各自有自己的实现，其余走 GroupScreen）。 */
    data class SettingsGroupRoute(val group: SettingsGroup) : Route

    /** 提示词只读页。以后编辑页同样从设置 push 进来。 */
    data object Prompts : Route

    /** 日志索引页：三种通道各一行，右上角齿轮进设置。 */
    data object Logs : Route

    /** 单个日志通道的子页：查看 / 复制 / 导出 / 清除。 */
    data class LogChannel(val kind: LogKind) : Route

    /** 日志设置页：三种通道各自的开关与上限。 */
    data object LogSettings : Route
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 日志开关必须在**第一行日志之前**就读到，所以放在最前。
        NLog.init(this)
        // 窗口底色 + 状态栏底色 + 系统栏图标深浅：这三件必须在第一帧之前一次做完，
        // 否则冷启动会先闪一帧错色（浅色主题闪黑、深色主题闪白图标）。
        applySystemBars(window, dark = ThemeStore.isDarkNow(this))
        // alias 启动时 componentName 是那个 alias，不是 MainActivity。
        // 这条日志同时回答两件事：入口是不是别名、现在生效的是哪一个图标。
        NLog.i("App", "启动入口：$componentName")

        setContent {
            val context = LocalContext.current
            // 主题必须在第一帧之前定：跟随系统时要读当下的系统配置（用户切系统主题会触发重组）。
            var themeMode by remember { mutableStateOf(ThemeStore.load(context)) }
            val dark = ThemeStore.isDark(themeMode)
            // 主题运行中切换时，系统栏图标要跟着改（浅底配深图标）。
            SystemBarsForTheme(dark)
            NMoiTheme(dark = dark) {
                // 路由栈：返回 = 回上一级。子页面以后（提示词编辑、模型详情…）同样只需 push，
                // 不用每页各写一个返回逻辑。
                var backStack by remember { mutableStateOf(listOf<Route>(Route.Conversations)) }
                val route = backStack.last()
                var iconChoice by remember { mutableStateOf(AppIconSwitcher.load(context)) }
                var iconWarning by remember { mutableStateOf<String?>(null) }
                // 日志摘要给设置索引页那一行用；三种通道各自的开关在「日志 → 齿轮」里。
                val logSummary = "3 通道 · 调试" + if (NLog.isEnabled(LogKind.Debug)) "开" else "关"
                // 抽屉挂在最外层：任何页面都能滑出来，而不是只有聊天页有。
                var drawerOpen by remember { mutableStateOf(false) }

                val nativeStatus = remember { NativeBridge.status() }
                // 开库和列表读盘都碰磁盘，不许在主线程上做。
                val snapshot by produceState<ConversationSnapshot?>(initialValue = null) {
                    value = withContext(Dispatchers.IO) {
                        ConversationRepository.load(File(filesDir, "nmoi.db").absolutePath)
                    }
                }

                // 只在栈深 > 1 时接管返回键：根页面按返回应当是退出应用，
                // 而不是被我们拦下来变成"什么都不发生"。全局只注册这一处，子页面不各自注册。
                BackHandler(enabled = backStack.size > 1) {
                    backStack = backStack.dropLast(1)
                }
                // 后注册者优先：抽屉开着时，返回先关抽屉，而不是直接退页面。
                BackHandler(enabled = drawerOpen) { drawerOpen = false }

                ConversationDrawer(
                    open = drawerOpen,
                    rows = snapshot?.rows.orEmpty(),
                    currentTitle = (route as? Route.Chat)?.title,
                    onOpen = { drawerOpen = true },
                    onOpenConversation = { picked ->
                        drawerOpen = false
                        backStack = listOf(
                            Route.Conversations,
                            Route.Chat(picked.title, picked.id.ifEmpty { null }),
                        )
                    },
                    onOpenSettings = {
                        drawerOpen = false
                        backStack = backStack + Route.Settings
                    },
                    onClose = { drawerOpen = false },
                ) {
                when (route) {
                    Route.Conversations -> ConversationListScreen(
                        snapshot = snapshot,
                        nativeStatus = nativeStatus,
                        onOpenDrawer = { drawerOpen = true },
                        onOpenConversation = { row ->
                            backStack = backStack + Route.Chat(row.title, row.id.ifEmpty { null })
                        },
                    )

                    is Route.Chat -> ChatScreen(
                        title = route.title,
                        sessionId = route.sessionId,
                        onBack = { backStack = backStack.dropLast(1) },
                        onOpenDrawer = { drawerOpen = true },
                    )

                    Route.Logs -> LogListScreen(
                        onOpen = { kind -> backStack = backStack + Route.LogChannel(kind) },
                        onOpenSettings = { backStack = backStack + Route.LogSettings },
                        onBack = { backStack = backStack.dropLast(1) },
                    )

                    is Route.LogChannel -> LogChannelScreen(
                        kind = route.kind,
                        onBack = { backStack = backStack.dropLast(1) },
                    )

                    Route.LogSettings -> LogSettingsScreen(
                        onBack = { backStack = backStack.dropLast(1) },
                    )

                    Route.Prompts -> PromptsScreen(
                        onBack = { backStack = backStack.dropLast(1) },
                    )

                    Route.Settings -> SettingsIndexScreen(
                        iconSummary = "${iconChoice.design.label} · ${iconChoice.plate.label} · ${themeMode.label}",
                        logSummary = logSummary,
                        onOpen = { group -> backStack = backStack + Route.SettingsGroupRoute(group) },
                        onBack = { backStack = backStack.dropLast(1) },
                    )

                    is Route.SettingsGroupRoute -> when (val group = route.group) {
                        // 外观与调试已有真实现，其余四组先给"还没接线"的清单。
                        SettingsGroup.Appearance -> AppearanceScreen(
                            choice = iconChoice,
                            warning = iconWarning,
                            themeMode = themeMode,
                            onThemePick = { mode ->
                                ThemeStore.save(context, mode)
                                themeMode = mode
                                NLog.i("Appearance", "主题改成 ${mode.label}")
                            },
                            onPick = { picked ->
                                if (AppIconSwitcher.apply(context, picked)) {
                                    iconChoice = picked
                                    iconWarning = null
                                } else {
                                    iconWarning = "切换没生效，详情看 logcat（tag: NMoiIcon）"
                                }
                            },
                            onBack = { backStack = backStack.dropLast(1) },
                        )

                        SettingsGroup.Debug -> DebugScreen(
                            logSummary = logSummary,
                            nativeStatus = nativeStatus,
                            onOpenLogs = { backStack = backStack + Route.Logs },
                            onBack = { backStack = backStack.dropLast(1) },
                        )

                        else -> GroupScreen(
                            group = group,
                            onOpenPrompts = { backStack = backStack + Route.Prompts },
                            onBack = { backStack = backStack.dropLast(1) },
                        )
                    }

                }
                }
            }
        }
    }
}
