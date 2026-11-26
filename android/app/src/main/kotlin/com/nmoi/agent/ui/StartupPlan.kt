package com.nmoi.agent.ui

/**
 * 启动对账的**纯逻辑**那一半（D435）。
 *
 * 与 [StartupCheck] 分开，是为了能在本机单测里跑：那一边要开库、要读 `SharedPreferences`，
 * 这一边只算集合。用户 2026-11-26 要的是「启动的时候先加载供应商和模型，然后检查一遍」，
 * 而"检查"错起来是两个方向：漏掉一个该清的（老设置越积越多，以后同名模型回来会继承
 * 一个用户没选过的档位），或者多清了一个（用户会以为设置没保存）。
 */
internal object StartupPlan {

    /**
     * 一遍对账的结果。`null` 的字段表示"这一项没读到"，与"读到了、是空的"分开——
     * 两者在日志里是两句不同的话。
     */
    data class Report(
        /** 现存模型数（`provider/model` 的对数）。 */
        val models: Int,
        /** 从助手上搬进模型那一格的条数（老数据迁移）。 */
        val migratedPrefs: Int = 0,
        /** 清掉了几格跟着模型走的设置。 */
        val prunedPrefs: Int,
        /** 指向已不存在的模型的助手：`助手名 → 它要的 provider/model`。 */
        val staleAssistants: List<Pair<String, String>>,
        /** 供应商与模型读失败的原因；null = 读到了。 */
        val failure: String? = null,
    ) {
        val ok: Boolean get() = failure == null

        /**
         * 一行日志 / 一行界面提示。**成功也要有一句**——否则"没消息"分不清是好了还是没跑。
         */
        fun summary(): String = when {
            failure != null -> "$failure（这一轮没做任何清理）"
            staleAssistants.isEmpty() && prunedPrefs == 0 && migratedPrefs == 0 ->
                "供应商与模型已加载：$models 个模型，设置对得上"
            else -> buildString {
                append("供应商与模型已加载：$models 个模型")
                if (migratedPrefs > 0) append("，把 $migratedPrefs 条助手设置搬进了模型")
                if (prunedPrefs > 0) append("，清掉 $prunedPrefs 条失效的模型设置")
                if (staleAssistants.isNotEmpty()) {
                    append("，${staleAssistants.size} 个助手指向的模型不在了（")
                    append(staleAssistants.take(3).joinToString("；") { "${it.first} → ${it.second}" })
                    if (staleAssistants.size > 3) append(" 等")
                    append("）")
                }
            }
        }
    }

    /** 一条待迁移的设置：`provider/model` → 档位。 */
    data class Migration(val target: String, val wire: String)

    /**
     * 老数据的迁移：**把助手行上那一份思考强度搬进模型那一格**。
     *
     * 为什么要有这一步：D435 之前思考强度挂在助手上。不搬的话，一个设过「极高」的助手
     * 换到另一个模型，那一档会原样跟过去——这正是用户这次要修的那件事。搬完之后
     * 每个模型各有一格，换模型就是换一格。
     *
     * 三条规矩：
     * 1. **已经有模型那一格的绝不覆盖**：用户在新版本里选过的东西优先于老数据。
     * 2. `auto` 不搬：它等于"没设置过"，搬过去只是把空值写成一个有值的键。
     * 3. 只在模型**还在**的时候搬（`alive`）：搬给一个已删的模型，下一次对账又要清掉。
     */
    fun migrations(
        candidates: List<Pair<String, String>>,
        existing: Set<String>,
        alive: Set<String>,
    ): List<Migration> {
        val taken = existing.toMutableSet()
        val out = ArrayList<Migration>()
        for ((target, wire) in candidates) {
            if (target !in alive) continue
            if (wire.isBlank() || wire == "auto") continue
            // 同一个模型可能被好几个助手共用，而它们的档位可能不一样。
            // **先到先得**（`AssistantRepository.list` 的顺序是确定的）：两个都写的话，
            // 最后那个赢——那等于按遍历顺序决定用户看到哪一档，没人说得清为什么。
            if (!taken.add(target)) continue
            out.add(Migration(target, wire))
        }
        return out
    }

    /**
     * 哪些助手指向的模型已经没了。`alive` 的形状是 `provider/model`（与 [ModelPrefs] 的键一致）。
     *
     * **只算，不改**：真要改归属是用户的事。静默把一个助手的模型换成"第一个能用的"，
     * 比不换更坏——他下一轮会对着一个自己没选过的模型说话。
     */
    fun stale(
        assistants: List<Pair<String, String>>,
        alive: Set<String>,
    ): List<Pair<String, String>> = assistants.filterNot { (_, target) -> target in alive }
}
