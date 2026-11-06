package com.nmoi.agent.ui

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 这一屏的数据到底是从哪来的——界面上要直说，不能让人以为"有数据 = 通了"。 */
internal enum class RowSource { Native, Placeholder }

internal data class ConversationSnapshot(
    val rows: List<ConversationRow>,
    val source: RowSource,
    /** 退回占位数据的原因，或者"通了但库是空的"这类说明。 */
    val note: String?,
)

/**
 * 会话列表的数据源。
 *
 * 现在只有两条路：能调原生层就去开库、列会话；调不到（`.so` 没打进包、
 * ABI 对不上、或者库没通过校验）就退回 `PlaceholderData`，并把原因写进 `note`。
 * 两条路都**不抛异常**：骨架阶段宁可少显示，也不要白屏。
 */
internal object ConversationRepository {
    private val timeFormat = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())

    /**
     * 新建一个会话，返回它的 id。
     *
     * 为什么必须有这个：全新安装的库里一条会话都没有，列表是空的、聊天页进不去，
     * 消息区就永远验不了（2026-10-23 用户指出：不能要求先跑桌面 CLI 造库）。
     */
    suspend fun create(
        databasePath: String,
        title: String,
        assistantId: String,
    ): Result<String> {
        if (assistantId.isBlank()) {
            return Result.failure(NmoiFfiException("bad_argument", "没有当前助手，建不了会话"))
        }
        if (!NativeBridge.loaded) {
            return Result.failure(NmoiFfiException("not_loaded", "原生层没加载（${NativeBridge.failureMessage ?: "未知原因"}）"))
        }
        val opened = runCatching { JSONObject(NativeBridge.openStorageJson(databasePath)) }
            .getOrElse { return Result.failure(NmoiFfiException("native_call", "打开库时报错：${it.message}")) }
        if (!opened.ok()) {
            return Result.failure(NmoiFfiException(opened.errorKind(), "打开库失败：${opened.errorText()}"))
        }
        val state = opened.optString("state", "")
        if (state != "ready") {
            return Result.failure(NmoiFfiException("blocked", "库没通过校验（state=$state），没有句柄可用"))
        }
        val handle = opened.optLong("handle", 0L)
        return try {
            NmoiFfi.createConversation(handle, title, assistantId).mapCatching { envelope ->
                val session = envelope.optString("session", "")
                if (session.isEmpty()) error("原生层没给会话 id")
                session
            }
        } finally {
            runCatching { NativeBridge.closeStorageJson(handle) }
        }
    }

    /**
     * 列表（D174）：**按助手过滤**。`assistantId` 必填——抽屉里看到的是当前助手的会话，
     * 传空串会拿到空列表（不是全部），因为"没有助手"这件事本身该被看见，而不是被兜底掩盖。
     */
    fun load(
        databasePath: String,
        assistantId: String,
        limit: Long = 50,
    ): ConversationSnapshot = try {
        loadInner(databasePath, assistantId, limit)
    } catch (failure: Throwable) {
        placeholder("读列表时出错：${failure.javaClass.simpleName}: ${failure.message}")
    }

    private fun loadInner(
        databasePath: String,
        assistantId: String,
        limit: Long,
    ): ConversationSnapshot {
        if (assistantId.isBlank()) return placeholder("没有当前助手，列表是空的")
        if (!NativeBridge.loaded) {
            val reason = NativeBridge.failureMessage ?: "未知原因"
            return placeholder("原生层没加载（$reason），数据是占位实现")
        }

        val opened = try {
            JSONObject(NativeBridge.openStorageJson(databasePath))
        } catch (failure: UnsatisfiedLinkError) {
            // JVM 那句话很长（还带一句 "is the library loaded?"），界面上只留结论。
            return placeholder("原生层已加载，但 JNI 符号对不上（logcat 搜 No implementation found）")
        } catch (failure: Throwable) {
            return placeholder("打开库时原生层报错：${failure.message}")
        }
        if (!opened.ok()) return placeholder("打开库失败：${opened.errorText()}")

        // blocked 不是错误（D57）：库没通过 L3 语义校验，核心不给句柄，恢复方式由用户选。
        val state = opened.optString("state", "")
        if (state != "ready") {
            return placeholder("库没通过校验（state=$state，issues=${opened.optInt("issues", 0)}），没有句柄可用")
        }

        val handle = opened.optLong("handle", 0L)
        // 临时探针（layer 1 的验收标志）：设置面四个 scope 通没通。
        // 结果进 NLog，也就是同时进 logcat 与应用内「设置 → 调试 → 日志」——
        // 后者是不依赖 adb 的那条路（ROM 会吞 I 级）。`SettingsRepository` 接手后删掉这一行。
        NmoiFfi.probeSettings(handle)
        return try {
            val page = JSONObject(NativeBridge.listConversationsJson(handle, limit, 0, assistantId))
            if (!page.ok()) return placeholder("列会话失败：${page.errorText()}")
            val rows = readRows(page.optJSONArray("items") ?: JSONArray())
            val note = when {
                rows.isEmpty() -> "原生层已接上，库是空的——还没有任何会话"
                else -> null
            }
            NLog.i("Storage", "列表来自原生层：${rows.size} 条")
            ConversationSnapshot(rows, RowSource.Native, note)
        } catch (failure: UnsatisfiedLinkError) {
            placeholder("原生层已加载，但 JNI 符号对不上（logcat 搜 No implementation found）")
        } catch (failure: Throwable) {
            placeholder("列会话时原生层报错：${failure.message}")
        } finally {
            // 幂等：重复关闭返回 closed:false，不报错。
            // 但 native 返回 String 是**非空类型**，Kotlin 会在调用点插 null 检查——
            // 原生万一给了 null（OOM 兜底路径），这里会抛 NPE，而 finally 里抛出会把
            // 上面已经算好的结果一起丢掉。所以 runCatching 兜住，关不掉就关不掉。
            runCatching { NativeBridge.closeStorageJson(handle) }
        }
    }

    /**
     * 把会话移到另一个助手（D174）。**改归属，不复制**：id 不变、消息一条不搬。
     *
     * 移动之后两个列表都要重读（源里消失、目标里出现），调用方负责刷新。
     */
    /** 改会话标题（0.15d）。 */
    suspend fun rename(databasePath: String, sessionId: String, title: String): Result<Unit> =
        withStorageHandle(databasePath) { handle ->
            NmoiFfi.renameConversation(handle, sessionId, title.trim()).map { }
        }

    /** 删会话（0.15d）。`false` = 本来就没有，不算失败。 */
    suspend fun delete(
        databasePath: String,
        sessionId: String,
        /** 给了就顺手把这个会话自己的目录也删掉（D231）。 */
        context: android.content.Context? = null,
    ): Result<Boolean> =
        withStorageHandle(databasePath) { handle ->
            NmoiFfi.deleteConversation(handle, sessionId)
                .map { envelope -> envelope.optBoolean("deleted", false) }
        }.onSuccess { deleted ->
            // **先删库、再删文件**：反过来的话中途失败会留下"消息还在、图没了"。
            if (deleted && context != null) {
                ToolingRepository.removeSessionDir(context, sessionId)
            }
        }

    /**
     * 复制会话（0.15d / D207）：新建一条，消息整份拷过去，原件不动。
     *
     * 返回新会话 id；`copied` 是拷过去的消息条数，界面拿它说"复制了 N 条"——
     * 只说"已复制"的话，用户得翻列表自己确认，而列表里两条长得一样。
     */
    suspend fun copy(
        databasePath: String,
        sessionId: String,
        title: String = "",
    ): Result<ConversationCopy> =
        withStorageHandle(databasePath) { handle ->
            NmoiFfi.copyConversation(handle, sessionId, title).mapCatching { envelope ->
                val id = envelope.optString("session", "")
                if (id.isEmpty()) error("原生层没给新会话 id")
                ConversationCopy(sessionId = id, messages = envelope.optInt("copied", 0))
            }
        }

    /**
     * 置顶的会话 id（按**用户排的顺序**，最早置顶的在最前）。
     *
     * 存 `settings(scope=conversation)` 的一个键，不加列：这是"我怎么看这个列表"，
     * 不是会话本身的事实；换一台机器恢复库时它跟着走，正好。
     */
    /**
     * 这条会话自己绑的工作区（D271）。`null` = 跟助手。
     *
     * 读失败给 `null` 而不是抛：工作区是"能不能用工具"的事，不该让聊天页打不开。
     * 但日志里留一句——真出问题时不能只剩一个"跟随助手"的假象。
     */
    suspend fun workspaceOf(databasePath: String, sessionId: String): String? =
        withStorageHandle(databasePath) { handle ->
            NmoiFfi.conversationWorkspace(handle, sessionId).map { envelope ->
                if (envelope.isNull("workspace")) null else envelope.optString("workspace", "")
                    .takeIf { it.isNotBlank() }
            }
        }.getOrElse { error ->
            NLog.w("Storage", "读会话工作区失败：${error.message}")
            null
        }

    /** 改这条会话自己的工作区（D271）。`null` = 跟助手。 */
    suspend fun setWorkspace(
        databasePath: String,
        sessionId: String,
        workspaceId: String?,
    ): Result<Unit> =
        withStorageHandle(databasePath) { handle ->
            NmoiFfi.setConversationWorkspace(handle, sessionId, workspaceId.orEmpty()).map { }
        }

    suspend fun pinned(databasePath: String): Result<List<String>> =
        withStorageHandle(databasePath) { handle ->
            NmoiFfi.settingsGet(handle, SettingsScope.Conversation, PINNED_KEY).map { envelope ->
                val ids = envelope.optJSONObject("doc")?.optJSONArray("ids")
                buildList {
                    if (ids != null) {
                        for (index in 0 until ids.length()) {
                            val id = ids.optString(index, "").trim()
                            if (id.isNotEmpty()) add(id)
                        }
                    }
                }
            }
        }

    suspend fun setPinned(databasePath: String, ids: List<String>): Result<Unit> =
        withStorageHandle(databasePath) { handle ->
            val doc = JSONObject().put("ids", JSONArray(ids))
            NmoiFfi.settingsPut(handle, SettingsScope.Conversation, PINNED_KEY, doc).map { }
        }

    private const val PINNED_KEY = "pinned"

    suspend fun move(databasePath: String, sessionId: String, assistantId: String): Result<Unit> =
        withStorageHandle(databasePath) { handle ->
            NmoiFfi.moveConversation(handle, sessionId, assistantId)
                .onSuccess { NLog.i("Storage", "会话 $sessionId 移到助手 $assistantId") }
                .onFailure { NLog.w("Storage", "移动会话失败：${it.message}") }
                .map { }
        }

    /** 这个助手底下有几个会话（D174）：删助手之前先问它。读不出来返回 -1（界面据此不给删）。 */
    suspend fun countOf(databasePath: String, assistantId: String): Long =
        withStorageHandle(databasePath) { handle ->
            NmoiFfi.countConversationsOf(handle, assistantId)
                .map { envelope -> envelope.optLong("count", -1L) }
        }.getOrDefault(-1L)

    private fun readRows(items: JSONArray): List<ConversationRow> = buildList {
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            add(
                ConversationRow(
                    id = item.optString("id", ""),
                    title = item.optString("title", "（无标题）"),
                    preview = item.optString("last_text", ""),
                    // model 是 D101 新加的那一列：NULL = 这个会话还没跑过。
                    model = item.optString("model", "").ifEmpty { null },
                    updatedAt = formatTime(item.optLong("updated_at", 0L)),
                    incomplete = item.optBoolean("incomplete", false),
                ),
            )
        }
    }

    /**
     * 库里存的是**毫秒**（`SystemTime::as_millis`）。上一版按秒解，抽屉里的时间会是
     * `+58782-03-11`——年份五万多。这里跟消息区用同一个单位。
     *
     * 抽屉这一行固定 24 小时制 `MM-dd HH:mm`：它没有 context，拿不到系统的 12/24 设置，
     * 而为了一个列表行去接 Activity 不值得。等 `SettingsRepository` 接上再统一。
     */
    private fun formatTime(epochMillis: Long): String =
        if (epochMillis <= 0L) "—" else timeFormat.format(Instant.ofEpochMilli(epochMillis))

    /**
     * 读不到库时的返回：**空列表 + 一句原因**，不拿占位数据充数。
     *
     * 以前这里返回 `PlaceholderData.conversations`（三行写死的样例），结果那三行被画成真会话：
     * 点不开、删不掉、看起来像"库里有东西"，其实是假的（2026-10-28 用户报的就是这个）。
     * 界面宁可显示"还没有会话 + 一句为什么"，也不要展示点不动的东西。
     */
    private fun placeholder(note: String): ConversationSnapshot {
        NLog.w("Storage", "列表读不到：$note")
        return ConversationSnapshot(emptyList(), RowSource.Placeholder, note)
    }
}
