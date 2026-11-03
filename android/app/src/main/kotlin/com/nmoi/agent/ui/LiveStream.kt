package com.nmoi.agent.ui

/**
 * 流式那一轮"屏幕上正在长的东西"（0.15ah）。
 *
 * 为什么不放在 `ChatScreen.kt` 里：这里两样都是**纯逻辑**，能脱离 Compose 单测。
 * 拼错一次的表现是"回答开头凭空消失"或者"同一段话出现两遍"，靠看屏幕很难定位。
 *
 * `round` 是模型第几次开口：工具往返之后会 +1，界面看到它变了就清掉上一轮那截——
 * 不清的话收尾落库时正文会突然缩掉一半（库里只存最后一轮）。
 */
internal data class FinishedRound(val text: String, val reasoning: String)

internal data class LiveReply(
    val text: String = "",
    val reasoning: String = "",
    val round: Int = 0,
    val running: Boolean = true,
    /** 断在中途：这段没落库，留着给他看写到哪儿。 */
    val cut: Boolean = false,
    /**
     * 续话时"原正文"这一截（0.15av）。
     *
     * native 那边的 partial 缓冲里垫了同一段（`seeded_delta`），所以总量算的是全文；
     * 这里预置一份，`growText` 才能只补新长出来的那一段，而不是从尾部重来。
     * 工具往返之后缓冲被清空、native 会再垫一次，所以换轮时这里也要回到 `prefix`。
     */
    val prefix: String = "",
    val finished: List<FinishedRound> = emptyList(),
)

/**
 * 把"这一轮到现在为止"接上。
 *
 * `turn_busy` 只给尾部（8 KiB）和总量，所以新长出来的那一段 = 尾部末尾 `need` 个字。
 * 差距比尾部还大（界面卡了一下）就**只显示尾部**——不假装完整，缺的那截确实没拿到。
 *
 * 计数按**码点**：Rust 那边 `chars().count()` 数的是码点，Kotlin 的 `String.length`
 * 数的是 UTF-16 单元。表情符号一个字算两个单元，用 `length` 会一直差一截，
 * 表现是每隔几个字就吞掉一个字。
 */
internal fun growText(current: String, total: Int, tail: String): String {
    if (total <= 0) return current
    val have = current.codePointCount(0, current.length)
    if (total <= have) return current
    val need = total - have
    val tailPoints = tail.codePointCount(0, tail.length)
    if (need > tailPoints) return tail
    val start = tail.offsetByCodePoints(0, tailPoints - need)
    return current + tail.substring(start)
}
