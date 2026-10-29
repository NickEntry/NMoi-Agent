package com.nmoi.agent.ui

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** 一条词条此刻的形态（四态由 core 算，这里只搬）。 */
internal data class PresetEntryView(
    val id: String,
    val title: String,
    val state: String,
    val label: String,
    val content: String,
    val upstream: String,
    val version: Int,
    val disabled: Boolean,
    /** 上游那一版的正文摘要：新建这一份文档时当 `base_hash` 存下去（**不在 Kotlin 里另算一份**）。 */
    val upstreamHash: String,
    val injectable: Boolean,
    /**
     * 用户上次对齐的那一版（没改过就是空）。
     *
     * 保存正文时要**原样写回**：不写回等于替他宣布"我改的时候已经对齐新版了"，
     * 而那是他没有做的决定（D178 的"绝不自动覆盖"）。
     */
    val baseVersion: Int?,
    val baseHash: String?,
) {
    val needsAttention: Boolean get() = state != "follow"
}

/** 一份预设 + 它的词条。 */
internal data class PresetView(
    val id: String,
    val title: String,
    val summary: String,
    val version: Int,
    val appliesTo: List<String>,
    val state: String,
    val label: String,
    val entries: List<PresetEntryView>,
)

internal data class PresetList(
    val items: List<PresetView>,
    /** core 给的那一句：「3 条预设：1 条可更新 · 1 条冲突」。 */
    val summaryLine: String,
    val needsAttention: Int,
)

/**
 * 预设（0.14t / D178）。
 *
 * 三件事，各有各的家，**不要互相搬**：
 *
 * 1. **原文**在 APK 的 `assets/presets/<id>/{manifest.json,preset.json}`——随包走，改了要发版。
 * 2. **用户改过的那一份**在 `settings(scope="preset", key="<预设>/<词条>")`——只记正文与
 *    `base_id/base_version/base_hash`（"上一次同步时看到的那一版"）。不动 schema。
 * 3. **四态与注入文案**由 core 算（`nmoi_presets_json` / `turn_run` 内部同一份）——
 *    界面显示什么、请求里注入了什么，不能是两个算法。
 */
internal object PresetRepository {
    /** 资产目录。名字写死一处：改目录要同时改这里与打包脚本。 */
    private const val DIR = "presets"

    /** 读出来的 assets 只在这一个进程里缓存：APK 里的东西运行期不会变。 */
    @Volatile
    private var cachedAssets: String? = null

    /**
     * 把 `assets/presets/` 下的每份预设拼成 core 要的那份 JSON。
     *
     * 形状：`{"presets":[{"id","version","title","summary","applies_to":[…],"entries":[…]}]}`
     * ——`manifest.json` 给元信息（版本、适用工具、标题），`preset.json` 给词条正文。
     * **两个文件都在才收**：只有一个说明打包漏了，这时候宁可少一条也不要凭空造。
     */
    fun assetsText(context: Context): String {
        cachedAssets?.let { return it }
        val presets = JSONArray()
        val dirs = context.assets.list(DIR).orEmpty().sorted()
        for (name in dirs) {
            val manifest = readJson(context, "$DIR/$name/manifest.json")
            val body = readJson(context, "$DIR/$name/preset.json")
            if (manifest == null || body == null) {
                NLog.w("Preset", "预设 $name 少一个文件（manifest/preset），跳过")
                continue
            }
            val id = manifest.optString("id", "")
            if (id.isEmpty() || id != name) {
                // 目录名与 id 不一致：`base_id` 会跟着错，四态从此算不准。宁可跳过并说清。
                NLog.w("Preset", "预设目录 $name 与 manifest.id「$id」不一致，跳过")
                continue
            }
            presets.put(
                JSONObject()
                    .put("id", id)
                    .put("version", manifest.optInt("version", 0))
                    .put("title", manifest.optString("title", id))
                    .put("summary", manifest.optString("summary", ""))
                    .put("applies_to", manifest.optJSONArray("applies_to") ?: JSONArray())
                    .put("entries", body.optJSONArray("entries") ?: JSONArray()),
            )
        }
        val text = JSONObject().put("presets", presets).toString()
        cachedAssets = text
        return text
    }

    private fun readJson(context: Context, path: String): JSONObject? = runCatching {
        JSONObject(context.assets.open(path).bufferedReader().use { it.readText() })
    }.onFailure {
        NLog.w("Preset", "读 $path 失败：${it.message}")
    }.getOrNull()

    /** 列表 + 四态。走 core，界面不自己推。 */
    suspend fun list(context: Context, databasePath: String): Result<PresetList> =
        withContext(Dispatchers.IO) {
            val assets = assetsText(context)
            withStorageHandle(databasePath) { handle ->
                NmoiFfi.presets(handle, assets).map { envelope -> parseList(envelope) }
            }
        }

