package com.nmoi.agent.ui

import org.json.JSONObject

/** 消息区这一屏的数据从哪来——跟列表页同一套口径，界面上要直说。 */
internal data class ChatSnapshot(
    val page: ChatPage?,
    val source: RowSource,
    /** 退回空态的原因，或者"读到了但是空的"这类说明。 */
    val note: String?,
)

/**
 * 会话内容的数据源。
 *
 * 形状与 [ConversationRepository] 一样：**自己开库、自己关**，任何异常都不往 UI 冒，
 * 失败就说清原因。区别只有一个——这里必须带 `sessionId`：库里的单元是按会话取的。
 *
 * 局限（写清，免得被当成已完成）：一次只取最后 `limit` 个单元，往上翻页还没做。
 * `truncated=true` 只用来在顶部提示"还有更早的"，不是分页游标。
 */
internal object ChatRepository {
    suspend fun load(databasePath: String, sessionId: String, limit: Long = 80L): ChatSnapshot = try {
        loadInner(databasePath, sessionId, limit)
    } catch (failure: Throwable) {
        empty("读会话时出错：${failure.javaClass.simpleName}: ${failure.message}")
    }

    private fun loadInner(databasePath: String, sessionId: String, limit: Long): ChatSnapshot {
        if (!NativeBridge.loaded) {
            return empty("原生层没加载（${NativeBridge.failureMessage ?: "未知原因"}）")
        }

        val opened = try {
            JSONObject(NativeBridge.openStorageJson(databasePath))
        } catch (failure: UnsatisfiedLinkError) {
            return empty("原生层已加载，但 JNI 符号对不上（logcat 搜 No implementation found）")
        } catch (failure: Throwable) {
            return empty("打开库时原生层报错：${failure.message}")
        }
        if (!opened.ok()) return empty("打开库失败：${opened.errorText()}")

        val state = opened.optString("state", "")
        if (state != "ready") {
            return empty("库没通过校验（state=$state，issues=${opened.optInt("issues", 0)}），没有句柄可用")
        }

        val handle = opened.optLong("handle", 0L)
        return try {
            val envelope = JSONObject(NativeBridge.loadUnitsJson(handle, sessionId, limit))
            if (!envelope.ok()) return empty("读消息失败：${envelope.errorText()}")
            val page = parseUnits(envelope)
            NLog.i("Chat", "会话 $sessionId 读到 ${page.units.size}/${page.total} 个单元")
            ChatSnapshot(
                page = page,
                source = RowSource.Native,
                note = if (page.isEmpty) "这个会话还一条消息都没有" else null,
            )
        } catch (failure: UnsatisfiedLinkError) {
            empty("原生层已加载，但 JNI 符号对不上（logcat 搜 No implementation found）")
        } catch (failure: Throwable) {
            empty("读消息时原生层报错：${failure.message}")
        } finally {
            runCatching { NativeBridge.closeStorageJson(handle) }
        }
    }

    private fun empty(note: String): ChatSnapshot {
        NLog.w("Chat", "消息区没有数据：$note")
        return ChatSnapshot(page = null, source = RowSource.Placeholder, note = note)
    }
}
