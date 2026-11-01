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
    /** 版本段（`-1017` → `[1017]`）。空 = 家族束形条目。比"谁最新"只认它。 */
    val version: List<Int> = emptyList(),
    val contextWindow: Int?,
    val maxOutput: Int?,
    val tools: Boolean?,
    val structuredOutput: Boolean?,
    val reasoning: Boolean?,
    /** 该模型接受的档位（wire 值，已按低到高排好）。 */
    val efforts: List<String>,
    /** 快照只能说"能不能收图"，其余模态它没有。 */
    val images: Boolean?,
    /**
     * 输入 / 输出模态（D209，来自 models.dev 的 MIT 数据）。
     *
     * `null` = 这条快照没有这个字段（老条目，或者上游没给）——**不等于"只收文本"**。
     * 空列表也按"没数据"处理，免得把一条真能收图的模型写成只收文本。
     */
    val inputModalities: List<String>? = null,
    val outputModalities: List<String>? = null,
    /** 别名（上游自己给的对应关系）：`raccoon/sn-deepseek-...` 这类写法常在别名里。 */
    val aliases: List<String> = emptyList(),
    val inputPerMillion: Double?,
    val outputPerMillion: Double?,
) {
    /** 自己的 id（归一化）。**字典里最权威的一把键**：它指的就是这一条。 */
    val idKey: String get() = ModelSnapshot.normalize(id)

    /** 别名（归一化）。同一个别名可能挂在两条上——那种时候 id 优先（见 [SnapshotIndex]）。 */
    val aliasKeys: List<String> by lazy {
        aliases.map { ModelSnapshot.normalize(it) }.filter { it.isNotEmpty() }.distinct()
    }
}

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
/**
 * 怎么对上的。前三种是"同一个名字的另一种写法"，可以直接用；
 * 后两种是**猜**的（家族最新 / 同一厂商最新），必须让用户点一下才写——
 * 猜错一个模型，他以后每个数字都得自己核。
 */
internal enum class MatchHow(val label: String, val guessed: Boolean) {
    Exact("名字一致", false),
    Stripped("去掉前缀 / 日期后一致", false),
    DigitsJoined("版本号写法不同（v4-1 ≈ v41）", false),
    FamilyLatest("按同一家族的最新版本猜的", true),
    VendorLatest("按同一厂商的最新模型猜的", true),
}

internal data class SnapshotMatch(val entry: SnapshotEntry, val how: MatchHow)

