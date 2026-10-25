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

    external fun versionsJson(handle: Long, message: String): String

    external fun switchVersionJson(handle: Long, message: String, versionNo: Long, view: String): String

    external fun deleteVersionJson(handle: Long, message: String, versionNo: Long): String

    external fun saveVersionJson(handle: Long, message: String, text: String): String

    external fun editVersionJson(handle: Long, message: String, versionNo: Long, text: String): String

    external fun listConversationsJson(handle: Long, limit: Long, offset: Long): String

    /** 读会话的原子单元（消息区）。limit ≤ 0 = 全给。 */
    external fun loadUnitsJson(handle: Long, session: String, limit: Long): String

    /** 新建会话，回 `{"session":"<id>"}`。 */
    external fun createConversationJson(handle: Long, title: String): String

    /** 存一句话为消息（不发模型）。这是接入 `turn_run` 之前唯一的写入口。 */
    external fun appendTextJson(handle: Long, session: String, role: String, text: String, nowMs: Long): String

    // 设置面（D86）：四个动词、scope 当参数传——core 那边就是这么切的，
    // 客户端不再按 scope 各包一个函数（加第五类时两边必然对不上）。
    // 这一层只负责搬字符串，语义包装在 `NmoiFfi`。

    external fun putSettingsJson(handle: Long, scope: String, key: String, doc: String, nowMs: Long): String

    external fun getSettingsJson(handle: Long, scope: String, key: String, nowMs: Long): String

    external fun listSettingsJson(handle: Long, scope: String, limit: Long): String

    external fun deleteSettingsJson(handle: Long, scope: String, key: String): String

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
