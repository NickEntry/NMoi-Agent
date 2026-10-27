package com.nmoi.agent.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 一个助手（D161）。**"用哪一套端点 + 哪个模型"归助手，不归供应商。**
 *
 * 供应商页现在只当目录：有哪些端点、有哪些模型、参数怎么设。
 * 谁来决定"这次用哪个"——是助手。聊天页顶部那个模型条改的就是它。
 */
internal data class AssistantEntry(
    val id: String = "default",
    val name: String = "默认助手",
    val providerId: String = "",
    val modelId: String = "",
    /** 覆盖模型上的默认参数；`null` = 用模型自己那份。 */
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    /** 思考强度（D162）：`auto` / `off` / `minimal` / `low` / `medium` / `high` / `xhigh` / `max`。 */
    val thinking: String = "auto",
) {
    val isRunnable: Boolean get() = providerId.isNotBlank() && modelId.isNotBlank()
}

/**
 * 助手配置（`settings(assistant, "default")`）。
 *
 * 这一版只有**一个**助手：多助手要先把"会话属于哪个助手"建模，那是下一步。
 * 但"配置放哪"这件事现在就定死：放助手，不放供应商——否则多助手出现时
 * 每个助手的模型选择都会被供应商那一层的"当前"覆盖掉。
 */
internal object AssistantRepository {
    private const val KEY = "default"

    /** 现读。读不到就从供应商那边**推导**一份（不写库：读不该有副作用）。 */
    suspend fun current(databasePath: String): Result<AssistantEntry> =
        withStorageHandle(databasePath) { handle ->
            NmoiFfi.settingsGet(handle, SettingsScope.Assistant, KEY)
                .onFailure { NLog.w("Assistant", "读助手配置失败：${it.message}") }
                .map { envelope ->
                    val doc = envelope.optJSONObject("doc")
                    if (envelope.optString("state", "") == "ready" && doc != null) {
                        parse(doc)
                    } else {
                        AssistantEntry()
                    }
                }
        }.mapCatching { entry ->
            if (entry.isRunnable) {
                entry
            } else {
                // 兜底：从供应商目录里推一份（老版本只有供应商配置）。
                val page = ProviderRepository.list(databasePath).getOrThrow()
                val provider = page.activeId?.let { id -> page.rows.firstOrNull { it.id == id } }
                    ?: page.rows.firstOrNull { it.id == "default" }
                    ?: page.rows.singleOrNull()
                    ?: return@mapCatching entry
                val model = provider.model ?: return@mapCatching entry
                AssistantEntry(
                    providerId = provider.id,
                    modelId = model.id,
                    temperature = model.temperature,
                    maxTokens = model.maxTokens,
                )
            }
        }

    suspend fun save(databasePath: String, entry: AssistantEntry): Result<Unit> =
        withStorageHandle(databasePath) { handle ->
            val doc = JSONObject()
                .put("name", entry.name)
                .put("provider_id", entry.providerId)
                .put("model_id", entry.modelId)
                .put("thinking", entry.thinking)
                .also { row ->
                    entry.temperature?.let { row.put("temperature", it) }
                    entry.maxTokens?.let { row.put("max_tokens", it) }
                }
            NmoiFfi.settingsPut(handle, SettingsScope.Assistant, KEY, doc)
                .onSuccess {
                    NLog.i(
                        "Assistant",
                        "当前模型：${entry.providerId} / ${entry.modelId} · 思考=${entry.thinking}",
                    )
                }
                .onFailure { NLog.w("Assistant", "保存助手配置失败：${it.message}") }
                .map { }
        }

    /** 聊天页那个选择器要的：所有 (供应商, 模型) 组合。 */
    suspend fun choices(databasePath: String): Result<List<Pair<ProviderEntry, ProviderModel>>> =
        ProviderRepository.list(databasePath).map { page ->
            page.rows.flatMap { provider -> provider.models.map { model -> provider to model } }
        }

    private fun parse(doc: JSONObject): AssistantEntry = AssistantEntry(
        name = doc.optString("name", "默认助手").ifBlank { "默认助手" },
        providerId = doc.optString("provider_id", ""),
        modelId = doc.optString("model_id", ""),
        temperature = if (doc.has("temperature")) {
            doc.optDouble("temperature", Double.NaN).takeIf { !it.isNaN() }
        } else {
            null
        },
        maxTokens = doc.optInt("max_tokens", 0).takeIf { it > 0 },
        thinking = doc.optString("thinking", "auto").ifBlank { "auto" },
    )
}

/** 把 (供应商, 模型) 拼成一行字：`本机中转 / glm-5.2`。 */
internal fun Pair<ProviderEntry, ProviderModel>.label(): String =
    "${first.name.ifBlank { first.id }} / ${second.id}"

/** 读当前助手的配置（给 `TurnRepository` 用，跑在 IO 上）。 */
internal suspend fun readCurrentAssistant(databasePath: String): AssistantEntry =
    withContext(Dispatchers.IO) {
        AssistantRepository.current(databasePath).getOrThrow()
    }
