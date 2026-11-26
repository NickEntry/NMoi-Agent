package com.nmoi.agent.ui

import android.content.Context
import android.content.SharedPreferences

/**
 * **跟着模型走**的那几项设置（D435）。
 *
 * 用户 2026-11-26 的原话：
 * 「思考强度的设置数据存储跟着模型走，顺带一提模型删除的时候应该一起清掉这样的数据，
 *   然后启动的时候，应该也先加载供应商和模型数据然后检查一遍」
 *
 * # 为什么不再挂在助手上
 *
 * 思考强度描述的是"这个模型能想多深"，不是"这个助手是什么脾气"。挂在助手上，
 * 换一个模型之后那一档会原样搬过去：给一个没有 reasoning 的模型留着 `xhigh`，
 * 界面照着显示"极高"，而请求里那个字段根本不认——看着生效、实际不发，是最难查的一种。
 *
 * # 落在哪
 *
 * `SharedPreferences` 里一张 `thinking.<provider>/<model>` 的小表。不进库是因为它**不是内容**，
 * 是设备的本地偏好（换设备重选一次就好），与 `ThemeStore` / `FilePrefs` 同一档。
 *
 * # 清理
 *
 * 两处：删模型 / 删供应商时当场清（[clearModel] / [clearProvider]），
 * 以及**启动时**拿现存的模型清单对一遍（[prune]）——老版本留下的、或者删模型那次
 * 崩在半个路上的，都在这里收掉。判据是纯函数 [staleKeys]，可以单测。
 */
internal object ModelPrefs {

    private const val FILE = "model_prefs"
    private const val THINKING_PREFIX = "thinking."

    /** 这一项存哪一格：`thinking.<providerId>/<modelId>`。 */
    fun thinkingKey(providerId: String, modelId: String): String =
        "$THINKING_PREFIX${providerId.trim()}/${modelId.trim()}"

    /** 键里那一对 `provider/model`；不是这一类的键给 null（判据里要能跳过别人的键）。 */
    fun modelOf(key: String): String? {
        if (!key.startsWith(THINKING_PREFIX)) return null
        val rest = key.removePrefix(THINKING_PREFIX)
        val provider = rest.substringBefore('/', "")
        val model = rest.substringAfter('/', "")
        // 两半都必须有：`thinking./m1` 与 `thinking.p1/` 都是坏键（写的时候被拦了，
        // 但老版本或手改过的偏好文件里可能有）。坏键不该被当成"某个模型"。
        if (provider.isBlank() || model.isBlank()) return null
        return "$provider/$model"
    }

    /**
     * 哪些键该清：不在 `alive` 里的那些。`alive` 是 `provider/model` 的集合。
     *
     * 纯函数——清理逻辑错起来是"悄悄删掉用户在用的设置"，那比不清理更坏，
     * 所以它不碰 SharedPreferences，只算集合差。
     */
    fun staleKeys(keys: Collection<String>, alive: Set<String>): List<String> =
        keys.mapNotNull { key ->
            val model = modelOf(key) ?: return@mapNotNull null
            key.takeIf { model !in alive }
        }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 这个模型的思考强度；`null` = 没设置过（此时用助手行上那一份，老数据就是这么来的）。 */
    fun thinking(context: Context, providerId: String, modelId: String): String? {
        if (providerId.isBlank() || modelId.isBlank()) return null
        return prefs(context).getString(thinkingKey(providerId, modelId), null)
            ?.takeIf { it.isNotBlank() }
    }

    fun setThinking(context: Context, providerId: String, modelId: String, wire: String) {
        if (providerId.isBlank() || modelId.isBlank()) return
        prefs(context).edit().putString(thinkingKey(providerId, modelId), wire.trim()).apply()
    }

    /**
     * 这一轮实际用的档位：**模型那一份优先**，没有就退回助手行上那一份（`fallback`）。
     *
     * 退回那一条只对老数据有意义（它们没有模型那一格），但它必须留着：
     * 少一个兜底，升级上来的助手会突然从「极高」掉回「自动」。
     *
     * 参数是散着传的，不直接收 `AssistantEntry`：这样这一层不依赖助手仓库，
     * 本机单测能把"优先读哪一格"钉住。
     */
    fun resolveThinking(
        context: Context,
        providerId: String,
        modelId: String,
        fallback: String,
    ): String = thinking(context, providerId, modelId) ?: fallback.ifBlank { "auto" }

    /** 删一个模型：它那一格跟着走。返回是否真删掉了什么。 */
    fun clearModel(context: Context, providerId: String, modelId: String): Boolean {
        if (providerId.isBlank() || modelId.isBlank()) return false
        val key = thinkingKey(providerId, modelId)
        val all = prefs(context)
        if (!all.contains(key)) return false
        all.edit().remove(key).apply()
        return true
    }

    /** 删一个供应商：它底下的模型全都清掉。返回清了几格。 */
    fun clearProvider(context: Context, providerId: String): Int {
        if (providerId.isBlank()) return 0
        val prefix = "$THINKING_PREFIX${providerId.trim()}/"
        val all = prefs(context)
        val doomed = all.all.keys.filter { it.startsWith(prefix) }
        if (doomed.isEmpty()) return 0
        val edit = all.edit()
        doomed.forEach { edit.remove(it) }
        edit.apply()
        return doomed.size
    }

    /**
     * 启动时对一遍账：`alive` 是**现在真的存在**的 `provider/model`。
     *
     * 返回清掉了几格。调用点先把供应商与模型读出来，再调这里——
     * 反过来的话"还没读完"会被当成"一个模型都没有"，那一次启动就会把设置全清掉。
     */
    /** 现在有哪几格（`provider/model` 的集合）。迁移要用它判"已经设过了"。 */
    fun storedTargets(context: Context): Set<String> =
        prefs(context).all.keys.mapNotNull { modelOf(it) }.toSet()

    /** 把一格写进去（迁移用；与 [setThinking] 同一份存储，只是入参是 `provider/model`）。 */
    fun setByTarget(context: Context, target: String, wire: String) {
        val provider = target.substringBefore('/', "")
        val model = target.substringAfter('/', "")
        setThinking(context, provider, model, wire)
    }

    fun prune(context: Context, alive: Set<String>): Int {
        val all = prefs(context)
        val doomed = staleKeys(all.all.keys, alive)
        if (doomed.isEmpty()) return 0
        val edit = all.edit()
        doomed.forEach { edit.remove(it) }
        edit.apply()
        return doomed.size
    }
}
