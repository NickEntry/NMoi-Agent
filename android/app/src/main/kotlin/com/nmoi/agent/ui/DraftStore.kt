package com.nmoi.agent.ui

import android.content.Context

/**
 * 每个会话的输入框草稿（0.15b）。
 *
 * 为什么**不进库**：草稿是"还没打算留下的东西"。落进 SQLite 就多一个要校验、要同步、
 * 要出现在导出里的实体，而它随时会被一句话覆盖掉。SharedPreferences 正好够用。
 *
 * 空白会话（`sessionId == null`）也有草稿：用户可能正写到一半就去翻了别的会话，
 * 回来时那半句应该还在。它存在 [NEW_DRAFT_KEY] 下，建了会话之后由 `ChatScreen` 迁移。
 */
internal object DraftStore {
    private const val FILE = "nmoi_drafts"

    /** 还没有会话 id 时用的桶（新会话位）。 */
    const val NEW_DRAFT_KEY = "__new__"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(context: Context, key: String): String =
        prefs(context).getString(key, "").orEmpty()

    fun save(context: Context, key: String, text: String) {
        val editor = prefs(context).edit()
        // 空草稿直接删键：抽屉里那一行"草稿"标记靠"键在不在、内容空不空"判断，
        // 留一堆空串会让它一直亮着。
        if (text.isBlank()) editor.remove(key) else editor.putString(key, text)
        editor.apply()
    }

    /** 所有非空草稿：`会话 id → 文字`。抽屉打开时读一次就够。 */
    fun all(context: Context): Map<String, String> =
        prefs(context).all.entries.mapNotNull { (key, value) ->
            val text = value as? String
            if (text.isNullOrBlank()) null else key to text
        }.toMap()

    /** 会话建出来了：把"新会话位"的草稿挪到真 id 上，别让用户在半句里等着。 */
    fun migrateNewTo(context: Context, sessionId: String) {
        val text = load(context, NEW_DRAFT_KEY)
        if (text.isBlank()) return
        save(context, sessionId, text)
        save(context, NEW_DRAFT_KEY, "")
    }
}
