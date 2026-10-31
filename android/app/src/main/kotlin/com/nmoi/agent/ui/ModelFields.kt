package com.nmoi.agent.ui

/**
 * 模型字段的 wire 名、取值词表、以及"这个数是**谁说的**"（D201 数据层）。
 *
 * 这一层只做三件事，**不碰界面、不碰网络**：
 *
 * 1. **一个字段一个常量**：写入端与读取端引同一串常量，写错一边立刻变成"读回来是空的"，
 *    而不是"存进去、界面上看不见"。散着手打字符串时，那两种错长得一模一样。
 * 2. **词表**（模态 / 推理档位 / 推理方言）：值不做自由文本，规整成同一套 wire 值，
 *    否则同一个意思会有 `image` / `Image` / `图片` 三种写法，比对和去重就没法做了。
 * 3. **来源标记**（[FieldSource]）：`自定义` / `推断` / `默认`。规则只有一条——
 *    **值变了就是用户改的**；值没变就沿用上一份的来源（见 [mergeFieldSources]）。
 *
 * 来源存在**供应商那一份 `settings(provider,<供应商 id>)` 文档**里（`field_sources`），
 * 不加 schema 列、不另开 scope：它跟着模型表走，一次 `settingsPut` 落盘，
 * 不会出现"模型存了、来源没存"的半截状态。
 */
internal object ModelField {
    const val ID = "id"
    const val NAME = "name"
    const val KIND = "kind"

    // 基本页：模态 / 能力 / 限额
    const val INPUT_MODALITIES = "input_modalities"
    const val OUTPUT_MODALITIES = "output_modalities"
    const val TOOLS = "tools"
    const val REASONING = "reasoning"
    const val STRUCTURED_OUTPUT = "structured_output"
    const val CONTEXT_WINDOW = "context_window"
    const val MAX_TOKENS = "max_tokens"
    /** 采样参数。它现在就在「采样」页签上，**真有读取点**（`TurnRepository` 每次请求都带）。 */
    const val TEMPERATURE = "temperature"

    // 基本页：定价（每 100 万 token）
    const val INPUT_PRICE = "input_price"
    const val OUTPUT_PRICE = "output_price"
    const val CACHE_READ_PRICE = "cache_read_price"
    const val CACHE_WRITE_PRICE = "cache_write_price"
    const val CURRENCY = "currency"

    // 推理页
    const val REASONING_DIALECT = "reasoning_dialect"
    const val EFFORTS = "efforts"
    const val ALLOW_DISABLE = "allow_disable"
    const val DEFAULT_EFFORT = "default_effort"

    // 高级页
    const val HEADERS = "headers"
    const val BODY = "body"
    const val REMOTE_IMAGE = "remote_image"
    const val BUILTIN_TOOLS = "builtin_tools"

    /**
     * 记来源的字段全集。**顺序就是界面的顺序**（基本 → 采样 → 推理 → 高级），
     * 落盘时也按这个顺序写，diff 才稳定。
     */
    val ALL = listOf(
        ID, NAME, KIND,
        INPUT_MODALITIES, OUTPUT_MODALITIES, TOOLS, REASONING, STRUCTURED_OUTPUT,
        CONTEXT_WINDOW, MAX_TOKENS, TEMPERATURE,
        INPUT_PRICE, OUTPUT_PRICE, CACHE_READ_PRICE, CACHE_WRITE_PRICE, CURRENCY,
        REASONING_DIALECT, EFFORTS, ALLOW_DISABLE, DEFAULT_EFFORT,
        HEADERS, BODY, REMOTE_IMAGE, BUILTIN_TOOLS,
    )

