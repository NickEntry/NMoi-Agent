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
    /**
     * 选中一把。返回 `id` 是为了**请求失败之后能标这一把**（D315）——
     * 只给 `value` 的话，401 回来时界面知道"哪一串钥匙不行"却不知道是哪一行。
     */
    data class Picked(val id: String, val value: String)

    fun pick(context: Context, providerId: String): Picked? {
        val pool = load(context, providerId)
        val healthy = pool.keys.filter { it.enabled && it.state == OK }
        val usable = healthy.ifEmpty { pool.keys.filter { it.enabled } }
        if (usable.isEmpty()) return null
        val candidates = usable.filter { it.favorite }.ifEmpty { usable }
        val chosen = if (pool.strategy == FIXED) {
            candidates.first()
        } else {
            val index = (cursors[providerId] ?: 0) % candidates.size
            cursors[providerId] = index + 1
            candidates[index]
        }
        return Picked(chosen.id, chosen.value)
    }

    /**
     * 这一把不行了（D315）：上游回 401/403 时调。
     *
     * 只动这一把——池里别的钥匙没问题。标上之后 `pick` 会先跳过它，
     * 所以下一轮自然换一把用；全标红了才会退而求其次（那条老规矩不变）。
     *
     * 返回是否真的改了：同一个错标两次不重复写盘。
     */
    fun markError(context: Context, providerId: String, keyId: String): Boolean {
        val pool = load(context, providerId)
        val row = pool.keys.firstOrNull { it.id == keyId } ?: return false
        if (row.state == ERROR) return false
        val next = pool.copy(
            keys = pool.keys.map { key -> if (key.id == keyId) key.copy(state = ERROR) else key },
        )
        save(context, providerId, next).onFailure {
            NLog.w("KeyPool", "标错误失败：${it.message}")
        }
        return true
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
