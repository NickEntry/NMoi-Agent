package com.nmoi.agent.ui.Terminal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 一条终端会话（D399）。
 *
 * 用户 2026-11-24 的要求：**返回不销毁终端**；再点进来时如果已经有会话，
 * 就打开已有的那个，不新建；页面里能管多个会话（长按重命名 / 关闭）。
 *
 * 所以会话活在一个**进程级**对象里，不跟着页面走：页面重建、返回上一级、
 * 甚至切到别的页面再回来，`lines` 都还在。只有"关掉这个会话"才清。
 */
internal data class TerminalSession(
    /** 稳定的 id（`t:<序号>`）。**改名字不改 id**——命令的登记按 id 走。 */
    val id: String,
    var title: String,
    val workspaceId: String,
    val workspacePath: String,
    val label: String,
    /** 已经画出来的行。跨页面保留，所以它在会话里而不是页面里。 */
    val lines: MutableList<TerminalLine>,
    /** 历史（上下键）。 */
    val history: MutableList<String>,
    /** 现在有没有一条命令在跑。 */
    var busy: Boolean,
    /** 上一个命令用的档位（切档位时更新）。 */
    var shellLabel: String,
)

/**
 * 终端会话表（D399）：进程级，页面只读它。
 *
 * 为什么不做成"页面 remember"：用户明确说"返回不会销毁终端"。`remember` 跟着组合走，
 * 一返回就没了——那正是他不想要的行为。
 *
 * 为什么不做成持久化到盘上：命令历史里有 token（计划稿 §4 已经定了"不进备份"），
 * 而进程被杀之后"会话还在"也没有意义（那些子进程早没了）。
 */
internal object TerminalSessions {
    private var next = 1

    private val _sessions = mutableStateMapOf<String, TerminalSession>()
    val sessions: List<TerminalSession> get() = _sessions.values.toList()

    /** 当前选中的那一个（返回再进来打开的就是它）。 */
    var activeId by mutableStateOf<String?>(null)
        private set

    fun active(): TerminalSession? = activeId?.let { _sessions[it] }

    fun select(id: String) {
        if (_sessions.containsKey(id)) activeId = id
    }

    /**
     * 开一个新会话，并选中它。
     *
     * `label` 是这一档的名字（`系统 shell` / `容器（…）`），只用于显示与"切档"。
     */
    fun create(
        workspaceId: String,
        workspacePath: String,
        label: String,
        shellLabel: String,
    ): TerminalSession {
        val id = "t:${next++}"
        val session = TerminalSession(
            id = id,
            title = defaultTitle(_sessions.values.map { it.title }),
            workspaceId = workspaceId,
            workspacePath = workspacePath,
            label = label,
            lines = mutableListOf(),
            history = mutableListOf(),
            busy = false,
            shellLabel = shellLabel,
        )
        _sessions[id] = session
        activeId = id
        return session
    }

    /**
     * 进终端时该打开哪一个（D399）：**有就复用，没有才新建**。
     *
     * 判据是"同一个工作区"。为什么按工作区而不是"随便打开一个"：
     * 终端里的 `cd` 不会持久（回显式，一条命令一个进程），所以"起点"就是工作区，
     * 同一个工作区的两条会话在行为上几乎一样，开第二条只会让人以为丢了一条。
     */
    fun openOrCreate(
        workspaceId: String,
        workspacePath: String,
        label: String,
        shellLabel: String,
    ): TerminalSession {
        val existing = _sessions.values.firstOrNull { it.workspaceId == workspaceId }
        if (existing != null) {
            activeId = existing.id
            return existing
        }
        return create(workspaceId, workspacePath, label, shellLabel)
    }

    fun rename(id: String, title: String) {
        val session = _sessions[id] ?: return
        val cleaned = title.trim().take(40)
        if (cleaned.isEmpty()) return
        session.title = cleaned
    }

    /**
     * 关掉一个会话。**先停掉它正在跑的命令**再删：不然那条命令会继续跑，
     * 而界面上已经找不到它了（"存了但看不见"）。
     */
    fun close(id: String) {
        val session = _sessions.remove(id) ?: return
        if (session.busy) {
            // 只置标志，杀进程由 core 的等待循环做（与界面那一侧同一个分工）。
            com.nmoi.agent.ui.NativeBridge.terminalCancelJson("{\"session\":\"$id\"}")
        }
        if (activeId == id) {
            activeId = _sessions.keys.lastOrNull()
        }
    }

    /** 退出所有会话（通知里那颗「退出」用的，D400）。返回关掉几个。 */
    fun closeAll(): Int {
        val ids = _sessions.keys.toList()
        for (id in ids) close(id)
        return ids.size
    }

    /** 有几个在跑（通知上那一行「N 个会话」用）。 */
    fun runningCount(): Int = _sessions.values.count { it.busy }

    /** `Default` / `Default 2` / `Default 3`：与 Termux 那种命名一致。 */
    private fun defaultTitle(taken: List<String>): String {
        if (taken.isEmpty()) return "Default"
        var index = 2
        while (taken.contains("Default $index")) index += 1
        return "Default $index"
    }
}
