package com.nmoi.agent.ui

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 自定义 Header 的一行（D201 高级页）。
 *
 * 用**有序列表**而不是 `Map`：Header 是允许同名的（各家网关偶尔这么用），
 * 而 `Map` 会在用户没看见的地方把第二行吃掉。
 */
internal data class HeaderRow(val name: String, val value: String)

/**
 * 一个供应商下的一个模型（D157）。
 *
 * 左边这几样（名字 / 类型 / 窗口 / 输出 / 温度）**每一处都有读取点**：
 * 选择器按 `kind` 过滤、聊天页顶部按 `contextWindow` 算占用、`TurnRepository`
 * 每次都带 `maxTokens` / `temperature`。
 *
 * 右边那一批（模态 / 能力位 / 定价 / 推理 / headers / body）是 D201 一并加进来的：
 * 它们此前被推迟的理由是"没有读取点"，而现在的答案是**给每个字段标出处**
 * （[FieldSource]）——界面上一眼能看见"这个数是谁说的"，用户就不会误以为它是自己填的。
 * 但要注意，其中一部分**请求里还不会用到**（`headers` / `body` / 推理方言这一批，
 * 要等 `TurnRepository` 与 FFI 那头接上）；建模型页时必须写明，不能做成假开关。
 */
internal data class ProviderModel(
    val id: String,
    /**
     * 显示名。空 = 用 id。
     *
     * 有了它，聊天页顶部才写得出「模型名称 (提供商)」。id 是给端点看的
     * （`trae-cn/Doubao-Seed-2.1-Turbo` 这种），拿去当标题只是把内部编号摆给用户看。
     */
    val name: String = "",
    /**
     * 这一行是**哪类**模型（0.15x 后半）：`chat` / `embedding` / `image`。
     *
     * **有读取点**：`AssistantRepository.choices()` 只把 `chat` 型交给聊天页与助手页的选择器
     * （见 [isChat]）——不然挑模型时会把一个嵌入模型摆在一堆聊天模型中间，点下去只会得到一轮 400。
     */
    val kind: String = ModelKinds.CHAT,
    /**
     * 上下文窗口（token）。**真的会用**：聊天页顶部的占用百分比按它算。
     *
     * 空 = 不知道，不显示百分比。这一条是"填了必须生效"——以前没有这个字段，
     * 因为那时没有任何一处代码会读它（存了就是死数据）。
     */
    val contextWindow: Int? = null,
    val maxTokens: Int? = null,
    val temperature: Double? = null,

    // ---- D201 基本页：模态 / 能力 / 定价 -------------------------------------
    /** 能收什么（`text` / `image` / `audio` / `video` / `pdf`）。空 = 不知道。 */
    val inputModalities: List<String> = emptyList(),
    /** 能吐什么（`text` / `image` / `audio`）。空 = 不知道。 */
    val outputModalities: List<String> = emptyList(),
    /** 三态：`null` = 没数据，`true/false` = 确定能用/不能用。**别把 `null` 当 `false`**。 */
    val tools: Boolean? = null,
    val reasoning: Boolean? = null,
    val structuredOutput: Boolean? = null,
    /** 定价一律按**每 100 万 token**记（各家网页上的写法），货币见 [currency]。 */
    val inputPrice: Double? = null,
    val outputPrice: Double? = null,
    val cacheReadPrice: Double? = null,
    val cacheWritePrice: Double? = null,
    /** ISO 代码（`USD` / `CNY`）。空 = 没填；有价格没货币的，界面上按数据源的货币显示。 */
    val currency: String = "",

    // ---- D201 推理页 ---------------------------------------------------------
    /** 思考注到哪个字段族（见 [ReasoningDialects]）。空 = 按供应商类型自动选。 */
    val reasoningDialect: String = "",
    /** 这个模型接受哪几档（[Efforts] 的子集，按低到高排）。空 = 没数据。 */
    val efforts: List<String> = emptyList(),
    /** 能不能显式关掉思考。三态，理由同 [tools]。 */
    val allowDisable: Boolean? = null,
    /** 默认用哪一档。空 = 不指定（交给助手页那份设置）。 */
    val defaultEffort: String = "",

    // ---- D201 高级页 ---------------------------------------------------------
    /** 每次请求附带的 Header（**还没有读取点**，见类注释）。 */
    val headers: List<HeaderRow> = emptyList(),
    /** 直接并进请求体的 JSON 片段（原文保存，校验留给保存那一下）。 */
    val bodyOverride: String = "",
    /** 远程图片链接（模型的示意图/图标）。 */
    val remoteImageUrl: String = "",
    /** 允许模型用供应商侧的**内置工具**（联网搜索那类）。三态，理由同 [tools]。 */
    val builtinTools: Boolean? = null,
) {
    /** 界面上一律用它：填了名字用名字，没填退回 id。 */
    val displayName: String get() = name.ifBlank { id }

    /** 聊天页/助手页的选择器只认这一类。 */
    val isChat: Boolean get() = kind == ModelKinds.CHAT

    /** 一行副标题：只写填了的，没填就不提。 */
    val summary: String
        get() {
            val parts = buildList {
                if (contextWindow != null) add("窗口 $contextWindow")
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
    /**
     * 每个模型每个字段的来源（D201）：`模型 id → 字段 wire 名 → [FieldSource]`。
     *
     * 跟模型表存在**同一份** `settings(provider,<供应商 id>)` 文档里（`field_sources`），
     * 一次写入一起落盘。老数据没有这一段——那时每个字段都按 [FieldSource.Default] 解释。
     */
    val fieldSources: Map<String, Map<String, FieldSource>> = emptyMap(),
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

    /** 四个 wire 名的字符串常量：凡是要按 kind 分支的地方都引这里，别再手打一次。 */
    const val WIRE_OPENAI = "openai_compatible"
    const val WIRE_RESPONSES = "openai_responses"
    const val WIRE_ANTHROPIC = "anthropic"
    const val WIRE_GEMINI = "gemini"

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
 * 模型类型（0.15x）。
 *
 * wire 值存在 `ProviderModel.kind` 里；**认不出的按 `chat` 处理**——
 * 老数据没有这个字段，而把一个旧模型当成嵌入模型会让它从选择器里消失（看着像丢了）。
 */
internal object ModelKinds {
    const val CHAT = "chat"
    const val EMBEDDING = "embedding"
    const val IMAGE = "image"

    val ALL = listOf(
        Kind(CHAT, "聊天"),
        Kind(EMBEDDING, "嵌入"),
        Kind(IMAGE, "图像"),
    )

    fun label(wire: String): String = ALL.firstOrNull { it.wire == wire }?.label ?: "聊天"

    fun normalize(wire: String): String =
        if (ALL.any { it.wire == wire }) wire else CHAT
}

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
            // 来源标记要在**写之前**算：它比的是"库里的上一份"与"这一份"，
            // 先写再读就成了自己跟自己比（什么都不会被记成用户改的）。
            val previous = loadIn(handle, entry.id).getOrNull()
            val sources = ModelField.mergeSources(previous, entry.models)
            val doc = JSONObject()
                .put("name", entry.name.trim())
                .put("kind", entry.kind)
                .put("base_url", entry.baseUrl.trim())
                .put(
                    "models",
                    JSONArray().apply {
                        entry.models.forEach { model -> put(encodeModel(model)) }
                    },
                )
                .put("active_model", entry.activeModel.trim())
                .put("field_sources", encodeSources(sources))
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

    // ---- 模型行 ↔ JSON（D201 数据层）-----------------------------------------
    //
    // 读写两端都引 [ModelField] 的常量：少一个逗号会在这里立刻露出来（读回来是空的），
    // 不会变成"存了但在界面上找不到"。**只写有值的字段**——空字符串与 null 不落盘，
    // 老版本读到这一份也不会被一堆空字段淹掉。
    //
    // 这几条**不是 private**：本机用一份真的 `org.json` 跑过一遍"编码 → 解码 → 相等"
    // （android.jar 里那个是桩，JVM 单测跑不了它）。这层最容易出的错就是
    // "写进去的键和读出来的键差一个字母"，而它在界面上表现为"填了但没存上"。

    fun encodeModel(model: ProviderModel): JSONObject = JSONObject().apply {
        put(ModelField.ID, model.id.trim())
        if (model.name.isNotBlank()) put(ModelField.NAME, model.name.trim())
        put(ModelField.KIND, model.kind)

        if (model.inputModalities.isNotEmpty()) {
            put(ModelField.INPUT_MODALITIES, JSONArray(model.inputModalities))
        }
        if (model.outputModalities.isNotEmpty()) {
            put(ModelField.OUTPUT_MODALITIES, JSONArray(model.outputModalities))
        }
        model.tools?.let { put(ModelField.TOOLS, it) }
        model.reasoning?.let { put(ModelField.REASONING, it) }
        model.structuredOutput?.let { put(ModelField.STRUCTURED_OUTPUT, it) }
        model.contextWindow?.let { put(ModelField.CONTEXT_WINDOW, it) }
        model.maxTokens?.let { put(ModelField.MAX_TOKENS, it) }
        model.temperature?.let { put(ModelField.TEMPERATURE, it) }

        model.inputPrice?.let { put(ModelField.INPUT_PRICE, it) }
        model.outputPrice?.let { put(ModelField.OUTPUT_PRICE, it) }
        model.cacheReadPrice?.let { put(ModelField.CACHE_READ_PRICE, it) }
        model.cacheWritePrice?.let { put(ModelField.CACHE_WRITE_PRICE, it) }
        if (model.currency.isNotBlank()) put(ModelField.CURRENCY, model.currency.trim())

        if (model.reasoningDialect.isNotBlank()) {
            put(ModelField.REASONING_DIALECT, model.reasoningDialect.trim())
        }
        if (model.efforts.isNotEmpty()) put(ModelField.EFFORTS, JSONArray(model.efforts))
        model.allowDisable?.let { put(ModelField.ALLOW_DISABLE, it) }
        if (model.defaultEffort.isNotBlank()) {
            put(ModelField.DEFAULT_EFFORT, model.defaultEffort.trim())
        }

        val keptHeaders = model.headers.filter { it.name.isNotBlank() }
        if (keptHeaders.isNotEmpty()) {
            put(
                ModelField.HEADERS,
                JSONArray().apply {
                    keptHeaders.forEach { header ->
                        put(
                            JSONObject()
                                .put("name", header.name.trim())
                                .put("value", header.value),
                        )
                    }
                },
            )
        }
        if (model.bodyOverride.isNotBlank()) put(ModelField.BODY, model.bodyOverride)
        if (model.remoteImageUrl.isNotBlank()) {
            put(ModelField.REMOTE_IMAGE, model.remoteImageUrl.trim())
        }
        model.builtinTools?.let { put(ModelField.BUILTIN_TOOLS, it) }
    }

    /** 一行模型。`id` 空的行直接跳过——没有 id 的模型是谁也发不出去的。 */
    fun decodeModel(row: JSONObject): ProviderModel? {
        val modelId = row.optString(ModelField.ID, "").trim()
        if (modelId.isEmpty()) return null
        return ProviderModel(
            id = modelId,
            name = row.optString(ModelField.NAME, ""),
            // 老数据没有 kind：按聊天处理（D191），否则它们会从选择器里消失。
            kind = ModelKinds.normalize(row.optString(ModelField.KIND, ModelKinds.CHAT)),
            contextWindow = positiveInt(row, ModelField.CONTEXT_WINDOW),
            maxTokens = positiveInt(row, ModelField.MAX_TOKENS),
            temperature = doubleOrNull(row, ModelField.TEMPERATURE),
            inputModalities = stringList(row.optJSONArray(ModelField.INPUT_MODALITIES)),
            outputModalities = stringList(row.optJSONArray(ModelField.OUTPUT_MODALITIES)),
            tools = boolOrNull(row, ModelField.TOOLS),
            reasoning = boolOrNull(row, ModelField.REASONING),
            structuredOutput = boolOrNull(row, ModelField.STRUCTURED_OUTPUT),
            inputPrice = doubleOrNull(row, ModelField.INPUT_PRICE),
            outputPrice = doubleOrNull(row, ModelField.OUTPUT_PRICE),
            cacheReadPrice = doubleOrNull(row, ModelField.CACHE_READ_PRICE),
            cacheWritePrice = doubleOrNull(row, ModelField.CACHE_WRITE_PRICE),
            currency = row.optString(ModelField.CURRENCY, "").trim(),
            reasoningDialect = row.optString(ModelField.REASONING_DIALECT, "").trim(),
            efforts = stringList(row.optJSONArray(ModelField.EFFORTS)),
            allowDisable = boolOrNull(row, ModelField.ALLOW_DISABLE),
            defaultEffort = row.optString(ModelField.DEFAULT_EFFORT, "").trim(),
            headers = headerRows(row.optJSONArray(ModelField.HEADERS)),
            bodyOverride = row.optString(ModelField.BODY, ""),
            remoteImageUrl = row.optString(ModelField.REMOTE_IMAGE, "").trim(),
            builtinTools = boolOrNull(row, ModelField.BUILTIN_TOOLS),
        )
    }

    fun encodeSources(sources: Map<String, Map<String, FieldSource>>): JSONObject =
        JSONObject().apply {
            sources.forEach { (modelId, row) ->
                put(
                    modelId,
                    JSONObject().apply { row.forEach { (key, source) -> put(key, source.wire) } },
                )
            }
        }

    fun decodeSources(doc: JSONObject?): Map<String, Map<String, FieldSource>> {
        if (doc == null) return emptyMap()
        val out = LinkedHashMap<String, Map<String, FieldSource>>()
        // 用 `keys()` 逐条取：`keySet()` 在 android.jar 那一版签名上会撞成"iterator 歧义"。
        for (modelId in keysOf(doc)) {
            val row = doc.optJSONObject(modelId) ?: continue
            val fields = LinkedHashMap<String, FieldSource>()
            for (key in keysOf(row)) {
                fields[key] = FieldSource.of(row.optString(key, ""))
            }
            out[modelId] = fields
        }
        return out
    }

    /** `JSONObject` 的键，按**写入顺序**（org.json 自己保序）——落盘顺序稳定，diff 才读得懂。 */
    private fun keysOf(doc: JSONObject): List<String> {
        val keys = doc.keys()
        val out = ArrayList<String>()
        while (keys.hasNext()) out.add(keys.next())
        return out
    }

    /** 三态布尔：**没有这个键**与「键在、值是 false」是两件事，不能合并。 */
    private fun boolOrNull(row: JSONObject, key: String): Boolean? =
        if (row.has(key) && !row.isNull(key)) row.optBoolean(key) else null

    private fun doubleOrNull(row: JSONObject, key: String): Double? =
        if (row.has(key) && !row.isNull(key)) row.optDouble(key, Double.NaN).takeIf { !it.isNaN() } else null

    private fun positiveInt(row: JSONObject, key: String): Int? =
        doubleOrNull(row, key)?.toInt()?.takeIf { it > 0 }

    private fun stringList(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val value = array.optString(index, "").trim()
                if (value.isNotEmpty()) add(value)
            }
        }
    }

    private fun headerRows(array: JSONArray?): List<HeaderRow> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val row = array.optJSONObject(index) ?: continue
                val headerName = row.optString("name", "").trim()
                if (headerName.isEmpty()) continue
                add(HeaderRow(headerName, row.optString("value", "")))
            }
        }
    }

    /**
     * 库里的 doc → [ProviderEntry]。**老形状（只有 `model` 一个字符串）在这里被就地解释**，
     * 不做一次性迁移：迁移要写库，写库就可能把没读懂的字段洗掉。
     */
    fun parse(id: String, doc: JSONObject?): ProviderEntry {
        if (doc == null) return ProviderEntry(id = id)
        val legacyModel = doc.optString("model", "").trim()
        val rows = doc.optJSONArray("models")
        val models = buildList {
            if (rows != null) {
                for (index in 0 until rows.length()) {
                    val row = rows.optJSONObject(index) ?: continue
                    decodeModel(row)?.let { add(it) }
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
            fieldSources = decodeSources(doc.optJSONObject("field_sources")),
        )
    }
}
