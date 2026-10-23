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

    fun load(databasePath: String, limit: Long = 50): ConversationSnapshot = try {
        loadInner(databasePath, limit)
    } catch (failure: Throwable) {
        placeholder("读列表时出错：${failure.javaClass.simpleName}: ${failure.message}")
    }

    private fun loadInner(databasePath: String, limit: Long): ConversationSnapshot {
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
            val page = JSONObject(NativeBridge.listConversationsJson(handle, limit, 0))
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

    private fun formatTime(seconds: Long): String =
        if (seconds <= 0L) "—" else timeFormat.format(Instant.ofEpochSecond(seconds))

    private fun placeholder(note: String): ConversationSnapshot {
        // 退回占位数据不是"正常情况"，留一条日志：真机上第一句要问的永远是"为什么是占位数据"。
        NLog.w("Storage", "退回占位数据：$note")
        return ConversationSnapshot(PlaceholderData.conversations, RowSource.Placeholder, note)
    }
}
