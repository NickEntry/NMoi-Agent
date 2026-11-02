package com.nmoi.agent.ui

/**
 * 能力位（D208）：模型到底收不收图片。
 *
 * 三态是刻意的——`null` = **没数据**，不等于"不支持"。库里 `input_modalities` 是空的时候
 * （快照没这一条、或者用户没填），拦下来只会让本来能发的图发不出去；
 * 而"明确写了 `text` 却没写 `image`"才是真的不支持。
 *
 * 这条和 D205 的"不给假开关"是同一件事的两面：不能因为不知道就把功能灰掉，
 * 也不能因为不知道就假装它能用。
 */
internal object ModelCapabilities {

    /** 快照 / 供应商里 `image` 这一档的写法不止一种，统一在这里认。 */
    private val IMAGE_ALIASES = setOf("image", "images", "vision", "image_url")

    /**
     * 这个模型收不收图片：`true` 收、`false` 明确不收、`null` 不知道。
     *
     * 判据是"有数据且里面没有图片档"才算不收；空列表按不知道处理。
     */
    fun acceptsImages(inputModalities: List<String>): Boolean? {
        if (inputModalities.isEmpty()) return null
        val normalized = inputModalities.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (normalized.isEmpty()) return null
        return normalized.any { it in IMAGE_ALIASES }
    }

    /**
     * 要不要因为"这个模型不收图"把入口灰掉（D210）。
     *
     * **只有用户自己勾的那一格算数**：快照 / 默认值都是推断，目录滞后是常态
     * （真机见过 `deepseek-v4.1-flash` 实际能收图、快照写纯文本）。
     * 拿推断值拦人，比让上游报错更糟——前者用户连试都试不了。
     */
    fun blocksImages(source: FieldSource?, modalities: List<String>): Boolean =
        source == FieldSource.Custom && acceptsImages(modalities) == false

    /** 灰掉时那句话：说清"为什么灰"，而不是让用户猜。 */
    fun refusal(inputModalities: List<String>): String {
        val listed = inputModalities
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" / ")
        return if (listed.isEmpty()) {
            "这个模型没有填写输入能力，图片可能发不出去"
        } else {
            "这个模型只收 $listed，不接受图片输入"
        }
    }
}
