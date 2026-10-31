package com.nmoi.agent.ui

import android.content.Context
import org.json.JSONObject

/**
 * 一份模型快照条目 —— 只取**事实字段**（长度 / 能力 / 档位 / 价格），
 * 描述文本、基准分、链接一律不取（见 `docs/model-data.md` 第 1 节）。
 */
internal data class SnapshotEntry(
    val id: String,
    val family: String,
    val contextWindow: Int?,
    val maxOutput: Int?,
    val tools: Boolean?,
    val structuredOutput: Boolean?,
    val reasoning: Boolean?,
    /** 该模型接受的档位（wire 值，已按低到高排好）。 */
    val efforts: List<String>,
    /** 快照只能说"能不能收图"，其余模态它没有。 */
    val images: Boolean?,
    val inputPerMillion: Double?,
    val outputPerMillion: Double?,
)

/**
 * 快照索引：把 1729 条压成两本字典。
 *
 * - `exact`：归一化后的 id / 别名 → 条目（**先查这个**：别名是上游自己给的对应关系）；
 * - `family`：归一化后的家族名 → 条目（模型名带版本尾巴时才用，且**必须唯一**）。
 *
 * 为什么不照搬 `core::model::resolve` 那条四级匹配链：那是"发请求时猜参数"用的，
 * 宁可多猜一级也不返回空；这里是"要不要改用户存下来的配置"，宁可少猜一级——
 * 猜错的代价是把一个模型的窗口写到另一个模型头上，而用户不会立刻发现。
 */
internal class SnapshotIndex(
    /** 快照生成时间（原样展示，"这份数据有多旧"是它唯一的有效期信息）。 */
    val fetchedAt: String,
    val entries: List<SnapshotEntry>,
) {
    private val exact: Map<String, SnapshotEntry>
    private val family: Map<String, SnapshotEntry?>

    init {
        val exactMap = LinkedHashMap<String, SnapshotEntry>()
        val familyMap = LinkedHashMap<String, SnapshotEntry?>()
        for (entry in entries) {
            exactMap.putIfAbsent(ModelSnapshot.normalize(entry.id), entry)
            // 家族名只登记一次：同一个家族出现第二条，就把它标成"歧义"（null），
            // 这样按家族匹配时不会挑到"排在前面的那一条"。
            val familyKey = ModelSnapshot.normalize(entry.family)
            if (familyKey.isNotEmpty()) {
                familyMap[familyKey] = if (familyMap.containsKey(familyKey)) null else entry
            }
        }
        exact = exactMap
        family = familyMap
    }

    /** 找不到 = 快照没有这一条（或者家族有歧义）。界面要说"快照里没有"，不许猜一个。 */
    fun find(modelId: String): SnapshotEntry? {
        val normalized = ModelSnapshot.normalize(modelId)
        if (normalized.isEmpty()) return null
        exact[normalized]?.let { return it }
        val (familyKey, _) = ModelSnapshot.splitTrailingVersion(normalized)
        if (familyKey.isEmpty() || familyKey == normalized) return null
        return family[familyKey]
    }
}

/**
 * 读随包走的 `data/models.snapshot.json`（由 gradle 同步进 assets 的 `model-data/`）。
 *
 * 为什么随包走而不是联网拉：这一页的用途是"把用户手上这份配置对齐到一份可信数据"，
 * 拉不到网的时候它也不能变成一条假路径；刷新数据是构建期的事（`scripts/fetch_models.py`），
 * 跟版本一起发出去，谁都知道自己用的是哪一天的那份。
 */
internal object ModelSnapshot {
    const val ASSET = "model-data/models.snapshot.json"

    /** 读不到就返回 null（assets 没同步进来 / 解析失败都算）。界面据此说"这一版没带数据"。 */
    fun load(context: Context): SnapshotIndex? = runCatching {
        val text = context.assets.open(ASSET).bufferedReader().use { it.readText() }
        parse(text)
    }.getOrElse { error ->
        NLog.w("Model", "读不到模型快照：${error.message}")
        null
    }

