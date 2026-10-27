package com.nmoi.agent.ui

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 一个供应商下的一个模型（D157）。
 *
 * 只有**真会被用到**的三样：名字、最大输出、温度。上下文窗口 / 定价 / 能力位
 * 那些看着更"完整"的字段这一版没有——它们现在没有任何一处代码读，
 * 存进去就是"存了但不生效"，正是用户最反感的那种。
 */
internal data class ProviderModel(
    val id: String,
    val maxTokens: Int? = null,
    val temperature: Double? = null,
) {
    /** 一行副标题：只写填了的，没填就不提。 */
    val summary: String
        get() {
            val parts = buildList {
                if (maxTokens != null) add("最大输出 $maxTokens")
                if (temperature != null) add("温度 $temperature")
            }
            return if (parts.isEmpty()) "用默认参数" else parts.joinToString(" · ")
        }
}

/**
 * 一份供应商配置（D157）。`id` 是库里的 key，也是密钥文件名的一部分。
 *
 * 分两处存，**故意的**：
 *
 * - `settings(provider, <id>)`：名字、端点、类型、模型表、当前模型。跟着库走，导出/备份时带走；
 * - `vault/provider-<id>.bin`：API Key 密文。不跟着库走，删掉它等于吊销（[Vault]）。
 *
 * 这样"换一台机器恢复库"只会带回空密钥，不会把凭据一起搬过去。
 */
internal data class ProviderEntry(
    val id: String,
    val name: String = "",
    val kind: String = ProviderKinds.DEFAULT,
    val baseUrl: String = "",
    val models: List<ProviderModel> = emptyList(),
    val activeModel: String = "",
) {
    /** 当前模型：按 `activeModel` 找，找不到就退回第一个（别让界面显示成"没有模型"）。 */
    val model: ProviderModel?
        get() = models.firstOrNull { it.id == activeModel } ?: models.firstOrNull()

    /** 能不能发请求：有端点、有模型。密钥不参与判定——本地端点可以不带钥匙。 */
    val isRunnable: Boolean get() = baseUrl.isNotBlank() && model != null
}

/** 列表页要的两样：当前是哪一个、其余按名字排好。 */
internal data class ProviderList(val activeId: String?, val rows: List<ProviderEntry>)

/** 四种 wire 形态，跟 `crates/ffi/src/turn.rs` 的 `parse_provider_kind` 对表。 */
internal object ProviderKinds {
    const val DEFAULT = "openai_compatible"

    val ALL = listOf(
        Kind("openai_compatible", "兼容"),
        Kind("openai_responses", "Responses"),
        Kind("anthropic", "Anthropic"),
        Kind("gemini", "Gemini"),
    )

    fun label(wire: String): String = ALL.firstOrNull { it.wire == wire }?.label ?: wire
}

internal data class Kind(val wire: String, val label: String)

/**
 * 供应商配置的读写（D156 单条 → D157 多条）。
 *
 * 老数据不改：D156 那版只存了一份 `settings(provider, default)`（`base_url` / `model` / `kind`）。
 * 读的时候按新形状**就地解释**（`model` 当成模型表里的第一条），保存时才写成新形状。
 * 密钥也一样——`id = default` 沿用 `vault/provider.bin`，升级上来不用重填。
 */
internal object ProviderRepository {
    private const val ACTIVE_KEY = "active"

    /** 我们自己生成的 id 一定匹配这个；库里读出来的别的形状一律不认，免得拼进文件名。 */
    private val ID_PATTERN = Regex("[A-Za-z0-9_-]{1,40}")

    /** `p` + 36 进制时间戳：够短、可读、同一毫秒里连点两次也不会撞（后一个是 p...1）。 */
    fun newId(): String = "p" + System.currentTimeMillis().toString(36)

    suspend fun list(databasePath: String): Result<ProviderList> =
        withStorageHandle(databasePath) { handle ->
            NmoiFfi.settingsList(handle, SettingsScope.Provider)
                .onFailure { NLog.w("Provider", "列供应商失败：${it.message}") }
                .map { envelope ->
                    val items = envelope.optJSONArray("items") ?: JSONArray()
                    val rows = buildList {
                        for (index in 0 until items.length()) {
                            val item = items.optJSONObject(index) ?: continue
                            val key = item.optString("key", "")
                            if (key == ACTIVE_KEY) continue
                            if (!ID_PATTERN.matches(key)) {
                                NLog.w("Provider", "跳过形状不对的 key：$key")
                                continue
                            }
                            add(parse(key, item.optJSONObject("doc")))
                        }
                    }
                    // 当前那个排最前，其余按名字；不按更新时间排——刚编辑过的会跳到顶上，
                    // 那在列表里看起来像"它自己选了当前"。
                    val active = activeIdOf(handle).getOrNull()
                    ProviderList(
                        activeId = active,
                        rows = rows.sortedWith(compareBy({ it.id != active }, { it.name })),
                    )
                }
        }

    suspend fun load(databasePath: String, id: String): Result<ProviderEntry> =
        withStorageHandle(databasePath) { handle -> loadIn(handle, id) }

    /**
     * 当前选中的那一份。**发请求前必须走这条**：它保证"界面上的当前"和"发出去的"是同一个。
     *
     * 没有 `active` 标记时的兜底顺序：`default`（D156 老配置）→ 只有一个供应商时就是它。
     * 多套配置又没选过当前，就明确失败——替他挑一个，等于替他决定花谁的钱。
     */
    suspend fun active(databasePath: String): Result<ProviderEntry> =
        list(databasePath).mapCatching { page ->
            val chosen = page.activeId?.let { id -> page.rows.firstOrNull { it.id == id } }
                ?: page.rows.firstOrNull { it.id == "default" }
                ?: page.rows.singleOrNull()
                ?: throw NmoiFfiException(
                    "no_provider",
                    "还没选当前供应商（设置 → 模型与提示词 → 模型服务）",
                )
            chosen
        }

