package com.nmoi.agent.ui

/**
 * 一个字段的"更新前 → 更新后"，只为显示（`from` / `to` 是人话）。
 */
internal data class FieldChange(val key: String, val label: String, val from: String, val to: String)

/**
 * 一个模型的更新计划。
 *
 * [write] 与 [changes] 分开：`changes` 是给眼睛看的（可以省略、可以本地化），
 * `write` 是真正落库的字段名单。用列表渲染 `changes` 的时候别顺手拿它当写入口。
 */
internal data class ModelPlan(
    val modelId: String,
    val displayName: String,
    /** null = 快照里没有这一条（界面上要说清，不许猜一个最像的）。 */
    val match: SnapshotMatch?,
    val changes: List<FieldChange>,
    /** 值确实不一样、但这一格是用户自己填的 → 不动它，只把原因说出来。 */
    val skipped: List<FieldChange>,
    val write: Set<String>,
    /**
     * 这一条是**猜**出来的（家族最新 / 厂商最新），用户还没点「用这条」。
     *
     * 猜出来的匹配只显示、不写。用户 2026-10-31 要的就是"没有对应数据时按公司最新模型判断"，
     * 但把猜的结果直接写进配置、他事后根本不知道哪几个数是猜的——所以多这一步确认。
     */
    val awaitingAccept: Boolean = false,
) {
    val entry: SnapshotEntry? get() = match?.entry
    val hasWork: Boolean get() = write.isNotEmpty()
}

/** 应用之后的结果：改完的模型表 + "哪些字段是快照写的"（`模型 → 字段 → 写进去的值`）。 */
internal data class UpdateApplied(
    val models: List<ProviderModel>,
    val inferred: Map<String, Map<String, String>>,
)

/**
 * 资源更新（D201 第三片）：把一份可信快照对齐到**用户没自己填过**的字段上。
 *
 * 三条界限，都在这一层里定死（界面只负责显示）：
 *
 * 1. **用户改过的不动**：`来源 = 自定义` 的字段一律跳过，并且把"为什么跳过"说出来——
 *    悄悄跳过和悄悄覆盖一样糟，用户下次只会发现"我明明改过"。
 * 2. **只写快照真有的**：快照里 `null` 的字段不碰（没有数据 ≠ 数据是空）。
 * 3. **值没变就不算更新**：避免"更新了 0 个字段"却给出 40 条 change 的假热闹。
 *
 * `apply` 同时交回 `inferred`：写进去的值与它出自快照这件事要一起落库，
 * 否则下次保存会被 `mergeSources` 判成"用户改的"（值的比对分不清是谁写的）。
 */
internal object ResourceUpdate {

    /** 快照 → 字段的对应关系。**没列在这里的字段，这一页不会碰。** */
    fun plan(
        models: List<ProviderModel>,
        sources: Map<String, Map<String, FieldSource>>,
        index: SnapshotIndex,
        /** 用户明确点了「用这条」的那些模型 id（只对"猜出来"的匹配有意义）。 */
        accepted: Set<String> = emptySet(),
    ): List<ModelPlan> = models.map { model ->
        val match = index.match(model.id)
        val entry = match?.entry
        val awaiting = match != null && match.how.guessed && model.id !in accepted
        val own = sources[model.id].orEmpty()
        val changes = mutableListOf<FieldChange>()
        val skipped = mutableListOf<FieldChange>()
        val write = LinkedHashSet<String>()

        for (candidate in candidates(model, entry)) {
            if (candidate.from == candidate.to) continue
            when {
                // 猜出来的匹配：先只显示"如果接受会改成什么"，一个字都不写。
                awaiting -> changes += candidate
                FieldSource.of(own[candidate.key]?.wire) == FieldSource.Custom -> skipped += candidate
                else -> {
                    changes += candidate
                    write += candidate.key
                }
            }
        }

        ModelPlan(
            modelId = model.id,
            displayName = model.displayName,
            match = match,
            changes = changes,
            skipped = skipped,
            write = write,
            awaitingAccept = awaiting,
        )
    }

