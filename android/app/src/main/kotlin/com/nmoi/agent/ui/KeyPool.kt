package com.nmoi.agent.ui

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一个供应商的一组 API Key（D302）。
 *
 * 与单 Key 是**两份数据**：单 Key 还是 `vault/provider-<id>.bin`，池在 `vault/keys-<id>.bin`。
 * 切换多 Key 开关不会把任何一边的内容搬到另一边——那是两套账号，混着用会花错人的钱。
 *
 * 轮询位置只存在内存里：那是"这次轮到谁"，不是数据，每发一条消息写一次加密文件不值。
 */
internal object KeyPool {
    const val OK = "ok"
    const val ERROR = "error"
    const val ROUND_ROBIN = "round_robin"
    const val FIXED = "fixed"

    data class Key(
        val id: String,
        val value: String,
        val label: String = "",
        val enabled: Boolean = true,
        val favorite: Boolean = false,
        val state: String = OK,
    ) {
        val masked: String get() = maskSecret(value)
    }

    data class Pool(val strategy: String = ROUND_ROBIN, val keys: List<Key> = emptyList()) {
        val okCount: Int get() = keys.count { it.state == OK }
        val errorCount: Int get() = keys.count { it.state == ERROR }
    }

    private val cursors = HashMap<String, Int>()

    fun load(context: Context, providerId: String): Pool {
        val text = Vault.loadPool(context, providerId) ?: return Pool()
        return runCatching { decode(text) }.getOrElse {
            NLog.w("KeyPool", "多 Key 池读不出来（${it.javaClass.simpleName}），当成空的")
            Pool()
        }
    }

    fun save(context: Context, providerId: String, pool: Pool): Result<Unit> =
        Vault.savePool(context, providerId, encode(pool)).onFailure {
            NLog.w("KeyPool", "多 Key 池写不进去：${it.message}")
        }

    fun newId(): String = "k" + System.currentTimeMillis().toString(36)

    /**
     * 这一次发请求用哪一把。
     *
     * 收藏的优先；全是"错误"时退而用"启用但标了错误"的——否则一条消息都发不出去，
     * 而用户其实只是没去清那两个红标。
     */
    fun pick(context: Context, providerId: String): String? {
        val pool = load(context, providerId)
        val healthy = pool.keys.filter { it.enabled && it.state == OK }
        val usable = healthy.ifEmpty { pool.keys.filter { it.enabled } }
        if (usable.isEmpty()) return null
        val candidates = usable.filter { it.favorite }.ifEmpty { usable }
        if (pool.strategy == FIXED) return candidates.first().value
        val index = (cursors[providerId] ?: 0) % candidates.size
        cursors[providerId] = index + 1
        return candidates[index].value
    }

    fun clear(context: Context, providerId: String) {
        cursors.remove(providerId)
        Vault.clearPool(context, providerId)
    }

    fun encode(pool: Pool): String = JSONObject()
        .put("strategy", pool.strategy)
        .put(
            "keys",
            JSONArray().apply {
                pool.keys.forEach { key ->
                    put(
                        JSONObject()
                            .put("id", key.id)
                            .put("value", key.value)
                            .put("label", key.label)
                            .put("enabled", key.enabled)
                            .put("favorite", key.favorite)
                            .put("state", key.state),
                    )
                }
            },
        )
        .toString()

    fun decode(text: String): Pool {
        val root = JSONObject(text)
        val rows = root.optJSONArray("keys") ?: JSONArray()
        val keys = ArrayList<Key>()
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val value = row.optString("value")
            if (value.isBlank()) continue
            keys.add(
                Key(
                    id = row.optString("id").ifBlank { "k${index + 1}" },
                    value = value,
                    label = row.optString("label"),
                    enabled = row.optBoolean("enabled", true),
                    favorite = row.optBoolean("favorite", false),
                    state = if (row.optString("state") == ERROR) ERROR else OK,
                ),
            )
        }
        return Pool(
            strategy = root.optString("strategy").ifBlank { ROUND_ROBIN },
            keys = keys,
        )
    }
}
