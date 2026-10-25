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

    /** 删一版；`versionNo = 0` 表示"只留当前这一版"。 */
    suspend fun deleteVersion(
        databasePath: String,
        messageId: String,
        versionNo: Long,
    ): Result<Unit> = withHandle(databasePath) { handle ->
        NmoiFfi.deleteVersion(handle, messageId, versionNo)
            .onSuccess { envelope ->
                NLog.i(
                    "Version",
                    "delete ok: 请求=$versionNo -> count=${envelope.optInt("count")} " +
                        "active=${envelope.optInt("active")}",
                )
            }
            .onFailure { NLog.w("Version", "delete 失败：请求=$versionNo ${it.message}") }
            .map { }
    }

    /**
     * 开库 → 跑一段 → 关库。三个版本动词共用这一份：每个都抄一遍 open/close 的话，
     * 迟早有一处漏了 `finally`，然后句柄泄漏到"库被锁住"。
     */
    private suspend fun <T> withHandle(
        databasePath: String,
        // **必须写成 `suspend (Long) -> Result<T>`**：调用方在里面等 FFI（`NmoiFfi` 是挂起的）。
        // 写成普通的函数类型会让"在 lambda 里调挂起函数"变成编译错误
        // （Suspension functions can only be called within coroutine body）——
        // 我第一版就是漏了这个 suspend，CI 直接拦下。
        block: suspend (Long) -> Result<T>,
    ): Result<T> {
        if (!NativeBridge.loaded) {
            return Result.failure(NmoiFfiException("not_loaded", "原生层没加载"))
        }
        val opened = runCatching { JSONObject(NativeBridge.openStorageJson(databasePath)) }
            .getOrElse {
                return Result.failure(
                    NmoiFfiException("native_call", "打开库时报错：${it.message}"),
                )
            }
        if (!opened.ok()) {
            return Result.failure(NmoiFfiException(opened.errorKind(), "打开库失败：${opened.errorText()}"))
        }
        if (opened.optString("state", "") != "ready") {
            return Result.failure(
                NmoiFfiException("blocked", "库没通过校验（state=${opened.optString("state")}）"),
            )
        }
        val handle = opened.optLong("handle", 0L)
        return try {
            block(handle)
        } finally {
            runCatching { NativeBridge.closeStorageJson(handle) }
        }
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
