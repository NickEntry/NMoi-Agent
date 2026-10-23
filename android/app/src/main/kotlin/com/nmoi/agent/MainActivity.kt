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
import com.nmoi.agent.ui.ChatScreen
import com.nmoi.agent.ui.ConversationListScreen
import com.nmoi.agent.ui.ConversationDrawer
import com.nmoi.agent.ui.ConversationRepository
import com.nmoi.agent.ui.ConversationSnapshot
import com.nmoi.agent.ui.LogChannelScreen
import com.nmoi.agent.ui.LogKind
import com.nmoi.agent.ui.LogListScreen
import com.nmoi.agent.ui.LogSettingsScreen
import com.nmoi.agent.ui.NLog
import com.nmoi.agent.ui.NativeBridge
import com.nmoi.agent.ui.PromptsScreen
import com.nmoi.agent.ui.SettingsScreen
import com.nmoi.agent.ui.theme.NMoiTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 三个页面，够用就行。导航还不值得引 Navigation-Compose：多一个依赖只为 3 个页面不值。 */
private sealed interface Route {
    data object Conversations : Route

    /** 只带标题，等 load_units 接上再带会话 id。 */
    data class Chat(val title: String) : Route

    data object Settings : Route

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
        // alias 启动时 componentName 是那个 alias，不是 MainActivity。
        // 这条日志同时回答两件事：入口是不是别名、现在生效的是哪一个图标。
        NLog.i("App", "启动入口：$componentName")

        setContent {
            NMoiTheme {
                val context = LocalContext.current
                // 路由栈：返回 = 回上一级。子页面以后（提示词编辑、模型详情…）同样只需 push，
                // 不用每页各写一个返回逻辑。
                var backStack by remember { mutableStateOf(listOf<Route>(Route.Conversations)) }
                val route = backStack.last()
                var iconChoice by remember { mutableStateOf(AppIconSwitcher.load(context)) }
                var iconWarning by remember { mutableStateOf<String?>(null) }
                // 这个开关现在只管「调试日志」这一路；另外两路在日志设置页里各自开关。
                var logEnabled by remember { mutableStateOf(NLog.isEnabled(LogKind.Debug)) }
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
                        backStack = listOf(Route.Conversations, Route.Chat(picked))
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
                        onOpenConversation = { title -> backStack = backStack + Route.Chat(title) },
                    )

                    is Route.Chat -> ChatScreen(
                        title = route.title,
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

                    Route.Settings -> SettingsScreen(
                        choice = iconChoice,
                        warning = iconWarning,
                        nativeStatus = nativeStatus,
                        logEnabled = logEnabled,
                        onPick = { picked ->
                            if (AppIconSwitcher.apply(context, picked)) {
                                iconChoice = picked
                                iconWarning = null
                            } else {
                                iconWarning = "切换没生效，详情看 logcat（tag: NMoiIcon）"
                            }
                        },
                        onLogToggle = { enabled ->
                            logEnabled = enabled
                            NLog.setEnabled(context, LogKind.Debug, enabled)
                            // 打开的那一刻先写一条，证明开关真的生效了；关的时候什么都不打。
                            NLog.i("Settings", "调试日志已开启")
                        },
                        onOpenLogs = { backStack = backStack + Route.Logs },
                        onOpenPrompts = { backStack = backStack + Route.Prompts },
                        onBack = { backStack = backStack.dropLast(1) },
                    )
                }
                }
            }
        }
    }
}
