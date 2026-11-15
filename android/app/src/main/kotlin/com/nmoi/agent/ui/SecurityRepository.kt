package com.nmoi.agent.ui

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 安全页的那份文件（0.17g）：`files/security.json`。
 *
 * 为什么与世界书同套写法、不进库：这些是"这台设备允不允许"的偏好，
 * 跟着数据走的话，换台机器导入一个 `ask=false` 就成了一句空话（和 `tool.native_shell` 同一个理由）。
 * 不进 schema 也就没有迁移。
 *
 * 旧版把开关放在 SharedPreferences 里（`tool.shell_ask` / `tool.shell_blacklist` / `tool.shell_group.*`）。
 * 第一次读这份文件时把它们搬进来，搬完就删掉那些键——两个来源迟早给出两个答案。
 */
internal object SecurityRepository {
    private const val FILE = "security.json"
    private const val VERSION = 1

    private const val LEGACY_ASK = "tool.shell_ask"
    private const val LEGACY_BLACKLIST = "tool.shell_blacklist"
    private const val LEGACY_GROUP = "tool.shell_group."
    private const val PREFS = "nmoi.local"

    fun file(context: Context): File = File(context.filesDir, FILE)

    fun state(context: Context): SecurityState {
        val target = file(context)
        if (!target.isFile) {
            val migrated = migrate(context)
            save(context, migrated)
            return migrated
        }
        val root = runCatching { JSONObject(target.readText()) }.getOrNull()
            ?: return SecurityState()
        return decode(root)
    }

    fun save(context: Context, state: SecurityState) {
        val root = JSONObject()
            .put("version", VERSION)
            .put("ask", state.ask)
            .put("blacklist", state.blacklist)
            .put("groups", JSONArray().also { array ->
                state.groups.forEach { group ->
                    array.put(
                        JSONObject()
                            .put("id", group.id)
                            .put("name", group.name)
                            .put("builtin", group.builtin)
                            .put("asks", group.asks)
                            .put("hidden", group.hidden)
                            .put("count", group.count)
                            .put("items", JSONArray(group.items)),
                    )
                }
            })
        runCatching {
            val target = file(context)
            val part = File(target.parentFile, "$FILE.part")
            part.writeText(root.toString(2))
            if (!part.renameTo(target)) {
                target.delete()
                part.renameTo(target)
            }
        }.onFailure { NLog.w("Security", "安全设置没写进去：${it.message}") }
    }

    /** 一轮请求要用的那四个值。只读一次文件——四个入口各读一次就是四种答案。 */
    fun guard(context: Context): GuardSettings = guardOf(state(context))

    fun setAsk(context: Context, ask: Boolean) {
        val state = state(context)
        save(context, state.copy(ask = ask))
        NLog.i("Security", "工具执行前询问：${if (ask) "开" else "关"}")
    }

    fun setBlacklist(context: Context, blacklist: Boolean) {
        val state = state(context)
        save(context, state.copy(blacklist = blacklist))
        NLog.i("Security", "黑名单模式：${if (blacklist) "开" else "关"}")
    }

    fun setGroups(context: Context, groups: List<RuleGroup>) {
        val state = state(context)
        save(context, state.copy(groups = groups))
    }

    private fun migrate(context: Context): SecurityState {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val all = prefs.all
        if (all.isEmpty()) return SecurityState()
        val ask = prefs.getBoolean(LEGACY_ASK, true)
        val blacklist = prefs.getBoolean(LEGACY_BLACKLIST, true)
        val groups = all
            .filter { (key, _) -> key.startsWith(LEGACY_GROUP) }
            .map { (key, value) ->
                RuleGroup(
                    id = key.removePrefix(LEGACY_GROUP),
                    name = "",
                    builtin = true,
                    asks = value != false,
                    hidden = false,
                    count = 0,
                    items = emptyList(),
                )
            }
        runCatching {
            val editor = prefs.edit()
            editor.remove(LEGACY_ASK)
            editor.remove(LEGACY_BLACKLIST)
            all.keys.filter { it.startsWith(LEGACY_GROUP) }.forEach { editor.remove(it) }
            editor.apply()
        }
        NLog.i("Security", "旧开关搬到 security.json：${groups.size} 处组设置")
        return SecurityState(ask = ask, blacklist = blacklist, groups = groups)
    }

    private fun decode(root: JSONObject): SecurityState {
        val groups = mutableListOf<RuleGroup>()
        val array = root.optJSONArray("groups")
        if (array != null) {
            for (index in 0 until array.length()) {
                val row = array.optJSONObject(index) ?: continue
                val id = row.text("id").trim()
                if (id.isEmpty()) continue
                val items = mutableListOf<String>()
                val names = row.optJSONArray("items")
                if (names != null) {
                    for (at in 0 until names.length()) {
                        names.optString(at).trim().lowercase()
                            .takeIf { it.isNotEmpty() && !items.contains(it) }
                            ?.let { items.add(it) }
                    }
                }
                groups.add(
                    RuleGroup(
                        id = id,
                        name = row.text("name").trim(),
                        builtin = row.optBoolean("builtin", true),
                        asks = row.optBoolean("asks", true),
                        hidden = row.optBoolean("hidden", false),
                        count = row.optInt("count", 0),
                        items = items,
                    ),
                )
            }
        }
        return SecurityState(
            ask = root.optBoolean("ask", true),
            blacklist = root.optBoolean("blacklist", true),
            groups = groups,
        )
    }
}