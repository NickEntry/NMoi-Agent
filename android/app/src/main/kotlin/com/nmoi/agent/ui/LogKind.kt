package com.nmoi.agent.ui

/**
 * 三种日志通道。**分开不是为了分类好看，是三件不同的事各要一套规矩。**
 *
 * | 差别 | 后果 |
 * |---|---|
 * | **量**：一次请求能写几十行（重试、SSE 分片） | 混在一起会把"启动那五行"冲掉，而那五行恰恰是排查时最常要的 |
 * | **隐私**：请求日志里有 header，可能还有 body | 必须默认脱敏、默认不写 body；调试日志不该共用这个开关 |
 * | **清理**：清请求日志常常是为了腾空间或清敏感内容 | 不该把调试日志一起清掉 |
 *
 * 每个通道：自己的文件、自己的开关、自己的上限、自己的清理。
 * 开关与上限存 `SharedPreferences`（`app_log/enabled.<id>`、`app_log/limit.<id>`）——
 * 必须在第一行日志之前读到，settings 表要开库、要线程，够不上（D117 的同一个理由）。
 */
internal enum class LogKind(
    val id: String,
    val label: String,
    /** 一行说明：这个通道里到底有什么。界面直接显示，不另写文案。 */
    val what: String,
    val fileName: String,
    /**
     * 还没有产生者时写在这，界面**必须直说**"这一类还没有内容，在等什么"。
     * 给了空列表装正常，用户只会以为日志功能坏了。有了真实写入之后置 null。
     */
    val pendingProducer: String?,
) {
    Context(
        id = "context",
        label = "上下文日志",
        what = "组装过程：注入了哪些块、各来源 token、压缩轮次与分界线位置",
        fileName = "context.log",
        pendingProducer = "等 P4 压缩线接上",
    ),
    Request(
        id = "request",
        label = "请求日志",
        what = "HTTP：URL、状态码、耗时、重试；header 脱敏，body 只在详细模式下写",
        fileName = "request.log",
        pendingProducer = "等 P7 HTTP 层接上",
    ),
    Debug(
        id = "debug",
        label = "调试日志",
        what = "应用事件：启动、开库、图标切换、原生层、崩溃前状态",
        fileName = "debug.log",
        pendingProducer = null,
    ),
}
