package com.nmoi.agent.ui

import android.content.Context
import java.io.File
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
    /** 这一轮模型真的改了几块消息内容（D177 / 0.14y）。`edits` 已经落库，消息区重读即可。 */
    val editedBlocks: Int,
    /** 想改但没写进去的块数（被锁定、内容没变、库拒绝）。有就一定要说。 */
    val failedEdits: Int,
    /** 这一轮预设注入了什么（给请求日志，不上界面）。 */
    val presetsLine: String,
) {
    /** 提示行那一句。用户要能一眼看出"它到底跑完了没有"。 */
    fun describe(): String {
        val length = assistant?.length ?: 0
        // 改了消息这件事必须出现在提示行：用户看到的是"某条旧消息变了"，
        // 不给一句话，那变化看着像自己眼花（铁律①的下一半：改完要能看出改掉了）。
        val edited = when {
            editedBlocks <= 0 -> ""
            else -> "，改了 $editedBlocks 处消息内容"
        }
        val failed = if (failedEdits > 0) "，$failedEdits 处没写进去（原因在日志）" else ""
        return when {
            cancelled -> "已停止（这一轮留下的正文长度 $length$edited$failed）"
            !finished -> "轮数用尽，模型没收尾（已存 $length 字$edited$failed）"
            else -> buildString {
                append("模型回了 $length 字")
                if (rounds > 1) append("，$rounds 轮")
                if (toolCalls > 0) append("，${toolCalls} 次工具调用")
                append(edited)
                append(failed)
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
                // 隔离环境（D172）：**装好了 + 开关开着**才把坐标交给 core。
                // 只给一半（开关开着但没装）会让模型看到一个"声明了但一调就失败"的工具，
                // 所以这里两个条件都满足才发，界面上也照这个口径显示。
                val envir = if (ToolingRepository.envirShell(context)) {
                    EnvirRepository.installed(context)
                } else {
                    null
                }
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
                .put("thinking", assistant.thinking)
                    .also { row -> envir?.let { row.put("envir", envirJson(context, it)) } }
                    .put("user_text", text)
                    // 预设（0.14t / D178）：原文从包里读，用户改过的那份由 core 从库里读。
                    // 过滤按"这一轮真的声明了哪些工具"算，**由 core 算**——
                    // 界面这边只负责把原文递进去。
                    .put("presets", PresetRepository.assetsText(context))
                    .put("now_ms", System.currentTimeMillis())
                    .also { row ->
                        maxTokens?.let { row.put("max_tokens", it) }
                        temperature?.let { row.put("temperature", it) }
                    }
                    .also { row ->
                        // 压缩判据（D90）：窗口来自**这个模型**填的 `contextWindow`。
                        //
                        // 以前这一条从来没传过——core 那边 `config.budget` 一直是 `None`，
                        // 于是"压缩判据"一次都没评估过，用户填的窗口也就等于没填。
                        // 没填 = 不评估：宁可不压，也不要按一个猜出来的数去压。
                        val window = model.contextWindow
                        if (window != null && window > 0) {
                            row.put(
                                "budget",
                                JSONObject()
                                    .put("window", window.toLong())
                                    .put(
                                        "reserve_output",
                                        reserveOutput(maxTokens, window),
                                    ),
                            )
                        }
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
                        // 预设（0.14t）：注入了哪几条、版本多少——用户要这个位置。
                        // 一条都没注入也要说（"没注入"与"注入了但没显示"是两件事）。
                        NLog.i("Preset", outcome.presetsLine)
                    }
                    .onFailure { error ->
                        NLog.w("Turn", "失败：kind=${(error as? NmoiFfiException)?.kind} ${error.message}")
                    }
            }
        } finally {
            workspace.getOrNull()?.let { ToolingRepository.close(it) }
        }
    }

    /**
     * 给输出留多少位置：优先用模型填的最大输出，没有就按窗口的四分之一，夹在 1024–8192。
     *
     * 压太狠（留太多）会让上下文剩不下多少；留太少则模型可能写一半被截断。
     * 这个区间是"能正常答完一段话"的经验值。
     */
    private fun reserveOutput(maxTokens: Int?, window: Int): Long {
        val base = maxTokens ?: (window / 4)
        return base.coerceIn(1024, 8192).toLong()
    }

    /** 交给 core 的五个宿主机路径。少一个 core 会在解析时点名，不会拼出一条半截命令。 */
    private fun envirJson(
        context: Context,
        installed: EnvirRepository.Installed,
    ): JSONObject = JSONObject()
        .put("proot", EnvirRepository.prootPath(context).absolutePath)
        .put("loader", File(EnvirRepository.runtimeDir(context), "libnmoi_proot_loader.so").absolutePath)
        .put("lib_dir", EnvirRepository.ensureLibLinks(context).getOrThrow().absolutePath)
        .put("rootfs", installed.rootfs)
        .put("tmp", File(EnvirRepository.envirDir(context), "tmp").apply { mkdirs() }.absolutePath)
        .also { row ->
            // DNS（D173）：**现取现写**，写在容器起来之前。没有 DNS 就干脆不给这个字段，
            // core 那边（`parse_envir`）把它当可选——离线也能跑，只是解析不出域名。
            EnvirRepository.writeResolvConf(context)?.let { file -> row.put("resolv", file.absolutePath) }
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
        // 模型改消息（D177 / 0.14y）：`edits` 是这一轮收尾时真落库的那几块。
        // 成功与失败分开数——"模型说改好了"和"库里真的变了"是两件事。
        val edits = envelope.optJSONArray("edits")
        val edited = (0 until (edits?.length() ?: 0))
            .mapNotNull { edits?.optJSONObject(it) }
            .filter { it.optBoolean("ok", false) }
        val failed = (0 until (edits?.length() ?: 0))
            .mapNotNull { edits?.optJSONObject(it) }
            .filterNot { it.optBoolean("ok", false) }
        if (edited.isNotEmpty() || failed.isNotEmpty()) {
            NLog.i(
                "Turn",
                "改了消息：${edited.size} 块成功（${edited.joinToString { it.optString("block") }}），" +
                    "${failed.size} 块没写成",
            )
            failed.forEach { NLog.w("Turn", "没写成：${it.optString("block")} —— ${it.optString("message")}") }
        }
        // 预设注入报告（0.14t）：`{"chars":n,"injected":[{"preset","entry","version"}]}`。
        val presets = envelope.optJSONObject("presets")
        val injected = presets?.optJSONArray("injected")
        val presetsLine = buildString {
            val count = injected?.length() ?: 0
            if (count == 0) {
                append("这一轮没有注入预设（工具都没开到、或者预设全被停用）")
            } else {
                append("注入 $count 条预设（${presets?.optInt("chars", 0) ?: 0} 字）：")
                append(
                    (0 until count).mapNotNull { injected?.optJSONObject(it) }
                        .joinToString("，") { row ->
                            val preset = row.optString("preset", "?")
                            val entry = row.optString("entry", "?")
                            val version = row.optInt("version", 0)
                            "$preset/$entry@v$version"
                        },
                )
            }
        }
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
            editedBlocks = edited.size,
            failedEdits = failed.size,
            presetsLine = presetsLine,
        )
    }
}
