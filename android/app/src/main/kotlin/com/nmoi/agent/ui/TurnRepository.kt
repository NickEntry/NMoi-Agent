package com.nmoi.agent.ui

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** 一轮跑完之后，界面该知道的几件事（不做成"模型原文"，那是库里的活）。 */
internal data class TurnOutcome(
    val assistant: String?,
    val rounds: Int,
    val toolCalls: Int,
    val cancelled: Boolean,
    val finished: Boolean,
    /** 上下文到了绝对线（D90）提前停手：与"到了轮数上限"是两回事，文案要分开。 */
    val truncated: Boolean = false,
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
            // 停止（D252）：正文为空时**这一版没有落库**，措辞要说清，别让人以为存了个空壳。
            cancelled && length == 0 ->
                "已停止：这一轮没有正文，没有落版本（旧的那一版留着）"
            cancelled -> "已停止（这一轮留下的正文长度 $length$edited$failed）"
            // 这句以前写「轮数用尽，模型没收尾」——像在报错，其实是我自己设的上限。
            // 现在上限可调（D244），文案也要写清"这是上限、不是模型坏了"。
            // 两种"没做完"要分开说（D245）：到了上下文绝对线，还是（用户自己设的）轮数上限。
            truncated -> "上下文到了绝对线，这一轮提前停手（已存 $length 字$edited$failed）"
            !finished -> "到了工具轮数上限，模型还没收尾（已存 $length 字$edited$failed；" +
                "上限在 应用设置 → 工具轮数 里调，填 0 就是不限制）"
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
        /** 图片（D208）：`[{mime,data}]`，core 会把它们挂在这一轮新落的那条用户消息上。 */
        images: List<PendingImage> = emptyList(),
    ): Result<TurnOutcome> = runTurn(
        context = context,
        databasePath = databasePath,
        sessionId = sessionId,
        decorate = { config ->
            config.put("user_text", text)
            if (images.isNotEmpty()) {
                val array = org.json.JSONArray()
                for (image in images) {
                    array.put(
                        org.json.JSONObject()
                            .put("mime", image.mime)
                            .put("data", image.data),
                    )
                }
                config.put("images", array)
            }
        },
        invoke = { config -> NmoiFfi.turnRun(config) },
        parse = { envelope -> parse(envelope) },
    )

    /**
     * 重试 / 续话（0.15h / D185）：**前缀重发**，不新增用户消息。
     *
     * `upTo` = 发出去的最后一条消息（含），`target` = 结果落成哪条助手消息的新版本。
     * 两个都由界面按"段"算好再传（段首 = 最后一个分界线之后）：core 那条路只认 id，
     * 替它猜的后果是"重试把别的段也发了"或者"新版本落在一条不相干的消息上"。
     */
    suspend fun regen(
        context: Context,
        databasePath: String,
        sessionId: String,
        upTo: String,
        target: String?,
        allowNewVersion: Boolean,
        /**
         * 续话（D193）：落库的正文接在 `target` 现在的正文后面；`false` = 重试（整段换掉）。
         *
         * `target` 为空时这一项没有意义（落的是新消息），所以只在有 target 时才发出去。
         */
        append: Boolean = false,
        /**
         * 这一轮走 SSE（D227）：增量进 native 的 partial 表，界面轮询 `turn_busy`
         * 把正文**就地**画在目标消息上。默认开——重试与发送用同一条路，
         * 不再有"发送逐字长、重试只能干等"这种两套行为。
         */
        stream: Boolean = true,
        /**
         * 重试落在哪儿（D284）：`in_place` = 只换 `target` 这一条，后面的消息不动；
         * 空 = 整轮替换（跟以前一样）。只有重试"不是最新"的那条时才会传 `in_place`。
         */
        regenScope: String? = null,
    ): Result<TurnOutcome> = runTurn(
        context = context,
        databasePath = databasePath,
        sessionId = sessionId,
        decorate = { config ->
            config.put("up_to", upTo).put("allow_new_version", allowNewVersion)
            config.put("stream", stream)
            if (append) config.put("append", true)
            regenScope?.let { config.put("regen_scope", it) }
            target?.let { config.put("target", it) }
        },
        invoke = { config -> NmoiFfi.turnRegen(config) },
        parse = { envelope -> parseRegen(envelope) },
    )

    /**
     * 一轮请求的公共部分：现读助手 / 供应商 / 模型、带钥匙、绑工作区、拼配置、调 FFI、收尾。
     *
     * **为什么抽出来**：`turn_run` 与 `turn_regen` 要的是同一份配置（core 那边就是同一个
     * `parse_config`）。两份各拼一遍，迟早一边加了 `thinking`、另一边忘了——那种错不报错，
     * 只会让"重试"发出去的东西和"发送"不一样。
     *
     * `parse` 由调用方给：两条 FFI 的返回形状不同（`turn_json` 带 `report`/`edits`/`presets`，
     * `regen` 是平的 `{text, rounds, target, appended}`），在这里统一解释就等于把两份口径揉成一份。
     */
    private suspend fun runTurn(
        context: Context,
        databasePath: String,
        sessionId: String,
        /** 往基础配置上补这一轮特有的字段。 */
        decorate: (JSONObject) -> Unit,
        invoke: suspend (JSONObject) -> Result<JSONObject>,
        parse: (JSONObject) -> TurnOutcome,
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
        // **不回退**（用户 2026-11-01 定的）：助手指着的那个模型不在了，就报错。
        //
        // 以前这里是 `?: provider.model`，也就是"悄悄换成本供应商的第一个模型"——
        // 用户看到的是"未选择模型"，发出去的却是别人的请求；那种错花的是他的钱，
        // 而且他没有任何办法发现。宁可发不出去。
        val model = provider.models.firstOrNull { it.id == assistant.modelId }
        if (model == null) {
            return@withContext Result.failure(
                NmoiFfiException("no_model", "未选择模型，无法发送消息"),
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
        //
        // 工具根是**助手绑的那个工作区**（D234）：用户建的、可复用、天生共享。
        // 会话自己的东西（附件、工具产物）在 `sessions/<id>/chat`，容器里是 `/chat`，
        // 由 `envir` 那段单独绑进去——它不是工具根。
        // 会话可以覆盖助手绑的那个（D271）：`null` = 跟助手。
        // 读不到（库还没建）就退回助手那一份——聊天不该因为一个工作区读不出来就发不出去。
        val override = if (sessionId.isNotBlank()) {
            ConversationRepository.workspaceOf(databasePath, sessionId)
        } else {
            null
        }
        val bound = WorkspaceRepository.resolve(context, override ?: assistant.workspaceId)
        val workspace = ToolingRepository.open(context, bound.dir)
        if (workspace.isFailure) {
            NLog.w("Turn", "工作区绑不上：${workspace.exceptionOrNull()?.message}")
        }
        val nativeShell = ToolingRepository.nativeShell(context) && workspace.isSuccess
        try {
            withStorageHandle(databasePath) { handle ->
                // 隔离环境（D172）：**装好了 + 开关开着**才把坐标交给 core。
                // 只给一半（开关开着但没装）会让模型看到一个"声明了但一调就失败"的工具，
                // 所以这里两个条件都满足才发，界面上也照这个口径显示。
                // 自检没过同样不发：容器起不来的话，每一条命令都只会带一句看不懂的错回来。
                val envir = if (ToolingRepository.envirShell(context)) {
                    EnvirRepository.installed(context)
                        ?.takeIf { it.health != EnvirRepository.Health.NeedsRepair }
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
                    // 跨会话只读（D295）：默认关；关着时 `Session-Files` 不声明。
                    .put("cross_session", ToolingRepository.crossSession(context))
                    .put("thinking", assistant.thinking)
                    .also { row ->
                        envir?.let { row.put("envir", envirJson(context, it, sessionId)) }
                    }
                    // 预设（0.14t / D178）：原文从包里读，用户改过的那份由 core 从库里读。
                    // 过滤按"这一轮真的声明了哪些工具"算，**由 core 算**——
                    // 界面这边只负责把原文递进去。
                    // 流式（0.15n）：开着，界面才能"边收边画"。
                    // FFI 那边不认识这个字段就当没开——二进制与界面版本错开时不会炸。
                    .put("stream", assistant.stream)
                    .put("presets", PresetRepository.assetsText(context))
                    .put("now_ms", System.currentTimeMillis())
                    // 工具轮数上限（D244）：安全阀，不是产品决定。用户在应用设置里能调。
                    .put("max_rounds", AppSettings.maxRounds(context))
                    // 上下文图片上限（D248）：`View-Image` 拉图进来时的清单长度。
                    .put("image_limit", AppSettings.imageLimit(context))
                    .put("image_mode", AppSettings.imageMode(context))
                    .put("image_temp_limit", AppSettings.imageTempLimit(context))
                    .also { row ->
                        maxTokens?.let { row.put("max_tokens", it) }
                        temperature?.let { row.put("temperature", it) }
                    }
                    .also { row ->
                        // 模型编辑页（D201 第四片）那三样真的接进请求：
                        // 自定义 Header / 自定义 Body / 推理方言与档位。
                        //
                        // 空值一律不发：FFI 那边把"没这个字段"当"用默认"，
                        // 发一个空数组/空串反而要它多判一次（两份规矩，迟早不一致）。
                        val keptHeaders = model.headers.filter { it.name.isNotBlank() }
                        if (keptHeaders.isNotEmpty()) {
                            row.put(
                                "headers",
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
                        // 自定义 Body：按行填的 key → JSON 值，在这里拼成一个对象再发。
                        // FFI 那边收的仍是**字符串**（它自己解析、自己拦保留键），
                        // 所以这里只负责把字面量拼对；填坏的行在保存时已经拦过了。
                        val bodyRows = model.bodyFields.filter { it.name.isNotBlank() }
                        if (bodyRows.isNotEmpty()) {
                            val body = JSONObject()
                            bodyRows.forEach { field ->
                                val parsed = runCatching {
                                    org.json.JSONTokener(field.value).nextValue()
                                }.getOrNull()
                                if (parsed != null) body.put(field.name.trim(), parsed)
                            }
                            if (body.length() > 0) row.put("body", body.toString())
                        }
                        if (model.reasoningDialect.isNotBlank()) {
                            row.put("thinking_field", model.reasoningDialect)
                        }
                        if (model.efforts.isNotEmpty()) {
                            row.put("thinking_efforts", JSONArray(model.efforts))
                        }
                        model.allowDisable?.let { row.put("thinking_allow_disable", it) }
                        if (model.defaultEffort.isNotBlank()) {
                            row.put("thinking_default", model.defaultEffort)
                        }
                    }
                    .also { row ->
                        // 压缩判据（D90）：窗口来自**这个模型**填的 `contextWindow`。
                        //
                        // 以前这一条从来没传过——core 那边 `config.budget` 一直是 `None`，
                        // 于是"压缩判据"一次都没评估过，用户填的窗口也就等于没填。
                        // 没填 = 不评估：宁可不压，也不要按一个猜出来的数去压。
                        //
                        // 模型没填窗口就按「应用设置」里的默认值算（用户 2026-10-31 定的）：
                        // 不填也得有判据，否则"压缩"这件事在界面上永远不发生。
                        val window = (model.contextWindow ?: AppSettings.defaultContextWindow(context))
                            .coerceAtLeast(1)
                        row.put(
                            "budget",
                            JSONObject()
                                .put("window", window.toLong())
                                .put(
                                    "reserve_output",
                                    AppSettings.reserveOutput(context)
                                        .takeIf { it > 0 }
                                        ?: reserveOutput(maxTokens, window),
                                )
                                // 两条线（D274）：用户在 应用设置 → 压缩 里能改。
                                .put("suggest", AppSettings.suggestLine(context))
                                .put("absolute", AppSettings.absoluteLine(context)),
                        )
                    }

                // 世界书（D280）：整份 JSON 递进去，core 那边按关键词激活、按位置注入。
                // 没导入就不发这个字段——省一次解析，也省得 core 判空串。
                .also { row ->
                    val text = LorebookStore.text(context)
                    if (text.isNotBlank()) {
                        // 真正生效的是**助手身上那一份**（2026-11-08 三层语义）：
                        // 册级开关只管"显示在＋里"，用户勾过的那几册才参与注入。
                        // `null` = 没设置过 → 全部启用册都算（老版本升上来不变）。
                        val filtered = LorebookStore.filterBy(LorebookStore.parse(text), assistant.lorebooks)
                        row.put("lorebook", filtered.toString())
                    }
                }
                // 上下文档位与内容形态阈值（D275）。F1 = 跟以前一模一样。
                .put("context_form", AppSettings.formLevel(context))
                .put(
                    "router",
                    JSONObject()
                        .put("small", AppSettings.routerValue(context, "context.router.small", AppSettings.DEFAULT_ROUTER_SMALL))
                        .put("large", AppSettings.routerValue(context, "context.router.large", AppSettings.DEFAULT_ROUTER_LARGE))
                        .put("huge", AppSettings.routerValue(context, "context.router.huge", AppSettings.DEFAULT_ROUTER_HUGE))
                        .put("head", AppSettings.routerValue(context, "context.router.head", AppSettings.DEFAULT_ROUTER_HEAD))
                        .put("tail", AppSettings.routerValue(context, "context.router.tail", AppSettings.DEFAULT_ROUTER_TAIL)),
                )

                // 这一轮特有的字段（`user_text` / `up_to` + `target`…）在基础配置之后加。
                decorate(config)

                invoke(config)
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
        sessionId: String,
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
            // 会话自己的 `/chat`（D234）：工具产物与附件落在那儿，模型要看就自己用命令读。
            // 附件目录单独一条绑定，是因为它不挪地方（D216 的路径写进了库与存储页）。
            row.put("chat", ToolingRepository.chatRoot(context, sessionId).absolutePath)
            // 附件目录**先建好再绑**：这一份配置是在用户那条消息落库之前拼的，
            // 那时 `images/` 还不存在（第一次带图就是这样），
            // 于是"有条...件才绑"等于永远不绑——容器里 `/chat/images` 一直缺席。
            val images = ToolingRepository.sessionImagesRoot(context, sessionId).apply { mkdirs() }
            row.put("chat_images", images.absolutePath)
            // 会话自己的 `/tmp`（D294）：容器里的 `/tmp` 与文件工具的 `/tmp` 是同一份。
            // 同样**先建好**——目录不存在时 proot 的 `-b` 静默不生效。
            val scratch = ToolingRepository.sessionTmpRoot(context, sessionId).apply { mkdirs() }
            row.put("session_tmp", scratch.absolutePath)
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

    /**
     * `turn_regen` 的信封是平的：`{text, reasoning, target, appended, rounds, tool_calls, finished}`。
     *
     * 和 `turn_json` 的形状不一样（那个包着 `report` / `assistant` / `edits` / `presets`），
     * 所以不能共用 [parse]——硬套会得到"回了 0 字、0 轮"这种一声不响的错。
     */
    private fun parseRegen(envelope: JSONObject): TurnOutcome {
        val landed = envelope.optString("target", "")
        val appended = envelope.optString("appended", "")
        NLog.i(
            "Turn",
            "前缀重发完成：rounds=${envelope.optInt("rounds", 0)} " +
                "落版本=${landed.ifEmpty { "没给 target" }} 新增=${appended.ifEmpty { "无" }}",
        )
        return TurnOutcome(
            assistant = envelope.optString("text", "").takeIf { it.isNotEmpty() },
            rounds = envelope.optInt("rounds", 0),
            toolCalls = envelope.optInt("tool_calls", 0),
            // 这两个字段以前没读：`finished=false` 一律被读成"到了轮数上限"，
            // 于是"用户点了停止"也会报成"到了上限"（2026-11-04 真机就是这么报的）。
            cancelled = envelope.optBoolean("cancelled", false),
            finished = envelope.optBoolean("finished", true),
            truncated = envelope.optBoolean("truncated", false),
            compression = null,
            editedBlocks = 0,
            failedEdits = 0,
            presetsLine = "",
        )
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
            truncated = report?.optBoolean("truncated", false) ?: false,
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
