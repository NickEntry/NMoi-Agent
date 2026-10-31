package com.nmoi.agent.ui

import org.json.JSONObject

/**
 * JNI 桥的 Kotlin 侧（P6.8）。符号名与 `crates/ffi/src/jni_bridge.rs` 一一对应：
 * `Java_com_nmoi_agent_ui_NativeBridge_<方法名>`（包里的 `.` 变 `_`）——
 * **改方法名要两边一起改**，漏一边的话 JVM 直接 `UnsatisfiedLinkError`，
 * 不会给你更客气的提示。`scripts/check_jni_symbols.sh` 在 CI 里对账这件事。
 *
 * `.so` 名字跟着 crate 走：`libnmoi_ffi.so`，所以这里是 `loadLibrary("nmoi_ffi")`。
 * 刻意不改名成 `nmoi`：改名会让 C ABI 的说明、cargo-ndk 的产物路径、
 * 这里的字符串三处必须同时记得改。
 *
 * 这一层**不抛异常给 UI**：加载失败就 `loaded = false`，调用方退回占位数据并
 * 在界面上直说"原生层没加载"。骨架阶段崩在启动第一步是最没用的失败方式。
 */
internal object NativeBridge {
    private const val LIB = "nmoi_ffi"

    private val loadFailure: Throwable? = try {
        System.loadLibrary(LIB)
        null
    } catch (failure: UnsatisfiedLinkError) {
        failure
    }

    val loaded: Boolean get() = loadFailure == null

    /** 给界面看的一句话；没失败时是 null。 */
    val failureMessage: String? get() = loadFailure?.message

    external fun versionJson(): String

    external fun openStorageJson(path: String): String

    external fun closeStorageJson(handle: Long): String

    /** 把 App 私有目录绑成工作区（D159），回 `{handle, root}`。目录要先建好。 */
    external fun openWorkspaceJson(root: String): String

    /** 解绑工作区。幂等。 */
    external fun closeWorkspaceJson(handle: Long): String

    external fun versionsJson(handle: Long, message: String): String

    external fun switchVersionJson(handle: Long, message: String, versionNo: Long, view: String): String

    external fun deleteVersionJson(handle: Long, message: String, versionNo: Long): String

    /** 删掉这条消息本身（连正文带版本，D152）。 */
    external fun deleteMessageJson(handle: Long, message: String): String

    external fun saveVersionJson(handle: Long, message: String, text: String): String

    external fun editVersionJson(handle: Long, message: String, versionNo: Long, text: String): String

    /** 工具编辑（临时探针）：会置 `edited_by_tool`，数字变蓝/红（D148）。 */
    external fun toolEditVersionJson(handle: Long, message: String, versionNo: Long, text: String): String

    /**
     * 会话列表（D174）。`assistant` **必填**：列表永远属于某一个助手，
     * 界面上看到的是哪个助手，列出来的就该是哪个的会话。
     */
    external fun listConversationsJson(handle: Long, limit: Long, offset: Long, assistant: String): String

    /** 读会话的原子单元（消息区）。limit ≤ 0 = 全给。 */
    external fun loadUnitsJson(handle: Long, session: String, limit: Long): String

    /** 新建会话，回 `{"session":"<id>","assistant":"<id>"}`。会话归 `assistant`。 */
    external fun createConversationJson(handle: Long, title: String, assistant: String): String

    /** 把会话移到另一个助手（D174）：改归属，不复制。回 `{"session","assistant"}`。 */
    external fun moveConversationJson(handle: Long, session: String, assistant: String): String

    /** 校验子助手名单（D176）：`[{owner,id,description}]` → `{ok, problems:[{kind,message}]}`。 */
    external fun validateVarsJson(list: String): String

    /** 某个助手底下有几个会话（D174）：删助手之前那道闸靠它。回 `{"assistant","count"}`。 */
    external fun countConversationsOfJson(handle: Long, assistant: String): String

    /** 存一句话为消息（不发模型）。这是接入 `turn_run` 之前唯一的写入口。 */
    external fun appendTextJson(handle: Long, session: String, role: String, text: String, nowMs: Long): String

    /**
     * 跑一轮会话：**真的发请求**（D157 之后 UI 才接到这条）。
     *
     * 阻塞到模型收尾或轮数用尽，所以只能在 IO 线程上调（[NmoiFfi] 已经这么做了）。
     * 参数是一个 JSON 对象，字段名见 `crates/ffi/src/turn.rs` 的 `parse_config`：
     * `storage` / `session` / `provider{id,kind,base_url,api_key}` / `model` /
     * `user_text` / `now_ms`，可选 `max_tokens` / `temperature` / `max_rounds`。
     */
    external fun turnRunJson(config: String): String

    /** 给正在跑的那一轮置取消标志。没在跑返回 `cancelled:false`，不是错误。 */
    external fun turnCancelJson(session: String): String

