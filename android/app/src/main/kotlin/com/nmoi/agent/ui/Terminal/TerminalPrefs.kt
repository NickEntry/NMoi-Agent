package com.nmoi.agent.ui.Terminal

import android.content.Context

/**
 * 终端的历史与偏好（D395 / D397）：**只存本机、不进备份**（计划稿 §4）。
 *
 * 命令历史里可能有用户打在命令行上的 token，所以它和"显示隐藏文件"那一类
 * 一样属于"这台设备上怎么摆"，跟着库搬到另一台设备没有意义。
 */
internal object TerminalPrefs {
    private const val PREFS = "nmoi.local"
    private const val KEY_HISTORY = "terminal.history"
    private const val KEY_LAYOUT = "terminal.layout"
    private const val KEY_COLUMNS = "terminal.columns"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun history(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_HISTORY, "") ?: ""
        if (raw.isEmpty()) return emptyList()
        // 一行一条。命令本身可能含换行（多行脚本），所以用 `\u0000` 分隔而不是换行——
        // 那个字符在 shell 命令里不可能出现（core 那边会直接拒）。
        return raw.split('\u0000').filter { it.isNotBlank() }
    }

    fun setHistory(context: Context, history: List<String>) {
        prefs(context).edit()
            .putString(KEY_HISTORY, history.takeLast(ShellRules.HISTORY_MAX).joinToString("\u0000"))
            .apply()
    }

    /**
     * 按键布局（D397）：`ESC,TAB,CTRL,ALT,-,UP,ENTER|INS,END,SHIFT,:,LEFT,DOWN,RIGHT`。
     *
     * 用枚举名而不是 label：label 是给人看的，改一次文案就会把用户的布局读坏。
     */
    fun layout(context: Context): Pair<List<List<TerminalKey>>, Int> {
        val columns = prefs(context)
            .getInt(KEY_COLUMNS, TerminalKeys.DEFAULT_COLUMNS)
            .coerceIn(TerminalKeys.MIN_COLUMNS, TerminalKeys.MAX_COLUMNS)
        val raw = prefs(context).getString(KEY_LAYOUT, null) ?: return TerminalKeys.DEFAULT_ROWS to columns
        val rows = raw.split('|').map { line ->
            line.split(',').mapNotNull { name ->
                TerminalKeys.ALL.firstOrNull { it.name == name.trim() }
            }
        }
        return TerminalKeys.sanitize(rows, columns)
    }

    fun setLayout(context: Context, rows: List<List<TerminalKey>>, columns: Int) {
        val (clean, fixedColumns) = TerminalKeys.sanitize(rows, columns)
        prefs(context).edit()
            .putString(KEY_LAYOUT, clean.joinToString("|") { row -> row.joinToString(",") { it.name } })
            .putInt(KEY_COLUMNS, fixedColumns)
            .apply()
    }
}
