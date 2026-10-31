package com.nmoi.agent.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 一次原生调用失败。`kind` 是 D53 里的稳定词表（`bad_argument` / `storage` /
 * `null_argument` / `not_loaded` / `missing_symbol` / `bad_envelope`），`message` 只给人看。
 *
 * 做成异常而不是返回 `null`：`Result.failure` 只装得住异常，装不住"空了但不报错"——
 * 而"空了不报错"正是最难查的那种。
 */
internal class NmoiFfiException(val kind: String, message: String) : Exception(message)

/**
 * D86 的四类设置文档。**wire 值是契约**，跟 `crates/ffi/src/settings.rs` 的
 * `Scope::parse` 对表；写错一个词的后果是运行期 `bad_argument`，编译期看不出来。
 */
internal enum class SettingsScope(val wire: String) {
    ModelOverrides("model_overrides"),
    Assistant("assistant"),
    Conversation("conversation"),
    Roles("roles"),

    /** 模型服务（D156）：端点 / 模型名 / 供应商类型。密钥不在这里（见 [Vault]）。 */
    Provider("provider"),

    /** 预设词条（0.14t / D178）：key = `<预设 id>/<词条 id>`，值是 `{base_id,base_version,base_hash,content,disabled}`。 */
    Preset("preset"),

    /**
     * 模型收藏（0.15x）：key = `<供应商 id>/<模型 id>`，值是 `{}`。
     * "收藏"只有在或不在，时间由 `settings` 行自己记着——不为它多存一份没人读的字段。
     */
    ModelFavorites("model_favorites"),
}

/**
 * 设置面的 Kotlin 绑定（P3.2 的 UI 接线，第一层）。
 *
 * `NativeBridge` 是 JNI 的裸转手：字符串进、字符串出。这一层补三件事——
 *
 * 1. **只在 IO 线程上跑**。每次调用都要开句柄、落一次 SQLite；放主线程就是
 *    "设置页点一下卡一帧"，而且卡顿会出现在看起来毫不相干的地方。
 * 2. **失败装成 `Result`，不抛给 Compose**。原生返回 `ok:false`、JNI 符号对不上、
 *    返回的不是 JSON——三种都是失败，界面只有一个分支要看（D55 的那条规矩到这里为止）。
 * 3. **scope 走枚举**，不是散落的字符串字面量。
 *
 * 这一层**不解释 `doc` 里有什么**：那是 `SettingsRepository` 的活（下一层）。
 * 这里只保证"输进去了、拿回来了、失败长什么样"。
 */
internal object NmoiFfi {
    /** C ABI 里 `limit ≤ 0` 就是默认值（上限 200），对齐着写。 */
    const val DEFAULT_LIST_LIMIT = 200

    /** 读会话的原子单元。limit ≤ 0 = 全给；首屏给 80 就够，翻页等接上再说。 */
    suspend fun loadUnits(handle: Long, session: String, limit: Long = 80L): Result<JSONObject> =
        call("load_units") { NativeBridge.loadUnitsJson(handle, session, limit) }

    /** 新建会话。返回的信封里 `session` 是会话 id，`assistant` 是它归到哪个助手（D174）。 */
    suspend fun createConversation(handle: Long, title: String, assistant: String): Result<JSONObject> =
        call("create_conversation") { NativeBridge.createConversationJson(handle, title, assistant) }

    /** 把会话移到另一个助手（D174）。改归属，不复制：id 不变、消息一条不搬。 */
    suspend fun moveConversation(handle: Long, session: String, assistant: String): Result<JSONObject> =
        call("move_conversation") { NativeBridge.moveConversationJson(handle, session, assistant) }

    /**
     * 校验子助手名单（D176）。**不需要存储句柄**：它是一条纯检查，
     * 保存助手配置之前调它，撞名当场红字，而不是等发请求时才 400。
     */
    suspend fun validateVars(listJson: String): Result<JSONObject> =
        call("vars_validate") { NativeBridge.validateVarsJson(listJson) }

    /** 这个助手底下有几个会话（D174）。删助手之前必须先问这一句。 */
    suspend fun countConversationsOf(handle: Long, assistant: String): Result<JSONObject> =
        call("count_conversations_of") { NativeBridge.countConversationsOfJson(handle, assistant) }