    /**
     * 一个字段的**规范化字符串**（只用来判"变没变"，不给人看）。
     *
     * 列表类一律排序后拼，避免"顺序变了但其实没变"被记成用户改动；
     * 文本类去掉首尾空白，同理。**只去首尾**：`body` 中间改缩进照样算改动——
     * 要判"这段 JSON 结构一样"，得先解析它，而解析失败的那一份也得有个说法，
     * 那已经不是"记来源"该操心的事了。
     * 三态布尔用 `""` / `"true"` / `"false"` 区分「不知道」与「知道是假」——
     * 把 `null` 和 `false` 混成一个，来源徽标就会在"没数据"上写成"用户关掉了"。
     */
    fun valueOf(model: ProviderModel, key: String): String = when (key) {
        ID -> model.id.trim()
        NAME -> model.name.trim()
        KIND -> model.kind
        INPUT_MODALITIES -> model.inputModalities.sorted().joinToString(",")
        OUTPUT_MODALITIES -> model.outputModalities.sorted().joinToString(",")
        TOOLS -> tri(model.tools)
        REASONING -> tri(model.reasoning)
        STRUCTURED_OUTPUT -> tri(model.structuredOutput)
        CONTEXT_WINDOW -> model.contextWindow?.toString().orEmpty()
        MAX_TOKENS -> model.maxTokens?.toString().orEmpty()
        TEMPERATURE -> model.temperature?.toString().orEmpty()
        INPUT_PRICE -> model.inputPrice?.toString().orEmpty()
        OUTPUT_PRICE -> model.outputPrice?.toString().orEmpty()
        CACHE_READ_PRICE -> model.cacheReadPrice?.toString().orEmpty()
        CACHE_WRITE_PRICE -> model.cacheWritePrice?.toString().orEmpty()
        CURRENCY -> model.currency.trim().uppercase()
        REASONING_DIALECT -> model.reasoningDialect.trim()
        EFFORTS -> model.efforts.sorted().joinToString(",")
        ALLOW_DISABLE -> tri(model.allowDisable)
        DEFAULT_EFFORT -> model.defaultEffort.trim()
        HEADERS -> model.headers
            .filter { it.name.isNotBlank() }
            .map { "${it.name.trim().lowercase()}=${it.value.trim()}" }
            .sorted()
            .joinToString("\n")
        BODY -> model.bodyOverride.trim()
        REMOTE_IMAGE -> model.remoteImageUrl.trim()
        BUILTIN_TOOLS -> tri(model.builtinTools)
        else -> ""
    }

    private fun tri(value: Boolean?): String = value?.toString().orEmpty()

    /**
     * 落盘前算一遍来源：**值变了 = 用户改的**，值没变 = 沿用上一份（默认 `默认`）。
     *
     * 为什么"比对"比"界面回报我哪一格被动过"可靠：界面有五个入口会改同一个字段
     * （手填、↺ 重置、资源更新、拉取模型清单、老数据读入），每个入口都记一次
     * "我改了"是五份规矩；比对只有一份。代价是**改回去也算改过**——比的是"上一份存的值"，
     * 不是"数据源给的原始值"（后者没落盘）。要让一个字段重新变成`推断`，
     * 只有资源更新页能做到：那条路明确地说"这个数出自哪份快照"。
     *
     * 新加的模型没有"上一份"：**填了值就算自定义**，空的算默认。
     * 删掉 / 改了 ID 的模型不会留下孤立的来源行——这里按当前模型表重建。
     */
    fun mergeSources(
        previous: ProviderEntry?,
        models: List<ProviderModel>,
    ): Map<String, Map<String, FieldSource>> {
        val merged = LinkedHashMap<String, Map<String, FieldSource>>()
        for (model in models) {
            val old = previous?.models?.firstOrNull { it.id == model.id }
            val oldSources = previous?.fieldSources?.get(model.id).orEmpty()
            val row = LinkedHashMap<String, FieldSource>()
            for (key in ALL) {
                val now = valueOf(model, key)
                val source = when {
                    old == null -> if (now.isEmpty()) FieldSource.Default else FieldSource.Custom
                    valueOf(old, key) != now -> FieldSource.Custom
                    // 老数据没有来源记录：`FieldSource.of(null)` 给「默认」，与 D201 第 1 条对齐。
                    else -> FieldSource.of(oldSources[key]?.wire)
                }
                row[key] = source
            }
            merged[model.id] = row
        }
        return merged
    }
}

