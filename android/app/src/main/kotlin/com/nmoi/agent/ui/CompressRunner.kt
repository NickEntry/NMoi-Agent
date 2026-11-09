package com.nmoi.agent.ui

import android.content.Context
import org.json.JSONObject

/**
 * 上下文压缩的入口（D305）。
 *
 * 两步走：`plan` 只算切点（不调模型、不动数据），`run` 真的跑一轮并把结果写进库。
 * 分两步是因为压缩**永久移走一段历史**：先让用户看见"会移走哪一段"，再让他按下去。
 *
 * 密钥跟着供应商的模式走（D302）：多 Key 时从池里挑一把，单 Key 时读那一个文件——
 * 与聊天发请求是同一条规矩，不然"压缩用 A 号、聊天用 B 号"会很难查。
 */
internal object CompressRunner {
    /** 窗口里默认留多少 token 的原文不动。 */
    const val KEEP_RECENT = 8_192

    suspend fun plan(databasePath: String, sessionId: String): Result<JSONObject> =
        withStorageHandle(databasePath) { handle ->
            NmoiFfi.compressPlan(
                JSONObject()
                    .put("storage", handle)
                    .put("session", sessionId)
                    .put("keep_recent", KEEP_RECENT),
            )
        }

    suspend fun run(
        context: Context,
        databasePath: String,
        sessionId: String,
        provider: ProviderEntry,
        modelId: String,
        keepRecent: Int = KEEP_RECENT,
    ): Result<JSONObject> = withStorageHandle(databasePath) { handle ->
        val key = if (provider.multiKey) {
            KeyPool.pick(context, provider.id)
        } else {
            Vault.load(context, provider.id)
        }
        val config = JSONObject()
            .put("storage", handle)
            .put("session", sessionId)
            .put("keep_recent", keepRecent)
            .put("model", modelId)
            .put(
                "provider",
                JSONObject()
                    .put("id", provider.id)
                    .put("kind", provider.kind)
                    .put("base_url", provider.baseUrl.trim()),
            )
        if (!key.isNullOrBlank()) config.put("api_key", key)
        NmoiFfi.compressRun(config)
    }
}
