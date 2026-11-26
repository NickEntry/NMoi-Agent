package com.nmoi.agent.ui

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 启动时的那一遍对账（D435）。
 *
 * 用户 2026-11-26 的原话：
 * 「启动的时候，应该也先加载供应商和模型数据然后检查一遍」
 *
 * # 顺序为什么是这个顺序
 *
 * **先读供应商与模型，再动任何东西**。反过来的话，一次读失败会被当成"一个模型都没有"，
 * 那一次启动就会把用户所有跟着模型走的设置清空（[ModelPrefs.prune] 的 `alive` 是空集）。
 * 这个错不会报任何异常，只会表现成"我明明设过极高，怎么回到自动了"。
 *
 * # 检查什么
 *
 * 1. **跟着模型走的设置**（思考强度）：现存模型之外的键一律清掉。老版本留下的、
 *    删模型那次崩在半路的，都在这里收掉。
 * 2. **助手指向的模型还在不在**：不替用户改任何东西，只把结论报出来。
 *    真要改归属是用户的事——静默把助手的模型换成"第一个能用的"，比不换更坏：
 *    他下一轮会对着一个自己没选过的模型说话。
 *
 * 结果与措辞那一半在 [StartupPlan]（纯逻辑，可单测），这里只负责开库与清理。
 */
internal object StartupCheck {

    /**
     * 跑一遍。**不抛**：启动路径上抛出去就是白屏，而这里最坏的结果只是"这一轮没清理"。
     *
     * 自己切 IO：调用方忘了切，界面就会掉一帧，而那种卡顿查起来没人会想到是这里。
     */
    suspend fun run(context: Context, databasePath: String): StartupPlan.Report =
        withContext(Dispatchers.IO) {
            val providers = ProviderRepository.list(databasePath).getOrElse { error ->
                val line = "读不到供应商：${error.message}"
                NLog.w("Startup", line)
                return@withContext StartupPlan.Report(
                    models = 0,
                    prunedPrefs = 0,
                    staleAssistants = emptyList(),
                    failure = line,
                )
            }

            // 1) 现存模型（只算对话模型：嵌入/图像模型不会挂在助手身上）。
            val alive = providers.rows
                .flatMap { provider ->
                    provider.models.filter { it.isChat }.map { provider.id to it.id }
                }
                .map { (provider, model) -> "$provider/$model" }
                .toSet()

            // 2) 先把老数据搬进模型那一格（D435 之前思考强度挂在助手上）。
            //    顺序在 prune **之前**：搬给一个已删的模型没有意义，
            //    而 `migrations` 自己会拿 alive 挡掉那种情况。
            val assistants = AssistantRepository.list(databasePath)
                .map { list -> list.rows }
                .getOrElse { emptyList() }
            val migrations = StartupPlan.migrations(
                candidates = assistants
                    .filter { it.isRunnable }
                    .map { "${it.providerId}/${it.modelId}" to it.thinking },
                existing = ModelPrefs.storedTargets(context),
                alive = alive,
            )
            migrations.forEach { ModelPrefs.setByTarget(context, it.target, it.wire) }

            // 3) 有了 alive 才敢清。
            val pruned = ModelPrefs.prune(context, alive)

            // 4) 助手指向的模型还在不在。**只报不改。**
            val wanted = assistants.map { row -> row.name to "${row.providerId}/${row.modelId}" }
            val stale = StartupPlan.stale(wanted, alive)

            val report = StartupPlan.Report(
                models = alive.size,
                migratedPrefs = migrations.size,
                prunedPrefs = pruned,
                staleAssistants = stale,
            )
            // 有东西被清、或者有助手已经指不到模型：这两样都该被看见，所以是 warn。
            if (stale.isEmpty() && pruned == 0) {
                NLog.i("Startup", report.summary())
            } else {
                NLog.w("Startup", report.summary())
            }
            report
        }
}