    fun parse(text: String): SnapshotIndex {
        val root = JSONObject(text)
        val fetchedAt = root.optJSONObject("provenance")?.optString("fetched_at", "").orEmpty()
        val rows = root.optJSONArray("entries") ?: return SnapshotIndex(fetchedAt, emptyList())
        val entries = buildList {
            for (index in 0 until rows.length()) {
                val row = rows.optJSONObject(index) ?: continue
                val id = row.optString("id", "").trim()
                if (id.isEmpty()) continue
                add(entryOf(row, id))
            }
        }
        return SnapshotIndex(fetchedAt, entries)
    }

    private fun entryOf(row: JSONObject, id: String): SnapshotEntry {
        val profile = row.optJSONObject("profile")
        val caps = profile?.optJSONObject("capabilities")
        val cost = profile?.optJSONObject("cost")
        return SnapshotEntry(
            id = id,
            family = row.optString("family", "").trim().ifEmpty { id },
            contextWindow = positiveInt(profile, "context_window"),
            maxOutput = positiveInt(profile, "max_output"),
            tools = boolOrNull(caps, "tools"),
            structuredOutput = boolOrNull(caps, "structured_output"),
            reasoning = boolOrNull(profile, "reasoning"),
            efforts = Efforts.normalize(strings(profile?.optJSONArray("supported_efforts"))),
            images = boolOrNull(caps, "images"),
            inputPerMillion = doubleOrNull(cost, "input_per_million"),
            outputPerMillion = doubleOrNull(cost, "output_per_million"),
        )
    }

    private fun boolOrNull(doc: JSONObject?, key: String): Boolean? =
        if (doc != null && doc.has(key) && !doc.isNull(key)) doc.optBoolean(key) else null

    private fun doubleOrNull(doc: JSONObject?, key: String): Double? =
        if (doc != null && doc.has(key) && !doc.isNull(key)) {
            doc.optDouble(key, Double.NaN).takeIf { !it.isNaN() }
        } else {
            null
        }

    private fun positiveInt(doc: JSONObject?, key: String): Int? =
        doubleOrNull(doc, key)?.toInt()?.takeIf { it > 0 }

    private fun strings(array: org.json.JSONArray?): List<String> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                val value = array.optString(index, "").trim()
                if (value.isNotEmpty()) add(value)
            }
        }
    }

    /**
     * 归一化：与 `crates/core/src/model.rs` 的 `normalize` 同一套规则
     * （剥 `[分组]` 前缀 → 小写 → `.` `_` 空格 `/` `:` 一律变 `-` → 合并连续分隔符）。
     *
     * 两份实现会漂移，所以这里只做"够用来查字典"的程度：查不到就是查不到，
     * 不会因为少归一化了一种写法就把 A 模型的数字写到 B 上。
     */
    fun normalize(raw: String): String {
        var text = raw.trim()
        while (text.startsWith("[")) {
            val end = text.indexOf(']')
            if (end < 0) break
            text = text.substring(end + 1).trimStart()
        }
        return text
            .lowercase()
            .map { ch ->
                if (ch == '.' || ch == '_' || ch == ' ' || ch == '\t' || ch == '/' || ch == ':') '-' else ch
            }
            .joinToString("")
            .split('-')
            .filter { it.isNotEmpty() }
            .joinToString("-")
    }

    /** 把结尾的版本段切出来：`claude-3-7-sonnet-20250219` → (`claude-3-7-sonnet`, `20250219`)。 */
    fun splitTrailingVersion(normalized: String): Pair<String, String> {
        if (normalized.isEmpty() || !normalized.last().isDigit()) return normalized to ""
        var start = normalized.length
        while (start > 0) {
            val ch = normalized[start - 1]
            if (ch.isDigit() || ch == '.' || ch == '-' || ch == '_') start-- else break
        }
        val version = normalized.substring(start)
        if (version.isEmpty()) return normalized to ""
        val family = normalized.substring(0, start).trimEnd('-', '_', '.')
        // 纯数字的名字（`gpt-4` 这种整串都是数字的极少见）不切，切了就成空家族了。
        return if (family.isEmpty()) normalized to "" else family to version
    }
}
