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

    /** 列目录（D392）。`depth` / `maxEntries` 传 0 用默认；回 `{path, entries[], count}`。 */
    external fun workspaceListJson(
        handle: Long,
        path: String,
        depth: Long,
        maxEntries: Long,
        includeHidden: Boolean,
    ): String

    /** 读一页文本（D392）。`startLine` 是 1-based，传 0 用默认；`forceFull` 忽略行数上限。 */
    external fun workspaceReadJson(
        handle: Long,
        path: String,
        startLine: Long,
        maxLines: Long,
        forceFull: Boolean,
    ): String

    /** 单个路径的大小与修改时间（D392）。 */
    external fun workspaceStatJson(handle: Long, path: String): String

    /** 建目录（D392）。已存在时回 `already_exists`。 */
    external fun workspaceMkdirJson(handle: Long, path: String): String

    /** 改名 / 移动（D392）。目标已存在时拒绝。 */
    external fun workspaceRenameJson(handle: Long, path: String, to: String): String

    /** 删文件或整棵目录（D392），回 `{path, is_dir, removed}`。 */
    external fun workspaceDeleteJson(handle: Long, path: String): String

    /** 整篇写入（D392）。父目录会被创建。 */
    external fun workspaceWriteJson(handle: Long, path: String, content: String): String

    /**
     * 把一组文件打成一份 7z（D425）。`spec` 是
     * `{"dest":"<包路径>","entries":[{"name":"<包内路径>","path":"<盘上路径>"}]}`。
     *
     * 压缩在 core 那一份里做（`nmoi_core::sevenz`）：它跟 `7z` 命令行对得上，
     * 界面上再写一份会让"有时能开、有时不能开"变成常态。
     */
    external fun sevenzWriteJson(spec: String): String

    /**
     * 备份到 `dest`，**验证通过才发布**（D48）。回 `{path, verified, problems}`。
     *
     * `verified=false` 时目标位置那份**旧备份一点没动**——所以调用方要读这个字段，
     * 不能拿到 ok 就当备份成功。
     */
    external fun storageBackupJson(handle: Long, dest: String): String

    /**
     * 终端里跑一条命令（D395）。`config` 是
     * `{"root":"<工作区绝对路径>","command":"…","timeout_ms":600000}`。
     *
     * 与 `toolInvokeJson("System-Shell")` 的区别只在"谁发起的"：这里**不过能力门控**——
     * 用户在自己屏幕上一行一行敲的命令，锁住它只会得到一个需要反复解锁的界面。
     */
    external fun terminalRunJson(config: String): String

    /**
     * 往终端里正在跑的那条命令送一段字节（D397）：`{"session":"t:1","data":"<base64>"}`。
     *
     * 控制字节（`\x03` Ctrl+C、`\x1b` Esc）不是合法 UTF-8，只能按 base64 过桥。
     * 回 `{"written":N}`；`0` 表示没有在跑的命令或管道已断——**不是错误**。
     */
    external fun terminalWriteJson(config: String): String

    /** 停掉终端里正在跑的那条命令（D397）：`{"session":"t:1"}` → `{"cancelled","found"}`。 */
    external fun terminalCancelJson(config: String): String

    external fun versionsJson(handle: Long, message: String): String

    external fun switchVersionJson(handle: Long, message: String, versionNo: Long, view: String): String

    external fun deleteVersionJson(handle: Long, message: String, versionNo: Long): String
    /** 删掉这条消息本身（连正文带版本，D152）。 */
    external fun deleteMessageJson(handle: Long, message: String): String

    external fun saveVersionJson(handle: Long, message: String, text: String): String

    external fun editVersionJson(handle: Long, message: String, versionNo: Long, text: String): String

    /**
     * 把某一版标成"被工具改过"（置 `edited_by_tool`，`n/n` 变蓝/红，D148）。
     *
     * 2026-11-26 起界面上没有入口了：验颜色的那个临时探针已从菜单里删掉，
     * 而"工具改消息"本身走 `applyUnitEditsJson`。保留这一层是因为它是 C ABI 与 JNI 的
     * 既有符号（删掉要动两个 ABI），而且以后重新生成那一轮会用到同一种标记。
     */
    external fun toolEditVersionJson(handle: Long, message: String, versionNo: Long, text: String): String

    /**
     * 会话列表（D174）。`assistant` **必填**：列表永远属于某一个助手，
     * 界面上看到的是哪个助手，列出来的就该是哪个的会话。
     */
    external fun listConversationsJson(handle: Long, limit: Long, offset: Long, assistant: String): String

    /**
     * **不带助手过滤**的会话列表（存储页的「会话文件」用）。
     *
     * 与 [listConversationsJson] 的差别只有一个：那一层传的是 `NULL` 而不是空串。
     * 空串是一次过滤（`assistant_id = ''`），只会得到 0 条——看起来像"库里没有会话"，
     * 而实际是"过滤错了一个空值"。所以这两个入口必须分开，不能靠传空串复用。
     */
    external fun listAllConversationsJson(handle: Long, limit: Long, offset: Long): String

    /** 读会话的原子单元（消息区）。limit ≤ 0 = 全给。 */
    external fun loadUnitsJson(handle: Long, session: String, limit: Long): String

    /** 新建会话，回 `{"session":"<id>","assistant":"<id>"}`。会话归 `assistant`。 */
    external fun createConversationJson(handle: Long, title: String, assistant: String): String

    /** 把会话移到另一个助手（D174）：改归属，不复制。回 `{"session","assistant"}`。 */
    external fun moveConversationJson(handle: Long, session: String, assistant: String): String

    /** 改会话标题（0.15d）。回 `{"renamed":true}`；不动 `updated_at`。 */
    external fun renameConversationJson(handle: Long, session: String, title: String): String

    /** 这条会话自己绑的工作区（D271）。回 `{"session","workspace":null|"w123"}`；null = 跟助手。 */
    external fun conversationWorkspaceJson(handle: Long, session: String): String

    /** 改这条会话自己的工作区（D271）。**空串 = 跟助手**。 */
    external fun setConversationWorkspaceJson(handle: Long, session: String, workspace: String): String

    /**
     * 这个会话欠着什么（D323）：`{"summaries":N,"incomplete":["<消息 id>",…]}`。
     *
     * 两样都**不进上下文**——界面拿它画标记，模型一个字看不到。
     */
    external fun storageInterruptsJson(handle: Long, session: String): String

    /** 删会话（0.15d）。回 `{"deleted":bool}`；`false` = 本来就没有（不算错）。 */
    external fun deleteConversationJson(handle: Long, session: String): String

    /** 删一批会话（D354）：`sessions` 是 id 数组的 JSON 文本。回 `{"deleted":N,"results":[bool…]}`。 */
    external fun deleteConversationsJson(handle: Long, sessions: String): String

    /**
     * 读一页单元（D355）：`before` <= 0 = 最新一页。回
     * `{"items":[…],"has_more":bool,"next_before":N,"units_total":N}`。
     */
    external fun loadUnitsPageJson(handle: Long, session: String, before: Long, limit: Long): String

    /** 往下翻一页（D365）：`after <= 0` = 从头读一页（位置键的"真正最上方"）。 */
    external fun loadUnitsAfterJson(handle: Long, session: String, after: Long, limit: Long): String

    /** 迷你地图（D365）：整条会话一条一行，只有前面 24 个字。 */
    external fun unitPreviewsJson(handle: Long, session: String): String

    /** 复制会话（0.15d）：消息整份拷到新会话。回 `{"session","copied","source"}`；title 空串用默认名。 */
    external fun copyConversationJson(handle: Long, session: String, title: String): String

    /** 存一张图片（D208）：`data` 是 base64。回 `{"id","message","mime","size"}`。 */
    external fun putAttachmentJson(handle: Long, session: String, message: String, mime: String, data: String): String

    /** 取一张图片（D208）：回 `{"id","mime","data"}`（base64）。 */
    external fun attachmentJson(handle: Long, attachment: String): String

    /** 一个会话的附件清单（D208）：回 `{"session","items":[…]}`，不含字节。 */
    external fun listAttachmentsJson(handle: Long, session: String): String

    /** 全部附件（D216，存储管理页）：回 `{"items":[…]}`，带会话标题与路径。 */
    external fun listAllAttachmentsJson(handle: Long): String

    /** 删一张附件（D216）：行与文件一起删。回 `{"deleted":bool}`。 */
    external fun deleteAttachmentJson(handle: Long, attachment: String): String

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

    /**
     * 上下文压缩：只算切点，不调模型（D305）。
     *
     * 字段：`storage` / `session`，可选 `keep_recent`（窗口里留多少 token 原文，默认 8192）。
     * 返回 `cut_after_seq` / `above_units` / `total_units` / `max_rounds`。
     */
    external fun compressPlanJson(config: String): String

    /**
     * 上下文压缩：真的跑一轮（D305）。**同步阻塞**几十秒，只能在 IO 线程上调。
     *
     * 比 plan 多四个字段：`model` / `provider{id,kind,base_url}` / `api_key` /
     * `fallback`（默认 true：模型没交时走确定性兜底）。
     */
    external fun compressRunJson(config: String): String

    /** 这个会话现在有没有一轮在跑（0.15c）。「正在生成…」靠它复核，不信页面里的缓存。 */
    external fun turnBusyJson(session: String): String

    /**
     * 关掉一条正在跑的工具调用（0.15ak）：`{"session","call_id"}` → `{"cancelled","found"}`。
     * `found:false` = 已经结束了，不是错误。
     */
    external fun toolCancelJson(config: String): String

    /**
     * 回答一条"这条命令要不要跑"（D329）：`{"session","call_id","verdict"}`。
     * `verdict` 只认 `allow_once` / `allow_rule` / `deny`；`answered:false` = 那条已经不在等了，不是错误。
     */
    external fun toolAnswerJson(config: String): String

    /** 内置命令规则清单（D329）：十组、每组的项、哪几项不能关。 */
    external fun shellRulesJson(): String

    /** 这一轮的上下文图片清单（D248）：`{"images":[{id,mime,bytes}],"count","max"}`。 */
    external fun imagesJson(session: String): String

    /** 把一张图移出上下文：`{"session","id"}`。不在清单里返回 `removed:false`，不是错误。 */
    external fun imageRemoveJson(config: String): String

    /** 清空这一轮的上下文图片。 */
    external fun imageClearJson(session: String): String

    /** 应用一份代理配置（`{"proxy": null}` = 关）。热句柄，下一次请求生效（D226）。 */
    external fun proxyApplyJson(config: String): String

    /** 现在生效的那一份（不回密码）。 */
    external fun proxyStatusJson(): String

    /** 用表单里这一份探一次（不落库、不改全局）：`{ok,status,ms}` 或 `{ok:false,error}`。 */
    external fun proxyProbeJson(config: String): String

    /** 起本地 SOCKS5 端点（给容器垫片用）。 */
    external fun proxyServerStartJson(config: String): String

    /** 停本地端点（幂等）。 */
    external fun proxyServerStopJson(): String

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

    /** 删一条分界线（D324）：压缩只画线不删原文，删线就等于还原。 */
    external fun deleteDividerJson(handle: Long, divider: String): String

    /** 注册表里的全部工具名（0.17e）：面板上的逐项开关按它渲染。 */
    external fun toolNamesJson(): String

    /**
     * **只摘掉库里那一行，不动盘上的文件**（D446）。
     *
     * 用在"编辑消息时删附件"：`allow_new_version` 开着时旧版本还引用着这份文件，
     * 删了它切回旧版本就是"芯片在、点开什么都没有"。
     */
    external fun detachAttachmentJson(handle: Long, attachment: String): String

    /**
     * 在某条消息之后插一条用户分界线（D445）：`{divider:{…}, created:bool}`。
     *
     * 这是「清空上下文」那个动作：不删原文、不调模型，只画一条线。
     */
    external fun insertDividerJson(handle: Long, spec: String): String

    /**
     * 扫一遍技能目录（D444）：`{skills:[{name,description,version,path}], count, root}`。
     *
     * 目录不存在给空表，不报错——技能是可选的。
     */
    external fun skillsListJson(root: String): String

    /**
     * 这一轮要进上下文的技能块（D444）：只有 `name + description`，全文让模型自己去读。
     *
     * 由 core 拼而不是界面拼：那几句措辞要与 `context_block` 同一份。
     */
    external fun skillsBlockJson(input: String): String

    /**
     * 上下文管理面板的八行用量（D443）。
     *
     * `extra` 是**库里没有的那几行**的原文（系统提示词 / 技能 / 世界书 / 工作区 / 草稿），
     * 由界面把"我注入了什么"递进来——库看不见编排层拼了什么，猜一个数只会更糟。
     * 空串 = 只算库里有的。
     */
    external fun contextBreakdownJson(handle: Long, session: String, extra: String): String

    /**
     * 工具声明占多少 token（D443）：`[{name, tokens}]`。
     *
     * 按与真实请求**同一份**声明算。拿工具名列表自己估一遍，估出来的数与发出去的
     * 差一截，而面板上那个数就是"这一轮到底送了多少"。
     */
    external fun toolDefinitionTokensJson(workspaceHandle: Long): String

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

/** 只取上游 HTTP 状态（D315）：没有就是 null——不造一个 0，那跟"没状态"分不开。 */
internal fun JSONObject.errorStatus(): Int? =
    optJSONObject("error")?.optInt("status", 0)?.takeIf { it > 0 }

/** 只取 `error.kind`：它才是可以拿来分支的那个，文案随时会改。 */
internal fun JSONObject.errorKind(): String =
    optJSONObject("error")?.optString("kind", "unknown") ?: "unknown"