    private fun parseList(envelope: JSONObject): PresetList {
        val items = mutableListOf<PresetView>()
        val array = envelope.optJSONArray("items") ?: JSONArray()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val entries = mutableListOf<PresetEntryView>()
            val rows = item.optJSONArray("entries") ?: JSONArray()
            for (row in 0 until rows.length()) {
                val entry = rows.optJSONObject(row) ?: continue
                entries.add(
                    PresetEntryView(
                        id = entry.optString("id", ""),
                        title = entry.optString("title", ""),
                        state = entry.optString("state", "follow"),
                        label = entry.optString("label", ""),
                        content = entry.optString("content", ""),
                        upstream = entry.optString("upstream", ""),
                        version = entry.optInt("version", 0),
                        disabled = entry.optBoolean("disabled", false),
                        upstreamHash = entry.optString("upstream_hash", ""),
                        injectable = entry.optBoolean("injectable", true),
                        baseVersion = entry.optJSONObject("base")?.optInt("version"),
                        baseHash = entry.optJSONObject("base")?.optString("hash"),
                    ),
                )
            }
            items.add(
                PresetView(
                    id = item.optString("id", ""),
                    title = item.optString("title", ""),
                    summary = item.optString("summary", ""),
                    version = item.optInt("version", 0),
                    appliesTo = item.optJSONArray("applies_to")?.let { list ->
                        (0 until list.length()).map { list.optString(it, "") }
                    }.orEmpty(),
                    state = item.optString("state", "follow"),
                    label = item.optString("label", ""),
                    entries = entries,
                ),
            )
        }
        val summary = envelope.optJSONObject("summary")
        return PresetList(
            items = items,
            summaryLine = envelope.optString("summary_line", "还没有预设"),
            needsAttention = summary?.optInt("needs_attention", 0) ?: 0,
        )
    }

    /**
     * 保存一条词条的正文（正文只改这一份）。
     *
     * `base` 传的是**用户动手那一刻看到的上游版本**（[PresetEntryView.version] 与
     * [PresetEntryView.upstreamHash]）：只有新建这份文档时才用它。已经有文档时不覆盖 base
     * ——覆盖等于悄悄宣布"我和新版对齐了"，那正是"绝不自动覆盖"要防的事。
     */
    suspend fun saveEntry(
        databasePath: String,
        presetId: String,
        entry: PresetEntryView,
        content: String,
        disabled: Boolean,
        /**
         * 要写回去的 `base`：默认用**视图带出来的那一份**（已经有的就用已有的，
         * 没有的才用上游这一刻的）。只有「保留本地」会显式传上游那一版——那是它的定义。
         */
        baseVersion: Int = entry.baseVersion ?: entry.version,
        baseHash: String = entry.baseHash ?: entry.upstreamHash,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        withStorageHandle(databasePath) { handle ->
            val doc = JSONObject()
                .put("base_id", "$presetId/${entry.id}")
                .put("base_version", baseVersion)
                .put("base_hash", baseHash)
                .put("content", content)
                .put("disabled", disabled)
            NmoiFfi.settingsPut(handle, SettingsScope.Preset, key(presetId, entry.id), doc)
                .map { }
        }
    }

    /** 回到"跟随上游"：删掉这份文档，正文就是包里那一份。 */
    suspend fun follow(databasePath: String, presetId: String, entryId: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            withStorageHandle(databasePath) { handle ->
                NmoiFfi.settingsDelete(handle, SettingsScope.Preset, key(presetId, entryId))
                    .map { }
            }
        }

    /**
     * 「保留本地 / 用上游」——两个动作都只做一件事：**把 base 前进到上游此刻那一版**。
     *
     * - 保留本地：写入 `content`（本地那份）→ 状态从"冲突"变成"已改"。
     * - 用新的：删文档 → 状态回到"跟随"，正文就是上游那份。
     *
     * 「用新的」不复用 [follow] 的名字是因为它们在界面上是两句话：一个是"我不要改了"，
     * 一个是"我要你说的新版"——语义一样，入口不一样，调用点读起来要对得上。
     */
    suspend fun keepLocal(
        databasePath: String,
        presetId: String,
        entry: PresetEntryView,
    ): Result<Unit> = saveEntry(
        databasePath = databasePath,
        presetId = presetId,
        entry = entry,
        content = entry.content,
        disabled = entry.disabled,
        // 「留本地」的**定义**就是"把 base 前进到上游这一版、正文不动"：
        // 所以这里显式给上游的值，不走默认那一条。
        baseVersion = entry.version,
        baseHash = entry.upstreamHash,
    )

    suspend fun takeUpstream(
        databasePath: String,
        presetId: String,
        entryId: String,
    ): Result<Unit> = follow(databasePath, presetId, entryId)

    /** 词条在 settings 里的 key。与 `crates/ffi/src/preset.rs` 的 `doc_key` 必须一致。 */
    fun key(presetId: String, entryId: String): String = "$presetId/$entryId"

    /** 一次"这一轮会注入什么"的自检（界面上不摆，但它让"预设到底生效没有"可验证）。 */
    suspend fun injectPreview(
        context: Context,
        databasePath: String,
        declared: List<String>,
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        val assets = assetsText(context)
        val declaredJson = JSONArray(declared).toString()
        withStorageHandle(databasePath) { handle ->
            NmoiFfi.presetsInject(handle, assets, declaredJson)
        }
    }
}