    /** 这个会话现在有没有一轮在跑（0.15c）。「正在生成…」靠它复核，不信页面里的缓存。 */
    external fun turnBusyJson(session: String): String

    /**
     * 前缀重发：重试 / 续话（0.15h / D185）。
     *
     * 参数与 [turnRunJson] 同一份，多三个字段：`up_to`（必填，发出去的最后一条消息 id）/
     * `target`（可选，结果落成哪条助手消息的新版本）/ `allow_new_version`（可选）。
     * 出参：`{text, reasoning, target, appended, rounds, tool_calls, finished}`。
     */
    external fun turnRegenJson(config: String): String

    /**
     * 一次性补全（D158）：**不落库、不进历史**。增强编辑要的是"拿一段文字回来"。
     *
     * 参数：`{provider{id,kind,base_url,api_key?}, model, prompt, system?, max_tokens?, temperature?}`。
     */
    external fun completeJson(config: String): String

    /**
     * 改一条消息的某一块（D158）：`block` 只收 `output` / `thinking` / `tool:<index>`。
     *
     * 工具调用只能改参数——名字与 `call_id` 不在射程里（它们决定这一块怎么被解释、跟谁配对）。
     */
    external fun applyBlockEditJson(handle: Long, message: String, block: String, content: String): String

    /** 单元级编辑（0.15f）：`spec` = `{"message_id":"…","ops":[…]}`，与模型的 edit_unit 同一套解析。 */
    external fun applyUnitEditsJson(handle: Long, spec: String): String

    /** 一个会话的 undo 日志：before/after 是原文，导出用它。 */
    external fun undoLogJson(handle: Long, session: String): String

    // 设置面（D86）：四个动词、scope 当参数传——core 那边就是这么切的，
    // 客户端不再按 scope 各包一个函数（加第五类时两边必然对不上）。
    // 这一层只负责搬字符串，语义包装在 `NmoiFfi`。

    external fun putSettingsJson(handle: Long, scope: String, key: String, doc: String, nowMs: Long): String

    external fun getSettingsJson(handle: Long, scope: String, key: String, nowMs: Long): String

    external fun listSettingsJson(handle: Long, scope: String, limit: Long): String

    external fun deleteSettingsJson(handle: Long, scope: String, key: String): String

    /** 预设列表 + 四态（0.14t / D178）。`assets` = 从 APK 读出来的预设原文。 */
    external fun presetsJson(handle: Long, assets: String): String

    /** 这一轮会注入哪几条预设（自检用）。`declared` 是工具名数组的 JSON 文本。 */
    external fun presetsInjectJson(handle: Long, assets: String, declared: String): String

    /**
     * 原生层的三态，给标题下面那行用。**必须分三态，不能两态**：
     * 「没打进包」和「打进去了但符号名不对」在界面上长得一样的话，
     * 下次又会把两种完全不同的故障混成一种（这件事真发生过，见 insights）。
     */
    fun status(): NativeStatus {
        if (!loaded) {
            return NativeStatus("原生层未加载：${failureMessage ?: "未知原因"}", ok = false)
        }
        val envelope = try {
            JSONObject(versionJson())
        } catch (failure: Throwable) {
            // 走到这里说明 .so 加载成功了，但 JVM 找不到那个方法——
            // 也就是符号名或类名对不上，不是"没打进包"。
            return NativeStatus("原生层已加载，但 JNI 符号对不上：logcat 搜 No implementation found", ok = false)
        }
        if (!envelope.ok()) {
            return NativeStatus("原生层已加载，但版本调用返回错误：${envelope.errorText()}", ok = false)
        }
        val product = envelope.optString("product", "?")
        val version = envelope.optString("version", "?")
        val abi = envelope.optInt("abi", -1)
        return NativeStatus("$product v$version · abi $abi", ok = true)
    }
}

/** 原生层的状态行：`ok = false` 时界面用醒目色。 */
internal data class NativeStatus(val text: String, val ok: Boolean)

/** 信封读法（D53 / D55）：`ok` 永远在第一个键，失败也是 JSON，所以只有一个分支要看。 */
internal fun JSONObject.ok(): Boolean = optBoolean("ok", false)

/** `error.kind` 是稳定词表，`error.message` 只给人看。 */
internal fun JSONObject.errorText(): String {
    val error = optJSONObject("error") ?: return "未知错误"
    val kind = error.optString("kind", "unknown")
    val message = error.optString("message", "")
    return if (message.isEmpty()) kind else "$kind：$message"
}

/** 只取 `error.kind`：它才是可以拿来分支的那个，文案随时会改。 */
internal fun JSONObject.errorKind(): String =
    optJSONObject("error")?.optString("kind", "unknown") ?: "unknown"