    /**
     * 把一段文本存成一条消息。`role` 只收 `user` / `assistant` / `system`。
     *
     * **不发模型**：这是"先记下来、晚点再发"的那条路（PLAN §3.12 发送键长按菜单）。
     * 存成 assistant 会让历史变成"以助手结尾"——那是用户要的语义，别在 UI 层"顺手修正"。
     */
    suspend fun appendText(
        handle: Long,
        session: String,
        role: String,
        text: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Result<JSONObject> = call("append_text") {
        NativeBridge.appendTextJson(handle, session, role, text, nowMs)
    }

    /** 记一版新正文（D137）。目前只有「更多 → 复制为新版本」这个临时探针会调。 */
    suspend fun saveVersion(handle: Long, message: String, text: String): Result<JSONObject> =
        call("save_version") {
            NativeBridge.saveVersionJson(handle, message, text)
        }

    /** 读一条消息的全部版本（面板自己拉的真相，不依赖列表快照）。 */
    suspend fun versions(handle: Long, message: String): Result<JSONObject> =
        call("versions") { NativeBridge.versionsJson(handle, message) }

    /** 写入"编辑后"正文并切到它（UI 的「编辑」走这一条）。 */
    suspend fun editVersion(
        handle: Long,
        message: String,
        versionNo: Long,
        text: String,
    ): Result<JSONObject> = call("edit_version") {
        NativeBridge.editVersionJson(handle, message, versionNo, text)
    }

    /** 工具编辑（临时探针）：会置 `edited_by_tool`，于是数字变蓝/红（D148）。 */
    suspend fun toolEditVersion(
        handle: Long,
        message: String,
        versionNo: Long,
        text: String,
    ): Result<JSONObject> = call("tool_edit_version") {
        NativeBridge.toolEditVersionJson(handle, message, versionNo, text)
    }

    /** 切版本 / 切原文-编辑。`view` 只收 `raw` / `edited`。 */
    suspend fun switchVersion(
        handle: Long,
        message: String,
        versionNo: Long,
        view: String,
    ): Result<JSONObject> = call("switch_version") {
        NativeBridge.switchVersionJson(handle, message, versionNo, view)
    }

    /** 删掉这条消息本身（连正文带版本，D152）。 */
    suspend fun deleteMessage(handle: Long, message: String): Result<JSONObject> =
        call("delete_message") { NativeBridge.deleteMessageJson(handle, message) }

    /**
     * 跑一轮会话：真发请求、落库、回 `turn_json`（D157）。
     *
     * `call` 已经把整段放在 `Dispatchers.IO` 上——这一条会阻塞到模型收尾，
     * 放主线程就是 ANR。**取消**走 [turnCancel]，不是靠丢线程。
     */
    suspend fun turnRun(config: JSONObject): Result<JSONObject> =
        call("turn_run") { NativeBridge.turnRunJson(config.toString()) }

    /**
     * 这个会话现在有没有一轮在跑（0.15c）。
     *
     * 不需要 storage 句柄：那张表是进程内的，跟着 `Registration` 的 Drop 走。
     */
    suspend fun turnBusy(session: String): Result<JSONObject> =
        call("turn_busy") { NativeBridge.turnBusyJson(session) }

    /** 请求取消正在跑的那一轮。没在跑不算失败（`cancelled:false`）。 */
    suspend fun turnCancel(session: String): Result<JSONObject> =
        call("turn_cancel") { NativeBridge.turnCancelJson(session) }

    /**
     * 前缀重发：重试 / 续话（0.15h / D185）。**阻塞**到模型收尾，和 [turnRun] 一样在 IO 上跑。
     *
     * `up_to` / `target` 由调用方按"段内第几条"算好再传——这一层不替它算，
     * 因为"哪条算段首"是界面与 core 都必须一致的规矩（见 `TurnRepository.regen`）。
     */
    suspend fun turnRegen(config: JSONObject): Result<JSONObject> =
        call("turn_regen") { NativeBridge.turnRegenJson(config.toString()) }

    /**
     * 一次性补全（D158）。**空串也是有意义的结果**（模型回了空），
     * 所以只把 `ok:false` 当失败，交给调用方去判断拿到的东西能不能用。
     */
    suspend fun complete(config: JSONObject): Result<JSONObject> =
        call("complete") { NativeBridge.completeJson(config.toString()) }

    /** 改一条消息的某一块。`block` = `output` / `thinking` / `tool:<index>`。 */
    suspend fun applyBlockEdit(
        handle: Long,
        message: String,
        block: String,
        content: String,
    ): Result<JSONObject> = call("apply_block_edit") {
        NativeBridge.applyBlockEditJson(handle, message, block, content)
    }

    /**
     * 一次改几块（0.15f / D188）：正文落红、思考 / 工具块就地改，整批一个事务。
     *
     * `spec` = `{"message_id":"<具体 id>","ops":[…]}`——和模型的 `edit_unit` 同一套解析，
     * 所以界面上写错的 ops 与模型写错的 ops 会得到同一句报错。
     */
    suspend fun applyUnitEdits(handle: Long, spec: JSONObject): Result<JSONObject> =
        call("apply_unit_edits") { NativeBridge.applyUnitEditsJson(handle, spec.toString()) }

    /** 一个会话的 undo 日志（最近的在前）。`before` 是原文，导出直接用它。 */
    suspend fun undoLog(handle: Long, session: String): Result<JSONObject> =
        call("undo_log") { NativeBridge.undoLogJson(handle, session) }

    /** 删一版（`versionNo` 从 1 起；删整条消息走 [deleteMessage]）。 */
    suspend fun deleteVersion(
        handle: Long,
        message: String,
        versionNo: Long,
    ): Result<JSONObject> = call("delete_version") {
        NativeBridge.deleteVersionJson(handle, message, versionNo)
    }

    suspend fun settingsList(
        handle: Long,
        scope: SettingsScope,
        limit: Long = DEFAULT_LIST_LIMIT.toLong(),
    ): Result<JSONObject> = call("settings_list") {
        NativeBridge.listSettingsJson(handle, scope.wire, limit)
    }

    /**
     * 读一份文档。返回的信封里 `state` 有三种，**都不是错误**：
     * `ready`（带 `doc`）/ `missing` / `reset`（带 `reason`，核心已经按默认处理并记了一笔）。
     * 所以这里只把 `ok:false` 当失败，`state` 交给调用方解释。
     */
    suspend fun settingsGet(
        handle: Long,
        scope: SettingsScope,
        key: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Result<JSONObject> = call("settings_get") {
        NativeBridge.getSettingsJson(handle, scope.wire, key, nowMs)
    }

    /**
     * 写一份文档。`doc` 必须是 JSON **对象**——不是对象会被 C ABI 挡在写入口
     * （`bad_argument`），这里不预先判：判两遍就有两份规矩。
     *
     * `nowMs` 由这边给：客户端和核心各取一次系统时钟，同一次写入就会有两个时间戳。
     */
    suspend fun settingsPut(
        handle: Long,
        scope: SettingsScope,
        key: String,
        doc: JSONObject,
        nowMs: Long = System.currentTimeMillis(),
    ): Result<JSONObject> = call("settings_put") {
        NativeBridge.putSettingsJson(handle, scope.wire, key, doc.toString(), nowMs)
    }

    /**
     * 预设列表 + 四态（0.14t / D178）。
     *
     * `assets` 是平台侧从 APK 读出来拼好的原文（[PresetRepository.assetsText]）；
     * 用户改过的那一份由核心自己从 `settings(scope=preset)` 读。
     */
    suspend fun presets(handle: Long, assets: String): Result<JSONObject> =
        call("presets") { NativeBridge.presetsJson(handle, assets) }

    /**
     * 这一轮会注入什么（自检用）。真正跑一轮时 `turn_run` 在里面算同一份，
     * 所以这里的结果与请求里的那一段不会各说各话。
     */
    suspend fun presetsInject(
        handle: Long,
        assets: String,
        declared: String,
    ): Result<JSONObject> = call("presets_inject") {
        NativeBridge.presetsInjectJson(handle, assets, declared)
    }

    /** 删一份文档。幂等：删不存在的返回 `deleted:false`，不是失败。 */
    suspend fun settingsDelete(
        handle: Long,
        scope: SettingsScope,
        key: String,
    ): Result<JSONObject> = call("settings_delete") {
        NativeBridge.deleteSettingsJson(handle, scope.wire, key)
    }

    /**
     * 临时探针：四个 scope 各列一次，把结果打到 logcat。
     *
     * 为什么现在需要它——layer 1 只做到"能调通"，界面上一个设置项都还没接，
     * 于是"符号对不对、信封是不是 `ok:true`、条目数跟库里一样吗"这三件事
     * 在真机上**没有任何别的途径能看到**。`SettingsRepository` 接手后删掉这段。
     *
     * 只读：`list` 不写库、不记修复记录（C ABI 里就是这么定的）。
     */
    fun probeSettings(handle: Long) {
        for (scope in SettingsScope.entries) {
            val text = try {
                NativeBridge.listSettingsJson(handle, scope.wire, DEFAULT_LIST_LIMIT.toLong())
            } catch (failure: UnsatisfiedLinkError) {
                NLog.w("Settings", "settings_list 探针：${scope.wire} 符号对不上（logcat 搜 No implementation found）")
                return
            } catch (failure: Throwable) {
                NLog.w("Settings", "settings_list 探针：${scope.wire} 调用抛错 ${failure.javaClass.simpleName}: ${failure.message}")
                continue
            }
            val envelope = runCatching { JSONObject(text) }.getOrNull()
            when {
                envelope == null ->
                    NLog.w("Settings", "settings_list 探针：${scope.wire} 返回的不是 JSON——${text.take(80)}")
                !envelope.ok() ->
                    NLog.w("Settings", "settings_list 探针：${scope.wire} 返回错误 ${envelope.errorText()}")
                else -> NLog.i(
                    "Settings",
                    "settings_list 通了：${scope.wire} 返回 ${envelope.optInt("returned", -1)} 条" +
                        "（truncated=${envelope.optBoolean("truncated", false)}）",
                )
            }
        }
    }

    /**
     * 一次调用的外壳：三态检查 + `ok:false` 落成 `failure`。
     *
     * 不重试、不兜底、不缓存：这层只搬东西。哪一步失败都有 `kind` 说清楚是哪一类，
     * 界面上要写原因时直接拿来用。
     */
    private suspend fun call(what: String, block: () -> String): Result<JSONObject> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (!NativeBridge.loaded) {
                    throw NmoiFfiException(
                        "not_loaded",
                        "$what：原生层未加载（${NativeBridge.failureMessage ?: "未知原因"}）",
                    )
                }
                val text = try {
                    block()
                } catch (failure: UnsatisfiedLinkError) {
                    // `.so` 在、符号名不对：这跟"没打进包"是两种故障，混成一句会带偏排查方向。
                    throw NmoiFfiException(
                        "missing_symbol",
                        "$what：JNI 符号对不上（logcat 搜 No implementation found）",
                    )
                } catch (failure: Throwable) {
                    // 原生给了 null（OOM 兜底路径）会在调用点变成 NPE——这里也接住，
                    // 否则它会以"空指针"的样子冒到 Compose 里，跟设置面毫无关系。
                    throw NmoiFfiException(
                        "native_call",
                        "$what：原生调用抛错 ${failure.javaClass.simpleName}: ${failure.message}",
                    )
                }
                val envelope = try {
                    JSONObject(text)
                } catch (failure: Throwable) {
                    throw NmoiFfiException(
                        "bad_envelope",
                        "$what：原生层返回的不是 JSON——${text.take(80)}",
                    )
                }
                if (!envelope.ok()) {
                    throw NmoiFfiException(envelope.errorKind(), envelope.errorText())
                }
                envelope
            }
        }
}

/**
 * 开库 → 跑一段 → 关库。**所有需要 storage 句柄的仓库共用这一份**（D156）。
 *
 * 每个仓库各抄一遍 open/close 的话，迟早有一处漏了 `finally`，
 * 然后句柄泄漏到"库被锁住"——那种故障离现场很远，查起来最贵。
 *
 * `block` 必须写成 `suspend (Long) -> Result<T>`：里面要等 FFI（`NmoiFfi` 是挂起的）。
 */
internal suspend fun <T> withStorageHandle(
    databasePath: String,
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
        return Result.failure(
            NmoiFfiException(opened.errorKind(), "打开库失败：${opened.errorText()}"),
        )
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
