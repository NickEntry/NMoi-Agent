package com.nmoi.agent.ui.Terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nmoi.agent.ui.ActionIcons
import com.nmoi.agent.ui.BackButton
import com.nmoi.agent.ui.MenuSheet
import com.nmoi.agent.ui.SheetItem
import kotlinx.coroutines.launch
import java.io.File

/**
 * 终端（D395）：**命令回显式**，不是真 PTY。
 *
 * 一行一条命令、输出追加到滚动区、提示符自己画。能跑脚本与构建，
 * 跑不了 `vim` / `top` 这类全屏程序——那一类在按下之前就拦下来并说清替代办法，
 * 而不是让它卡住屏（见 `ShellRules`）。
 *
 * 形状照计划稿 §3.3：顶栏（返回 + 工作区名 + `⋯`）→ 来源胶囊 → 主体 → 底部修饰键。
 */
@Composable
internal fun TerminalScreen(
    workspaceRoot: File,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // 历史与偏好只存本机、不进备份（计划稿 §4）。
    var session by remember { mutableStateOf(TerminalPrefs.lastSession(context) ?: ShellSessions.default()) }
    val history = remember { mutableStateListOf<String>().apply { addAll(TerminalPrefs.history(context)) } }
    var historyIndex by remember { mutableStateOf(-1) }
    val lines = remember { mutableStateListOf<TerminalLine>() }
    var draft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var switchSheet by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        lines += TerminalLine.Prompt(session.label, workspaceRoot.absolutePath)
    }

    fun submit() {
        val command = draft.trim()
        if (command.isEmpty() || busy) return
        val blocked = ShellRules.blockedReason(command)
        if (blocked != null) {
            // 拦下来要说清原因与替代办法，且**不写进历史**——它没跑过。
            lines += TerminalLine.Command(command)
            lines += TerminalLine.Notice(blocked)
            draft = ""
            return
        }
        lines += TerminalLine.Command(command)
        draft = ""
        historyIndex = -1
        val next = ShellRules.pushHistory(history.toList(), command)
        history.clear()
        history.addAll(next)
        TerminalPrefs.setHistory(context, next)
        busy = true
        scope.launch {
            session.run(command, workspaceRoot.absolutePath).fold(
                onSuccess = { result ->
                    if (result.stdout.isNotEmpty()) lines += TerminalLine.Output(result.stdout.trimEnd('\n'))
                    if (result.stderr.isNotEmpty()) lines += TerminalLine.Error(result.stderr.trimEnd('\n'))
                    if (result.isEmpty) lines += TerminalLine.Notice("（没有输出）")
                    lines += TerminalLine.Notice(result.summary())
                    // 改了的文件要说出来（铁律②：不能有"存了但看不见"的东西）。
                    // 终端这一层不画卡片，只在这里列一行——变更卡片是助手消息那边的事。
                    if (result.changes.isNotEmpty()) {
                        lines += TerminalLine.Notice(
                            "改了 ${result.changes.size} 个文件：" + result.changes.joinToString("、"),
                        )
                    }
                },
                onFailure = { lines += TerminalLine.Error(it.message ?: "跑不起来") },
            )
            busy = false
        }
    }

    // 自动滚到底：新行进来时跟到底。用户往上翻的时候不抢——判据是"已经在底部"。
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty() && !listState.canScrollForward) {
            listState.animateScrollToItem(lines.lastIndex)
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackButton(onBack = onBack)
                Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(
                        text = "终端",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = workspaceRoot.absolutePath,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                com.nmoi.agent.ui.IconActionButton(
                    image = ActionIcons.Ellipsis,
                    description = "更多",
                    onClick = { switchSheet = true },
                    compact = true,
                )
            }

            // 来源胶囊：一颗，点了弹出三档选择。长按也是同一个面板。
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.clickable { switchSheet = true },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .background(
                                    if (session.available()) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.outline
                                    },
                                    RoundedCornerShape(999.dp),
                                ),
                        )
                        Text(
                            text = session.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(start = 7.dp),
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = if (busy) "跑着…" else "一行一条命令",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val note = notice
            if (note != null) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                items(lines.size) { index ->
                    TerminalRow(lines[index])
                }
            }

            // 底部修饰键：Esc / Tab / Ctrl / Alt / 复制 / 粘贴。
            // 前四个是"下一键组合"的修饰键——第一版没有 PTY，按下它们只往输入框里塞一个
            // 对应字符（`\t` / `\x1b` 进不了输入框），所以这一排先只做复制 / 粘贴与清屏，
            // 其余四颗灰着并写清原因。**不摆点了没反应的键**。
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ModifierKey("Esc", enabled = false) {}
                ModifierKey("Tab", enabled = false) {}
                ModifierKey("Ctrl", enabled = false) {}
                ModifierKey("Alt", enabled = false) {}
                Spacer(Modifier.weight(1f))
                ModifierKey("清屏", enabled = true) {
                    lines.clear()
                    lines += TerminalLine.Prompt(session.label, workspaceRoot.absolutePath)
                }
                ModifierKey("复制", enabled = true) {
                    val text = lines.joinToString("\n") { it.plain() }
                    val clipboard = context
                        .getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("终端", text))
                    notice = "已复制 ${text.length} 个字符"
                }
            }

            // 输入行：`$ ` 前缀 + 单行输入 + 发送。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, bottom = 10.dp, top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "$ ",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                    ),
                    color = MaterialTheme.colorScheme.primary,
                )
                BasicTextField(
                    value = draft,
                    onValueChange = {
                        draft = it
                        historyIndex = -1
                    },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.weight(1f).padding(vertical = 10.dp),
                    decorationBox = { inner ->
                        Box {
                            if (draft.isEmpty()) {
                                Text(
                                    text = if (busy) "等这一条跑完…" else "敲一条命令",
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 13.sp,
                                    ),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            inner()
                        }
                    },
                )
                com.nmoi.agent.ui.IconActionButton(
                    image = ActionIcons.ArrowUp,
                    description = "运行",
                    enabled = draft.isNotBlank() && !busy,
                    onClick = { submit() },
                    compact = true,
                )
            }
        }
    }

    if (switchSheet) {
        MenuSheet(
            title = "用哪个终端",
            items = ShellSessions.all.map { candidate ->
                SheetItem(
                    label = candidate.label,
                    icon = null,
                    value = if (candidate == session) "当前" else null,
                    hint = if (!candidate.available()) candidate.unavailableReason() else null,
                    onClick = if (!candidate.available()) {
                        null
                    } else {
                        {
                            session = candidate
                            TerminalPrefs.setLastSession(context, candidate)
                            lines += TerminalLine.Notice("已切到「${candidate.label}」")
                        }
                    },
                )
            },
            onHint = { notice = it },
            onDismiss = { switchSheet = false },
        )
    }
}