    /**
     * 新加一个模型时**自动把快照里有的字段填上**（用户 2026-10-31 的要求）。
     *
     * 填进去的值一律算 `推断`（它们出自快照，不是用户手打的）：调用方拿第二项
     * `inferred` 交给 `ProviderRepository.save(..., inferred)`，界面上的徽标才会写「推断」。
     *
     * 猜出来的匹配（家族/厂商最新）**不填**：刚加一个模型就把别人的窗口写上去，
     * 是最容易让人对整份数据失去信任的那种错。要填得走资源更新页、点「用这条」。
     */
    fun fill(model: ProviderModel, entry: SnapshotEntry): Pair<ProviderModel, Map<String, String>> {
        var filled = model
        val written = LinkedHashMap<String, String>()
        for (key in ModelField.ALL) {
            if (key !in FILLABLE) continue
            // **只补空的**：用户已经填了的格子一个字都不动（"自动填"不该覆盖手打的值）。
            if (ModelField.valueOf(filled, key).isNotEmpty()) continue
            filled = writeField(filled, key, entry)
            val value = ModelField.valueOf(filled, key)
            if (value.isNotEmpty() && value != ModelField.valueOf(model, key)) {
                written[key] = value
            }
        }
        return filled to written
    }

    /** 自动填只碰这些：都是"快照真给得出"的字段。温度、显示名、类型这些它没有。 */
    private val FILLABLE = setOf(
        ModelField.CONTEXT_WINDOW,
        ModelField.MAX_TOKENS,
        ModelField.TOOLS,
        ModelField.STRUCTURED_OUTPUT,
        ModelField.REASONING,
        ModelField.EFFORTS,
        ModelField.INPUT_MODALITIES,
        ModelField.INPUT_PRICE,
        ModelField.OUTPUT_PRICE,
        ModelField.CURRENCY,
    )

    fun apply(models: List<ProviderModel>, plans: List<ModelPlan>): UpdateApplied {
        val byId = plans.filter { it.entry != null && it.write.isNotEmpty() }.associateBy { it.modelId }
        val inferred = LinkedHashMap<String, Map<String, String>>()
        val updated = models.map { model ->
            val plan = byId[model.id] ?: return@map model
            val entry = plan.entry ?: return@map model
            var next = model
            val written = LinkedHashMap<String, String>()
            for (key in ModelField.ALL) {
                if (key !in plan.write) continue
                next = writeField(next, key, entry)
                written[key] = ModelField.valueOf(next, key)
            }
            if (written.isNotEmpty()) inferred[model.id] = written
            next
        }
        return UpdateApplied(updated, inferred)
    }

    // -----------------------------------------------------------------------

    /**
     * 这个模型能被快照改动的字段。`from` 用"人话"，`to` 也是——两串一样就代表"不用动"。
     *
     * 注意 `from` 的取值与 `to` 的格式化**必须用同一套写法**，否则"没变"会被判成"变了"，
     * 于是每次打开这一页都显示一堆"更新"。
     */
    private fun candidates(model: ProviderModel, entry: SnapshotEntry?): List<FieldChange> {
        if (entry == null) return emptyList()
        val rows = mutableListOf<FieldChange>()

        entry.contextWindow?.let { value ->
            rows += FieldChange(
                ModelField.CONTEXT_WINDOW,
                "上下文窗口",
                intText(model.contextWindow),
                value.toString(),
            )
        }
        entry.maxOutput?.let { value ->
            rows += FieldChange(
                ModelField.MAX_TOKENS,
                "最大输出",
                intText(model.maxTokens),
                value.toString(),
            )
        }
        entry.tools?.let { value ->
            rows += FieldChange(ModelField.TOOLS, "工具调用", triText(model.tools), triText(value))
        }
        entry.structuredOutput?.let { value ->
            rows += FieldChange(
                ModelField.STRUCTURED_OUTPUT,
                "结构化输出",
                triText(model.structuredOutput),
                triText(value),
            )
        }
        entry.reasoning?.let { value ->
            rows += FieldChange(ModelField.REASONING, "推理", triText(model.reasoning), triText(value))
        }
        if (entry.efforts.isNotEmpty()) {
            rows += FieldChange(
                ModelField.EFFORTS,
                "支持的级别",
                effortsText(model.efforts),
                effortsText(entry.efforts),
            )
        }
        entry.images?.let { images ->
            val now = Modalities.normalize(model.inputModalities, Modalities.INPUT)
            val after = if (images) {
                Modalities.normalize(now + "image", Modalities.INPUT)
            } else {
                now - "image"
            }
            rows += FieldChange(
                ModelField.INPUT_MODALITIES,
                "输入模态",
                modalitiesText(now),
                modalitiesText(after),
            )
        }
        entry.inputPerMillion?.let { value ->
            rows += FieldChange(
                ModelField.INPUT_PRICE,
                "输入价",
                priceText(model.inputPrice),
                priceText(value),
            )
        }
        entry.outputPerMillion?.let { value ->
            rows += FieldChange(
                ModelField.OUTPUT_PRICE,
                "输出价",
                priceText(model.outputPrice),
                priceText(value),
            )
        }
        // 价格与货币是一件事：改了价却不写货币，界面上会出现"1.5 元"这种凭空多出来的单位。
        val currencyGap = (entry.inputPerMillion != null || entry.outputPerMillion != null) &&
            model.currency.trim().uppercase() != SNAPSHOT_CURRENCY
        if (currencyGap) {
            rows += FieldChange(
                ModelField.CURRENCY,
                "货币",
                model.currency.trim().ifEmpty { "没填" },
                SNAPSHOT_CURRENCY,
            )
        }
        return rows
    }