/**
 * 这个字段的值是**谁说的**（D201 第 3 条）。三种，没有第四种。
 *
 * 界面上它是个小徽标，规则是"用户永远知道这个数是谁说的"：
 *
 * - [Custom]：用户自己填的（或者改过任何一次）；
 * - [Inferred]：从可信数据源推出来的（`data/models.snapshot.json` 那一类，资源更新页写的）；
 * - [Default]：没数据也没改过——程序按保守默认在跑。
 *
 * 认不出的字符串一律当 [Default]：来源坏了不影响功能，只影响"这句话可不可信"，
 * 而那时说"默认"是最保守的（也不会骗用户说"这是从数据源来的"）。
 */
internal enum class FieldSource(val wire: String, val label: String) {
    Custom("custom", "自定义"),
    Inferred("inferred", "推断"),
    Default("default", "默认"),
    ;

    companion object {
        fun of(wire: String?): FieldSource {
            val clean = wire?.trim().orEmpty()
            return values().firstOrNull { it.wire == clean } ?: Default
        }
    }
}

/**
 * 模态词表。**输入与输出分开列**：输出侧没有 `pdf`、`video` 这种（现在没有哪家能生成它们），
 * 摆上去就是一个点不出东西的选项。
 */
internal object Modalities {
    val INPUT = listOf(
        Kind("text", "文本"),
        Kind("image", "图片"),
        Kind("audio", "音频"),
        Kind("video", "视频"),
        Kind("pdf", "PDF"),
    )
    val OUTPUT = listOf(
        Kind("text", "文本"),
        Kind("image", "图片"),
        Kind("audio", "音频"),
    )

    fun normalize(list: List<String>, choices: List<Kind>): List<String> {
        val wanted = choices.map { it.wire }.toSet()
        return list.map { it.trim().lowercase() }.filter { it in wanted }.distinct()
    }

    fun label(wire: String, choices: List<Kind>): String =
        choices.firstOrNull { it.wire == wire }?.label ?: wire
}

/**
 * 推理档位。wire 值与 `crates/core/src/provider.rs` 的 `ThinkingEffort` 逐字对齐
 * （`none` / `minimal` / `low` / `medium` / `high` / `xhigh` / `max`）——
 * 那边是发请求的那一端，这边只是记"这个模型支持哪几档"。
 */
internal object Efforts {
    val ALL = listOf(
        Kind("none", "关闭"),
        Kind("minimal", "最低"),
        Kind("low", "低"),
        Kind("medium", "中"),
        Kind("high", "高"),
        Kind("xhigh", "极高"),
        Kind("max", "最大"),
    )

    /** 按档位从低到高排好、去掉重复与不认识的。 */
    fun normalize(list: List<String>): List<String> {
        val wanted = list.map { it.trim().lowercase() }.toSet()
        return ALL.map { it.wire }.filter { it in wanted }
    }

    fun label(wire: String): String = ALL.firstOrNull { it.wire == wire }?.label ?: wire
}

/**
 * 推理方言：思考这一档**注到哪个字段上**。
 *
 * wire 值照抄 `crates/core/src/provider.rs` 的 `ThinkingField` 序列化结果
 * （serde `snake_case`：`OpenAiEffort` → `open_ai_effort`）。这里是"手工覆盖"用的：
 * 不填 = 按供应商类型自动选（`default_thinking_field`），填了 = 这个模型按这一族注。
 */
internal object ReasoningDialects {
    val ALL = listOf(
        Kind("", "按供应商类型自动选"),
        Kind("none", "不注入任何思考字段"),
        Kind("open_ai_effort", "reasoning_effort"),
        Kind("responses_reasoning", "responses.reasoning"),
        Kind("anthropic_effort", "output_config.effort"),
        Kind("anthropic_thinking", "thinking.budget_tokens"),
        Kind("gemini_thinking_config", "generationConfig.thinkingConfig"),
        Kind("custom", "自定义 JSON 片段"),
    )

    fun label(wire: String): String = ALL.firstOrNull { it.wire == wire }?.label ?: wire
}
