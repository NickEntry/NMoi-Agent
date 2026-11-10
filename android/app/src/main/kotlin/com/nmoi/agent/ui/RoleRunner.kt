package com.nmoi.agent.ui

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 按角色跑一次「一次性补全」（D291）。
 *
 * 只做一件事：把「默认模型」里这个角色的配置（供应商 + 模型）翻成 `nmoi_complete` 的入参。
 * 没有会话、不落库——这类旁路不该在历史里留下假对话（D158 的理由）。
 *
 * 现在接上的消费者只有**视觉覆盖**：发图时先让那个模型把图转录成文字，
 * 再把文字随消息发出去。主模型收不收图都不影响——它拿到的是文字。
 */
internal object RoleRunner {
    /**
     * 视觉覆盖那段固定提示词。
     *
     * 要的是**转录**：看得见的人不需要，看不见的那个模型需要的正是"图里到底有什么"。
     * 所以这里点明"不要评论、不要建议、不要总结成一句话"——那三种是模型的默认反应。
     */
    const val VISION_PROMPT =
        "把这张图里的内容完整转录成文字，写给一个看不见这张图的人。\n\n" +
            "要写到：画面里有什么（人物 / 物体 / 界面），图上的文字原样抄下来，布局与先后顺序，" +
            "颜色与状态（例如按钮是亮的还是灰的、哪一行被选中），以及一眼能看出含义的细节" +
            "（报错信息、进度、数字、光标位置）。\n\n" +
            "不要评论，不要给建议，不要总结成一句话。看不清的地方写「看不清」，不要猜。"

    /** 这个角色要用的 (供应商, 模型)；`null` = 不启用（未启用 / 跟随 / 供应商没了）。 */
    suspend fun target(
        context: Context,
        databasePath: String,
        role: ModelRole,
    ): Pair<ProviderEntry, String>? = withContext(Dispatchers.IO) {
        val raw = AppSettings.roleValue(context, role.key, role.followByDefault)
        // `disabled` / `follow` 都不是"指定了模型"：跟随 = 用同一个模型，旁路没有意义。
        if (!raw.contains('|')) return@withContext null
        val providerId = raw.substringBefore('|').trim()
        val modelId = raw.substringAfter('|').trim()
        if (providerId.isEmpty() || modelId.isEmpty()) return@withContext null
        val provider = ProviderRepository.load(databasePath, providerId).getOrNull()
            ?: return@withContext null
        if (provider.baseUrl.isBlank()) return@withContext null
        provider to modelId
    }

    /** 跑一次补全。`null` = 这个角色没指定模型（调用方按"这条旁路不启用"走）。 */
    suspend fun complete(
        context: Context,
        databasePath: String,
        role: ModelRole,
        prompt: String,
        system: String? = null,
        images: List<PendingImage> = emptyList(),
    ): String? = withContext(Dispatchers.IO) {
        val (provider, model) = target(context, databasePath, role) ?: return@withContext null
        // 这个角色自己的系统提示词（D298）：排在内置那段之前，正文（prompt）不动。
        // 空串就当没有——发一个空 system 会让某些上游把它当成"清空系统提示"。
        val extra = AppSettings.roleSystem(context, role.key).trim()
        val merged = listOfNotNull(extra.takeIf { it.isNotEmpty() }, system?.takeIf { it.isNotBlank() })
            .joinToString("\n\n")
            .ifBlank { null }
        val key = if (provider.multiKey) {
            KeyPool.pick(context, provider.id)
        } else {
            Vault.load(context, provider.id)
        }
        // 这个角色自己的思考强度与世界书（D313）。世界书空集 = 一册都不带，老行为不变。
        val thinking = AppSettings.roleThinking(context, role.key).trim()
        val books = AppSettings.roleLorebooks(context, role.key)
        val lorebook = if (books.isEmpty()) {
            null
        } else {
            LorebookStore.filterBy(LorebookStore.library(context), books).toString()
        }
        val config = JSONObject()
            .put(
                "provider",
                JSONObject()
                    .put("id", provider.id)
                    .put("kind", provider.kind)
                    .put("base_url", provider.baseUrl.trim())
                    .also { row -> if (key != null) row.put("api_key", key) },
            )
            .put("model", model)
            .put("prompt", prompt)
            .also { row -> merged?.let { row.put("system", it) } }
            .also { row -> if (thinking.isNotEmpty()) row.put("thinking", thinking) }
            .also { row -> lorebook?.let { row.put("lorebook", it) } }
            .also { row ->
                if (images.isNotEmpty()) {
                    val array = JSONArray()
                    images.forEach { image ->
                        array.put(JSONObject().put("mime", image.mime).put("data", image.data))
                    }
                    row.put("images", array)
                }
            }
        val result = NmoiFfi.complete(config)
        result.getOrThrow().optString("text").trim()
    }

    /**
     * 视觉覆盖：把这几张图转录成一段文字。
     *
     * `null` = 没启用（照旧把图发给主模型）。一张一段、各自带标号：四张图一起喂，
     * 模型常常只描述最后一张，而用户要的是"这四张分别是什么"。
     */
    suspend fun transcribe(
        context: Context,
        databasePath: String,
        images: List<PendingImage>,
    ): String? {
        if (images.isEmpty()) return null
        if (target(context, databasePath, ModelRole.Vision) == null) return null
        val parts = ArrayList<String>()
        images.forEachIndexed { index, image ->
            val text = complete(
                context = context,
                databasePath = databasePath,
                role = ModelRole.Vision,
                prompt = VISION_PROMPT,
                images = listOf(image),
            ) ?: return null
            if (text.isBlank()) return null
            parts.add("〔第 ${index + 1} 张图〕\n${text.trim()}")
        }
        return parts.joinToString("\n\n").ifBlank { null }
    }
}