/** 终端里的一行。四种：提示符 / 命令回显 / 输出 / 错误或说明。 */
internal sealed interface TerminalLine {
    data class Prompt(val label: String, val cwd: String) : TerminalLine
    data class Command(val text: String) : TerminalLine
    data class Output(val text: String) : TerminalLine
    data class Error(val text: String) : TerminalLine
    data class Notice(val text: String) : TerminalLine

    /** 复制全部时用的一份纯文本。 */
    fun plain(): String = when (this) {
        is Prompt -> "── $label · $cwd"
        is Command -> "$ $text"
        is Output -> text
        is Error -> text
        is Notice -> text
    }
}

@Composable
private fun TerminalRow(line: TerminalLine) {
    val mono = MaterialTheme.typography.bodySmall.copy(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        lineHeight = 18.sp,
    )
    when (line) {
        is TerminalLine.Prompt -> Text(
            text = "── ${line.label} · ${line.cwd}",
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 4.dp),
        )

        is TerminalLine.Command -> Row(modifier = Modifier.padding(vertical = 1.dp)) {
            Text(
                text = "$ ",
                style = mono,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(text = line.text, style = mono, color = MaterialTheme.colorScheme.onSurface)
        }

        is TerminalLine.Output -> Text(
            text = line.text,
            style = mono,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(vertical = 1.dp),
        )

        is TerminalLine.Error -> Text(
            text = line.text,
            style = mono,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(vertical = 1.dp),
        )

        is TerminalLine.Notice -> Text(
            text = line.text,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 2.dp),
        )
    }
}

/** 底部那一颗修饰键。灰着的时候点不动，也不给提示——它的原因写在计划稿里。 */
@Composable
private fun ModifierKey(label: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .heightIn(min = 32.dp)
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                },
            )
        }
    }
}

/**
 * 终端的历史与偏好（D395）：**只存本机、不进备份**（计划稿 §4）。
 *
 * 命令历史里可能有用户打在命令行上的 token，所以它和"显示隐藏文件"那一类
 * 一样属于"这台设备上怎么摆"，跟着库搬到另一台设备没有意义。
 */
internal object TerminalPrefs {
    private const val PREFS = "nmoi.local"
    private const val KEY_HISTORY = "terminal.history"
    private const val KEY_SESSION = "terminal.session"

    private fun prefs(context: android.content.Context) =
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)

    fun history(context: android.content.Context): List<String> {
        val raw = prefs(context).getString(KEY_HISTORY, "") ?: ""
        if (raw.isEmpty()) return emptyList()
        // 一行一条。命令本身可能含换行（多行脚本），所以用 `\u0000` 分隔而不是换行——
        // 那个字符在 shell 命令里不可能出现（core 那边会直接拒）。
        return raw.split('\u0000').filter { it.isNotBlank() }
    }

    fun setHistory(context: android.content.Context, history: List<String>) {
        prefs(context).edit()
            .putString(KEY_HISTORY, history.takeLast(ShellRules.HISTORY_MAX).joinToString("\u0000"))
            .apply()
    }

    fun lastSession(context: android.content.Context): ShellSession? {
        val label = prefs(context).getString(KEY_SESSION, null) ?: return null
        return ShellSessions.all.firstOrNull { it.label == label }
    }

    fun setLastSession(context: android.content.Context, session: ShellSession) {
        prefs(context).edit().putString(KEY_SESSION, session.label).apply()
    }
}
