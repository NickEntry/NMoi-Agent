package com.nmoi.agent.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 一个助手（D161）。**"用哪一套端点 + 哪个模型"归助手，不归供应商。**
 *
 * 供应商页只当目录：有哪些端点、有哪些模型、参数怎么设。
 * 谁来决定"这次用哪个"——是助手。聊天页顶部那个模型条改的就是它。
 */
internal data class AssistantEntry(
    val id: String = AssistantRepository.DEFAULT_ID,
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

/** 当前助手 + 全部助手（D174）。当前那个排最前，其余按名字。 */
internal data class AssistantList(
    val activeId: String,
    val rows: List<AssistantEntry>,
)

/**
 * 助手配置（`settings(assistant, <id>)`，D161 / D174）。
 *
 * **一套配置一个 key**，与供应商（`settings(provider, <id>)`）同形；
 * "当前是哪个助手"存 `settings(assistant, "active")`——和 `settings(provider, "active")`
 * 是同一个做法，理由也一样：它是数据的一部分（决定新会话归谁），不是某台机器上的开关，
 * 换台设备打开应当还是同一个助手。
 *
 * `default` 这个 id 是**定死的**：v9 迁移把老会话全归到它，老配置也写在它下面，
 * 所以永远不能改名、不能删。
 */
internal object AssistantRepository {
    const val DEFAULT_ID = "default"

    /** 当前助手的指针。列助手时要跳过它——它是一份"指针文档"，不是一个助手。 */
    private const val ACTIVE_KEY = "active"

    /** 我们自己生成的 id 一定匹配这个；库里读出来的别的形状一律不认。 */
    private val ID_PATTERN = Regex("[A-Za-z0-9_-]{1,40}")

    /** `a` + 36 进制时间戳：与供应商的 `p…` 同形，一眼能看出是哪个 scope 的 id。 */
    fun newId(): String = "a" + System.currentTimeMillis().toString(36)

    /** 全部助手 + 当前那个（D174）。列表为空时给一份默认助手（老版本没有文档）。 */
    suspend fun list(databasePath: String): Result<AssistantList> =
        withStorageHandle(databasePath) { handle ->
            NmoiFfi.settingsList(handle, SettingsScope.Assistant)
                .onFailure { NLog.w("Assistant", "列助手失败：${it.message}") }
                .map { envelope ->
                    val items = envelope.optJSONArray("items") ?: org.json.JSONArray()
                    val rows = buildList {
                        for (index in 0 until items.length()) {
                            val item = items.optJSONObject(index) ?: continue
                            val key = item.optString("key", "")
                            if (key == ACTIVE_KEY) continue
                            if (!ID_PATTERN.matches(key)) {
                                NLog.w("Assistant", "跳过形状不对的 key：$key")
                                continue
                            }
                            add(parse(key, item.optJSONObject("doc")))
                        }
                    }
                    // 一份都没有 = 老版本（只有供应商配置）。给一行默认助手，
                    // 但**不写库**：读不该有副作用，等用户真的存一次才有那条记录。
                    val all = rows.ifEmpty { listOf(AssistantEntry()) }
                    val active = activeIdOf(handle).getOrNull()
                        ?: all.firstOrNull { it.id == DEFAULT_ID }?.id
                        ?: all.first().id
                    AssistantList(
                        activeId = active,
                        rows = all.sortedWith(compareBy({ it.id != active }, { it.name })),
                    )
                }
        }

    /** 当前助手（D174）。界面上看到的和发出去的是同一份——那条路只此一条。 */
    suspend fun active(databasePath: String): Result<AssistantEntry> =
        list(databasePath).mapCatching { page ->
            val chosen = page.rows.firstOrNull { it.id == page.activeId } ?: page.rows.first()
            if (chosen.isRunnable) {
                chosen
            } else {
                // 兜底：从供应商目录里推一份（老版本只有供应商配置，或助手还没挑过模型）。
                derive(chosen, databasePath)
            }
        }

    /** 兼容旧调用点：`current` 就是"当前助手"，语义与 [active] 一致。 */
    suspend fun current(databasePath: String): Result<AssistantEntry> = active(databasePath)

    suspend fun load(databasePath: String, id: String): Result<AssistantEntry> =
        withStorageHandle(databasePath) { handle ->
            NmoiFfi.settingsGet(handle, SettingsScope.Assistant, id)
                .onFailure { NLog.w("Assistant", "读助手 $id 失败：${it.message}") }
                .map { envelope ->
                    val doc = envelope.optJSONObject("doc")
                    if (envelope.optString("state", "") == "ready" && doc != null) {
                        parse(id, doc)
                    } else {
                        AssistantEntry(id = id, name = if (id == DEFAULT_ID) "默认助手" else id)
                    }
                }
        }

    /** 换当前助手（D174）。写的是指针，不动任何一个助手自己的配置。 */
    suspend fun setActive(databasePath: String, id: String): Result<Unit> =
        withStorageHandle(databasePath) { handle ->
            NmoiFfi.settingsPut(handle, SettingsScope.Assistant, ACTIVE_KEY, JSONObject().put("id", id))
                .onSuccess { NLog.i("Assistant", "当前助手 = $id") }
                .onFailure { NLog.w("Assistant", "切助手失败：${it.message}") }
                .map { }
        }

    suspend fun save(databasePath: String, entry: AssistantEntry): Result<Unit> =
        withStorageHandle(databasePath) { handle ->
            val doc = JSONObject()
                .put("name", entry.name.trim().ifEmpty { entry.id })
                .put("provider_id", entry.providerId)
                .put("model_id", entry.modelId)
                .put("thinking", entry.thinking)
                .also { row ->
                    entry.temperature?.let { row.put("temperature", it) }
                    entry.maxTokens?.let { row.put("max_tokens", it) }
                }
            NmoiFfi.settingsPut(handle, SettingsScope.Assistant, entry.id, doc)
                .onSuccess {
                    NLog.i(
                        "Assistant",
                        "保存助手 ${entry.id}：模型 ${entry.providerId}/${entry.modelId} · 思考=${entry.thinking}",
                    )
                }
                .onFailure { NLog.w("Assistant", "保存助手配置失败：${it.message}") }
                .map { }
        }

    /**
     * 删一个助手（D174）。**库里还有它的会话就不许删**——调用方必须先用
     * [ConversationRepository.countOf] 问过，并且按用户的选择把会话移走。
     *
     * `default` 永远不删：v9 迁移把老会话都归在它下面，而"迁移目标不存在"这种库
     * 谁都读不回来。
     */
    suspend fun delete(databasePath: String, id: String): Result<Unit> {
        if (id == DEFAULT_ID) {
            return Result.failure(NmoiFfiException("bad_argument", "默认助手不能删"))
        }
        return withStorageHandle(databasePath) { handle ->
            NmoiFfi.settingsDelete(handle, SettingsScope.Assistant, id)
                .onSuccess { NLog.i("Assistant", "已删助手 $id") }
                .onFailure { NLog.w("Assistant", "删助手失败：${it.message}") }
                .map { }
        }
    }

    /** 聊天页那个选择器要的：所有 (供应商, 模型) 组合。 */
    suspend fun choices(databasePath: String): Result<List<Pair<ProviderEntry, ProviderModel>>> =
        ProviderRepository.list(databasePath).map { page ->
            page.rows.flatMap { provider -> provider.models.map { model -> provider to model } }
        }

    /**
     * 读当前指针。**读不出来不算错**：老库里没有那份文档，那就是"还没选过"，
     * 由 [list] 的兜底决定当前是 `default`。
     */
    private suspend fun activeIdOf(handle: Long): Result<String> =
        NmoiFfi.settingsGet(handle, SettingsScope.Assistant, ACTIVE_KEY).mapCatching { envelope ->
            val doc = envelope.optJSONObject("doc")
            val id = doc?.optString("id", "").orEmpty()
            if (envelope.optString("state", "") == "ready" && ID_PATTERN.matches(id)) {
                id
            } else {
                throw NmoiFfiException("missing", "还没选过当前助手")
            }
        }

    /** 从供应商目录里推一份可跑的配置（不写库）。挑不着就原样返回，界面会提示"还没选模型"。 */
    private suspend fun derive(entry: AssistantEntry, databasePath: String): AssistantEntry {
        val page = ProviderRepository.list(databasePath).getOrNull() ?: return entry
        val provider = page.activeId?.let { id -> page.rows.firstOrNull { it.id == id } }
            ?: page.rows.firstOrNull { it.id == "default" }
            ?: page.rows.singleOrNull()
            ?: return entry
        val model = provider.model ?: return entry
        return entry.copy(
            providerId = provider.id,
            modelId = model.id,
            temperature = entry.temperature ?: model.temperature,
            maxTokens = entry.maxTokens ?: model.maxTokens,
        )
    }

    private fun parse(id: String, doc: JSONObject?): AssistantEntry {
        val safe = doc ?: JSONObject()
        return AssistantEntry(
            id = id,
            name = safe.optString("name", if (id == DEFAULT_ID) "默认助手" else id)
                .ifBlank { if (id == DEFAULT_ID) "默认助手" else id },
            providerId = safe.optString("provider_id", ""),
            modelId = safe.optString("model_id", ""),
            temperature = if (safe.has("temperature")) {
                safe.optDouble("temperature", Double.NaN).takeIf { !it.isNaN() }
            } else {
                null
            },
            maxTokens = safe.optInt("max_tokens", 0).takeIf { it > 0 },
            thinking = safe.optString("thinking", "auto").ifBlank { "auto" },
        )
    }
}

/** 把 (供应商, 模型) 拼成一行字：`本机中转 / glm-5.2`。 */
internal fun Pair<ProviderEntry, ProviderModel>.label(): String =
    "${first.name.ifBlank { first.id }} / ${second.id}"

/** 读当前助手的配置（给 `TurnRepository` 用，跑在 IO 上）。 */
internal suspend fun readCurrentAssistant(databasePath: String): AssistantEntry =
    withContext(Dispatchers.IO) {
        AssistantRepository.active(databasePath).getOrThrow()
    }
