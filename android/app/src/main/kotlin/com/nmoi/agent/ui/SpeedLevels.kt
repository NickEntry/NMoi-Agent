package com.nmoi.agent.ui

/**
 * 速度档位（D402）：与思考强度是**两根轴**。思考决定想多深，速度决定出字多快。
 *
 * # 为什么两家不共用一张表
 *
 * GPT 写 `service_tier`（`flex` / `default` / `priority` / `ultrafast`），
 * Claude 写 `speed`（只有 `standard` / `fast`）。**档位数量都不一样**，
 * 所以不能"四格里灰掉两格"——那会让人以为那两档将来会有。
 *
 * 这一层只做三件事：算这个模型该列哪几档、档位写进哪个字段、倍率是多少。
 * 界面与请求组装都引这里，不各拼一遍。
 */
internal enum class SpeedTier(val wire: String, val zh: String, val en: String) {
    Flex("flex", "省流", "flex"),
    Standard("standard", "标准", "standard"),
    Priority("priority", "优先", "priority"),
    Ultrafast("ultrafast", "极速", "ultrafast"),
    Fast("fast", "快速", "fast"),
    ;

    /** 价格倍率（相对标准档）。`null` = 按端点单独计价，没有一个通用倍数。 */
    val multiplier: Double?
        get() = when (this) {
            Flex -> 0.5
            Standard -> 1.0
            Priority, Fast -> 2.0
            Ultrafast -> null
        }

    /** 界面上那行倍率：`0.5×` / `1.0×` / `按端点`。 */
    fun multiplierLabel(): String = when (this) {
        Flex -> "0.5×"
        Standard -> "1.0×"
        Priority, Fast -> "约 2×"
        Ultrafast -> "按端点"
    }

    /** 一句话说明。 */
    val note: String
        get() = when (this) {
            Flex -> "便宜，但排队更靠后"
            Standard -> "常规容量与价格"
            Priority -> "优先排队，更快出字"
            Ultrafast -> "比优先再上一档，按端点计价"
            Fast -> "出字最多快 2.5 倍，价约翻倍"
        }

    /** 星点的颜色：低档浅、高档深；ultrafast 主体紫、最外两层红。 */
    val tint: Long
        get() = when (this) {
            Flex -> 0xFF7DFFA8
            Standard -> 0x00000000
            Priority -> 0xFFA78BD8
            Ultrafast -> 0xFF8F6FD0
            Fast -> 0xFFE8B98A
        }

    /** 最外几层换色（ultrafast 的红边）。`null` = 整片同色。 */
    val outerTint: Long?
        get() = if (this == Ultrafast) 0xFFE05A4A else null
}

/** 速度这一档注到哪个字段族。 */
internal enum class SpeedField { None, ServiceTier, AnthropicSpeed }

internal object SpeedLevels {

    /** 一档都没设 = 一个字段都不注（老行为不变）。 */
    const val AUTO = ""

    /**
     * 四个端口的 wire 名。**与 `ProviderKinds` 逐字一致**。
     *
     * 为什么这里不直接引 `ProviderKinds`：那个文件带 FFI 调用，进了单测就编不过
     * （`NmoiFfi` 是 JNI 绑定）。所以这一层自己留一份常量，并由
     * `SpeedLevelsTest` 里那条对照断言盯着两边不漂移。
     */
    const val KIND_OPENAI = "openai_compatible"
    const val KIND_RESPONSES = "openai_responses"
    const val KIND_ANTHROPIC = "anthropic"
    const val KIND_GEMINI = "gemini"

    /**
     * 这个模型该列哪几档。**判据只有两条**：
     *
     * 1. 模型名含 `claude` 或端口是 Anthropic → `standard` / `fast`；
     * 2. OpenAI 兼容 / Responses 端口 → `flex` / `default` / `priority` / `ultrafast`；
     * 3. 其余（Gemini 等）→ **空表**，界面上整条隐藏，不给假开关。
     *
     * 为什么模型名优先于端口：Claude 经常挂在 OpenAI 兼容中转后面，只看端口的话
     * 给 Claude 发的是 `service_tier`，对方不认，用户看到的是"选了 fast 没反应"。
     */
    fun forModel(providerKind: String, modelId: String): List<SpeedTier> {
        val family = modelId.lowercase()
        if (family.contains("claude") || providerKind == KIND_ANTHROPIC) {
            return listOf(SpeedTier.Standard, SpeedTier.Fast)
        }
        return when (providerKind) {
            KIND_OPENAI, KIND_RESPONSES ->
                listOf(SpeedTier.Flex, SpeedTier.Standard, SpeedTier.Priority, SpeedTier.Ultrafast)

            else -> emptyList()
        }
    }

    /** 与 [forModel] 配套：这一族写进哪个字段。 */
    fun fieldFor(providerKind: String, modelId: String): SpeedField {
        val family = modelId.lowercase()
        if (family.contains("claude") || providerKind == KIND_ANTHROPIC) {
            return SpeedField.AnthropicSpeed
        }
        return when (providerKind) {
            KIND_OPENAI, KIND_RESPONSES -> SpeedField.ServiceTier
            else -> SpeedField.None
        }
    }

    fun parse(wire: String): SpeedTier? =
        SpeedTier.entries.firstOrNull { it.wire == wire.trim().lowercase() }

    /** 界面上那一行：`flex (省流)`。 */
    fun label(tier: SpeedTier): String = "${tier.en} (${tier.zh})"
}
