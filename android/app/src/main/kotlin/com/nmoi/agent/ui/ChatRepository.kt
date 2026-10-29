package com.nmoi.agent.ui

import org.json.JSONArray
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

    /**
     * 存一条消息（不发模型）。**这是接入 `turn_run` 之前唯一的写入口**——
     * 没有它，消息区在真机上永远造不出内容来验。
     *
     * 失败把 `NmoiFfiException` 原样带出去：`kind` 能分清是"没加载"还是"库没过校验"。
     */
    suspend fun append(databasePath: String, sessionId: String, role: String, text: String): Result<Unit> {
        if (!NativeBridge.loaded) {
            return Result.failure(NmoiFfiException("not_loaded", "原生层没加载"))
        }
        val opened = runCatching { JSONObject(NativeBridge.openStorageJson(databasePath)) }
            .getOrElse { return Result.failure(NmoiFfiException("native_call", "打开库时报错：${it.message}")) }
        if (!opened.ok()) {
            return Result.failure(NmoiFfiException(opened.errorKind(), "打开库失败：${opened.errorText()}"))
        }
        val state = opened.optString("state", "")
        if (state != "ready") {
            return Result.failure(
                NmoiFfiException("blocked", "库没通过校验（state=$state），没有句柄可用"),
            )
        }
        val handle = opened.optLong("handle", 0L)
        return try {
            NmoiFfi.appendText(handle, sessionId, role, text).map { }
        } finally {
            runCatching { NativeBridge.closeStorageJson(handle) }
        }
    }

    /**
     * 一条消息的版本实况：总共几版、当前第几版、这一版被编辑过没有、显示哪一组。
     *
     * 为什么单独给一条而不是读列表里的字段：面板曾经用「列表快照 + 点开时的那个对象」，
     * 结果是**面板显示的和动作使用的不是同一份数据**——看着 2/2，编辑却落到 1/2（2026-10-26 报）。
     * 面板要动谁，就自己来问一次库。
     */
    data class VersionInfo(
        val count: Int,
        val active: Int,
        val edited: Boolean,
        val view: String,
        /** 当前版、当前视图**该显示的正文**（库现算的那份）。编辑预填只用它。 */
        val shown: String,
    )

    suspend fun versions(databasePath: String, messageId: String): Result<VersionInfo> =
        withHandle(databasePath) { handle ->
            NmoiFfi.versions(handle, messageId).map { envelope ->
                val active = envelope.optInt("active", 0)
                val rows = envelope.optJSONArray("versions") ?: JSONArray()
                var edited = false
                var view = "raw"
                for (index in 0 until rows.length()) {
                    val row = rows.optJSONObject(index) ?: continue
                    if (row.optInt("no", 0) == active) {
                        edited = row.optBoolean("edited", false)
                        view = row.optString("view", "raw")
                    }
                }
                VersionInfo(
                    count = envelope.optInt("count", 0),
                    active = active,
                    edited = edited,
                    view = view,
                    shown = envelope.optString("shown", ""),
                )
            }
        }

    /**
     * 当前**看得见的那一份正文**（D153：所见即所得）。
     *
     * 有版本时取 `versions()` 里现算的 `shown`（= 屏幕上显示的那一组，含他自己改过的字）；
     * 没有版本时退回消息正文。复制新版本、编辑预填都以它为准，**不用列表快照**——
     * 快照滞后一拍，用户看到的就是"我明明改了，它复制的还是旧的"。
     */
    suspend fun shownText(databasePath: String, messageId: String, fallback: String): String {
        val fresh = versions(databasePath, messageId).getOrNull() ?: return fallback
        return fresh.shown.takeIf { it.isNotEmpty() } ?: fallback
    }

    /**
     * 记一版新正文（D137）。**现在只有"更多 → 复制为新版本"这个临时探针会调它**；
     * `turn_run` 接上之后，重新生成走同一条路。
     */
    suspend fun saveVersion(databasePath: String, messageId: String, text: String): Result<Unit> =
        withHandle(databasePath) { handle ->
            NmoiFfi.saveVersion(handle, messageId, text)
                .onSuccess { envelope ->
                    NLog.i(
                        "Version",
                        "save ok: id=$messageId version=${envelope.optInt("version_no")} " +
                            "count=${envelope.optInt("count")}",
                    )
                }
                .onFailure { NLog.w("Version", "save 失败：${it.message}") }
                .map { }
        }

    /** 切到某一版的某一组正文，返回切换后应当显示的正文。 */
    suspend fun switchVersion(
        databasePath: String,
        messageId: String,
        versionNo: Long,
        view: String,
    ): Result<String> = withHandle(databasePath) { handle ->
        NmoiFfi.switchVersion(handle, messageId, versionNo, view)
            .onSuccess { envelope ->
                NLog.i(
                    "Version",
                    "switch ok: no=$versionNo view=$view -> 实际 ${envelope.optString("view")} " +
                        "长度=${envelope.optString("content").length}",
                )
            }
            .onFailure { NLog.w("Version", "switch 失败：no=$versionNo view=$view ${it.message}") }
            .map { envelope -> envelope.optString("content", "") }
    }

    /** 写入"编辑后"正文并切到它（UI 的「编辑」走这一条）。 */
    suspend fun editVersion(
        databasePath: String,
        messageId: String,
        versionNo: Long,
        text: String,
    ): Result<String> = withHandle(databasePath) { handle ->
        NmoiFfi.editVersion(handle, messageId, versionNo, text)
            .onSuccess { NLog.i("Version", "edit ok: no=$versionNo 新长度=${text.length}") }
            .onFailure { NLog.w("Version", "edit 失败：no=$versionNo ${it.message}") }
            .map { envelope -> envelope.optString("content", "") }
    }

    /**
     * **工具编辑**某一版（临时探针，D148）。
     *
     * 跟 [editVersion] 的区别：这条会置 `edited_by_tool`，也就是让 `n/n` 变蓝/红。
     * `editVersion` 是用户手改，只写用户编辑层，不改变颜色。
     */
    suspend fun toolEditVersion(
        databasePath: String,
        messageId: String,
        versionNo: Long,
        text: String,
    ): Result<String> = withHandle(databasePath) { handle ->
        NmoiFfi.toolEditVersion(handle, messageId, versionNo, text)
            .onSuccess { NLog.i("Version", "tool edit ok: no=$versionNo 新长度=${text.length}") }
            .onFailure { NLog.w("Version", "tool edit 失败：no=$versionNo ${it.message}") }
            .map { envelope -> envelope.optString("content", "") }
    }

    /**
     * 改一条消息的**某一块**（D177 的手动编辑器走这条）。
     *
     * `block` 只认三种：`output` / `thinking` / `tool:<index>`（与 FFI 同一套词）。
     * 落库走 `apply_block_edit` → `save_message_edit`，与 `edits` 审计表在同一个事务里，
     * 所以不会出现"改了但没记账"的半截状态。
     */
    suspend fun applyBlock(
        databasePath: String,
        messageId: String,
        block: String,
        content: String,
    ): Result<JSONObject> = withHandle(databasePath) { handle ->
        NmoiFfi.applyBlockEdit(handle, messageId, block, content)
            .onSuccess { NLog.i("Edit", "块级编辑 ok: block=$block 长度=${content.length}") }
            .onFailure { NLog.w("Edit", "块级编辑失败：block=$block ${it.message}") }
    }

    /**
     * 删掉这条消息本身（连正文带版本，D152）。
     *
     * 跟 [deleteVersion] 分开：那个删的是"一版"，这个删的是"一条消息"。
     * 工具往返单元会被 storage 拒（那要整单元删），`kind=storage` 带原因。
     */
    suspend fun deleteMessage(databasePath: String, messageId: String): Result<Int> =
        withHandle(databasePath) { handle ->
            NmoiFfi.deleteMessage(handle, messageId)
                .onSuccess { envelope ->
                    NLog.i(
                        "Version",
                        "delete message ok: id=$messageId removed=${envelope.optInt("removed")}",
                    )
                }
                .onFailure { NLog.w("Version", "delete message 失败：id=$messageId ${it.message}") }
                // `removed` > 1 说明这条消息带着工具往返（整单元一起删了）。
                .map { envelope -> envelope.optInt("removed", 1) }
        }

    /**
     * 删一版；`versionNo` 从 1 起（删整条消息走 [deleteMessage]）。
     *
     * 返回**删完之后的版数**：界面要能说"剩 1 版了"，否则用户看不出这次点击到底做了什么
     * （2026-10-26 报：删除所有版本之后不知道删没删掉）。
     */
    suspend fun deleteVersion(
        databasePath: String,
        messageId: String,
        versionNo: Long,
    ): Result<Int> = withHandle(databasePath) { handle ->
        NmoiFfi.deleteVersion(handle, messageId, versionNo)
            .onSuccess { envelope ->
                NLog.i(
                    "Version",
                    "delete ok: 请求=$versionNo -> count=${envelope.optInt("count")} " +
                        "active=${envelope.optInt("active")}",
                )
            }
            .onFailure { NLog.w("Version", "delete 失败：请求=$versionNo ${it.message}") }
            .map { envelope -> envelope.optInt("count", 0) }
    }

    /**
     * 开库 → 跑一段 → 关库。
     *
     * 实现搬到 [withStorageHandle]（D156）：模型服务那页也要同一套 open/close，
     * 两处各抄一遍的话迟早有一处漏 `finally`，然后句柄泄漏到"库被锁住"。
     */
    private suspend fun <T> withHandle(
        databasePath: String,
        block: suspend (Long) -> Result<T>,
    ): Result<T> = withStorageHandle(databasePath, block)

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
            // 带版本的条数：若列表里的 `n/n` 一直不出现，就看这个数——
            // 它是 0 说明 `load_units` 的 JSON 没把版本字段带到 Kotlin 这一层。
            val withVersion = page.units.sumOf { unit -> unit.messages.count { it.versionCount > 0 } }
            NLog.i(
                "Chat",
                "会话 $sessionId 读到 ${page.units.size}/${page.total} 个单元（带版本 $withVersion 条）",
            )
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