    private fun writeField(model: ProviderModel, key: String, entry: SnapshotEntry): ProviderModel = when (key) {
        ModelField.CONTEXT_WINDOW -> model.copy(contextWindow = entry.contextWindow ?: model.contextWindow)
        ModelField.MAX_TOKENS -> model.copy(maxTokens = entry.maxOutput ?: model.maxTokens)
        ModelField.TOOLS -> model.copy(tools = entry.tools ?: model.tools)
        ModelField.STRUCTURED_OUTPUT -> model.copy(
            structuredOutput = entry.structuredOutput ?: model.structuredOutput,
        )
        ModelField.REASONING -> model.copy(reasoning = entry.reasoning ?: model.reasoning)
        ModelField.EFFORTS -> model.copy(
            efforts = if (entry.efforts.isEmpty()) model.efforts else entry.efforts,
        )
        ModelField.INPUT_MODALITIES -> {
            val now = Modalities.normalize(model.inputModalities, Modalities.INPUT)
            val after = when (entry.images) {
                true -> Modalities.normalize(now + "image", Modalities.INPUT)
                false -> now - "image"
                null -> now
            }
            model.copy(inputModalities = after)
        }
        ModelField.INPUT_PRICE -> model.copy(
            inputPrice = entry.inputPerMillion ?: model.inputPrice,
        )
        ModelField.OUTPUT_PRICE -> model.copy(
            outputPrice = entry.outputPerMillion ?: model.outputPrice,
        )
        ModelField.CURRENCY -> model.copy(currency = SNAPSHOT_CURRENCY)
        else -> model
    }

    /**
     * 快照里的价格一律是美元（models.dev / OpenRouter / aihubmix 三家都用美元报）。
     * 写进去的时候把这个事实**写进字段**，而不是让界面"默认按美元显示"。
     */
    private const val SNAPSHOT_CURRENCY = "USD"

    private fun intText(value: Int?): String = value?.toString() ?: "没填"

    private fun triText(value: Boolean?): String = when (value) {
        true -> "支持"
        false -> "不支持"
        null -> "没填"
    }

    private fun effortsText(values: List<String>): String {
        val normalized = Efforts.normalize(values)
        return if (normalized.isEmpty()) "没填" else normalized.joinToString("·") { Efforts.label(it) }
    }

    private fun modalitiesText(values: List<String>): String {
        val normalized = Modalities.normalize(values, Modalities.INPUT)
        return if (normalized.isEmpty()) {
            "没填"
        } else {
            normalized.joinToString("·") { Modalities.label(it, Modalities.INPUT) }
        }
    }

    /** 价格：`0.27` / `1`（去掉无意义的小数位）——两个来源写法的差别不该显示成一次"更新"。 */
    private fun priceText(value: Double?): String {
        if (value == null) return "没填"
        val rounded = String.format("%.4f", value).trimEnd('0').trimEnd('.')
        return rounded.ifEmpty { "0" }
    }
}
