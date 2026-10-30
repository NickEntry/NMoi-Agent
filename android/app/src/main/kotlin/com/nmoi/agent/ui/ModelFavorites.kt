package com.nmoi.agent.ui

import org.json.JSONArray
import org.json.JSONObject

/**
 * 模型收藏（0.15x / 模型选择面板）。
 *
 * 存在库里的 `settings(model_favorites)`，key = `<供应商 id>/<模型 id>`，值是 `{}`——
 * "收藏"只有在或不在，时间由 `settings` 行自己的 `updated_at` 记着。
 *
 * 为什么不用 `AppSettings`（SharedPreferences）：它指向的是库里那份模型表
 * （`settings(provider, …)`）。两边分家的话，删掉一个供应商就会在手机上留下一串
 * 指向空气的收藏键；放在一起则跟着库一起导出、一起恢复。
 *
 * **key 只当不透明字符串用**：模型 id 本身就可能带 `/`（`alibaba/qwen-max`），
 * 谁也别去 split 它——要拆开只能从 [ModelChoice] 的那两个字段拿。
 */
internal object ModelFavorites {
    /** 收藏键。拼法是 `供应商/模型`，顺序固定，别在别处再拼一次。 */
    fun key(providerId: String, modelId: String): String = "$providerId/$modelId"

    /**
     * 读一遍收藏，顺手把**指向已经不存在的东西**的键删掉（`alive` = 库里此刻真有的模型）。
     *
     * `alive` 为空时**只读不删**：那说明供应商一条都没读出来（没配过，或者读失败），
     * 这时候"没有活着的键"和"全都该死"看起来一样，删下去就是一次静默的数据丢失。
     *
     * 读失败当空集：收藏只是排序偏好，不该挡住打开面板。
     */
    suspend fun sync(databasePath: String, alive: Set<String>): Set<String> {
        val stored = load(databasePath)
        if (alive.isEmpty() || stored.isEmpty()) return stored
        val kept = stored.filterTo(mutableSetOf()) { it in alive }
        val dead = stored - kept
        if (dead.isNotEmpty()) {
            withStorageHandle(databasePath) { handle ->
                for (key in dead) {
                    NmoiFfi.settingsDelete(handle, SettingsScope.ModelFavorites, key)
                        .onFailure { NLog.w("Model", "清理失效收藏 $key 失败：${it.message}") }
                }
                Result.success(Unit)
            }
            NLog.i("Model", "清理失效收藏 ${dead.size} 个（模型已经不在了）")
        }
        return kept
    }

    /** 全部收藏键。读不出来当空集——理由同上。 */
    suspend fun load(databasePath: String): Set<String> =
        withStorageHandle(databasePath) { handle ->
            NmoiFfi.settingsList(handle, SettingsScope.ModelFavorites).map { envelope ->
                val items = envelope.optJSONArray("items") ?: JSONArray()
                buildSet {
                    for (index in 0 until items.length()) {
                        val item = items.optJSONObject(index) ?: continue
                        val key = item.optString("key", "")
                        if (key.isNotBlank()) add(key)
                    }
                }
            }
        }.getOrElse {
            NLog.w("Model", "读收藏失败：${it.message}")
            emptySet()
        }

    /** 收藏 / 取消收藏。界面先改自己那份（点了要立刻有回应），失败再换回来。 */
    suspend fun set(databasePath: String, key: String, favorite: Boolean): Result<Unit> =
        withStorageHandle(databasePath) { handle ->
            val result = if (favorite) {
                NmoiFfi.settingsPut(handle, SettingsScope.ModelFavorites, key, JSONObject())
            } else {
                NmoiFfi.settingsDelete(handle, SettingsScope.ModelFavorites, key)
            }
            result
                .onSuccess { NLog.i("Model", if (favorite) "收藏 $key" else "取消收藏 $key") }
                .onFailure { NLog.w("Model", "写收藏失败 $key：${it.message}") }
                .map { }
        }
}