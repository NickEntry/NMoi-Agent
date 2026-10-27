package com.nmoi.agent.ui

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** 一轮跑完之后，界面该知道的几件事（不做成"模型原文"，那是库里的活）。 */
internal data class TurnOutcome(
    val assistant: String?,
    val rounds: Int,
    val toolCalls: Int,
    val cancelled: Boolean,
    val finished: Boolean,
    val compression: String?,
) {
    /** 提示行那一句。用户要能一眼看出"它到底跑完了没有"。 */
    fun describe(): String {
        val length = assistant?.length ?: 0
        return when {
            cancelled -> "已停止（这一轮留下的正文长度 $length）"
            !finished -> "轮数用尽，模型没收尾（已存 $length 字）"
            else -> buildString {
                append("模型回了 $length 字")
                if (rounds > 1) append("，$rounds 轮")
                if (toolCalls > 0) append("，${toolCalls} 次工具调用")
                compression?.let { append("；$it") }
            }
        }
    }
}

/**
 * 发请求那一条路（D157：`turn_run` 接进 UI）。
 *
 * 三件事按顺序排好，缺一样就别发：
 *
 * 1. **用哪一套**——现读库里的"当前供应商"（[ProviderRepository.active]），
 *    不用界面上那份快照。设置页刚改完就发，发的必须是改过的。
 * 2. **带哪把钥匙**——[Vault] 现解一次。解不开就照发（本地端点常常不要钥匙），
 *    真被 401 打回来时错误会带着状态码，那时候才知道是钥匙的问题。
 * 3. **落库**——不用我们写：`run_session_turn` 第一步就把用户那句话存进去，
 *    再发请求。所以**失败时用户的话可能已经进库了**，这一点必须回给界面
 *    （[wasUserTextSaved]），否则要么丢字，要么重复发两条一样的话。
 */
internal object TurnRepository {
    suspend fun send(
        context: Context,
        databasePath: String,
        sessionId: String,
        text: String,
    ): Result<TurnOutcome> = withContext(Dispatchers.IO) {
        // 用哪一套由**助手**决定（D161）：供应商页只是目录，不再有"当前"。
        val assistant = AssistantRepository.current(databasePath)
            .getOrElse { return@withContext Result.failure(it) }
        if (!assistant.isRunnable) {
            return@withContext Result.failure(
                NmoiFfiException(
                    "no_model",
                    "还没选模型：聊天页顶部的模型条里挑一个（供应商先要在设置里配好）",
                ),
            )
        }
        val provider = ProviderRepository.load(databasePath, assistant.providerId)
            .getOrElse { return@withContext Result.failure(it) }
        val model = provider.models.firstOrNull { it.id == assistant.modelId } ?: provider.model
        if (model == null) {
            return@withContext Result.failure(
                NmoiFfiException(
                    "no_model",
                    "「${provider.name.ifBlank { provider.id }}」里没有模型 ${assistant.modelId}",
                ),
            )
        }
        val temperature = assistant.temperature ?: model.temperature
        val maxTokens = assistant.maxTokens ?: model.maxTokens

        val key = Vault.load(context, provider.id)
        // 只记"有没有"，不记内容也不记长度：长度也是信息。
        NLog.i(
            "Turn",
            "会话 $sessionId 发往 ${provider.id}（${provider.kind}）模型 ${model.id} 带钥匙=${key != null}",
        )

        // 工作区：先建目录再绑。绑不上不是这一轮的致命错误——文件工具与命令不可用而已，
        // 聊天本身还能继续（所以只记一条日志，不中断）。
        val workspace = ToolingRepository.open(context)
        if (workspace.isFailure) {
            NLog.w("Turn", "工作区绑不上：${workspace.exceptionOrNull()?.message}")
        }
        val nativeShell = ToolingRepository.nativeShell(context) && workspace.isSuccess
        try {
            withStorageHandle(databasePath) { handle ->
                val config = JSONObject()
                    .put("storage", handle)
                    .put("session", sessionId)
                    .put(
                        "provider",
                        JSONObject()
                            .put("id", provider.id)
                            .put("kind", provider.kind)
                            .put("base_url", provider.baseUrl.trim())
                            .also { row -> if (key != null) row.put("api_key", key) },
                    )
                    .put("model", model.id)
                    // 工作区句柄 0 = 没绑（FFI 那边 `optional_u64(...).filter(|id| *id != 0)` 会忽略它）。
                    .put("workspace", workspace.getOrNull() ?: 0L)
                    .put("native_shell", nativeShell)
                    .put("user_text", text)
                    .put("now_ms", System.currentTimeMillis())
                    .also { row ->
                        maxTokens?.let { row.put("max_tokens", it) }
                        temperature?.let { row.put("temperature", it) }
                    }

                NmoiFfi.turnRun(config)
                    .map { envelope -> parse(envelope) }
                    .onSuccess { outcome ->
                        NLog.i(
                            "Turn",
                            "完成：rounds=${outcome.rounds} tool=${outcome.toolCalls} " +
                                "finished=${outcome.finished} cancelled=${outcome.cancelled} " +
                                "长度=${outcome.assistant?.length ?: 0}",
                        )
                    }
                    .onFailure { error ->
                        NLog.w("Turn", "失败：kind=${(error as? NmoiFfiException)?.kind} ${error.message}")
                    }
            }
        } finally {
            workspace.getOrNull()?.let { ToolingRepository.close(it) }
        }
    }

    /** 点"停止"。没在跑不算失败：`cancelled:false` 只说明它已经收尾了。 */
    suspend fun cancel(sessionId: String): Result<Boolean> =
        NmoiFfi.turnCancel(sessionId).map { envelope -> envelope.optBoolean("cancelled", false) }

    /**
     * 这一轮失败之后：用户那句话到底进库没有？
     *
     * 判据是**现读库**：最后一条消息是不是用户消息、正文是否与刚发的完全一样
     * （`run_session_turn` 原样存，不做 trim/改写，所以能这么比）。
     * 读不出来就保守当成"没存"——宁可让用户看到重复的一句话，也不要让他以为存了其实丢了。
     */
    suspend fun wasUserTextSaved(
        databasePath: String,
        sessionId: String,
        text: String,
    ): Boolean = withContext(Dispatchers.IO) {
        val snapshot = ChatRepository.load(databasePath, sessionId, limit = 1L)
        val last = snapshot.page?.units
            ?.flatMap { unit -> unit.messages }
            ?.lastOrNull()
        last != null && last.isUser && last.content == text
    }

    private fun parse(envelope: JSONObject): TurnOutcome {
        val report = envelope.optJSONObject("report")
        val assistant = envelope.optJSONObject("assistant")?.optString("content", "")
        return TurnOutcome(
            assistant = assistant?.takeIf { it.isNotEmpty() },
            rounds = report?.optInt("rounds", 0) ?: 0,
            toolCalls = report?.optJSONArray("tool_calls")?.length() ?: 0,
            cancelled = report?.optBoolean("cancelled", false) ?: false,
            finished = report?.optBoolean("finished", true) ?: true,
            compression = envelope.optJSONObject("compression")?.let { signal ->
                val trigger = signal.optJSONObject("trigger")?.optString("kind", "") ?: ""
                val action = signal.optString("action", "")
                when (trigger) {
                    "none", "" -> null
                    else -> "压缩信号 $trigger（$action）"
                }
            },
        )
    }
}
