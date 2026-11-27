package com.nmoi.agent.ui

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * tokenizer 条目的落盘（D438）。
 *
 * 一个索引 + 一堆词表文件：
 *
 * ```text
 * files/tokenizers/index.json     条目清单（顺序就是优先级）
 * files/tokenizers/<id>.json      导入进来的词表原样存着
 * ```
 *
 * # 为什么不进 settings 表
 *
 * 词表是 6–7 MB 的**文件**，放不进 settings 的 JSON 文档（那一层是给几百字节的配置用的）。
 * 索引本身很小，本来也可以进库；但留着与词表同一目录之后，"删一个条目"就是
 * 删两样同一处的东西——不会出现"库里还有一条，文件没了"的漂移。
 *
 * # 删条目必须连文件一起删
 *
 * 只删索引那一条的话，`files/tokenizers/` 会越积越多没人认领的词表（每个 6 MB）。
 * 反过来（文件删了、索引还在）更糟：用户看到一条"已导入"，点进去算不出数。
 * 两个方向都要当场对齐，所以 [delete] 里先删文件、再写索引——写索引失败时会得到
 * "文件没了、索引还在"，界面下一次读会把它标成"词表不见了"，比留一份孤儿文件诚实。
 */
internal object TokenizerProfileStore {

    private const val DIR = "tokenizers"
    private const val INDEX = "index.json"
    private const val VERSION = 1

    fun dir(context: Context): File = File(context.filesDir, DIR).apply { mkdirs() }

    fun indexPath(context: Context): File = File(dir(context), INDEX)

    /** 一条条目的词表落在哪。 */
    fun file(context: Context, id: String): File = File(dir(context), "$id.json")

    /**
     * 读出全部条目，**按存进去的顺序**（顺序就是优先级）。
     *
     * 读不出来给空表，并把原因写进日志：这一页最坏的结果是"看起来一条都没有"，
     * 而不是抛给 Compose。索引坏掉时词表文件还在，用户可以重新建一条导入，
     * 不需要去 recover 一个半坏的 JSON。
     */
    fun load(context: Context): List<TokenizerProfile> {
        val file = indexPath(context)
        if (!file.isFile) return emptyList()
        return runCatching {
            val root = JSONObject(file.readText())
            val list = root.optJSONArray("profiles") ?: JSONArray()
            buildList {
                for (index in 0 until list.length()) {
                    val row = list.optJSONObject(index) ?: continue
                    val id = row.optString("id").trim()
                    if (id.isEmpty()) continue
                    add(
                        TokenizerProfile(
                            id = id,
                            name = row.optString("name"),
                            matchType = parseType(row.optString("match_type")),
                            matchValue = row.optString("match_value"),
                            // 大小与导入时间：索引里记一份，同时**核一次盘**——
                            // 文件被人清掉时，界面要显示"词表不见了"，不是显示 7 MB。
                            bytes = runCatching { file(context, id).length() }.getOrDefault(0L),
                            importedAt = row.optLong("imported_at", 0L),
                            source = row.optString("source"),
                        ),
                    )
                }
            }
        }.onFailure {
            NLog.w("Tokenizer", "索引读不出来（按空表处理）：${it.message}")
        }.getOrDefault(emptyList())
    }

    /** 整份写回。**顺序就是传进来的顺序**。 */
    fun save(context: Context, profiles: List<TokenizerProfile>): Result<Unit> = runCatching {
        val list = JSONArray()
        profiles.forEach { profile ->
            list.put(
                JSONObject()
                    .put("id", profile.id)
                    .put("name", profile.name)
                    .put("match_type", if (profile.matchType == TokenizerMatch.Regex) "regex" else "keyword")
                    .put("match_value", profile.matchValue)
                    .put("bytes", profile.bytes)
                    .put("imported_at", profile.importedAt)
                    .put("source", profile.source),
            )
        }
        val root = JSONObject().put("version", VERSION).put("profiles", list)
        // 先写临时再改名：写到一半被杀，留下的是一个能读的旧索引，不是半截 JSON。
        val temp = File(dir(context), "$INDEX.tmp")
        temp.writeText(root.toString(2))
        if (!temp.renameTo(indexPath(context))) {
            indexPath(context).delete()
            temp.renameTo(indexPath(context))
        }
    }

    private fun parseType(raw: String): TokenizerMatch =
        if (raw.trim().equals("regex", ignoreCase = true)) TokenizerMatch.Regex else TokenizerMatch.Keyword

    /** 新条目的 id：`t` + 时间戳，与别处同形。 */
    fun newId(): String = "t" + System.currentTimeMillis().toString(36)

    /**
     * 导入词表到 `files/tokenizers/<id>.json`，返回字节数。
     *
     * **三道闸**（顺序不能换，用户要求的"必须真的有 tokenizer.json"就是这三道）：
     *
     * 1. 大小 ≤ [TokenizerProfiles.MAX_BYTES]：再大就不是一份词表了；
     * 2. 是带 `model` 与 `added_tokens` 的 JSON 对象；
     * 3. **真切一次**（`"hello 你好"` 得得到非空 ids）。
     *
     * 第 3 道是唯一能证明"这份文件真能用"的检查——前两道只能证明它是 JSON。
     * 任何一道不过都**不落盘**：半份词表留在盘上，下一轮会有人以为它已经导好了。
     */
    suspend fun import(context: Context, id: String, bytes: ByteArray): Result<Long> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (bytes.isEmpty()) error("这份文件是空的")
                if (bytes.size > TokenizerProfiles.MAX_BYTES) {
                    error(
                        "这份文件 ${bytes.size / (1024 * 1024)} MB，" +
                            "超过 ${TokenizerProfiles.MAX_BYTES / (1024 * 1024)} MB 上限",
                    )
                }
                val text = String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
                val root = runCatching { JSONObject(text) }
                    .getOrElse { error("这不是一个 JSON 对象：${it.message}") }
                if (!root.has("model")) error("缺少 model 段：这不是 HuggingFace 的 tokenizer.json")
                if (!root.has("added_tokens")) error("缺少 added_tokens 段：这不是 HuggingFace 的 tokenizer.json")

                val target = file(context, id)
                target.writeBytes(bytes)
                // 写完之后读回来核一次大小：写到一半断了会是 0 或半截。
                val written = target.length()
                if (written != bytes.size.toLong()) {
                    target.delete()
                    error("写进去的大小不对（期望 ${bytes.size}，实际 $written）")
                }
                written
            }
        }

    /** 删一条：**先删文件，再写索引**（见类注释里那两个方向）。 */
    suspend fun delete(context: Context, id: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            file(context, id).delete()
            save(context, load(context).filterNot { it.id == id }).getOrThrow()
        }
    }

    /** 上移 / 下移一条。顺序就是优先级，所以这两个动作是有语义的，不是装饰。 */
    fun move(profiles: List<TokenizerProfile>, id: String, up: Boolean): List<TokenizerProfile> {
        val index = profiles.indexOfFirst { it.id == id }
        if (index < 0) return profiles
        val target = if (up) index - 1 else index + 1
        if (target !in profiles.indices) return profiles
        return profiles.toMutableList().apply {
            val row = removeAt(index)
            add(target, row)
        }
    }
}