internal class SnapshotIndex(
    /** 快照生成时间（原样展示，"这份数据有多旧"是它唯一的有效期信息）。 */
    val fetchedAt: String,
    val entries: List<SnapshotEntry>,
) {
    private val exact: Map<String, SnapshotEntry>
    /** 家族 → 该家族的全部条目（按"版本从新到旧"排好）。 */
    private val family: Map<String, List<SnapshotEntry>>
    /** 数字合写后的键 → 条目（`v4-1` 与 `v41` 是同一个键）。 */
    private val joined: Map<String, SnapshotEntry>
    /** 厂商（id 的第一个词）→ 该厂商的条目，按"版本从新到旧"排好。 */
    private val vendor: Map<String, List<SnapshotEntry>>

    init {
        val exactMap = LinkedHashMap<String, SnapshotEntry>()
        val joinedMap = LinkedHashMap<String, SnapshotEntry>()
        val familyMap = LinkedHashMap<String, MutableList<SnapshotEntry>>()
        val vendorMap = LinkedHashMap<String, MutableList<SnapshotEntry>>()
        // **两遍**：先登记每一份自己的 id，再登记别名。
        //
        // 一遍写死（只是 putIfAbsent）会踩到真事：快照里 `deepseek-1017` 与
        // `deepseek-v4-1-flash-1017` 都挂别名 `deepseek-v4.1-flash-1017`，谁先被读到谁赢。
        // 自己的 id 比"挂在别人名下的别名"更权威，所以分两遍。
        for (entry in entries) {
            exactMap.putIfAbsent(entry.idKey, entry)
            joinedMap.putIfAbsent(ModelSnapshot.joinDigits(entry.idKey), entry)
        }
        for (entry in entries) {
            for (key in entry.aliasKeys) {
                exactMap.putIfAbsent(key, entry)
                joinedMap.putIfAbsent(ModelSnapshot.joinDigits(key), entry)
            }
            val familyKey = ModelSnapshot.normalize(entry.family)
            if (familyKey.isNotEmpty()) familyMap.getOrPut(familyKey) { mutableListOf() }.add(entry)
            val vendorKey = ModelSnapshot.normalize(entry.id).substringBefore('-')
            if (vendorKey.length >= 3) {
                vendorMap.getOrPut(vendorKey) { mutableListOf() }.add(entry)
            }
        }
        exact = exactMap
        joined = joinedMap
        family = familyMap.mapValues { (_, rows) -> rows.sortedWith(newestFirst) }
        vendor = vendorMap.mapValues { (_, rows) -> rows.sortedWith(newestFirst) }
    }

    /** 只要条目（不看怎么对上的）。界面里已经改过的老调用点用它。 */
    fun find(modelId: String): SnapshotEntry? = match(modelId)?.entry

    /**
     * 五档匹配，**先严后松**。返回 `null` = 快照里没有（界面要说"没有"，不许猜一个）。
     *
     * 用户 2026-10-31 的原话给了三个例子：`sn-deepseek-v4-1-flash-1017`、
     * `deepseek-v41-flash`、`deepseek-flash`——分别对应"去装饰"、"数字合写"、
     * "家族/厂商最新"三档。设计成阶梯而不是一个模糊分，是为了**能把'这是猜的'说出来**。
     */
    fun match(modelId: String): SnapshotMatch? {
        val raw = modelId.trim()
        if (raw.isEmpty()) return null
        val candidates = ModelSnapshot.candidates(raw)
        // 第 0 个是原样写法（Exact），其余都是"去掉某种装饰"之后的样子（Stripped）。
        // 两个分开报，是因为界面要写清"这个是怎么对上的"——用户才判断得了该不该信。
        candidates.forEachIndexed { position, candidate ->
            exact[candidate]?.let { entry ->
                return SnapshotMatch(
                    entry = entry,
                    how = if (position == 0) MatchHow.Exact else MatchHow.Stripped,
                )
            }
        }
        for (candidate in candidates) {
            joined[ModelSnapshot.joinDigits(candidate)]?.let {
                return SnapshotMatch(it, MatchHow.DigitsJoined)
            }
        }
        // 家族：从长到短试前缀（`deepseek-v4-1-flash-xyz` → … → `deepseek-v4-1`）。
        // 同名家族有多条时取**版本最新**的那一条（用户要的就是"最新的那份数据"）。
        // 单段前缀（`deepseek`）留给下面那一档，别在这里抢——那是"整家公司"的猜测。
        for (candidate in candidates) {
            for (prefix in ModelSnapshot.prefixes(candidate)) {
                family[prefix]?.firstOrNull()?.let { return SnapshotMatch(it, MatchHow.FamilyLatest) }
            }
        }
        // 厂商：`deepseek-flash` 这种"公司 + 档位"的名字，落到该厂商最新的一条上。
        //
        // 这是**最后一档**，也是最粗的一档：到了这里说明前四档全空，名字里只剩"公司"是对的。
        // 猜出来的结果不会自动写（`SnapshotMatch.how.guessed` → 界面要求点「用这条」）。
        for (candidate in candidates) {
            val parts = candidate.split('-')
            if (parts.size < 2) continue
            val vendorKey = parts[0]
            if (vendorKey.length < 3) continue
            vendor[vendorKey]?.firstOrNull()?.let { return SnapshotMatch(it, MatchHow.VendorLatest) }
        }
        return null
    }

    companion object {
        /** 版本从新到旧：版本号大的在前；都没版本时短名字在前（束形条目比带日期的更"正"）。 */
        private val newestFirst = Comparator<SnapshotEntry> { left, right ->
            val byVersion = compareVersions(right.version, left.version)
            if (byVersion != 0) {
                byVersion
            } else {
                left.id.length.compareTo(right.id.length)
            }
        }

        private fun compareVersions(left: List<Int>, right: List<Int>): Int {
            val size = maxOf(left.size, right.size)
            for (index in 0 until size) {
                val a = left.getOrElse(index) { -1 }
                val b = right.getOrElse(index) { -1 }
                if (a != b) return a.compareTo(b)
            }
            return 0
        }
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
            version = ints(row.optJSONArray("version")),
            aliases = strings(row.optJSONArray("aliases")),
            contextWindow = positiveInt(profile, "context_window"),
            maxOutput = positiveInt(profile, "max_output"),
            tools = boolOrNull(caps, "tools"),
            structuredOutput = boolOrNull(caps, "structured_output"),
            reasoning = boolOrNull(profile, "reasoning"),
            efforts = Efforts.normalize(strings(profile?.optJSONArray("supported_efforts"))),
            images = boolOrNull(caps, "images"),
            inputModalities = modalities(profile, "input_modalities"),
            outputModalities = modalities(profile, "output_modalities"),
            inputPerMillion = doubleOrNull(cost, "input_per_million"),
            outputPerMillion = doubleOrNull(cost, "output_per_million"),
        )
    }

    /** 模态数组：缺字段 / 空数组都返回 `null`（"没数据"，不是"只收文本"）。 */
    private fun modalities(doc: JSONObject?, key: String): List<String>? {
        val array = doc?.optJSONArray(key) ?: return null
        val values = strings(array).map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        return values.takeIf { it.isNotEmpty() }
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

    private fun ints(array: org.json.JSONArray?): List<Int> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until array.length()) add(array.optInt(index, 0))
        }
    }

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

    /**
     * 同一个名字的几种写法，**从原样到越来越宽**。
     *
     * 顺序就是匹配的顺序：先按原样查，再逐级去装饰——先去供应商前缀（`raccoon/sn-…` → `sn-…`），
     * 再去 `sn-` 这种短前缀，最后去尾部的日期/序号（`-1017`、`-20250219`）。
     * 这样"同一个名字"先命中，宽松写法只在必要时才用，不会把 A 的条目安到 B 头上。
     */
    fun candidates(raw: String): List<String> {
        val out = LinkedHashSet<String>()
        val whole = normalize(raw)
        if (whole.isNotEmpty()) out += whole
        val lastSegment = raw.substringAfterLast('/').trim()
        if (lastSegment.isNotEmpty() && lastSegment != raw) {
            val normalized = normalize(lastSegment)
            if (normalized.isNotEmpty()) out += normalized
        }
        for (base in out.toList()) {
            val stripped = base.removePrefix("sn-")
            if (stripped != base && stripped.isNotEmpty()) out += stripped
        }
        for (base in out.toList()) {
            val trimmed = base.split('-').dropLastWhile { part -> part.all(Char::isDigit) }
            if (trimmed.isNotEmpty()) {
                val joined = trimmed.joinToString("-")
                if (joined.isNotEmpty() && joined != base) out += joined
            }
        }
        return out.toList()
    }

    /** 从长到短的前缀（至少两段、至少 3 个字符）：家族档只吃这个范围。 */
    fun prefixes(key: String): List<String> {
        val parts = key.split('-')
        if (parts.size < 2) return emptyList()
        return buildList {
            for (size in parts.size downTo 2) {
                val prefix = parts.take(size).joinToString("-")
                if (prefix.length >= 3) add(prefix)
            }
        }
    }

    /**
     * 只把**两段数字之间的连字符**去掉：`v4-1` → `v41`，`gpt-4-o` 不动。
     *
     * 用户给的例子 `deepseek-v41-flash` 与快照里的 `deepseek-v4-1-flash` 说的是同一个模型，
     * 差别只在有没有那个连字符。宽松归宽松，仍然要求其余部分逐字相同。
     */
    fun joinDigits(key: String): String = key.replace(Regex("(?<=[0-9])-(?=[0-9])"), "")

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
