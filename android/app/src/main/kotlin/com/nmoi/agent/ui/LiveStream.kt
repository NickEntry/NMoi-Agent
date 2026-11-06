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

/**
 * 一条正在跑的工具调用（0.15ak）。
 *
 * `cancelled` 是**界面自己的**状态：点了「关闭」之后、结果回来之前，那一行要立刻变成
 * 「已请求关闭」。它不来自 native——native 只知道标志置上了没有，不知道用户点过几下。
 */
internal data class RunningCall(
    val callId: String,
    val name: String,
    val elapsedMs: Int,
    /** 参数原文（native 截到 2 000 字）。弹窗里要能看见"它到底在跑什么"。 */
    val arguments: String = "",
    val cancelled: Boolean = false,
    /**
     * 到目前为止吐出来的东西（D264）。native 那边只留尾部 8 KiB，
     * `outputCut` 说明前面被裁过——弹窗里要能看出来"这不是全部"。
     */
    val output: String = "",
    val outputCut: Boolean = false,
)

/**
 * 这一轮**已经跑完**的一次工具调用（D251）。
 *
 * 结果要等整轮收尾才落库，中间这一段界面上得能看见"它跑过什么"——
 * 否则用户只看到模型在思考，以为工具没动（2026-11-04 真机就是这么报的）。
 */
internal data class DoneCall(
    val callId: String,
    val name: String,
    val arguments: String,
    val output: String,
    val ok: Boolean,
    val summary: String,
)

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
    /**
     * 这一轮**收到过**增量没有（D242）。
     *
     * 收尾时用来说实话：一个字都没逐字长出来，要么是助手把「逐字输出」关了，
     * 要么是端点不认 `stream`（core 会退回非流式重发一次，结果一次性出现）。
     * 两种情况用户看到的都是"流式没生效"，不给一句话，他只能猜。
     */
    val sawDelta: Boolean = false,
    /** 正在跑的工具调用（0.15ak）。空 = 这一拍没有工具在跑。 */
    val calls: List<RunningCall> = emptyList(),
    /** 这一轮已经跑完的调用（D251），按发生顺序。 */
    val done: List<DoneCall> = emptyList(),
    /**
     * 这一轮是**重试/续话**（D249）：正文画在目标消息的位置上，旧的那一版还在库里。
     *
     * 只影响那一行说明——"正在生成"和"正在重写这一版"对用户是两件事：
     * 前者以为多了一条消息，后者知道自己刚才点的是重试、旧的切得回去。
     */
    val regen: Boolean = false,
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

/**
 * 一次 `turn_busy` 的快照（D240）。
 *
 * 抽出来是为了**能单测**：轮询那段以前写在 `ChatScreen` 的 `LaunchedEffect` 里，
 * 而两次真机事故都发生在它的时序上（见 D236）。Compose 里的循环测不了，
 * 所以把"拿到一份快照之后状态怎么变"变成一个纯函数。
 */
internal data class BusySnapshot(
    val running: Boolean,
    val round: Int,
    val textLen: Int,
    val reasoningLen: Int,
    val text: String,
    val reasoning: String,
    /** 这一拍有哪些工具调用在跑（0.15ak）。 */
    val calls: List<RunningCall> = emptyList(),
    /** 这一轮已经跑完的调用（D251）。 */
    val done: List<DoneCall> = emptyList(),
)

/** 轮询自己的状态：**见过一次 `running:true` 没有**。 */
internal data class LivePoll(val started: Boolean = false)

internal sealed interface LiveStep {
    /** 还在跑（或者还没开始）：带着新的回复与轮询状态继续问。 */
    data class Update(val reply: LiveReply, val poll: LivePoll) : LiveStep

    /** 这一轮真的结束了（见过 `running:true` 之后又变成 false）。 */
    data class Stop(val reply: LiveReply) : LiveStep
}

/**
 * 拿到一份快照之后，屏幕上那份该变成什么。
 *
 * 三条规矩，每条都对应一次真机上撞到的东西：
 *
 * 1. **还没见过 `running:true` 时，`running:false` 不是终态**——`Started` 早于 native 登记，
 *    这一小段里的 false 只说明"还没开始"。当终态处理的话，轮询当场退出、正文一个字都不长。
 * 2. 这一小段里 `reply.running` 保持 `true`：它是外面那条 effect 的 key，写 false 会把它自己重启掉。
 * 3. 快照里的增量一律按 [growText] 接，**不在"没在跑"时清空**：收尾那一瞬 native 给的是空，
 *    照着清就会让气泡在换库之前闪一下白。
 */
internal fun advanceLive(reply: LiveReply, poll: LivePoll, snapshot: BusySnapshot): LiveStep {
    val started = poll.started || snapshot.running
    val have = reply.text.codePointCount(0, reply.text.length)
    val base = when {
        // 换轮：native 那边缓冲清了、原正文会再垫一次（0.15av），
        // 所以这里也回到 prefix——不然续话的正文会从"续文"重新开始。
        snapshot.round != reply.round -> reply.copy(
            finished = reply.finished + FinishedRound(reply.text, reply.reasoning),
            text = reply.prefix,
            reasoning = "",
            round = snapshot.round,
        )
        // native 的缓冲有上限（50 KB，超了砍头）。这时总量比界面手里的还少，
        // 再按差值算就永远"没有新字"——屏幕上会卡住不动。从尾部重来，不假装完整。
        snapshot.textLen > 0 && snapshot.textLen < have -> reply.copy(text = "")
        else -> reply
    }
    val next = base.copy(
        text = growText(base.text, snapshot.textLen, snapshot.text),
        reasoning = growText(base.reasoning, snapshot.reasoningLen, snapshot.reasoning),
        running = if (started) snapshot.running else true,
        sawDelta = base.sawDelta || snapshot.textLen > 0 || snapshot.reasoningLen > 0,
        // 点了「关闭」之后 native 还会把这一条报上几拍（进程还没死），
        // 所以"已请求关闭"要跨快照保住——否则按钮会闪回可点状态，用户会再点一次。
        done = snapshot.done,
        calls = snapshot.calls.map { call ->
            val asked = base.calls.firstOrNull { it.callId == call.callId }?.cancelled == true
            call.copy(cancelled = asked)
        },
    )
    val nextPoll = LivePoll(started = started)
    return if (started && !snapshot.running) {
        LiveStep.Stop(next)
    } else {
        LiveStep.Update(next, nextPoll)
    }
}