    suspend fun save(databasePath: String, entry: ProviderEntry): Result<Unit> =
        withStorageHandle(databasePath) { handle ->
            val doc = JSONObject()
                .put("name", entry.name.trim())
                .put("kind", entry.kind)
                .put("base_url", entry.baseUrl.trim())
                .put(
                    "models",
                    JSONArray().apply {
                        entry.models.forEach { model ->
                            put(
                                JSONObject()
                                    .put("id", model.id.trim())
                                    .also { row ->
                                        model.maxTokens?.let { row.put("max_tokens", it) }
                                        model.temperature?.let { row.put("temperature", it) }
                                    },
                            )
                        }
                    },
                )
                .put("active_model", entry.activeModel.trim())
            NmoiFfi.settingsPut(handle, SettingsScope.Provider, entry.id, doc)
                .onSuccess {
                    NLog.i(
                        "Provider",
                        "已保存 ${entry.id}：kind=${entry.kind} 模型 ${entry.models.size} 个" +
                            " 端点长度=${entry.baseUrl.length}",
                    )
                }
                .onFailure { NLog.w("Provider", "保存失败 ${entry.id}：${it.message}") }
                .map { }
        }

    /**
     * 删掉一个供应商：设置文档 + 它自己的密钥文件。
     *
     * 只删这两样，**不动**别的供应商、不动消息、不动会话。如果删的是当前那个，
     * 顺手把 `active` 清掉——不清的话下次发请求会去读一份已经不存在的配置。
     */
    suspend fun delete(context: Context, databasePath: String, id: String): Result<Unit> =
        withStorageHandle(databasePath) { handle ->
            val removed = NmoiFfi.settingsDelete(handle, SettingsScope.Provider, id)
            if (removed.isFailure) return@withStorageHandle removed.map { }
            val active = activeIdOf(handle).getOrNull()
            if (active == id) {
                NmoiFfi.settingsDelete(handle, SettingsScope.Provider, ACTIVE_KEY)
                    .onFailure { NLog.w("Provider", "清当前标记失败：${it.message}") }
            }
            Vault.clear(context, id)
            NLog.i("Provider", "已删除供应商 $id（设置与密钥文件）")
            Result.success(Unit)
        }

    suspend fun setActive(databasePath: String, id: String): Result<Unit> =
        withStorageHandle(databasePath) { handle ->
            NmoiFfi.settingsPut(
                handle,
                SettingsScope.Provider,
                ACTIVE_KEY,
                JSONObject().put("id", id),
            )
                .onSuccess { NLog.i("Provider", "当前供应商 = $id") }
                .onFailure { NLog.w("Provider", "设当前失败：${it.message}") }
                .map { }
        }

    /** 已存的密钥打码（给界面显示用）。解不开返回 `null`，界面写"要重填"。 */
    fun keyHint(context: Context, id: String): String? =
        Vault.load(context, id)?.let(::maskSecret)

    fun hasKey(context: Context, id: String): Boolean = Vault.has(context, id)

    fun databasePath(context: Context): String =
        File(context.filesDir, "nmoi.db").absolutePath

    // -----------------------------------------------------------------------

    private suspend fun activeIdOf(handle: Long): Result<String?> =
        NmoiFfi.settingsGet(handle, SettingsScope.Provider, ACTIVE_KEY).map { envelope ->
            val doc = envelope.optJSONObject("doc")
            doc?.optString("id")?.takeIf { it.isNotBlank() }
        }

    private suspend fun loadIn(handle: Long, id: String): Result<ProviderEntry> =
        NmoiFfi.settingsGet(handle, SettingsScope.Provider, id).map { envelope ->
            val doc = envelope.optJSONObject("doc")
            if (envelope.optString("state", "") != "ready" || doc == null) {
                // 读不到就当"新建"的底稿：界面拿它填表单，保存才落库。
                ProviderEntry(id = id)
            } else {
                parse(id, doc)
            }
        }

    /**
     * 库里的 doc → [ProviderEntry]。**老形状（只有 `model` 一个字符串）在这里被就地解释**，
     * 不做一次性迁移：迁移要写库，写库就可能把没读懂的字段洗掉。
     */
    private fun parse(id: String, doc: JSONObject?): ProviderEntry {
        if (doc == null) return ProviderEntry(id = id)
        val legacyModel = doc.optString("model", "").trim()
        val rows = doc.optJSONArray("models")
        val models = buildList {
            if (rows != null) {
                for (index in 0 until rows.length()) {
                    val row = rows.optJSONObject(index) ?: continue
                    val modelId = row.optString("id", "").trim()
                    if (modelId.isEmpty()) continue
                    add(
                        ProviderModel(
                            id = modelId,
                            maxTokens = row.optInt("max_tokens", 0).takeIf { it > 0 },
                            temperature = if (row.has("temperature")) {
                                row.optDouble("temperature", Double.NaN).takeIf { !it.isNaN() }
                            } else {
                                null
                            },
                        ),
                    )
                }
            }
            if (isEmpty() && legacyModel.isNotEmpty()) add(ProviderModel(legacyModel))
        }
        val name = doc.optString("name", "").trim().ifEmpty {
            if (id == "default") "默认供应商" else id
        }
        return ProviderEntry(
            id = id,
            name = name,
            kind = doc.optString("kind", ProviderKinds.DEFAULT).ifBlank { ProviderKinds.DEFAULT },
            baseUrl = doc.optString("base_url", ""),
            models = models,
            activeModel = doc.optString("active_model", "").trim().ifEmpty {
                models.firstOrNull()?.id.orEmpty()
            },
        )
    }
}
