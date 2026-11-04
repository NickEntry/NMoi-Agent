package com.nmoi.agent.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.text.format.DateFormat
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.drawBehind
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 聊天页——**这一页就是主页**（D125）。
 *
 * 消息区的形状照 D129（2026-10-24 用户按截图重新定的）：
 *
 * - **用户**：右侧。上面一行是「名字 + 时间 + 头像」，下面是气泡（`primaryContainer`、圆角 16），
 *   气泡下面一排动作（复制 / 重新生成 / 编辑 / 更多）。
 * - **助手**：左侧。**没有气泡**——头像 + 名字 + 时间，正文直接铺在背景上，
 *   走 [MarkdownBody] 渲染。思考块与工具卡在这里仍然是卡片（它们是"过程"，不是"回答"）。
 * - **长按 = 进入选择模式**（不是复制）：整行泛出选中底色，顶部换成选择条，
 *   可以逐条点选，然后复制。删除还灰着（`delete` 的 FFI 还没有）。
 *
 * 名字与头像现在都是占位：库里还没有"助手"这个实体（助手设置页未接），
 * 所以写死「我」/「助手」+ 首字母圆标。等助手设置接上，从 D122 的助手文档里取名字与头像。
 */
@Composable
internal fun ChatScreen(
    title: String,
    sessionId: String?,
    drawerOpen: Boolean,
    onOpenDrawer: () -> Unit,
    onCreateSession: suspend () -> String?,
    onNewChat: () -> Unit,
    /**
     * 助手换了就变（D174）：用户切了助手之后，这一页顶部的模型条必须跟着换。
     *
     * 为什么不能只靠 `sessionId`：切换助手时如果本来就停在一个空白会话上，
     * `sessionId` 一直是 null，那个 `LaunchedEffect` 不会重跑——于是顶部还写着上一个助手的模型，
     * 而下一句话已经发给新的那个。这就是"看到的和发出去的不是同一个"。
     */
    assistantKey: Int = 0,
    /** 进"编辑消息"整页（D177）。没给就退回到旧的行为（弹输入框问模型）。 */
    onEditMessage: ((String) -> Unit)? = null,
    /**
     * 外部要求重读（比如"编辑消息"存完了）。**不能只靠内部 `reloadKey`**：
     * 那个只有本页自己发起的动作才会加一，从别的页面回来时它没变，消息区就不会重画。
     */
    externalReload: Int = 0,
    /** 长按模型选择里的一行 → 去改这个模型（D209）。 */
    onEditModel: ((providerId: String, modelId: String) -> Unit)? = null,
    /** 长按模型选择里的供应商组头 → 去改这个供应商。 */
    onEditProvider: ((providerId: String) -> Unit)? = null,
) {
    val context = LocalContext.current
    // 草稿按会话存（0.15b）：切走再切回来，那半句还在。空白会话也有自己的桶。
    val draftKey = sessionId ?: DraftStore.NEW_DRAFT_KEY
    // **这个状态对象必须跨会话稳定**（D210）。原生输入框的 `TextWatcher` 在 factory 里
    // 只绑一次，它写的就是这个 `MutableState`；以前写成 `remember(draftKey)`，切会话
    // （包括"首次保存时新建会话"）会换一个新对象，之后敲的字全写进旧对象——
    // 发送键永远是灰的，而"仅保存"因为读的是屏幕上那一份，照样能用。
    var draft by remember { mutableStateOf(DraftStore.load(context, draftKey)) }
    // 换会话时才重新装载草稿；状态对象本身不换。
    LaunchedEffect(draftKey) { draft = DraftStore.load(context, draftKey) }
    // 打一个字存一次（`apply()` 是异步落盘，不卡输入）。空草稿会被 `save` 删键，
    // 抽屉里那一行"草稿"标记才不会一直亮着。
    LaunchedEffect(draft, draftKey) { DraftStore.save(context, draftKey, draft) }
    val scope = rememberCoroutineScope()
    // 存完一条要重读：`produceState` 只有 key 变了才会重跑，所以用它当"刷新一下"。
    var reloadKey by remember { mutableStateOf(0) }
    var notice by remember { mutableStateOf<String?>(null) }
    // 提示"上一轮还没结束"）。原来 `remember {}` 不带 key，切会话时这个状态跟着上一场
    // 留在原地，于是新会话一进来就是 busy。
    var busy by remember(sessionId) {
        mutableStateOf(sessionId != null && GeneratingTracker.isRunning(sessionId))
    }
    // 刚发出去的那句话：**先进消息区**（用户 2026-10-27："发送的时候先把消息推到消息框"），
    // 输入框当场清空。这一条不进库、也不算"已存"——库里有没有得等这一轮收尾后现读。
    var pending by remember { mutableStateOf<PendingSend?>(null) }
    // "这一轮正在等模型"——只给消息区那个"正在生成…"占位用（`busy` 还管别的事）。
    //
    // 初值从 `GeneratingTracker` 取（0.15c）：切走再切回来时，那一轮其实还在跑，
    // 而这里的 state 已经随重组回到初值了。写成 `remember(sessionId)` 才跟着会话换。
    // 这一轮已经收到的正文/思考（0.15ah）。**只用于画**：一轮结束以落库结果为准，
    // 免得"流里画的和库里存的"两套东西对不上（铁律①）。
    var live by remember(sessionId) { mutableStateOf<LiveReply?>(null) }
    // 关掉一条正在跑的工具调用（0.15ak）。**先改屏幕上那一行再发请求**：
    // 点了之后立刻变「已请求关闭」，等 native 杀完、结果回来时整块被库里那份替掉。
    // 反过来（等 native 回了再改）会出现"点了没反应"——而这条命令可能要跑几十秒。
    val cancelCall: (String) -> Unit = { callId ->
        live = live?.let { reply ->
            reply.copy(
                calls = reply.calls.map { call ->
                    if (call.callId == callId) call.copy(cancelled = true) else call
                },
            )
        }
        val target = sessionId
        if (target != null) {
            scope.launch {
                val result = NmoiFfi.toolCancel(target, callId)
                // 点晚了一拍：那条调用已经结束了。说一句，别留一个永远转着的"已请求关闭"。
                if (result.isSuccess && result.getOrNull()?.optBoolean("found") == false) {
                    notice = "那条调用已经结束了"
                }
            }
        }
    }
    // 工具块点开的那一条（D243）：半屏弹窗，不在聊天页里就地展开。
    var toolSheet by remember(sessionId) { mutableStateOf<ChatUnit?>(null) }
    // 收尾之后还要等一次重读：库里那份到了才把内存里这条换掉，否则气泡会闪一下。
    var settling by remember(sessionId) { mutableStateOf(false) }
    // 重试落在哪条消息上（D227）：这一轮的部分正文**就地**画在它身上，不再在底部另起一个
    // 「助手 / 正在生成…」的块。`null` = 这一轮不是重试，或者目标为空（结果会落成新消息）。
    var regenTarget by remember(sessionId) { mutableStateOf<String?>(null) }
    // 重试失败 → 三选一（D228）。它决定库里留下哪一版，所以是"必须处理"的那种，点外面不关。
    var regenFailure by remember { mutableStateOf<RegenFailure?>(null) }
    // 过程块展开/收起（D229）：**只在本次浏览会话内记忆**——切到别的会话再回来，
    // 恢复默认（最新两个 + 其余折叠）。所以键里带 sessionId，换会话就是一张新表。
    val openGroups = remember(sessionId) { mutableStateMapOf<String, Boolean>() }
    // 正在跑的那一轮挂在哪个会话上——点"停止"要的就是这个 id。
    // 单独存一份而不是直接读 `sessionId`：跑的过程中用户可能切了会话。
    var runningSession by remember(sessionId) {
        mutableStateOf(sessionId?.let { GeneratingTracker.sessionFor(it) })
    }
    // 选择模式：长按任意一条进入，点别的消息加选，顶部条上复制 / 分享。
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    // 分享底部两排：上排两个开关（思考调用 / 详细信息），下排两个动作（MD / 图片）。
    var shareOpen by remember { mutableStateOf(false) }
    // 「更多」是一个**自己画的底部面板**（不是 DropdownMenu）：要带图标、要盖住整屏。
    //
    // **只记 id，不记对象**（D155）：`ChatMessage` 是点开那一刻的副本，拿它当动作输入，
    // 中间刷新过的版本/正文就用不上——"我明明改了，它复制的还是旧的"就是这么来的。
    var moreForId by remember { mutableStateOf<String?>(null) }
    // 「编辑」：把某一版的正文改一遍（写 `edited_text` 并切到"编辑后"，D137）。
    // `editingVersion` 是**点开「编辑」那一刻面板确认过的版号**，不许回退到列表里的旧值。
    var editingId by remember { mutableStateOf<String?>(null) }
    var editingVersion by remember { mutableStateOf(0L) }
    // 编辑框**预填的那一份正文**。必须是库里现读的 `shown`（屏幕上显示的就是它），
    // 不能用列表快照里的 content：两者一旦错开，就会出现"看着改后、编辑框里是原文"
    // 而保存又落到改后那一组上（2026-10-26 报）。
    var editingInitial by remember { mutableStateOf("") }
    // 待确认的"删除整条消息"（破坏性动作要确认，D152）。
    var confirmDelete by remember { mutableStateOf<ChatMessage?>(null) }
    // 「更多」面板自己读一次库：显示与动作都用这一份。
    // 为什么不信列表里的字段：列表是快照，面板是"点开那一刻"的副本，
    // 两者一旦错开就会出现"看着 2/2、编辑落到 1/2"（2026-10-26 报）。
    val moreVersion by produceState<ChatRepository.VersionInfo?>(
        initialValue = null,
        moreForId,
        reloadKey,
    ) {
        value = moreForId?.let { id ->
            withContext(Dispatchers.IO) {
                ChatRepository.versions(File(context.filesDir, "nmoi.db").absolutePath, id)
                    .getOrNull()
            }
        }
    }
    var includeThinkingTools by remember { mutableStateOf(false) }
    var includeDetails by remember { mutableStateOf(false) }
    val databasePath = remember { File(context.filesDir, "nmoi.db").absolutePath }
    // 用哪一套端点 / 哪个模型归**助手**（D161）。这一屏只负责显示和切换它。
    var assistant by remember { mutableStateOf(AssistantEntry()) }
    var choices by remember { mutableStateOf(listOf<Pair<ProviderEntry, ProviderModel>>()) }
    /**
     * 供应商 id → 名字。
     *
     * 为什么不从 `choices` 里取：那一份只收"能跑的聊天模型"。助手指着的模型被删掉之后
     * 它就消失了，于是标题掉回内部 id（`pmvtgpg6c`）——真机截图里那一行就是这么来的。
     * 名称要按**供应商**查，与"那个模型还在不在"无关。
     */
    var providerNames by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var modelSheet by remember { mutableStateOf(false) }
    var thinkingSheet by remember { mutableStateOf(false) }
    /** 发送队列那一层小窗（长按发送键进）。 */
    var queueSheet by remember { mutableStateOf(false) }
    /** 「＋」面板（D208）：附件入口在这里，不再靠一句"待接"。 */
    var plusMenu by remember { mutableStateOf(false) }
    /**
     * 待发的图片（D208）。发送时随这一轮交出去，发完清空。
     *
     * 为什么不先进库再发：这一轮的用户消息由 core 在跑的时候落（`run_session_turn`），
     * 图片跟着那条消息一起落——先落一条再补图，中间断掉就会留下"一条没有图的空消息"。
     */
    var pendingImages by remember { mutableStateOf(listOf<PendingImage>()) }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            when {
                pendingImages.size >= 4 -> notice = "一轮最多带 4 张图"
                else -> scope.launch {
                    val picked = withContext(Dispatchers.IO) {
                        runCatching {
                            val mime = context.contentResolver.getType(uri)
                                ?.takeIf { it.startsWith("image/") }
                                ?: "image/jpeg"
                            val raw = context.contentResolver.openInputStream(uri)
                                ?.use { it.readBytes() }
                                ?: error("读不到这张图")
                            if (raw.isEmpty()) error("这张图是空的")
                            // 压缩在**选图这一刻**做（D241）：这张图后面还要落库、落盘、
                            // base64 编进请求，三处都按同一份字节算，压得越早越省。
                            val packed = ImageCompress.apply(raw, mime, ImageLimits.of(context))
                            when {
                                packed.bytes.isEmpty() -> error("压缩之后是空的")
                                packed.bytes.size > MAX_IMAGE_BYTES -> error(
                                    "图片 ${packed.bytes.size / 1024 / 1024} MB，超过 8 MB 上限",
                                )
                            }
                            PendingImage(
                                mime = packed.mime,
                                data = Base64.encodeToString(packed.bytes, Base64.NO_WRAP),
                                bytes = packed.bytes.size,
                            ).also {
                                if (packed.reencoded) {
                                    NLog.i(
                                        "Chat",
                                        "图片已压缩：${raw.size / 1024} KB → ${packed.bytes.size / 1024} KB" +
                                            "（${packed.mime}）",
                                    )
                                }
                            }
                        }
                    }
                    picked.fold(
                        onSuccess = { pendingImages = pendingImages + it },
                        onFailure = { notice = "选图失败：${it.message}" },
                    )
                }
            }
        }
    }
    LaunchedEffect(sessionId, reloadKey, assistantKey, externalReload) {
        withContext(Dispatchers.IO) {
            AssistantRepository.current(databasePath).onSuccess { assistant = it }
            AssistantRepository.choices(databasePath).onSuccess { choices = it }
            ProviderRepository.list(databasePath).onSuccess { page ->
                providerNames = page.rows.associate { it.id to it.name.ifBlank { it.id } }
            }
        }
    }
    // 这个助手当前用的那个模型：思考档位能选哪几档由它说了算（D201 第四片之后的读取点）。
    val currentModel = remember(assistant, choices) {
        choices.firstOrNull {
            it.first.id == assistant.providerId && it.second.id == assistant.modelId
        }?.second
    }
    /**
     * 这个模型收不收图片（D208 的能力位）：`true` 收、`false` 明确不收、`null` 不知道。
     * 只有 `false` 才拦——没数据就当它能发，等上游报错也比冤枉它强。
     */
    val imageCapability = remember(currentModel, choices) {
        // 只有**用户自己勾的**模态才拿来拦（D210）。快照/默认值推断出来的不可靠：
        // 真机上 `deepseek-v4.1-flash` 明明能收图，快照却写着纯文本——
        // 拿这种数据把图片入口灰掉，比让上游报错更糟。
        val source = choices
            .firstOrNull { it.first.id == assistant.providerId && it.second.id == assistant.modelId }
            ?.first?.fieldSources?.get(assistant.modelId)?.get(ModelField.INPUT_MODALITIES)
        if (ModelCapabilities.blocksImages(source, currentModel?.inputModalities.orEmpty())) {
            false
        } else {
            null
        }
    }
    /**
     * 「思考」那一张表：**只列这个模型真接受的档位**。
     *
     * 真机反馈（2026-10-31）：模型里只勾了 low / medium，助手这边照样能把「高」选上，
     * 然后发请求时被 FFI 顶回来——选的时候不拦，发的时候才报错，等于让用户白填一次。
     *
     * 三条兜底：
     * - 模型没勾任何档位（`efforts` 空 = 没数据）→ **全列**，别替它做决定；
     * - `off` 只在「允许关闭」不是 `false` 时出现；
     * - `auto` 永远在场（它是"这次不指定"）。
     */
    val thinkingLevels = remember(currentModel) {
        val allowed = currentModel?.efforts.orEmpty()
        val canDisable = currentModel?.allowDisable != false
        THINKING_LEVELS.filter { (wire, _) ->
            when {
                wire == "auto" -> true
                wire == "off" -> canDisable
                allowed.isEmpty() -> true
                else -> wire in allowed
            }
        }
    }
    val thinkingLabel = remember(assistant.thinking, thinkingLevels) {
        val known = THINKING_LEVELS.firstOrNull { it.first == assistant.thinking }?.second
            ?: assistant.thinking
        val supported = thinkingLevels.any { it.first == assistant.thinking }
        if (supported) known else "$known（这个模型不支持）"
    }
    // 顶部这一行是**显示**，不是选择器（用户 2026-10-31）：一轮里用哪个模型由这一页决定，
    // 而模型条还兼着"当前用的是谁"的说明作用。格式「模型名称 (模型提供商)」——
    // id 是给端点看的，摆在标题下面只会让用户读到一串内部编号。
    val modelLabel = remember(assistant, choices, providerNames) {
        val pair = choices.firstOrNull {
            it.first.id == assistant.providerId && it.second.id == assistant.modelId
        }
        val providerName = providerNames[assistant.providerId]
            ?: assistant.providerId.ifBlank { "" }
        when {
            pair != null -> "${pair.second.displayName} ($providerName)"
            // 供应商里已经没有这个模型了：说清楚"没选"，而不是继续显示一个不存在的名字。
            assistant.providerId.isBlank() || assistant.modelId.isBlank() -> "还没选模型"
            else -> "未选择模型（$providerName 里已没有 ${assistant.modelId}）"
        }
    }

    // 原生 EditText 的引用：抽屉打开时要主动清焦点 + 收输入法。
    // Compose 的 clearFocus 对 AndroidView 里的原生控件不一定生效，所以这里拿真控件。
    val editTextRef = remember { mutableStateOf<EditText?>(null) }
    LaunchedEffect(drawerOpen) {
        if (drawerOpen) {
            editTextRef.value?.let { field ->
                field.clearFocus()
                val imm = context.getSystemService(
                    android.view.inputmethod.InputMethodManager::class.java,
                )
                imm?.hideSoftInputFromWindow(field.windowToken, 0)
            }
        }
    }

    // 边收边画（0.15ah）：等的时候每 ~100 ms 问一次 `turn_busy`。
    //
    // 拿的是**尾部 + 总量**：尾部只有 8 KiB，所以这里自己往上接——
    // 只把新长出来的那一段补到手里已有的正文后面。直接显示尾部的话，
    // 回答一过 8 KiB 开头就会凭空消失（看着像"前面被吞了"）。
    //
    // 轮号变了就是工具往返之后模型重新开口：清掉上一轮那截，别把两轮拼成一段。
    LaunchedEffect(sessionId, live?.running) {
        val sid = sessionId ?: return@LaunchedEffect
        if (live?.running != true) return@LaunchedEffect
        // `Started` 是在**发请求之前**发的，而 native 那边要等 `turn_run` 进来才登记。
        // 这一小段里 `turn_busy` 说"没在跑"——不能当成跑完了（D236 / D240）。
        var poll = LivePoll()
        var waited = 0
        while (true) {
            val info = withContext(Dispatchers.IO) { NmoiFfi.turnBusy(sid).getOrNull() } ?: break
            val reply = live ?: break
            val snapshot = BusySnapshot(
                running = info.optBoolean("running", false),
                round = info.optInt("round", 0),
                textLen = info.optInt("text_len", 0),
                reasoningLen = info.optInt("reasoning_len", 0),
                text = info.optString("text", ""),
                reasoning = info.optString("reasoning", ""),
                calls = runningCallsOf(info),
            )
            when (val step = advanceLive(reply, poll, snapshot)) {
                is LiveStep.Update -> {
                    live = step.reply
                    poll = step.poll
                }
                is LiveStep.Stop -> {
                    live = step.reply
                    break
                }
            }
            // 还没开始：给 native 一点时间登记，但别无限空转。
            if (!poll.started) {
                waited += 100
                if (waited > 15_000) break
            }
            delay(100)
        }
    }

    // 收尾之后等一次重读：`reloadKey` 变了 → `snapshot` 换了新对象 → 这里把内存里
    // 那条换成库里那份。等不到（内容没变）就 1.2 秒后收掉，别让"正在生成"一直挂着。
    LaunchedEffect(settling) {
        if (!settling) return@LaunchedEffect
        delay(1_200)
        if (settling) {
            settling = false
            live = null
            pending = null
        }
    }

    // 换会话就把选择清掉：跨会话的选中集合没有任何意义，还容易误复制。
    LaunchedEffect(sessionId) {
        selecting = false
        selected = emptySet()
    }

    // 空白会话（还没落库）不发读请求：没有 id 可读，界面直接给"还没有消息"的静态提示。
    val snapshot by produceState<ChatSnapshot?>(
        initialValue = null,
        sessionId,
        reloadKey,
        externalReload,
    ) {
        value = if (sessionId == null) {
            null
        } else {
            withContext(Dispatchers.IO) {
                ChatRepository.load(File(context.filesDir, "nmoi.db").absolutePath, sessionId)
            }
        }
    }

    val allMessages = snapshot?.page?.units?.flatMap { it.messages }.orEmpty()

    // 库里那份到了：收尾（0.15ah）。放在 `snapshot` 之后——它要等这个值换过才动。
    LaunchedEffect(snapshot) {
        if (settling) {
            settling = false
            live = null
            pending = null
        }
    }

    // 上下文占用：**和压缩判据同一个数字**（`TurnRepository` 把窗口交给 core 算绝对线）。
    // 窗口没填就不显示——显示一个猜出来的百分比比不显示更糟。
    //
    // 放在 `allMessages` 之后：它要用这个列表。写在前面会引用到还没声明的变量。
    val contextPercent = remember(allMessages, assistant, choices) {
        // 和压缩判据同一份分母：模型没填窗口就按应用设置里的默认值算。
        val window = choices
            .firstOrNull { it.first.id == assistant.providerId && it.second.id == assistant.modelId }
            ?.second?.contextWindow
            ?: AppSettings.defaultContextWindow(context)
        if (window <= 0) {
            null
        } else {
            val used = allMessages.sumOf { it.tokens }
            ((used * 100.0) / window).toInt()
        }
    }

    val selectedMessages = allMessages.filter { it.id in selected }.sortedBy { it.seq }

    val copySelected: () -> Unit = {
        if (selectedMessages.isEmpty()) {
            notice = "先选一条消息"
        } else {
            putClipboard(context, "选中的消息", selectedMessages.joinToString("\n\n") { it.content })
            notice = "已复制 ${selectedMessages.size} 条消息"
            selecting = false
            selected = emptySet()
        }
    }

    // 切版本 / 切原文-编辑（D137）：成功后重读，界面只看库里的状态，不做本地猜测。
    val switchVersion: (String, Long, String) -> Unit = { id, versionNo, view ->
        scope.launch {
            ChatRepository.switchVersion(databasePath, id, versionNo, view).fold(
                onSuccess = { reloadKey += 1 },
                onFailure = { notice = "切版本失败：${it.message}" },
            )
        }
    }

    // 「更多 → 复制为新版本」：**临时探针**。
    // `turn_run` 还没接，重新生成产不出版本，而版本 UI 没有数据就没法验。
    // 这条把当前正文再存一版，专门用来验 D137 那一套；接上 turn_run 就删掉它。
    val saveVersionProbe: (ChatMessage) -> Unit = { message ->
        scope.launch {
            // **所见即所得**（D153）：复制的必须是屏幕上现在显示的那一份——
            // 切在红就复制改后的，切在蓝就复制原文，他改过的字也要在。
            // 所以源文本现读（`versions().shown`），不用列表快照。
            val existing = ChatRepository.versions(databasePath, message.id).getOrNull()
            val source = existing?.shown?.takeIf { it.isNotEmpty() } ?: message.content
            // 这条消息还没有任何版本时，一次「复制」要落**两条**：
            // 先把现在显示的正文落成第 1 版，再把复制品落成第 2 版。
            // 只落一条的话 count=1，而界面按设计只在 count>1 时才显示 `n/n`——
            // 于是"点一次没反应、点两次才出 2/2"（2026-10-26 报的正是这个）。
            val needSeed = (existing?.count ?: 0) <= 0
            if (needSeed) {
                ChatRepository.saveVersion(databasePath, message.id, source)
                    .onFailure { notice = "存版本失败：${it.message}" }
            }
            ChatRepository.saveVersion(databasePath, message.id, source).fold(
                onSuccess = {
                    reloadKey += 1
                    notice = if (needSeed) "已复制为新版本（临时探针，两版）" else "已复制为新版本（临时探针）"
                },
                onFailure = { notice = "存版本失败：${it.message}" },
            )
        }
    }

    val saveEdit: (ChatMessage, Long, String, String) -> Unit = { message, versionNo, text, before ->
        val trimmed = text.trim()
        when {
            trimmed.isEmpty() -> notice = "编辑后的内容不能为空"
            // 比的是**预填的那一份**，不是列表快照：快照滞后时会误判成"内容没变"。
            trimmed == before.trim() -> notice = "内容没变"
            else -> scope.launch {
                ChatRepository.editVersion(databasePath, message.id, versionNo, trimmed).fold(
                    onSuccess = {
                        reloadKey += 1
                        notice = "已保存（用户编辑：不改颜色）"
                    },
                    onFailure = { notice = "编辑失败：${it.message}" },
                )
            }
        }
    }

    // **临时探针**：用"工具编辑"这条路改一版，专门用来验红/蓝到底怎么来的（D148）。
    // 真正的工具编辑要等 `core::edit::apply_edit` 桥出 FFI，那之前先用它把颜色造出来。
    val toolEditProbe: (ChatMessage, Long) -> Unit = { message, versionNo ->
        val marked = message.content + "\n\n（这一版是工具改的）"
        scope.launch {
            ChatRepository.toolEditVersion(databasePath, message.id, versionNo, marked).fold(
                onSuccess = {
                    reloadKey += 1
                    notice = "工具改完了（这一版应当变红/蓝）"
                },
                onFailure = { notice = "工具编辑失败：${it.message}" },
            )
        }
    }

    val deleteVersion: (String, Long) -> Unit = { id, versionNo ->
        // 先记下"界面想删哪一版"，再看仓储那边回的 count/active：两行对不上就能定位。
        NLog.i("Version", "点删除：id=$id 请求版号=$versionNo")
        scope.launch {
            ChatRepository.deleteVersion(databasePath, id, versionNo).fold(
                onSuccess = { left ->
                    reloadKey += 1
                    notice = "已删除这一版（剩 $left 版）"
                },
                onFailure = { notice = "删版本失败：${it.message}" },
            )
        }
    }

    // 删整条消息（D152）：正文连版本一起没。破坏性动作，先问一次。
    val deleteMessage: (ChatMessage) -> Unit = { message ->
        NLog.i("Version", "点删除整条消息：id=${message.id}")
        scope.launch {
            ChatRepository.deleteMessage(databasePath, message.id).fold(
                onSuccess = { removed ->
                    reloadKey += 1
                    notice = if (removed > 1) {
                        "已删除这条消息（连同它的 $removed 条工具往返）"
                    } else {
                        "已删除这条消息"
                    }
                },
                onFailure = { notice = "删除失败：${it.message}" },
            )
        }
    }

    // 分享的正文在这里成形：Markdown 而不是纯文本——分享出去多半是要粘进文档或笔记。
    val shareAsMarkdown: () -> Unit = {
        if (selectedMessages.isEmpty()) {
            notice = "先选一条消息"
        } else {
            val body = buildShareMarkdown(
                picked = selectedMessages,
                units = snapshot?.page?.units.orEmpty(),
                includeThinkingTools = includeThinkingTools,
                includeDetails = includeDetails,
                stampOf = { millis -> stampOf(context, millis) ?: "—" },
            )
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/markdown"
                putExtra(Intent.EXTRA_SUBJECT, title)
                putExtra(Intent.EXTRA_TEXT, body)
            }
            context.startActivity(Intent.createChooser(send, "分享对话"))
            shareOpen = false
            selecting = false
            selected = emptySet()
            notice = "已打开分享（Markdown，含 ${selectedMessages.size} 条消息）"
        }
    }

    // 选择模式也要吃返回键：先退选择，再谈退出页面（和抽屉同一套"后注册者优先"）。
    // 返回键要一层一层退（用户 2026-10-26 报：浮层弹出来后按返回直接退应用，不是关浮层）。
    // 优先级按叠放层次从高到低：编辑对话框 → 「更多」面板 → 分享面板 → 选择模式。
    // 抽屉与页面栈的返回在 MainActivity 里；ChatScreen 后注册，所以内层先轮到它。
    val overlayOpen = confirmDelete != null || editingId != null || moreForId != null || shareOpen ||
        selecting || modelSheet || thinkingSheet || queueSheet || plusMenu || regenFailure != null
    BackHandler(enabled = overlayOpen) {
        when {
            // 三选一**必须处理**（D228）：它决定库里留下哪一版，返回键在这里什么也不做。
            // 点外面也不关——不是"关掉就回到原样"，关掉等于替用户选了"保留上一版"。
            regenFailure != null -> Unit
            // 顺序 = 叠放顺序，从上往下退：先关最后打开的那一层。
            plusMenu -> plusMenu = false
            queueSheet -> queueSheet = false
            thinkingSheet -> thinkingSheet = false
            modelSheet -> modelSheet = false
            confirmDelete != null -> confirmDelete = null
            editingId != null -> editingId = null
            moreForId != null -> moreForId = null
            shareOpen -> shareOpen = false
            else -> {
                selecting = false
                selected = emptySet()
            }
        }
    }

    /**
     * 重试（0.15h / D185）：**前缀重发**——发出去的是"这一条（含）之前的历史"，不新增用户消息。
     *
     * `upTo` = 最后一条发出去的消息，`target` = 结果落成哪条助手消息的新版本。
     * 助手消息重试 → 重新生成这条回答，旧回答留成上一版；用户消息重试 → 这一句再发一次，
     * 下面那条助手消息多一版（下面没有就新增一条）。
     *
     * **关于"段"**：`upTo` 取的是列表里的上一条。分界线在界面上还画不出来、也插不了，
     * 所以真会话里目前只有一段；等线能插了，core 会用 `NotInWindow` 把跨段的请求顶回来
     * （不泄漏别的段），界面上再补"这条是这段的第一条"那种前置判断。
     */
    val runRegen: (String, String, String?, Boolean) -> Unit = { sid, upTo, target, append ->
        scope.launch {
            busy = true
            notice = null
            runningSession = sid
            // 就地渲染（D227）：这一轮的正文画在**目标消息**上，不再在底部另起一块。
            // 目标为空（用户消息重试、下面没有回答可挂）时仍然在底部——结果会落成一条新消息，
            // 那个位置就是它要去的地方，提前摆一个气泡在那里不算撒谎。
            regenTarget = target
            // 续话（0.15av）：落库那份是「原正文 + 续文」，流里只有续文那一截。
            // 这里把原正文预置成起点——native 那边的缓冲也垫了同一段，两边的字数才对得上。
            val before = if (append && target != null) {
                allMessages.firstOrNull { it.id == target }?.content.orEmpty()
            } else {
                ""
            }
            live = LiveReply(round = 0, running = true, text = before, prefix = before)
            settling = false
            // 和发送同一条记录：切走再切回来时"还在跑"要能显示（0.15c）。
            GeneratingTracker.mark(sid, true)
            val result = TurnRepository.regen(
                context = context,
                databasePath = databasePath,
                sessionId = sid,
                upTo = upTo,
                target = target,
                // 「允许生成新版本」关着 → 覆盖那一版（D185）。读的是应用设置，不是页面里的缓存。
                allowNewVersion = AppSettings.allowNewVersion(context),
                append = append,
                stream = assistant.stream,
            )
            // 残文要在清掉 `live` 之前取：失败三选一里「保留这一版」存的就是这一份。
            val partial = live?.text.orEmpty()
            val partialReasoning = live?.reasoning.orEmpty()
            runningSession = null
            GeneratingTracker.mark(sid, false)
            busy = false
            live = null
            regenTarget = null
            settling = false
            reloadKey += 1
            result.fold(
                onSuccess = { outcome ->
                    notice = (if (append) "续话：" else "重试：") + outcome.describe()
                },
                onFailure = { error ->
                    val reason = error.message ?: "原因不明"
                    if (target != null) {
                        // 三选一（D228）：这一版要不要留、上一版要不要留，由用户定。
                        // 一个字都没收到时也弹——「保留这一版」灰着写原因，
                        // 比一行会自己消失的提示更能说清"你的消息还在"。
                        regenFailure = RegenFailure(
                            messageId = target,
                            text = partial,
                            reasoning = partialReasoning,
                            reason = reason,
                        )
                    } else {
                        notice = (if (append) "续话失败：" else "重试失败：") + reason
                    }
                },
            )
        }
    }

    /** 点「重试」：按这一条在列表里的位置算出 `upTo` / `target`，算不出来就说原因。 */
    val retryTurn: (ChatMessage) -> Unit = { message ->
        val sid = sessionId
        val index = allMessages.indexOfFirst { it.id == message.id }
        when {
            busy -> notice = "上一轮还没结束（要打断就先点停止）"
            sid == null -> notice = "这条会话还没落库，没法重发"
            index < 0 -> notice = "这条消息已经不在列表里了：先刷新再看"
            message.isUser -> {
                // 用户消息重试：这一句自己就是最后一条发出去的东西。
                val reply = allMessages.getOrNull(index + 1)?.takeIf { !it.isUser }
                runRegen(sid, message.id, reply?.id, false)
            }

            index == 0 -> notice = "这条是这段的第一条，前面没有可重发的内容"
            else -> runRegen(sid, allMessages[index - 1].id, message.id, false)
        }
    }

    /**
     * 续话（D193）：把这一段再发一次，让模型**接着往下写**。
     *
     * - 最后一条是助手消息：`upTo` 与 `target` 都是它，正文接在原正文后面，落成新版本
     *   （切回上一版能看见接之前的样子）。
     * - 最后一条是用户消息：照常落一条新的助手消息。
     *
     * 只在"列表里最后一条可重试的消息"上给这一项——续话问的是"现在聊到哪儿了"，
     * 挂在中间某条上会让模型把后面的话再写一遍。
     */
    val continueTurn: (ChatMessage) -> Unit = { message ->
        val sid = sessionId
        when {
            busy -> notice = "上一轮还没结束（要打断就先点停止）"
            sid == null -> notice = "这条会话还没落库，没法接着说"
            message.isUser -> runRegen(sid, message.id, null, false)
            else -> runRegen(sid, message.id, message.id, true)
        }
    }

    // 只落库、不请求（长按 ↑ 的那两项，PLAN §3.12）：语义是"先记下来、晚点再发"。
    // 会话不存在就先建——建失败要说原因，不能静默丢掉用户刚打的字。
    /**
     * 屏幕上此刻的正文（D209）。**以原生输入框为准**，`draft` 只当兜底：
     * 输入法、程序化清空、草稿恢复三条路都可能让两者短暂不一致，
     * 而用户点发送时看到的是框里那一份。
     */
    fun composerText(): String =
        editTextRef.value?.text?.toString()?.trim().takeUnless { it.isNullOrEmpty() }
            ?: draft.trim()

    val appendOnly: (String) -> Unit = { role ->
        val text = composerText()
        when {
            text.isEmpty() -> notice = "先写点什么"
            busy -> notice = "上一轮还没结束（要打断就先点停止）"
            else -> scope.launch {
                busy = true
                val sid = sessionId ?: onCreateSession()
                if (sid == null) {
                    notice = "建会话失败：原因在 设置 → 调试 → 日志"
                } else {
                    ChatRepository.append(databasePath, sid, role, text).fold(
                        onSuccess = {
                            draft = ""
                            reloadKey += 1
                            notice = if (role == "user") {
                                "已存为用户消息（没发给模型）"
                            } else {
                                "已存为助手消息（没发给模型）"
                            }
                        },
                        onFailure = { notice = "存失败：${it.message}" },
                    )
                }
                busy = false
            }
        }
    }

    // 进页面时跟原生对一次账（0.15c）：本地缓存可能是上一轮的残留。
    // 查不到就当他还在跑——宁可多显示一次"正在生成"，也别把真在跑的那一轮当成结束了。
    LaunchedEffect(sessionId) {
        val sid = sessionId ?: return@LaunchedEffect
        if (!GeneratingTracker.isRunning(sid)) return@LaunchedEffect
        val alive = withContext(Dispatchers.IO) {
            NmoiFfi.turnBusy(sid).getOrNull()?.optBoolean("running") ?: true
        }
        if (GeneratingTracker.reconcile(sid, alive)) {
            // 这一轮在我们不在的时候结束了（或者根本没跑起来）。
            // 三样都要收干净：`pending` 不清，那句「发送中…」会一直挂着（用户 2026-11-04 报的）。
            runningSession = null
            pending = null
            live = null
            settling = false
            busy = false
            reloadKey += 1
            // 结果可能是在页面不在的时候到的：那时候事件没人收，从 `TurnRunner` 补领一次。
            notice = when (val missed = TurnRunner.takeRecent(sid)) {
                is TurnEvent.Finished -> missed.message
                is TurnEvent.Failed -> missed.message
                else -> "上一轮已经结束（原生那边没有在跑的了）：没有新消息就是它失败了，原因在 设置 → 调试 → 日志"
            }
        } else if (alive) {
            runningSession = sid
            // 切走再切回来：这一轮还在跑，把流式气泡重新立起来，轮询才会开始。
            if (live == null) live = LiveReply(round = 0, running = true)
            // 用户那条消息已经落库了（`turn_run` 先存再发），让库里那份顶掉乐观气泡。
            pending = null
            reloadKey += 1
        }
    }

    // 真的发一轮（D157；2026-10-31 改成"交给 TurnRunner，页面只订阅"）。
    //
    // 三条规矩合起来是用户那天口述的语义：
    // - 会话**空闲** → 立刻发；
    // - 会话**正在跑** → 不报错、不丢字，**排进这个会话的队列**，并告诉他排第几位；
    // - 这一轮**正常结束** → TurnRunner 自动取队首接着发；**失败 / 被停止** → 队列停在原地等他处理。
    val sendTurn: () -> Unit = {
        val text = composerText()
        val images = pendingImages
        // 只有图没有字：正文给一句占位，否则库里会是一条空消息（`Message::validate` 也拒）。
        // 这句话是**用户那条消息的正文**，界面上能看见，不是藏在别处的元数据。
        val body = text.ifEmpty { if (images.isEmpty()) "" else "（图片）" }
        when {
            body.isEmpty() -> notice = "先写点什么"
            else -> {
                draft = ""
                pendingImages = emptyList()
                notice = null
                scope.launch {
                    val sid = sessionId ?: onCreateSession()
                    if (sid == null) {
                        // 连会话都建不出来：把字放回输入框，别吞掉用户刚打的。
                        draft = text
                        pendingImages = images
                        notice = "建会话失败：原因在 设置 → 调试 → 日志"
                        return@launch
                    }
                    // 空白会话的草稿挪到真 id：用户回来时那半句还应该在。
                    DraftStore.migrateNewTo(context, sid)
                    if (TurnRunner.isRunning(sid)) {
                        if (images.isNotEmpty()) {
                            // 队列里只存文字：带图的这一轮排进去，图就悄悄丢了。
                            draft = text
                            pendingImages = images
                            notice = "这一轮还在跑：等它结束再发图（排队暂不支持图片）"
                            return@launch
                        }
                        val position = SendQueue.enqueue(sid, text)
                        notice = "已排队（第 $position 位）：这一轮结束后自动继续"
                    } else if (!TurnRunner.start(context, databasePath, sid, body, images)) {
                        if (images.isNotEmpty()) {
                            draft = text
                            pendingImages = images
                            notice = "这一轮马上结束：等它收尾再发图（排队暂不支持图片）"
                            return@launch
                        }
                        // 抢跑（刚好在这一瞬间被别的入口起来了）：退一步排队，不丢字。
                        SendQueue.enqueue(sid, text)
                        notice = "已排队：这一轮马上结束，等它收尾就继续"
                    } else {
                        // 立刻上屏（0.15ah）：**不等 Started 事件**。
                        // 新建会话时页面要先换到新 id，事件可能赶在那之前到、随后被
                        // `remember(sessionId)` 清掉——那样第一句话就看不见了。
                        pending = PendingSend(
                            session = sid,
                            text = body,
                            at = System.currentTimeMillis(),
                            imageCount = images.size,
                        )
                    }
                }
            }
        }
    }

    // 后台跑出来的结果。**只在"正看着这个会话"时反应**：事件带 sessionId，
    // 不是当前这个就丢掉——这正是以前"跑完 A 却把 B 的按钮点亮"的那类错。
    LaunchedEffect(sessionId) {
        val watching = sessionId ?: return@LaunchedEffect
        TurnRunner.events.collect { event ->
            if (event.sessionId != watching) return@collect
            when (event) {
                is TurnEvent.Started -> {
                    pending = PendingSend(
                        session = event.sessionId,
                        text = event.text,
                        at = System.currentTimeMillis(),
                        // 图已经交给 core 了，界面上得留个痕：不然发完那一刻图凭空消失，
                        // 看着像"图被吞了"。
                        imageCount = event.imageCount,
                    )
                    // 空壳先立起来：正文与思考各自从零开始长。
                    live = LiveReply(round = 0, running = true)
                    settling = false
                    busy = true
                }

                is TurnEvent.Finished -> {
                    // **不立刻清**：库里那份还没读回来，先清会让气泡闪一下。
                    // 保持"正在生成"的形态，等 `snapshot` 换过再收（见上面那条 effect）。
                    val streamed = live?.sawDelta == true
                    live = live?.copy(running = false)
                    settling = true
                    busy = false
                    reloadKey += 1
                    val left = SendQueue.count(watching)
                    // 一个字都没逐字长出来：说清是哪一种原因（D242）。不说的话，
                    // 用户只会看到"等半天一下全出来"，然后问"流式意义何在"。
                    val why = when {
                        streamed -> ""
                        !assistant.stream -> "（助手的「逐字输出」关着：去助手设置里打开）"
                        else -> "（这一轮没收到任何增量：端点可能不支持流式，已退回非流式一次性返回）"
                    }
                    notice = buildString {
                        append(event.message)
                        append(why)
                        if (left > 0) append("（队列还有 $left 条）")
                    }
                }

                is TurnEvent.Failed -> {
                    // 断在中途：已经收到的部分**留在屏幕上**（用户要能看见它写到哪儿断的），
                    // 但不假装它落库了——库里没有，所以标一句、点一下收掉。
                    val cut = live?.let { reply ->
                        if (reply.text.isBlank() && reply.reasoning.isBlank()) null
                        else reply.copy(running = false, cut = true)
                    }
                    live = cut
                    settling = false
                    pending = null
                    busy = false
                    reloadKey += 1
                    // 没存进去就把字还回输入框：宁可让他看到重复的一句，也不要静默丢字。
                    if (!event.textSaved) draft = event.text
                    notice = event.message
                }
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (selecting) {
                SelectionTopBar(
                    count = selected.size,
                    onCancel = {
                        selecting = false
                        selected = emptySet()
                    },
                    onCopy = copySelected,
                    onShare = { shareOpen = true },
                )
                // 选择模式下输入区是藏起来的，所以"已复制 N 条"这类提示要放在这儿。
                // 注意不能写 `if (notice != null) Text(notice)`：notice 是委托属性，
                // Kotlin 不给它做智能转换（编译期就会报 Smart cast is impossible）。
                val selectingNotice = notice
                if (selectingNotice != null) {
                    Text(
                        text = selectingNotice,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp),
                    )
                }
            } else {
                ChatTopBar(
                    title = title,
                    onOpenDrawer = onOpenDrawer,
                    onNewChat = onNewChat,
                )
                ModelBar(label = modelLabel, percent = contextPercent, onClick = { modelSheet = true })
            }

            Box(modifier = Modifier.weight(1f)) {
                if (sessionId == null) {
                    BlankConversationHint()
                } else {
                    MessageArea(
                        snapshot = snapshot,
                        // 只画**当前这个会话**的那一条：切走时它不该跟着过去。
                        pending = pending?.takeIf { it.session == sessionId },
                        live = live,
                        // 重试就地渲染（D227）：这一轮的正文画在它身上，不在底部另起一块。
                        regenTarget = regenTarget,
                        // 过程块（D229）：展开状态只活在这个会话的这次浏览里。
                        openGroups = openGroups,
                        onToggleGroup = { key -> openGroups[key] = openGroups[key] != true },
                        onOpenTool = { unit -> toolSheet = unit },
                        onCancelCall = cancelCall,
                        onDismissCut = { live = null },
                        selecting = selecting,
                        selected = selected,
                        onLongPressMessage = { id ->
                            selecting = true
                            selected = setOf(id)
                        },
                        onToggleMessage = { id ->
                            val next = if (id in selected) selected - id else selected + id
                            selected = next
                            if (next.isEmpty()) selecting = false
                        },
                        onCopied = { notice = "已复制这条消息" },
                        onMoreMessage = { id ->
                            // 只记 id（D155）：对象是这一拍的副本，写操作要用时现取。
                            // 这一行仍是排查版本的**第一现场**，但打的是"此刻行内认为"的值。
                            val target = allMessages.firstOrNull { it.id == id }
                            if (target != null) {
                                NLog.i(
                                    "Version",
                                    "打开更多：id=$id 行内=${target.versionCount}/${target.versionActive} " +
                                        "view=${target.versionView} edited=${target.versionEdited}",
                                )
                            }
                            moreForId = id
                        },
                        onVersionMessage = switchVersion,
                        // 重试（0.15av）：动作行里那颗「刷新」不再是灰的。
                        // 只传 id，点下去再按 id 现取——列表是快照，动作要用的是库里那一份。
                        retryEnabled = sessionId != null,
                        onRetryMessage = { id ->
                            val target = allMessages.firstOrNull { it.id == id }
                            if (target == null) {
                                notice = "这条消息已经不在列表里了：先刷新再看"
                            } else {
                                retryTurn(target)
                            }
                        },
                        // 「更多 → 分享」= 选中这一条并直接打开分享面板：
                        // 分享本来就是"选几条再导"，让它从一条开始，少两步。
                        onShareMessage = { id ->
                            selecting = true
                            selected = setOf(id)
                            shareOpen = true
                        },
                    )
                }
            }

            if (!selecting) {
                Composer(
                    draft = draft,
                    onDraftChange = { draft = it },
                    notice = notice,
                    canSend = !busy,
                    busy = busy,
                    unsaved = sessionId == null,
                    onHint = { notice = it },
                    onModel = { modelSheet = true },
                    onThinking = { thinkingSheet = true },
                    thinkingLabel = thinkingLabel,
                    onEditTextReady = { editTextRef.value = it },
                    onSend = sendTurn,
                    onQueue = { queueSheet = true },
                    queued = if (sessionId == null) 0 else SendQueue.count(sessionId),
                    onStop = {
                        val running = runningSession
                            ?: sessionId?.takeIf { GeneratingTracker.isRunning(it) }
                        if (running == null) {
                            notice = "这一轮已经收尾了"
                        } else {
                            scope.launch {
                                TurnRepository.cancel(running).fold(
                                    onSuccess = { cancelled ->
                                        notice = if (cancelled) {
                                            "已请求停止：当前这一次请求回来之后就不再往下走了"
                                        } else {
                                            "这一轮已经收尾了"
                                        }
                                    },
                                    onFailure = { notice = "取消失败：${it.message}" },
                                )
                            }
                        }
                    },
                    onSaveAssistant = { appendOnly("assistant") },
                    onSaveUser = { appendOnly("user") },
                    pending = pendingImages,
                    onRemovePending = { index ->
                        pendingImages = pendingImages.filterIndexed { i, _ -> i != index }
                    },
                    onPlus = { plusMenu = true },
                    imageCapability = imageCapability,
                )

            } else {
                // 选择模式下藏起输入区，但留 10dp 底边，免得最后一条消息贴住屏幕边缘。
                Spacer(Modifier.heightIn(min = 10.dp))
            }
        }

        if (plusMenu) {
            // 「＋」面板（D208）：图片是真入口，其余三项照旧给"差什么"。
            // 不给假开关的意思是"点了要有回应"，不是"不许摆"。
            MenuSheet(
                title = "添加",
                items = listOf(
                    // 图片**永远能选**（D211）：计划里有视觉覆盖，模型自己收不收图
                    // 不该在这里拦人。已知不收图时只提示一句，让上游去判。
                    SheetItem(
                        label = "图片",
                        icon = ActionIcons.Cpu,
                        onClick = {
                            plusMenu = false
                            if (imageCapability == false) {
                                notice = ModelCapabilities.refusal(currentModel?.inputModalities.orEmpty()) +
                                    "（仍会发出去；要换模型再说）"
                            }
                            pickImage.launch("image/*")
                        },
                    ),
                    SheetItem(
                        label = "文件",
                        icon = ActionIcons.Wrench,
                        hint = "文件附件待接：这一批只做了图片（D208）",
                    ),
                    SheetItem(
                        label = "技能 / 世界书",
                        icon = ActionIcons.GitBranch,
                        hint = "技能与世界书入口待接：预设（P4）之后",
                    ),
                ),
                onHint = { notice = it },
                onDismiss = { plusMenu = false },
            )
        }

        if (modelSheet) {
            ModelPickerSheet(
                databasePath = databasePath,
                title = "用哪个模型",
                choices = choices.map { (provider, model) -> ModelChoice(provider, model) },
                selected = ModelFavorites.key(assistant.providerId, assistant.modelId),
                emptyHint = "还没有可用的模型：先去 设置 → 模型与提示词 → 模型服务 里填一套端点与模型",
                onPick = { choice ->
                    modelSheet = false
                    scope.launch {
                        val next = assistant.copy(
                            providerId = choice.provider.id,
                            modelId = choice.model.id,
                        )
                        AssistantRepository.save(databasePath, next).fold(
                            onSuccess = {
                                assistant = next
                                notice = "这一轮起用 ${choice.model.displayName}"
                            },
                            onFailure = { notice = "切换失败：${it.message}" },
                        )
                    }
                },
                onNotice = { notice = it },
                onDismiss = { modelSheet = false },
                onEditModel = onEditModel?.let { edit ->
                    { choice ->
                        modelSheet = false
                        edit(choice.provider.id, choice.model.id)
                    }
                },
                onEditProvider = onEditProvider?.let { edit ->
                    { providerId ->
                        modelSheet = false
                        edit(providerId)
                    }
                },
            )
        }

        if (queueSheet) {
            val sid = sessionId
            if (sid == null) {
                // 还没有会话就没有队列：这一层不该出现（调用方已经拦了，这里只是兜底）。
                queueSheet = false
            } else {
                SendQueueSheet(
                    sessionId = sid,
                    running = TurnRunner.isRunning(sid),
                    onSendNow = { text ->
                        // 手动发队首：只在"这个会话没在请求"时允许（用户 2026-10-31 的口径）。
                        pending = PendingSend(
                            session = sid,
                            text = text,
                            at = System.currentTimeMillis(),
                        )
                        live = LiveReply(round = 0, running = true)
                        settling = false
                        busy = true
                        TurnRunner.start(context, databasePath, sid, text)
                        queueSheet = false
                    },
                    onClose = { queueSheet = false },
                )
            }
        }

        // 工具块点开的那一条（D243）：半屏弹窗，看完就收。
        toolSheet?.let { unit ->
            ToolSheet(unit = unit, onDismiss = { toolSheet = null })
        }

        if (thinkingSheet) {
            MenuSheet(
                title = "思考强度",
                items = thinkingLevels.map { (wire, label) ->
                    SheetItem(
                        label = if (wire == assistant.thinking) "$label（当前）" else label,
                        icon = ActionIcons.Gauge,
                        hint = null,
                        onClick = {
                            thinkingSheet = false
                            scope.launch {
                                val next = assistant.copy(thinking = wire)
                                AssistantRepository.save(databasePath, next).fold(
                                    onSuccess = {
                                        assistant = next
                                        notice = "思考强度：$label（下一轮开始生效）"
                                    },
                                    onFailure = { notice = "保存失败：${it.message}" },
                                )
                            }
                        },
                    )
                },
                onHint = { notice = it },
                onDismiss = { thinkingSheet = false },
            )
        }

        if (shareOpen) {
            ShareOptionsSheet(
                count = selectedMessages.size,
                includeThinkingTools = includeThinkingTools,
                includeDetails = includeDetails,
                onToggleThinkingTools = { includeThinkingTools = !includeThinkingTools },
                onToggleDetails = { includeDetails = !includeDetails },
                onMarkdown = shareAsMarkdown,
                onImage = {},
                onDismiss = { shareOpen = false },
            )
        }

        val pendingDelete = confirmDelete
        if (pendingDelete != null) {
            ConfirmDeleteDialog(
                preview = pendingDelete.content,
                versionCount = pendingDelete.versionCount,
                onCancel = { confirmDelete = null },
                onConfirm = {
                    confirmDelete = null
                    deleteMessage(pendingDelete)
                },
            )
        }

        // 失败三选一（D228）的两个"留残文"分支：先存成新版本，再按选择决定上一版去留。
        // 存的是**内存里这一轮收到的正文**——它还没落过库，所以这是唯一的一份，不是快照。
        val keepPartial: (RegenFailure, Boolean) -> Unit = { failure, dropPrevious ->
            regenFailure = null
            scope.launch {
                val saved = ChatRepository.saveVersion(databasePath, failure.messageId, failure.text)
                if (saved.isFailure) {
                    notice = "存这一版失败：${saved.exceptionOrNull()?.message}"
                } else {
                    reloadKey += 1
                    if (!dropPrevious) {
                        notice = "两版都留着（这一版是这次收到的 ${failure.text.length} 字，切回去能看上一版）"
                    } else {
                        // 「保留这一版」= 只留这一版：上一版删掉。版号现读——存完之后
                        // 新的那一版才是 `active`，上一版就是它前面那个。
                        val info = ChatRepository.versions(databasePath, failure.messageId).getOrNull()
                        val previous = ((info?.active ?: 2) - 1).toLong()
                        val dropped = if (previous >= 1L) {
                            ChatRepository.deleteVersion(databasePath, failure.messageId, previous)
                        } else {
                            Result.success(0)
                        }
                        reloadKey += 1
                        notice = if (dropped.isSuccess) {
                            "只留这一版（上一版已删）"
                        } else {
                            "这一版存下了，但上一版没删掉：${dropped.exceptionOrNull()?.message}"
                        }
                    }
                }
            }
        }
        val pendingFailure = regenFailure
        if (pendingFailure != null) {
            RegenFailureDialog(
                failure = pendingFailure,
                onKeepPrevious = {
                    regenFailure = null
                    notice = "已保留上一版：这次收到的内容没有存进库里"
                },
                onKeepThis = { keepPartial(pendingFailure, true) },
                onKeepBoth = { keepPartial(pendingFailure, false) },
            )
        }

        // 现取（D155）：编辑的是"这一条现在的内容"，不是点开那一刻的副本。
        val editingMessage = editingId?.let { id -> allMessages.firstOrNull { it.id == id } }
        if (editingMessage != null) {
            EditVersionDialog(
                initial = editingInitial,
                onCancel = { editingId = null },
                onSave = { text ->
                    editingId = null
                    saveEdit(editingMessage, editingVersion.coerceAtLeast(1L), text, editingInitial)
                },
            )
        }

        // 现取（D155）：面板显示的、动作改的，都是"现在这一条"。
        val sheetMessage = moreForId?.let { id -> allMessages.firstOrNull { it.id == id } }
        // 那一条已经不在了（删掉 / 换了会话）：把面板收掉。
        // 不收的话浮层状态还挂着，按返回会先"退"这个看不见的层，用户以为返回没反应。
        LaunchedEffect(moreForId, allMessages) {
            if (moreForId != null && allMessages.isNotEmpty() &&
                allMessages.none { it.id == moreForId }
            ) {
                moreForId = null
            }
        }
        LaunchedEffect(editingId, allMessages) {
            if (editingId != null && allMessages.isNotEmpty() &&
                allMessages.none { it.id == editingId }
            ) {
                editingId = null
            }
        }
        if (sheetMessage != null) {
            // 面板显示/动作统一用 `moreVersion`（开面板时现读的库）；还没读到时先用行内那份顶一下。
            val sheetCount = moreVersion?.count ?: sheetMessage.versionCount
            val sheetActive = (moreVersion?.active ?: sheetMessage.versionActive).coerceAtLeast(1)
            // 第一现场：**同一刻**两个来源（行内快照 / 现读库）。不相等说明行内那条旧了。
            val nowCount = moreVersion?.count ?: -1
            val nowActive = moreVersion?.active ?: -1
            val nowView = moreVersion?.view ?: "none"
            LaunchedEffect(moreVersion, sheetMessage.versionCount) {
                NLog.i(
                    "Version",
                    "面板 id=${sheetMessage.id} 行内=${sheetMessage.versionCount}/${sheetMessage.versionActive} " +
                        "view=${sheetMessage.versionView} | 现读=$nowCount/$nowActive view=$nowView",
                )
            }
            val shown = sheetMessage.copy(
                versionCount = sheetCount,
                versionActive = sheetActive,
                versionView = moreVersion?.view ?: sheetMessage.versionView,
                versionEdited = moreVersion?.edited ?: sheetMessage.versionEdited,
            )
            MenuSheet(
                title = if (shown.isUser) "这条用户消息" else "这条助手消息",
                items = messageSheetItems(
                    message = shown,
                    // 重试（0.15h）：会话都没落库时不给这一项——没有可重发的东西，
                    // 摆一个点了只能说"不行"的行，比不摆更吵（「空位可以、假开关不行」）。
                    retryItem = if (sessionId == null || !shown.canRetry) {
                        null
                    } else {
                        SheetItem(
                            label = if (shown.isUser) "重试这条之后" else "重试这一轮",
                            icon = ActionIcons.RotateCcw,
                            onClick = { retryTurn(shown) },
                        )
                    },
                    // 续话（D193）：只挂在**列表里最后一条**可重试的消息上。
                    // 挂在中间那条上，模型会把后面已经说过的话再写一遍。
                    continueItem = if (
                        sessionId == null || !shown.canRetry ||
                        allMessages.lastOrNull { it.canRetry }?.id != shown.id
                    ) {
                        null
                    } else {
                        SheetItem(
                            label = if (shown.isUser) "续话（让它回一条）" else "续话（接着往下写）",
                            icon = ActionIcons.ArrowRight,
                            onClick = { continueTurn(shown) },
                        )
                    },
                    onShare = {
                        selecting = true
                        selected = setOf(sheetMessage.id)
                        shareOpen = true
                    },
                    onSelectOnly = {
                        selecting = true
                        selected = setOf(sheetMessage.id)
                    },
                    onSaveVersion = { saveVersionProbe(sheetMessage) },
                    onEdit = {
                        // 版号与预填都取**面板确认过的那个**，不是列表里的：
                        // 这就是"编辑 2/2 却改到 1/2"和"看着改后、编辑框里是原文"的病根。
                        editingVersion = sheetActive.toLong()
                        editingInitial = moreVersion?.shown?.takeIf { it.isNotEmpty() }
                            ?: sheetMessage.content
                        editingId = sheetMessage.id
                    },
                    onToolEdit = { toolEditProbe(sheetMessage, sheetActive.toLong()) },
                    onOpenEditor = {
                        moreForId = null
                        // 整页编辑器要的是**消息 id**（它进页再现读库，铁律①）。
                        // 没传回调说明这一屏不是从 MainActivity 挂上来的（预览/测试），
                        // 那时候要说清"差什么"，不能点了没反应。
                        val open = onEditMessage
                        if (open != null) {
                            open(sheetMessage.id)
                        } else {
                            notice = "编辑页还没接上：这一屏没有传 onEditMessage"
                        }
                    },
                    // `versionActive` 兜底成 1：万一它是 0（老库/字段没读到），
                    // 传 0 会被 storage 当成"删除所有版本"，那不是用户点"删除此版本"的意思。
                    onDeleteThis = {
                        deleteVersion(sheetMessage.id, sheetActive.toLong())
                    },
                    onDeleteAll = { confirmDelete = sheetMessage },
                ),
                onHint = { notice = it },
                onDismiss = { moreForId = null },
            )
        }
    }
}

/**
 * 分享选项：**上排两个开关、下排两个动作**（用户 2026-10-24 定的形状）。
 *
 * - 上排：思考调用（把这条消息所属单元里的思考 / 工具往返一并带上）、详细信息（带上 id / seq / token 这类元数据）。
 * - 下排：MD（真做，走系统分享）、图片（**灰着**：把消息渲染成图片那一层还没做，
 *   现在给"能按但没反应"的按钮就是骗人）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShareOptionsSheet(
    count: Int,
    includeThinkingTools: Boolean,
    includeDetails: Boolean,
    onToggleThinkingTools: () -> Unit,
    onToggleDetails: () -> Unit,
    onMarkdown: () -> Unit,
    onImage: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
            Text(
                text = if (count == 0) "分享" else "分享 $count 条消息",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "先选要带上的内容，再决定导成什么。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )

            // 上排：两个开关。选中的按钮用 tonal 底色 + 勾，别只靠颜色差别。
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SheetToggle(
                    label = "思考调用",
                    checked = includeThinkingTools,
                    onClick = onToggleThinkingTools,
                    modifier = Modifier.weight(1f),
                )
                SheetToggle(
                    label = "详细信息",
                    checked = includeDetails,
                    onClick = onToggleDetails,
                    modifier = Modifier.weight(1f),
                )
            }

            // 下排：两个动作。
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                FilledTonalButton(
                    onClick = onMarkdown,
                    enabled = count > 0,
                    modifier = Modifier.weight(1f),
                ) { Text("MD") }
                FilledTonalButton(
                    onClick = onImage,
                    enabled = false,
                    modifier = Modifier.weight(1f),
                ) { Text("图片（待接）") }
            }
        }
    }
}

/** 分享面板里的开关：勾选态用图标 + 底色两个信号，色盲也分得出来。 */
@Composable
private fun SheetToggle(
    label: String,
    checked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (checked) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        modifier = modifier.clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (checked) ActionIcons.Check else ActionIcons.Plus,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

/**
 * 分享正文：Markdown。
 *
 * `includeThinkingTools` 时，把**同一条消息所属单元**里的思考与工具往返也带上——
 * 用户勾这个开关的意思是"我要连过程一起分享"，不是"只分享我选的那几条正文"。
 * `includeDetails` 时每条后面附一行元数据（id / seq / source / tokens / 是否未完成）。
 */
private fun buildShareMarkdown(
    picked: List<ChatMessage>,
    units: List<ChatUnit>,
    includeThinkingTools: Boolean,
    includeDetails: Boolean,
    stampOf: (Long) -> String,
): String {
    val byId = picked.associateBy { it.id }
    val unitOf = mutableMapOf<String, ChatUnit>()
    units.forEach { unit -> unit.messages.forEach { if (it.id in byId) unitOf[it.id] = unit } }

    return buildString {
        picked.forEach { message ->
            append("### ")
            append(if (message.isUser) USER_NAME else ASSISTANT_NAME)
            append(" · ")
            append(stampOf(message.createdAt))
            append("\n\n")
            append(message.content.ifBlank { "（空消息）" })
            append("\n")

            if (includeThinkingTools) {
                val unit = unitOf[message.id]
                val extra = unit?.messages.orEmpty().filter {
                    it.id != message.id && (it.isThinking || it.isToolCall || it.isToolResult)
                }
                if (extra.isNotEmpty()) {
                    append("\n<details><summary>思考与工具调用（")
                    append(extra.size)
                    append(" 段）</summary>\n\n")
                    extra.forEach { part ->
                        append("- **")
                        append(
                            when {
                                part.isThinking -> "思考"
                                part.isToolCall -> "工具调用"
                                else -> "工具结果"
                            },
                        )
                        append("**：")
                        append(part.content.replace("\n", "\n  "))
                        append("\n")
                    }
                    append("\n</details>\n")
                }
            }

            if (includeDetails) {
                append("\n> id=")
                append(message.id)
                append(" · seq=")
                append(message.seq)
                append(" · source=")
                append(message.source)
                append(" · tokens=")
                append(message.tokens)
                if (message.incomplete) append(" · 未完成")
                append("\n")
            }
            append("\n---\n\n")
        }
    }
}

/** 选择模式的顶栏：取消 + 已选条数 + 复制。删除灰着（`delete` 的 FFI 还没有）。 */
@Composable
private fun SelectionTopBar(
    count: Int,
    onCancel: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconActionButton(
            image = ActionIcons.Close,
            description = "取消选择",
            onClick = onCancel,
        )
        Text(
            text = if (count == 0) "选择消息" else "已选 $count 条",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f).padding(start = 4.dp),
            maxLines = 1,
        )
        IconActionButton(
            image = ActionIcons.Copy,
            description = "复制选中的消息",
            onClick = onCopy,
            enabled = count > 0,
        )
        IconActionButton(
            image = ActionIcons.Share2,
            description = "分享选中的消息",
            onClick = onShare,
            enabled = count > 0,
        )
    }
}

/**
 * 顶栏：抽屉 + 标题 + 新建。
 *
 * 标题只给一行、可省略：会话名长了之后换行会把顶栏撑高，滚动时看着像页面在跳。
 * 右上角是"新建"而不是"更多"——这是每次都要用的动作，藏进菜单等于每次多点一层。
 */
@Composable
private fun ChatTopBar(title: String, onOpenDrawer: () -> Unit, onNewChat: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MenuButton(onClick = onOpenDrawer)
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f).padding(start = 4.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        IconActionButton(
            image = ActionIcons.Plus,
            description = "新建会话",
            onClick = onNewChat,
        )
    }
}

/** 还没落库的空会话：说明"接下来会发生什么"，不摆一个假的欢迎卡。 */
@Composable
private fun BlankConversationHint() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "还没有消息",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "写第一句存下去，会话就在那一刻建立。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, start = 24.dp, end = 24.dp),
            )
        }
    }
}

/**
 * 消息区。
 *
 * 状态必须说清是哪一种，不能都是"一片空"：
 * ① 还没读出来（`snapshot == null` 且已落库）；② 读不出来（`page == null`，附原因）；
 * ③ 读到了但一条没有；④ 有内容。
 */
@Composable
private fun MessageArea(
    snapshot: ChatSnapshot?,
    /** 刚发出去、还在路上的那句话：**立刻显示在消息区里**，不要留在输入框里等。 */
    pending: PendingSend?,
    /** 这一轮正在长的回复（0.15ah）。`null` = 没有在跑，也没留残文。 */
    live: LiveReply?,
    /**
     * 这一轮长在哪条消息上（D227）。非空时 `live` **不再**在底部画，
     * 而是画在这条消息的正文位置——重试看着像"这条回答在重写"，不是"又开了一个气泡"。
     */
    regenTarget: String?,
    /** 过程块（D229）展开状态：键是组的第一项 id。 */
    openGroups: Map<String, Boolean> = emptyMap(),
    onToggleGroup: (String) -> Unit = {},
    /** 工具块点开：在**半屏弹窗**里看细节（用户 2026-11-04 定），不在聊天页里就地展开。 */
    onOpenTool: (ChatUnit) -> Unit = {},
    /** 关掉一条正在跑的工具调用（0.15ak）。 */
    onCancelCall: (String) -> Unit = {},
    /** 断在中途那一段：点一下收掉（它没落库，留着只是让他看见写到哪儿）。 */
    onDismissCut: () -> Unit,
    selecting: Boolean,
    selected: Set<String>,
    onLongPressMessage: (String) -> Unit,
    onToggleMessage: (String) -> Unit,
    onCopied: () -> Unit,
    onShareMessage: (String) -> Unit,
    onMoreMessage: (String) -> Unit,
    onVersionMessage: (String, Long, String) -> Unit,
    /** 会话落库了没有（重试要写版本）。 */
    retryEnabled: Boolean = false,
    onRetryMessage: (String) -> Unit = {},
) {
    if (snapshot == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "正在读…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val page = snapshot.page
    if ((page == null || page.isEmpty) && pending == null && live == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (page == null) "这一段历史还没被读出来" else "这个会话还空着",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val note = snapshot.note
                if (note != null) {
                    Text(
                        text = note,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 6.dp),
                    )
                }
            }
        }
        return
    }

    val units = page?.units.orEmpty()
    // 连续的思考/工具合成一行（D229）。分组只影响渲染，数据一个字都不动。
    val rows = remember(units) { buildRows(units) }
    val truncated = page?.truncated == true
    val listState = rememberLazyListState()
    val headerCount = if (truncated) 1 else 0
    val maxBubble = (LocalConfiguration.current.screenWidthDp * 0.78f).dp

    // 打开会话应当看到最新的一条，而不是最早的一条。
    // 发出去那一刻 `pending` 会插在末尾，所以它也要进 key——否则新气泡在屏幕外面。
    val liveItem = if (live != null && regenTarget == null) 1 else 0
    LaunchedEffect(rows.size, truncated, pending, live != null, regenTarget) {
        val target = headerCount + rows.size + if (pending != null) 1 else 0 + liveItem
        if (target - 1 >= 0) listState.scrollToItem(target - 1)
    }

    // 边写边跟（0.15ah）：只在**已经在底部**时跟。用户往上翻看旧消息时不抢他的位置——
    // 判断用的是这一帧的 `canScrollForward`：能往前滚就说明底下还有东西没看到。
    LaunchedEffect(live?.text?.length ?: 0, live?.reasoning?.length ?: 0) {
        if (live == null) return@LaunchedEffect
        if (listState.canScrollForward) return@LaunchedEffect
        val target = headerCount + rows.size + if (pending != null) 1 else 0 + liveItem
        if (target - 1 >= 0) listState.scrollToItem(target - 1)
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        // 左右边距一致 18dp（2026-10-24 第二轮：8dp 太贴边，按 2.2 倍放大）。
        // 助手正文**整行铺开**、左边缘和头像左边缘同一条线（D142）。
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
        // 同角色 4dp、异角色 12dp（D126）：4 是基准间距，换人时再加 8。
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (truncated) {
            item {
                Text(
                    text = "上面还有更早的消息：共 ${page?.total} 个单元，这里只读了最后 ${units.size} 个",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
            }
        }
        itemsIndexed(rows) { index, row ->
            val previous = rows.getOrNull(index - 1)
            // 换人（用户 ↔ 助手）才加那 8dp；同一轮里的几个块贴在一起。
            val gap = if (previous != null && previous.alignsRight() != row.alignsRight()) 8.dp else 0.dp
            // 一轮助手的话可能被拆成好几个单元（工具往返一段、正文一段），抬头只画一次。
            val assistantSide = !row.alignsRight()
            val previousAssistantSide = previous != null && !previous.alignsRight()
            val showHeader = assistantSide && !previousAssistantSide
            if (row is ChatRow.Process) {
                ProcessGroupRow(
                    group = row,
                    showHeader = showHeader,
                    gapAbove = gap,
                    expanded = openGroups[row.key] == true,
                    onToggle = { onToggleGroup(row.key) },
                    onOpenTool = onOpenTool,
                    thinkingOpen = { id -> openGroups["think:$id"] == true },
                    onToggleThinking = { id -> onToggleGroup("think:$id") },
                )
                return@itemsIndexed
            }
            val unit = (row as ChatRow.Single).unit
            UnitBlock(
                unit = unit,
                showHeader = showHeader,
                hideReasoning = row.hideReasoning,
                onOpenTool = onOpenTool,
                onCancelCall = onCancelCall,
                maxBubble = maxBubble,
                gapAbove = gap,
                live = live,
                regenTarget = regenTarget,
                selecting = selecting,
                selected = selected,
                onLongPressMessage = onLongPressMessage,
                onToggleMessage = onToggleMessage,
                onCopied = onCopied,
                onShareMessage = onShareMessage,
                onMoreMessage = onMoreMessage,
                onVersionMessage = onVersionMessage,
                retryEnabled = retryEnabled,
                onRetryMessage = onRetryMessage,
            )
        }

        val outbox = pending
        if (outbox != null) {
            item(key = "pending-user") {
                // 这一条还没从库里读回来（`run_session_turn` 是先落库再发请求，
                // 但读完要等这一轮收尾），所以先按用户消息的形状画出来。
                UserMessage(
                    message = ChatMessage(
                        id = "pending:user",
                        seq = Long.MAX_VALUE,
                        role = "user",
                        source = "history",
                        content = outbox.text,
                        tokens = 0,
                        createdAt = outbox.at,
                        incomplete = false,
                        locked = false,
                        toolCalls = 0,
                        toolCallId = null,
                    ),
                    maxBubble = maxBubble,
                    selecting = false,
                    isSelected = false,
                    onLongPress = {},
                    onTap = {},
                    onCopied = {},
                    onShare = {},
                    onMore = {},
                    onVersion = { _, _ -> },
                    // 「发送中」不是一个装饰：它说明这条**还没进库**，
                    // 失败时会退回输入框而不是留在这里（TurnEvent.Failed 那条）。
                    sending = true,
                    pendingImageCount = outbox.imageCount,
                )
            }
        }
        val streaming = live
        // 有目标消息时这一轮画在那条消息上（D227）；只有"结果会落成一条新消息"时
        // 才在底部立一个占位——那个位置本来就是新消息要去的地方。
        if (streaming != null && regenTarget == null) {
            item(key = "live-assistant") {
                LiveReplyRow(
                    reply = streaming,
                    onDismissCut = onDismissCut,
                    onCancelCall = onCancelCall,
                )
            }
        }
    }
}

/**
 * 这一轮正在长出来的回复（0.15ah）。
 *
 * 三件事必须**同时**成立，否则真机上看着就像坏了：
 *
 * 1. 形状与落库那条一致（抬头、思考卡、正文的字体与边距）——收尾换成库里那份时不跳；
 * 2. 思考块只显示字数、默认收起——几百字的思考铺开会把正文挤到屏幕外；
 * 3. 断在中途要**留着**并说清楚（它没落库），点一下才收掉。
 */
@Composable
private fun LiveReplyRow(
    reply: LiveReply,
    onDismissCut: () -> Unit,
    onCancelCall: (String) -> Unit = {},
) {
    val transition = rememberInfiniteTransition(label = "live")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 900), RepeatMode.Reverse),
        label = "live-alpha",
    )
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        // 抬头没有时间：这一条还没落库，写一个假时间比空着更糟。
        AssistantHeader(createdAt = 0L)
        if (reply.reasoning.isNotBlank()) {
            LiveReasoningCard(text = reply.reasoning)
        }
        if (reply.text.isNotBlank()) {
            MarkdownBody(
                text = reply.text,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
        }
        // 正在跑的工具调用（0.15ak）：一行一条 + 「关闭」。
        RunningCallRows(calls = reply.calls, onCancel = onCancelCall)
        val note = when {
            reply.cut -> "这一轮没写完：上面是已经收到的部分（没存进库里，点一下收掉）"
            reply.text.isBlank() && reply.reasoning.isBlank() -> "正在生成…"
            else -> "正在生成…（停止键随时可用）"
        }
        Text(
            text = note,
            style = MaterialTheme.typography.labelSmall,
            color = if (reply.cut) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)
            },
            modifier = Modifier
                .padding(top = 6.dp)
                .then(if (reply.cut) Modifier.clickable(onClick = onDismissCut) else Modifier),
        )
    }
}


/**
 * 抬头该用哪个时间：优先这一段里**正文**那条的时间（用户看到的就是它），
 * 纯工具单元没有正文，退回单元自己的时间。思考块的时间更早，不拿它当抬头。
 */
private fun ChatUnit.headerAt(): Long =
    messages.firstOrNull { !it.isThinking && !it.isToolCall && !it.isToolResult }?.createdAt
        ?: messages.firstOrNull()?.createdAt
        ?: createdAt

/** 这个单元整体靠哪边——只用来算"换人"的间距，不参与别的判断。 */
private fun ChatUnit.alignsRight(): Boolean =
    !isToolPair && messages.isNotEmpty() && messages.all { it.isUser }

/** 这一行整体靠哪边——只用来算"换人"的间距，不参与别的判断。 */
private fun ChatRow.alignsRight(): Boolean = when (this) {
    is ChatRow.Single -> unit.alignsRight()
    // 过程块永远是助手那边的东西。
    is ChatRow.Process -> false
}

/**
 * 一次回合里连续的过程项（D229）：思考与工具合成一个大块，左侧一条竖线串起来。
 *
 * 三条规矩都在这一屏上：
 *
 * 1. **≥3 才折**，折起来保留最新两个——用户要的是"最近发生了什么"，最早那几步只是过程。
 * 2. **正在跑的不折**：折起来就看不见它在动，而"它还在动"是此刻唯一想知道的事。
 * 3. 折叠状态由调用方记（`openGroups`），**只活在本次浏览会话里**。
 *
 * 没有做的那一条（D229 第 6 条）：运行中的工具块显示最新 3 行输出。
 * 现在没有"单条调用的实时输出"这条通道（0.15an 才有），所以只能显示标题行；
 * 硬画三行空行等于假装有输出。
 */
@Composable
private fun ProcessGroupRow(
    group: ChatRow.Process,
    showHeader: Boolean,
    gapAbove: Dp,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpenTool: (ChatUnit) -> Unit,
    /** 某一项的思考展开没有。键由调用方加前缀（`think:`），这里只管问、只管翻。 */
    thinkingOpen: (String) -> Boolean = { false },
    onToggleThinking: (String) -> Unit = {},
) {
    val running = group.items.any { it.running }
    val foldable = foldable(group.items) && !running
    val open = expanded && foldable
    val visible = visibleItems(group.items, open)
    val hidden = hiddenCount(group.items, open)
    val line = MaterialTheme.colorScheme.outlineVariant

    Column(modifier = Modifier.fillMaxWidth().padding(top = gapAbove)) {
        if (showHeader) AssistantHeader(createdAt = group.items.first().unit.createdAt)
        // 一个块，不是一叠卡（用户 2026-11-03：块之间有空隙，看着是好几张）。
        // 所以整组套**一层**底色 + 圆角，里面的项不再各自留白、也不再各画一张卡面——
        // 头部（展开/收起）也在这层里，它属于这个块，不是块外面的一行字。
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        val x = 6.dp.toPx()
                        drawLine(
                            color = line,
                            start = Offset(x, 0f),
                            end = Offset(x, size.height),
                            strokeWidth = 1.dp.toPx(),
                        )
                    },
            ) {
                // 头部：折叠时「展开更多 N 步」，展开时「收起」；不够三个就没有这一行。
                if (foldable) {
                    Text(
                        text = if (expanded) "收起" else "展开更多 $hidden 步",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onToggle)
                            .padding(start = 18.dp, top = 8.dp, bottom = 2.dp),
                    )
                }
                visible.forEach { item ->
                    Box(modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 2.dp, bottom = 2.dp)) {
                        when (item.kind) {
                            ProcessItem.Kind.Thinking -> item.thinking?.let { thinking ->
                                // 思考**在聊天页里**展开（用户 2026-11-04 定的）：一行标题 + 一个竖标，
                                // 点开就地长出来，不弹窗——它属于这条回答的一部分。
                                ThinkingProcessRow(
                                    message = thinking,
                                    expanded = thinkingOpen(item.id),
                                    onToggle = { onToggleThinking(item.id) },
                                )
                            }
                            ProcessItem.Kind.Tool -> ToolProcessRow(
                                unit = item.unit,
                                onOpen = { onOpenTool(item.unit) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 一个原子单元：`turn`（一条文本）或 `tool_pair`（assistant(tool_calls) + 全部 tool_result）。 */
@Composable
private fun UnitBlock(
    unit: ChatUnit,
    /** 这一段是不是"这一轮助手的头一块"。连续的工具卡/正文只画一次抬头。 */
    showHeader: Boolean,
    /** 思考已经并进上面那个大块了：这里别再画一遍（见 `ProcessRows.buildRows`）。 */
    hideReasoning: Boolean = false,
    /** 单独一条工具单元：点开也是弹窗，不是就地展开。 */
    onOpenTool: (ChatUnit) -> Unit = {},
    /** 关掉一条正在跑的工具调用（0.15ak）。 */
    onCancelCall: (String) -> Unit = {},
    maxBubble: Dp,
    gapAbove: Dp,
    /** 这一轮正在长的回复（就地渲染用，D227）。 */
    live: LiveReply? = null,
    /** 它长在哪条消息上；`null` = 不在这一轮里。 */
    regenTarget: String? = null,
    selecting: Boolean,
    selected: Set<String>,
    onLongPressMessage: (String) -> Unit,
    onToggleMessage: (String) -> Unit,
    onCopied: () -> Unit,
    onShareMessage: (String) -> Unit,
    onMoreMessage: (String) -> Unit,
    onVersionMessage: (String, Long, String) -> Unit,
    /** 会话落库了没有（重试要往库里写版本，没 id 就没有可写的地方）。 */
    retryEnabled: Boolean = false,
    /** 点重试（0.15av）：只传 id，动作发生时再按 id 现取。 */
    onRetryMessage: (String) -> Unit = {},
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = gapAbove),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val broken = unit.broken
        // 抬头（头像 + 助手 + 时间）要在**思考块与工具卡的上方**（用户 2026-10-27 报：
        // 思考卡排在"助手"名字上面，看着像别人说的话）。所以这里统一先画抬头，
        // 再画这一段的卡和正文；助手连续几段只画第一个，不然一轮里会冒出三四行"助手"。
        val assistantSide = unit.isToolPair || unit.messages.none { it.isUser }
        if (assistantSide && showHeader) {
            AssistantHeader(createdAt = unit.headerAt())
        }
        if (broken != null) {
            BrokenCard(declaration = broken.declaration, reason = broken.reason)
        }
        if (unit.isToolPair) {
            ToolProcessRow(unit = unit, onOpen = { onOpenTool(unit) })
        } else {
            unit.messages.forEach { message ->
                when {
                    // 空的思考消息没有内容可指，画一个"思考 · 0 字"的空壳只会让人以为坏了。
                    message.isThinking -> if (message.content.isNotBlank()) ThinkingBlock(message)
                    message.isUser -> UserMessage(
                        message = message,
                        maxBubble = maxBubble,
                        selecting = selecting,
                        isSelected = message.id in selected,
                        onLongPress = { onLongPressMessage(message.id) },
                        onTap = { if (selecting) onToggleMessage(message.id) },
                        onCopied = onCopied,
                        onShare = { onShareMessage(message.id) },
                        onMore = { onMoreMessage(message.id) },
                        onVersion = { no, view -> onVersionMessage(message.id, no, view) },
                        // 灰的判据是"有没有东西可跑"：思考/工具片段、断块、会话还没落库都不给。
                        onRetry = if (retryEnabled && message.canRetry) {
                            { onRetryMessage(message.id) }
                        } else {
                            null
                        },
                    )

                    else -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        // 思考块（D162）：以前 `reasoning` 只存不显示，界面上永远看不见——
                        // 那是铁律 ②（不许有存了但看不见的东西）直接踩线的一处。
                        if (!hideReasoning) {
                            message.reasoning?.takeIf { it.isNotBlank() }?.let { ReasoningCard(it) }
                        }
                        AssistantMessage(
                            message = message,
                            showHeader = false,
                            // 就地渲染（D227）：这一轮长在这条消息上。
                            live = live?.takeIf { message.id == regenTarget },
                            onCancelCall = onCancelCall,
                            selecting = selecting,
                            isSelected = message.id in selected,
                            onLongPress = { onLongPressMessage(message.id) },
                            onTap = { if (selecting) onToggleMessage(message.id) },
                            onCopied = onCopied,
                            onShare = { onShareMessage(message.id) },
                            onMore = { onMoreMessage(message.id) },
                            onVersion = { no, view -> onVersionMessage(message.id, no, view) },
                            onRetry = if (retryEnabled && message.canRetry) {
                                { onRetryMessage(message.id) }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 刚发出去、还没从库里读回来的那一句。
 *
 * 它**不是**一条"看不见的存了的数据"（铁律 ② 管的是库里）：这一条只活在内存里，
 * 屏幕上看得见，`turn_run` 收尾后就用库里那份替换掉它。
 */
private data class PendingSend(
    /** 它属于哪个会话：切到别的会话时这一条不该跟过去。 */
    val session: String,
    val text: String,
    val at: Long,
    /** 随这一条一起发出去的图（还没从库里读回来，所以只能给数量）。 */
    val imageCount: Int = 0,
)

private const val USER_NAME = "我"

/** 单张图片的上限（D208）：与存储层 `MAX_ATTACHMENT_BYTES` 同一个数，界面先挡一次。 */
private const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
private const val ASSISTANT_NAME = "助手"

/**
 * 时间戳。
 *
 * 两个坑都在这一行里：
 * - **库里存的是毫秒**（`SystemTime::as_millis`，见 `crates/storage`）。上一版按秒解，
 *   界面上出现 `+58782-03-11`——年份 5 万多，是"毫秒当秒用"的典型症状。
 * - **12/24 小时制要跟系统走**：用户在系统设置里选的，不该被我们写死成 24 小时。
 */
private var cachedStampPattern: String? = null
private var cachedStampFormat: DateTimeFormatter? = null

private fun stampFormatFor(context: Context): DateTimeFormatter {
    val pattern = if (DateFormat.is24HourFormat(context)) {
        "yyyy-MM-dd HH:mm:ss"
    } else {
        "yyyy-MM-dd hh:mm:ss a"
    }
    cachedStampFormat?.let { if (cachedStampPattern == pattern) return it }
    // 缓存一份：每条消息都新建 Formatter 在长会话里是纯浪费。
    return DateTimeFormatter.ofPattern(pattern, Locale.getDefault())
        .withZone(ZoneId.systemDefault())
        .also {
            cachedStampPattern = pattern
            cachedStampFormat = it
        }
}

/** 0 表示库里没给，宁可不显示也不写一个假时间。 */
private fun stampOf(context: Context, epochMillis: Long): String? =
    if (epochMillis <= 0L) null else stampFormatFor(context).format(Instant.ofEpochMilli(epochMillis))

private fun putClipboard(context: Context, label: String, text: String) {
    val manager = context.getSystemService(ClipboardManager::class.java) ?: return
    manager.setPrimaryClip(ClipData.newPlainText(label, text))
}

/**
 * 首字母圆标。
 *
 * 先不引图片：`Assistant` 还没有实体（助手设置页未接），拿不到名字与头像字节。
 * 用首字母圆标是"诚实的占位"——它明确地不是一张脸，也不会假装自己是谁。
 */
@Composable
private fun InitialAvatar(letter: String, size: Dp, mine: Boolean) {
    Surface(
        shape = CircleShape,
        color = if (mine) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        modifier = Modifier.size(size),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = letter,
                style = MaterialTheme.typography.labelLarge,
                color = if (mine) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
            )
        }
    }
}

/** 用户那一条：右对齐的头像/名字/时间 + 气泡 + 动作行。 */
/**
 * 待发图片那排缩略图（D208）。
 *
 * 为什么画在输入框**上面**：那是"还没发出去"的东西，跟草稿同一个位置；
 * 塞进输入框里会被原生 `EditText` 盖住，画在下面又会被当成"已经发过"。
 */
@Composable
private fun PendingImageStrip(images: List<PendingImage>, onRemove: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        images.forEachIndexed { index, image ->
            Box {
                val bitmap = remember(image) { image.bitmap() }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "待发图片 $index",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(10.dp)),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceContainerHighest,
                                RoundedCornerShape(10.dp),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("读不出", style = MaterialTheme.typography.labelSmall)
                    }
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 6.dp, y = (-6).dp)
                        .size(20.dp)
                        .background(MaterialTheme.colorScheme.surface, CircleShape)
                        .clickable { onRemove(index) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = ActionIcons.Close,
                        contentDescription = "移除这张图",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        }
    }
}

/**
 * 消息气泡里的图片（D208）。
 *
 * 字节按 id 现取：一条消息可能带几张图，而首屏只画看得见的那几条。
 * 读不出来的**要说出来**——画一个空白框，用户会以为图被吞了。
 */
/**
 * 一张附件图在气泡里占多大：**等比塞进 240×200 的框**。
 *
 * 只给宽度上限是不够的——竖屏截图会按原始比例把高度顶到整屏（用户 2026-11-04 报的）。
 * 这里直接算出宽高，`ContentScale.Fit` 就不会再留黑边。
 */
private fun imageBox(bitmap: android.graphics.Bitmap): Pair<Dp, Dp> {
    val maxW = 240f
    val maxH = 200f
    if (bitmap.width <= 0 || bitmap.height <= 0) return maxW.dp to maxH.dp
    val ratio = bitmap.width.toFloat() / bitmap.height.toFloat()
    return if (ratio >= maxW / maxH) {
        maxW.dp to (maxW / ratio).dp
    } else {
        (maxH * ratio).dp to maxH.dp
    }
}

@Composable
private fun MessageImages(images: List<ChatImage>) {
    if (images.isEmpty()) return
    val context = LocalContext.current
    val databasePath = remember(context) { ProviderRepository.databasePath(context) }
    val loaded by produceState(initialValue = null as List<Pair<ChatImage, android.graphics.Bitmap?>>?, images) {
        value = withContext(Dispatchers.IO) {
            images.map { image -> image to AttachmentStore.bitmap(databasePath, image.id) }
        }
    }
    val rows = loaded
    Column(
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (rows == null) {
            Text(
                text = "读图…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
            )
        } else {
            for ((image, bitmap) in rows) {
                if (bitmap != null) {
                    // 等比塞进一个框（用户 2026-11-04：太大，调小点）。
                    // 只给 `widthIn` 的话高度不设限——一张竖屏截图能占满整屏。
                    val box = imageBox(bitmap)
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "图片附件",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .size(width = box.first, height = box.second)
                            .clip(RoundedCornerShape(12.dp)),
                    )
                } else {
                    Text(
                        text = "有一张图读不出来（${image.mime}，${image.size / 1024} KB）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun UserMessage(
    message: ChatMessage,
    maxBubble: Dp,
    selecting: Boolean,
    isSelected: Boolean,
    onLongPress: () -> Unit,
    onTap: () -> Unit,
    onCopied: () -> Unit,
    onShare: () -> Unit,
    onMore: () -> Unit,
    onVersion: (Long, String) -> Unit,
    /** 重试（0.15av）。`null` = 现在不能重试，按钮灰着。 */
    onRetry: (() -> Unit)? = null,
    /** 还没落库的那一条（0.15ah）：动作行换成「发送中」，别给一排点了没反应的按钮。 */
    sending: Boolean = false,
    /** 随这一条一起发出去、还没从库里读回来的图。 */
    pendingImageCount: Int = 0,
) {
    val context = LocalContext.current
    val stamp = stampOf(context, message.createdAt)
    val tint = if (selecting && isSelected) {
        Modifier
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f), RoundedCornerShape(12.dp))
            .padding(4.dp)
    } else {
        Modifier
    }

    Column(modifier = Modifier.fillMaxWidth().then(tint)) {
        // 用户：**名字在上排、时间在下排**，两行都在头像左侧（D139）。
        // 文字块右对齐、头像贴右边，跟助手那边成镜像。
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.Top,
        ) {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = USER_NAME,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                if (stamp != null) {
                    Text(
                        text = stamp,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        maxLines = 1,
                        modifier = Modifier.padding(top = 1.dp),
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            InitialAvatar(letter = USER_NAME, size = 32.dp, mine = true)
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier
                    .widthIn(max = maxBubble)
                    .pointerInput(selecting, message.id) {
                        detectTapGestures(
                            onTap = { onTap() },
                            onLongPress = { onLongPress() },
                        )
                    },
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                    MessageImages(images = message.images)
                    if (sending && pendingImageCount > 0) {
                        // 图已经随这一轮交出去了，库里那份要等收尾才读得回来。
                        // 这里留一句，免得发出去那一刻缩略图凭空消失、看着像被吞了。
                        Text(
                            text = "带 $pendingImageCount 张图（发送中）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                    Text(
                        text = message.content.ifEmpty { "（空消息）" },
                        style = MaterialTheme.typography.bodyMedium,
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    if (message.incomplete) {
                        Text(
                            text = "未完成 · 这一轮被中断",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }

        if (sending) {
            // 动作行在这里没有意义：这条还没 id，复制/更多都指不到库里的东西。
            Text(
                text = "发送中…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        } else {
            MessageActionRow(
                mine = true,
                onCopy = {
                    putClipboard(context, "消息", message.content)
                    onCopied()
                },
                onShare = onShare,
                onSelect = onLongPress,
                onMore = onMore,
                onRetry = onRetry,
                retryNote = "重试这条之后",
                versionControl = { VersionControl(message = message, onPick = onVersion) },
            )
        }
    }
}

/**
 * 助手那一条：**没有气泡**。
 *
 * 头像在左上，名字、时间各自一行，正文用 [MarkdownBody] 铺开——气泡会把长回答框成一个
 * "大卡片"，读起来像在念一段引文；真实聊天客户端里助手本来也是贴着背景说话的。
 */
@Composable
private fun AssistantMessage(
    message: ChatMessage,
    /** 抬头由 [UnitBlock] 统一画（思考卡/工具卡要排在它下面），所以这里默认不画。 */
    showHeader: Boolean = false,
    /**
     * 这一轮正在长出来的那一份（D227，就地渲染）。
     *
     * 非空时正文画的是**正在长的这一份**，不是库里那一版——重试期间库里还是旧回答，
     * 画旧回答再加一行"正在生成"会让人以为它没动。动作行这时也不画：
     * 复制/版本/编辑指的都是库里那份，而现在屏幕上这份还没落库。
     */
    live: LiveReply? = null,
    selecting: Boolean,
    isSelected: Boolean,
    onLongPress: () -> Unit,
    onTap: () -> Unit,
    onCopied: () -> Unit,
    onShare: () -> Unit,
    onMore: () -> Unit,
    onVersion: (Long, String) -> Unit,
    /** 重试（0.15av）。`null` = 现在不能重试，按钮灰着。 */
    onRetry: (() -> Unit)? = null,
    /** 关掉一条正在跑的工具调用（0.15ak）。 */
    onCancelCall: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val stamp = stampOf(context, message.createdAt)
    val tint = if (selecting && isSelected) {
        Modifier
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f), RoundedCornerShape(12.dp))
            .padding(4.dp)
    } else {
        Modifier
    }

    Column(modifier = Modifier.fillMaxWidth().then(tint)) {
        // 头像 + 名字/时间占一行；**正文在它们下面整行铺开**，
        // 左边缘和头像左边缘同一条线（用户 2026-10-24："消息要在头像下方，这样美观"）。
        if (showHeader) AssistantHeader(createdAt = message.createdAt)
        if (live != null) {
            live.finished.forEach { done ->
                if (done.reasoning.isNotBlank()) LiveReasoningCard(text = done.reasoning)
                if (done.text.isNotBlank()) {
                    MarkdownBody(
                        text = done.text,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                }
            }
            // 就地渲染：思考卡与正文都取这一轮收到的增量，收尾时换成库里那份（形状一致，不跳）。
            if (live.reasoning.isNotBlank()) LiveReasoningCard(text = live.reasoning)
            if (live.text.isNotBlank()) {
                MarkdownBody(
                    text = live.text,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
            }
            // 正在跑的工具调用（0.15ak）：一行一条 + 「关闭」。这一段还没落库，
            // 所以它画在正在生成的这块里，而不是等工具卡出现。
            RunningCallRows(calls = live.calls, onCancel = onCancelCall)
            val note = when {
                live.cut -> "这一轮没写完：上面是已经收到的部分（没存进库里）"
                live.text.isBlank() && live.reasoning.isBlank() -> "正在生成…"
                else -> "正在生成…（停止键随时可用）"
            }
            Text(
                text = note,
                style = MaterialTheme.typography.labelSmall,
                color = if (live.cut) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(top = 6.dp),
            )
            return@Column
        }
        MarkdownBody(
            text = message.content.ifEmpty { "（空消息）" },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .pointerInput(selecting, message.id) {
                    detectTapGestures(
                        onTap = { onTap() },
                        onLongPress = { onLongPress() },
                    )
                },
        )
        if (message.incomplete) {
            Text(
                text = "未完成 · 这一轮被中断",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        MessageActionRow(
            mine = false,
            onCopy = {
                putClipboard(context, "消息", message.content)
                onCopied()
            },
            onShare = onShare,
            onSelect = onLongPress,
            onMore = onMore,
            onRetry = onRetry,
            retryNote = "重试这一轮",
            versionControl = { VersionControl(message = message, onPick = onVersion) },
        )
    }
}

/**
 * 助手抬头：头像 + 名字 + 时间。
 *
 * 单独拎出来是因为它要画在**思考块和工具卡的上方**（2026-10-27 报），
 * 而那些卡片不在 `AssistantMessage` 里，而是在同一个单元的更上面。
 */
@Composable
private fun AssistantHeader(createdAt: Long) {
    val context = LocalContext.current
    val stamp = stampOf(context, createdAt)
    Row(verticalAlignment = Alignment.Top) {
        InitialAvatar(letter = "助", size = 32.dp, mine = false)
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                text = ASSISTANT_NAME,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            if (stamp != null) {
                Text(
                    text = stamp,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    maxLines = 1,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }
    }
}

/**
 * 编辑某一版的正文。
 *
 * **改的是"现在显示的那一组"**（D148）：显示着原文就改原文，显示着 AI 改后那份就改它——
 * 两份各存各的，互不覆盖。改完还是显示他改的那份，**颜色不变**（颜色只表示"被 AI 改过"）。
 * 预填的正文由调用方给（库里现读的 `shown`），不是列表快照里的 `content`。
 *
 * 用自绘的居中卡片，不用系统 `AlertDialog`：这里要放进原生 `EditText`（中文输入法那套），
 * 而系统对话框的尺寸与输入法联动在深浅两套主题下都不好对齐。
 */
@Composable
private fun EditVersionDialog(initial: String, onCancel: () -> Unit, onSave: (String) -> Unit) {
    var text by remember(initial) { mutableStateOf(initial) }
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.32f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onCancel,
                ),
        )
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "编辑这一版",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "改的是现在显示的那一组（原文 / AI 改后各存一份，互不覆盖）；颜色不变。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    ) {
                        NativeEditText(
                            value = text,
                            onValueChange = { text = it },
                            hint = "编辑后的正文",
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DialogAction(label = "取消", primary = false, onClick = onCancel)
                        Spacer(Modifier.width(8.dp))
                        DialogAction(label = "保存", primary = true, onClick = { onSave(text) })
                    }
                }
            }
        }
    }
}

@Composable
private fun DialogAction(
    label: String,
    primary: Boolean,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val fill = when {
        danger -> MaterialTheme.colorScheme.error
        primary -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceContainer
    }
    val ink = when {
        danger -> MaterialTheme.colorScheme.onError
        primary -> MaterialTheme.colorScheme.onPrimary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = fill,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = ink,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/**
 * 重试失败之后要用户拍板的那一份东西（D228）。
 *
 * 存的是**内存里这一轮收到的正文**：它还没落过库，所以这不是"点开那一刻的副本"，
 * 而是唯一的一份。用户选「保留这一版」时才把它写进库里。
 */
private data class RegenFailure(
    val messageId: String,
    val text: String,
    val reasoning: String,
    val reason: String,
)

/** 失败三选一里选中的那一项。 */
private enum class KeepMode { Previous, This, Both }

/**
 * 重试失败：保留上一版 / 保留这一版 / 都保留（D228）。
 *
 * 三条规矩：
 *
 * 1. **必须处理**——它决定库里留下哪一版，点外面与返回键都不关（不是"关掉就回到原样"）。
 * 2. **默认选上一版**：失败之后最常想做的是回到能看的那一版。
 * 3. **这一版是空的时候两个"留这一版"灰掉并写原因**——留着一条 0 字消息，
 *    比报错更让人以为消息被吞了。
 */
@Composable
private fun RegenFailureDialog(
    failure: RegenFailure,
    onKeepPrevious: () -> Unit,
    onKeepThis: () -> Unit,
    onKeepBoth: () -> Unit,
) {
    val empty = failure.text.isBlank()
    var mode by remember(failure) { mutableStateOf(KeepMode.Previous) }
    Box(modifier = Modifier.fillMaxSize()) {
        // 遮罩不吃点击：这一层没有"点外面取消"（必须处理）。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.32f)),
        )
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "重试失败",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = failure.reason,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Text(
                        text = if (empty) {
                            "这一轮一个字都没收到。"
                        } else {
                            "这一轮收到了 ${failure.text.length} 字，还没存进库里。"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                    KeepChoice(
                        title = "保留上一版",
                        note = "回到重试之前那一版，这次收到的内容不留。",
                        selected = mode == KeepMode.Previous,
                        enabled = true,
                        onPick = { mode = KeepMode.Previous },
                    )
                    KeepChoice(
                        title = "保留这一版",
                        note = if (empty) {
                            "这一版是空的"
                        } else {
                            "把这次收到的 ${failure.text.length} 字存成一版，删掉上一版。"
                        },
                        selected = mode == KeepMode.This,
                        enabled = !empty,
                        onPick = { mode = KeepMode.This },
                    )
                    KeepChoice(
                        title = "都保留",
                        note = if (empty) {
                            "这一版是空的"
                        } else {
                            "两版都留着，当前显示这一版，可以切回上一版。"
                        },
                        selected = mode == KeepMode.Both,
                        enabled = !empty,
                        onPick = { mode = KeepMode.Both },
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        DialogAction(label = "确定", primary = true) {
                            when (mode) {
                                KeepMode.Previous -> onKeepPrevious()
                                KeepMode.This -> onKeepThis()
                                KeepMode.Both -> onKeepBoth()
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 三选一里的一行：圆点 + 标题 + 一句说明。灰掉的那行写清为什么灰。 */
@Composable
private fun KeepChoice(
    title: String,
    note: String,
    selected: Boolean,
    enabled: Boolean,
    onPick: () -> Unit,
) {
    val ink = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
        selected -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .then(
                if (enabled) {
                    Modifier.clickable(onClick = onPick)
                } else {
                    Modifier
                },
            ),
    ) {
        Box(
            modifier = Modifier
                .padding(top = 3.dp)
                .size(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .border(
                        width = if (selected) 5.dp else 1.dp,
                        color = if (enabled) {
                            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                        } else {
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                        },
                        shape = CircleShape,
                    ),
            )
        }
        Column(modifier = Modifier.padding(start = 10.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium, color = ink)
            Text(
                text = note,
                style = MaterialTheme.typography.labelSmall,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                },
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * 删除整条消息前的确认（D152）。
 *
 * 这条动作把**正文、全部版本、编辑记录**一起删掉，收不回来——所以必须问一次，
 * 而且要把"会删掉什么"写出来（用户 2026-10-26 明确要的是"不保留任何正文"，
 * 那就更得先说清楚，别让他点完才发现）。
 *
 * 形制跟编辑框同一套：自绘居中卡片，不用系统 `AlertDialog`（那套在这两套主题下都不好对齐）。
 */
@Composable
private fun ConfirmDeleteDialog(
    preview: String,
    versionCount: Int,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.32f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onCancel,
                ),
        )
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "删除这条消息？",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = if (versionCount > 1) {
                            "正文、全部 $versionCount 个版本，以及它的工具往返一起删掉，收不回来。"
                        } else {
                            "正文、版本记录，以及它的工具往返一起删掉，收不回来。"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    if (preview.isNotBlank()) {
                        Text(
                            text = preview.trim().take(80).let {
                                if (preview.trim().length > 80) "$it…" else it
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 10.dp),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DialogAction(label = "取消", primary = false, onClick = onCancel)
                        Spacer(Modifier.width(8.dp))
                        DialogAction(label = "删除", primary = false, danger = true, onClick = onConfirm)
                    }
                }
            }
        }
    }
}

/**
 * 版本切换（D135 / D137）：`<  n/n  >`。
 *
 * 那个数字的颜色就是三态语义：
 * - 白（`onSurface`）：这一版**没被工具编辑过**，点它没反应；
 * - 蓝（`primary`）：被编辑过，现在显示**原文**，点它切到编辑后；
 * - 红（`error`）：被编辑过，现在显示**编辑后**，点它切回原文。
 *
 * 左右箭头翻版本，传 `keep`：**沿用那一版自己记住的视图**。用当前这一版的视图去套邻居，
 * 会出现"翻过去突然变成编辑态"的怪事。
 *
 * `versionCount <= 1` 时整个控件不渲染——只有一个版本的消息不该看到翻页按钮。
 */
@Composable
private fun VersionControl(message: ChatMessage, onPick: (Long, String) -> Unit) {
    // 什么时候不画：**只有一版、而且这一版没被 AI 改过**。
    // 只管条数会漏掉"一版被工具改过"——那时候数字有颜色、点它能切原文/改后，
    // 藏起来等于把这个能力藏了（2026-10-26 报：改完只有一条消息，应该出现 1/1）。
    if (message.versionCount <= 1 && !message.versionEdited) return
    val active = message.versionActive.coerceAtLeast(1)
    // 列表里 `version_count` 可能是 0（那条字段滞后的老问题），但只要有 AI 标记就至少是 1 版；
    // 不兜这一下会显示成 `1/0`。
    val total = message.versionCount.coerceAtLeast(active)
    val numberColor = when {
        !message.versionEdited -> MaterialTheme.colorScheme.onSurface
        message.versionView == "edited" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VersionArrow(
            description = "上一版",
            rotate = 90f,
            enabled = active > 1,
            onClick = { onPick((active - 1).toLong(), "keep") },
        )
        Text(
            text = "$active/$total",
            style = MaterialTheme.typography.labelMedium,
            color = numberColor,
            modifier = Modifier
                .pointerInput(message.id, message.versionView, message.versionEdited) {
                    detectTapGestures(
                        onTap = {
                            if (message.versionEdited) {
                                onPick(
                                    active.toLong(),
                                    if (message.versionView == "edited") "raw" else "edited",
                                )
                            }
                        },
                    )
                }
                .padding(horizontal = 8.dp),
        )
        VersionArrow(
            description = "下一版",
            rotate = -90f,
            enabled = active < total,
            onClick = { onPick((active + 1).toLong(), "keep") },
        )
    }
}

@Composable
private fun VersionArrow(description: String, rotate: Float, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .pointerInput(enabled) {
                detectTapGestures { if (enabled) onClick() }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = ActionIcons.ChevronDown,
            contentDescription = description,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                alpha = if (enabled) 1f else 0.3f,
            ),
            modifier = Modifier.size(16.dp).rotate(rotate),
        )
    }
}

/**
 * 消息下面那排动作（照用户给的截图）：复制 / 重新生成 / 编辑 / 更多。
 *
 * **重新生成与编辑现在是灰的**：`turn_run` 没接（没有可重发的一轮），`edit` 没桥出 FFI。
 * 灰着而不是藏起来，是因为这两个动作的位置会一直在；藏起来用户会以为这里没有编辑。
 * 长按灰按钮会浮一句"待接什么"，不让人猜。
 */
@Composable
private fun MessageActionRow(
    mine: Boolean,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onSelect: () -> Unit,
    onMore: () -> Unit,
    /**
     * 重试（0.15av）。`null` = 这一条现在不能重试，按钮灰着。
     *
     * 为什么放在这一排而不是只留在「⋯」里：重试是这一条消息上最常用的动作，
     * 收进菜单等于每次多两下。菜单里那一项留着——两个入口打开的是同一个动作。
     */
    onRetry: (() -> Unit)? = null,
    /** 读屏与长按提示念的那句。灰着的时候它就是"为什么灰"。 */
    retryNote: String = "重试",
    versionControl: @Composable () -> Unit = {},
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 一排四个动作（用户 2026-10-24 定的最终形状）：复制 / 刷新 / 文字转语音 / 翻译。
        // 后三个现在是灰的：turn_run、TTS、翻译都还没接。灰着而不是藏起来——
        // 位置就是最终位置，接上之后只改 enabled，不改布局。
        IconActionButton(
            image = ActionIcons.Copy,
            description = "复制",
            onClick = onCopy,
            compact = true,
        )
        IconActionButton(
            image = ActionIcons.RotateCcw,
            description = if (onRetry != null) retryNote else "$retryNote（现在不可用）",
            onClick = { onRetry?.invoke() },
            compact = true,
            enabled = onRetry != null,
        )
        IconActionButton(
            image = ActionIcons.Volume2,
            description = "文字转语音（待接 TTS）",
            onClick = {},
            compact = true,
            enabled = false,
        )
        IconActionButton(
            image = ActionIcons.Languages,
            description = "翻译（待接翻译）",
            onClick = {},
            compact = true,
            enabled = false,
        )
        IconActionButton(
            image = ActionIcons.Ellipsis,
            description = "更多",
            onClick = onMore,
            compact = true,
        )
        // 版本控件跟在这一排里、⋯ 的右边，中间空一个图标的宽度（用户 2026-10-24 定的位置）。
        Spacer(Modifier.width(36.dp))
        versionControl()
    }
}

/**
 * 思考块：**默认收起**，只显示"思考 · N 字"。展开看全文。
 *
 * 收起是刻意的：思考通常很长，默认铺开会把真正的回答挤到屏幕外。
 * 左边留出头像那一段缩进，让它看起来属于助手那一条，而不是一条独立卡片。
 */
@Composable
private fun ThinkingBlock(message: ChatMessage) {
    var expanded by remember(message.id) { mutableStateOf(false) }
    Row(modifier = Modifier.fillMaxWidth()) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = ActionIcons.ChevronDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        text = if (expanded) {
                            "思考（点一下收起）"
                        } else {
                            "思考 · ${message.content.length} 字（点一下展开）"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/**
 * 工具块的一行（D243，用户 2026-11-04 定的形状）。
 *
 * 以前是"点一下就地展开"，一展开就把整段命令输出铺在聊天页里，一条回答能顶三屏。
 * 现在只留一行：图标 + 名字 + 结果摘要（`exit 0 · 202ms`）+ 右侧 `>`，
 * 点开在**半屏弹窗**里看细节。
 *
 * 摘要从结果 JSON 里取（`exit_code` / `duration_ms`）：拿不到就不编，
 * 只写"结果 N 条"。
 */
@Composable
private fun ToolProcessRow(unit: ChatUnit, onOpen: () -> Unit) {
    val calls = unit.messages.flatMap { it.toolArgs }
    val results = unit.messages.filter { it.isToolResult }
    val name = calls.firstOrNull()?.name?.takeIf { it.isNotBlank() } ?: "工具调用"
    val summary = toolSummary(results)
    val preview = calls.firstOrNull()?.arguments?.let { toolArgumentPreview(it) }.orEmpty()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = ActionIcons.Wrench,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = name,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
            if (summary.isNotEmpty()) {
                Text(
                    text = summary,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = ActionIcons.ChevronRight,
                contentDescription = "查看工具调用详情",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp).size(16.dp),
            )
        }
        if (preview.isNotEmpty()) {
            Text(
                text = preview,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 24.dp, top = 2.dp),
            )
        }
    }
}

/** 结果摘要：`exit 0 · 202ms`。取不到就说"结果 N 条"，不编数字。 */
private fun toolSummary(results: List<ChatMessage>): String {
    val first = results.firstOrNull() ?: return ""
    val text = first.content.trim()
    if (!text.startsWith("{")) {
        return if (results.size > 1) "结果 ${results.size} 条" else ""
    }
    val json = runCatching { org.json.JSONObject(text) }.getOrNull()
    if (json == null) return if (results.size > 1) "结果 ${results.size} 条" else ""
    val exit = if (json.has("exit_code")) "exit ${json.optInt("exit_code", 0)}" else ""
    val ms = if (json.has("duration_ms")) "${json.optLong("duration_ms", 0)}ms" else ""
    return listOf(exit, ms).filter { it.isNotEmpty() }.joinToString(" · ")
}

/** 参数里的"那一眼能认出来"的一句：`command` 优先，其次第一个字符串字段。 */
private fun toolArgumentPreview(arguments: String): String {
    val json = runCatching { org.json.JSONObject(arguments) }.getOrNull() ?: return arguments.take(80)
    json.optString("command", "").takeIf { it.isNotBlank() }?.let { return it }
    json.optString("path", "").takeIf { it.isNotBlank() }?.let { return it }
    json.optString("query", "").takeIf { it.isNotBlank() }?.let { return it }
    return json.keys().asSequence().firstOrNull()?.let { json.optString(it, "") }.orEmpty()
}

/**
 * 思考块的一行（D243）：标题 + 竖标，**在聊天页里**展开。
 *
 * 为什么思考不弹窗、工具弹窗：思考是"这条回答怎么想出来的"，属于正文的一部分，
 * 就地展开读起来是连着的；工具输出是"外部世界返回的一坨"，弹窗看更干净。
 */
@Composable
private fun ThinkingProcessRow(message: ChatMessage, expanded: Boolean, onToggle: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = ActionIcons.Gauge,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = "深度思考 · ${message.content.length} 字",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
            Icon(
                imageVector = if (expanded) ActionIcons.ChevronUp else ActionIcons.ChevronDown,
                contentDescription = if (expanded) "收起思考" else "展开思考",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
        if (expanded) {
            Text(
                text = message.content,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, bottom = 6.dp),
            )
        }
    }
}

/**
 * 工具调用的详情弹窗（D243）：**初始最高半屏**，里面自己滚。
 *
 * 为什么半屏而不是整屏：它是"看一眼这次调用干了什么"，看完就收；
 * 整屏会让人以为换了一页，还得再找回去。
 */
@Composable
private fun ToolSheet(unit: ChatUnit, onDismiss: () -> Unit) {
    val screen = LocalConfiguration.current.screenHeightDp
    val maxHeight = (screen * 0.5f).dp
    SheetFrame(title = "工具调用", onDismiss = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
        ) {
            ToolPairBody(unit)
        }
    }
}

/**
 * 工具调用的正文：按"调用 ↔ 结果"配对列出来（调用参数与结果原文，等宽字体）。
 *
 * 只在**弹窗**里画（D243）。以前它挂在聊天页的可折叠卡里，一展开就顶掉三屏。
 * 配对靠 `call_id`——那是唯一能对上"这次调用"和"那条结果"的东西；
 * 配不上（断块、被取消的批）就照老样子单独列，不假装配上了。
 */
@Composable
private fun ToolPairBody(unit: ChatUnit) {
    val results = unit.messages
        .filter { it.isToolResult }
        .associateBy { it.toolCallId.orEmpty() }
    val pairs = unit.messages.flatMap { message ->
        message.toolArgs.map { arg -> arg to results[arg.callId] }
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        if (!unit.closed) {
            Text(
                text = "这一批还没收全",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        if (pairs.isNotEmpty()) {
            pairs.forEach { (arg, result) ->
                ToolCallBlock(command = arg, result = result)
            }
        } else {
            unit.messages.forEach { message ->
                Text(
                    text = buildString {
                        append(if (message.isToolResult) "← 结果" else "→ 调用")
                        message.toolCallId?.let { append(" [$it]") }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Text(
                    text = message.content.ifEmpty { "（空）" },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** 断块（P4.1 / D67）：声明行由 core 给，UI 不自己拼。 */
@Composable
private fun BrokenCard(declaration: String, reason: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                text = declaration,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                text = reason,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 输入岛。
 *
 * 形状（圆角 24 的整条输入区、圆形的发送键）是聊天软件的通行做法：
 * 手指落在屏幕底部中间，不看也能点准。左边**没有**假附件按钮——附件管线没接，
 * 摆一个点了没反应的图标比不摆更糟。
 *
 * 长按发送键 = 存为助手消息：这条留着是因为还没有模型可发，
 * 只有"能往库里写两侧"才验得了气泡形状。等 `nmoi_turn_run_json` 接上，这个手势就删掉。
 */
@Composable
private fun Composer(
    draft: String,
    onDraftChange: (String) -> Unit,
    notice: String?,
    canSend: Boolean,
    busy: Boolean,
    unsaved: Boolean,
    onHint: (String) -> Unit,
    onModel: () -> Unit,
    onThinking: () -> Unit,
    thinkingLabel: String,
    onEditTextReady: (EditText) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    /** 长按菜单里进"发送队列"那一层（用户 2026-10-31 指定的入口）。 */
    onQueue: () -> Unit,
    queued: Int,
    onSaveAssistant: () -> Unit,
    onSaveUser: () -> Unit,
    /** 待发的图片（D208）：输入框上方那排缩略图。 */
    pending: List<PendingImage>,
    onRemovePending: (Int) -> Unit,
    /** 「＋」面板：附件入口。 */
    onPlus: () -> Unit,
    /** `false` = 这个模型明确不收图（灰掉「图片」那一项并给原因）。 */
    imageCapability: Boolean?,
) {
    Column {
        if (notice != null) {
            Text(
                text = notice,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 4.dp),
            )
        }

        // 形状照 PLAN §3.12（也是 kelivo 那一套）：**输入框 + 底部一行图标按钮**。
        // 左：模型 / 搜索 / 思考 / 工具；右：＋菜单 / 语音 / 发送（生成中变停止，同一按钮换形态）。
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp),
        ) {
            Column(modifier = Modifier.padding(top = 6.dp, bottom = 6.dp)) {
                if (pending.isNotEmpty()) {
                    PendingImageStrip(images = pending, onRemove = onRemovePending)
                }
                NativeEditText(
                    value = draft,
                    onValueChange = onDraftChange,
                    hint = "说点什么…",
                    modifier = Modifier.fillMaxWidth(),
                    onReady = onEditTextReady,
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 六个里还剩五个没接：点一下把"差什么、等哪个阶段"写进上面那行提示。
                    // 不给假开关的意思是"点了要有回应"，不是"不许摆"。
                    // 模型这一颗 0.15x 接上了：和顶部那条模型条开的是同一个面板。
                    IconActionButton(
                        image = ActionIcons.Cpu,
                        description = "模型",
                        onClick = onModel,
                        compact = true,
                    )
                    IconActionButton(
                        image = ActionIcons.SearchInput,
                        description = "网络搜索",
                        onClick = { onHint("网络搜索待接：工具层（P7）") },
                        compact = true,
                    )
                    IconActionButton(
                        image = ActionIcons.Gauge,
                        description = "思考强度：$thinkingLabel",
                        onClick = onThinking,
                        compact = true,
                    )
                    IconActionButton(
                        image = ActionIcons.Wrench,
                        description = "工具开关",
                        onClick = { onHint("工具开关待接：工具层（P7，两级门）") },
                        compact = true,
                    )
                    Spacer(Modifier.weight(1f))
                    IconActionButton(
                        image = ActionIcons.Plus,
                        description = "＋ 菜单",
                        onClick = onPlus,
                        compact = true,
                    )
                    IconActionButton(
                        image = ActionIcons.Mic,
                        description = "语音识别",
                        onClick = { onHint("语音识别待接：ASR（未排期）") },
                        compact = true,
                    )
                    SendStopButton(
                        enabled = canSend && draft.isNotBlank(),
                        busy = busy,
                        onSend = onSend,
                    onQueue = onQueue,
                    queued = queued,
                        onStop = onStop,
                        onSaveAssistant = onSaveAssistant,
                        onSaveUser = onSaveUser,
                    )
                }
            }
        }

        Text(
            text = if (unsaved) {
                "这是还没落库的新会话：第一条消息发出去时才建会话。"
            } else {
                "点 ↑ 发请求；生成中再点一下是停止（停在这一次请求回来之后）。" +
                    "长按 ↑ 可以只保存、不发送。"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 10.dp),
        )
    }
}

/**
 * 发送 / 停止：**同一个按钮换形态**（PLAN §3.12）。
 *
 * - 空闲：↑，草稿非空才亮；
 * - 生成中：方块，点它 = 取消（`turn_cancel`，在下一个回合边界断开，D58 那条代价）；
 * - **长按出菜单**（用户 2026-10-21 定的）：仅保存为助手消息 / 仅保存为用户消息。
 *   这两项走 `append_text`，语义是"先记下来、晚点再发"，**不发请求**。
 */
@Composable
private fun SendStopButton(
    enabled: Boolean,
    busy: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onQueue: () -> Unit,
    queued: Int,
    onSaveAssistant: () -> Unit,
    onSaveUser: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val active = enabled && !busy
    val background = if (active || busy) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.30f)
    }

    Box {
        Box(
            modifier = Modifier
                .size(44.dp)
                .pointerInput(active, busy) {
                    detectTapGestures(
                        onTap = {
                            when {
                                busy -> onStop()
                                active -> onSend()
                            }
                        },
                        // **生成中也允许长按**：队列窗口正是在"它还在跑"的时候最需要打开
                        // （看排到第几条、要不要插队、要不要删掉）。以前这一下被 `if (!busy)` 挡了。
                        onLongPress = { menuOpen = true },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Surface(shape = CircleShape, color = background, modifier = Modifier.size(36.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (busy) ActionIcons.Square else ActionIcons.ArrowUp,
                        contentDescription = if (busy) "停止" else "发送",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = {
                    Text(if (queued > 0) "发送队列（$queued）" else "发送队列")
                },
                onClick = {
                    menuOpen = false
                    onQueue()
                },
            )
            DropdownMenuItem(
                text = { Text("仅保存为助手消息") },
                onClick = {
                    menuOpen = false
                    onSaveAssistant()
                },
            )
            DropdownMenuItem(
                text = { Text("仅保存为用户消息") },
                onClick = {
                    menuOpen = false
                    onSaveUser()
                },
            )
        }
    }
}

/**
 * 「更多」面板的内容。
 *
 * 顺序照用户 2026-10-24 给的：编辑 / 增强编辑 / 分享 / 选择消息 / 创建分支 / 删除此版本 /
 * 删除所有版本（**只有真的还有其他版本时才出现**）。没接的项不隐藏、也不做成一按就死的行，
 * 而是点了给一句"待接什么"——这是这个项目里"不给假开关"的一贯做法。
 */
private fun messageSheetItems(
    message: ChatMessage,
    /** 重试（0.15h）。`null` = 这一条现在没有可重发的东西（比如会话还没落库）。 */
    retryItem: SheetItem? = null,
    /** 续话（D193）。只有列表里最后一条可重试的消息才有。 */
    continueItem: SheetItem? = null,
    onShare: () -> Unit,
    onSelectOnly: () -> Unit,
    onSaveVersion: () -> Unit,
    onEdit: () -> Unit,
    onToolEdit: () -> Unit,
    onOpenEditor: () -> Unit,
    onDeleteThis: () -> Unit,
    onDeleteAll: () -> Unit,
): List<SheetItem> {
    val items = mutableListOf(
        // 重试 / 续话排在最前：它们是对这一条最常见的动作，也是仅有的两条会真的再发请求的。
        *listOfNotNull(retryItem, continueItem).toTypedArray(),
        SheetItem(
            label = "编辑",
            icon = ActionIcons.Pencil,
            onClick = onEdit,
        ),
        // D177：增强编辑 = **用户手动改块**（正文 / 思考 / 工具参数），整页编辑、点保存落库。
        // 模型改消息走工具调用（`edit_unit`，0.14y 叫 `edit_message`；这一轮结束时生效），不在这张表里。
        //
        // 入口走 `onEditMessage`（MainActivity 那边 push `Route.MessageEdit`）。
        // 以前这里接的是旧的"问模型改"弹窗：那个入口在 0.14x 之后就没有意义了，
        // 而且它让整页编辑器**在真机上一个入口都没有**（2026-10-29 找出来的）。
        SheetItem(
            label = "编辑消息（正文 / 思考 / 工具参数）",
            icon = ActionIcons.Wrench,
            onClick = onOpenEditor,
        ),
        // 临时探针：走"工具编辑"那条路，把 `edited_by_tool` 置上，用来验红/蓝（D148）。
        SheetItem(
            label = "用工具改这一版（临时探针）",
            icon = ActionIcons.Pencil,
            onClick = onToolEdit,
        ),
        SheetItem(
            label = "分享",
            icon = ActionIcons.Share2,
            onClick = onShare,
        ),
        SheetItem(
            label = "选择消息",
            icon = ActionIcons.Check,
            onClick = onSelectOnly,
        ),
        // 临时探针：`turn_run` 未接，重新生成产不出版本；
        // 没有它，版本 UI 在真机上永远没有数据可看。接上 turn_run 就删掉这一项。
        SheetItem(
            label = "复制为新版本（临时探针）",
            icon = ActionIcons.Copy,
            onClick = onSaveVersion,
        ),
        SheetItem(
            label = "创建分支",
            icon = ActionIcons.GitBranch,
            hint = "创建分支待接：要先把「从某条消息开始的新会话」建模",
        ),
    )
    // 多版：两个都给。只有一版：只给"删除所有版本"——它是**清空版本记录**
    // （连这一版一起删、正文不动，D151），所以单版也能用；"删除此版本"删到零版等于删消息，
    // 那组动词还没做（D130），于是只在多版时出现。
    if (message.versionCount > 1) {
        items += SheetItem(
            label = "删除此版本",
            icon = ActionIcons.Trash2,
            onClick = onDeleteThis,
        )
    }
    // 这一项删的是**整条消息**（正文一起没，D152）：所以只要有消息就给，不限版本数，
    // 而且文案把"含所有版本"写出来——`hint` 面板不画，写在里面等于没写。
    items += SheetItem(
        label = "删除这条消息（含所有版本）",
        icon = ActionIcons.Trash2,
        onClick = onDeleteAll,
    )
    return items
}

/**
 * 原生 `EditText` 塞进 Compose。三处要留神：
 * - **别在 `update` 里无脑 `setText`**：中文输入法还在组字时改内容会把候选打断。
 *   只在外部值真的变了、且与控件当前文本不同才写。
 * - 背景置空、padding 交给外层 Surface，否则会看到两层圆角。
 * - `maxLines` 给 6、`heightIn` 给下限：一行时不能塌成一条缝。
 */
@Composable
private fun NativeEditText(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    onReady: (EditText) -> Unit = {},
) {
    // 文字色跟着主题走：写死深色主题的两支颜色，换到浅色就是"浅灰字压白底"，读不出来。
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val hintColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    AndroidView(
        modifier = modifier.heightIn(min = 44.dp),
        factory = { context ->
            EditText(context).apply {
                background = null
                setTextColor(textColor)
                setHintTextColor(hintColor)
                this.hint = hint
                textSize = 16f
                maxLines = 6
                inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                imeOptions = EditorInfo.IME_FLAG_NO_ENTER_ACTION or EditorInfo.IME_ACTION_NONE
                setPadding(16, 22, 12, 18)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // 光标跟着主题走；不设的话某些 ROM 上是纯黑，深色底看不见。
                    textCursorDrawable = null
                }
                onReady(this)
                addTextChangedListener(
                    object : android.text.TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                        override fun afterTextChanged(s: android.text.Editable?) {
                            onValueChange(s?.toString().orEmpty())
                        }
                    },
                )
            }
        },
        update = { editText ->
            // factory 只跑一次：主题在运行中切换时，颜色要在这里跟上来，
            // 否则从浅色切回深色，输入框还是白底黑字的旧色。
            editText.setTextColor(textColor)
            editText.setHintTextColor(hintColor)
            if (editText.text?.toString() != value) {
                if (value.isEmpty()) {
                    // **原地清空**，不要 `setText("")`：那会把 Editable 换成一个新对象，
                    // 而输入法还连着旧的——之后敲的字进不了这个框，`afterTextChanged`
                    // 也不再触发。真机上的"保存一次之后再也发不出去、提示先写点什么"
                    // 就是这个：界面看着有字，`draft` 一直是空串。
                    editText.text?.clear()
                } else {
                    editText.setText(value)
                    editText.setSelection(value.length)
                }
            }
        },
    )
}
/**
 * 一次工具调用 + 它的结果（D159）。
 *
 * 形状照用户给的那张图：**命令**一块、**输出**一块（stdout / stderr 两个页签），
 * 各带复制。命令是模型传来的 `arguments.command`，输出是结果里那份结构化 JSON。
 *
 * 为什么不再"把参数和结果原样铺出来"：一条 `ls -la` 的输出几十行，
 * 原样铺开之后人根本看不出哪段是命令、哪段是 stdout、哪段是报错。
 */
@Composable
private fun ToolCallBlock(command: ToolCallArg, result: ChatMessage?) {
    val context = LocalContext.current
    val payload = result?.content?.let { text ->
        runCatching { JSONObject(text) }.getOrNull()?.takeIf { it.has("stdout") || it.has("stderr") }
    }
    val shown = if (payload != null) {
        payload.optString("command", "")
    } else {
        runCatching { JSONObject(command.arguments).optString("command", "") }.getOrNull().orEmpty()
    }
    val label = if (command.name == "System-Shell") "运行命令" else command.name
    // 用「选过没有」+ 默认页签，而不是在组合过程里改状态：组合里赋值会多走一轮重组。
    var picked by remember(result?.id) { mutableStateOf<Int?>(null) }

    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (result == null) {
                Text(
                    text = "没有结果（这一批里它没跑完）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        Text(
            text = "命令",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
        MonoBox(text = shown.ifEmpty { command.arguments })
        CopyHint(text = shown.ifEmpty { command.arguments }, onCopied = { putClipboard(context, "命令", it) })

        if (payload == null) {
            if (result != null) {
                Text(
                    text = "输出",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
                MonoBox(text = result.content.ifEmpty { "（空）" })
            }
            return@Column
        }

        val stdout = payload.optString("stdout", "")
        val stderr = payload.optString("stderr", "")
        // 失败时默认停在 stderr：让人第一眼看到的就是报错，而不是一片空白。
        val failed = (payload.opt("exit_code") as? Number)?.toInt()?.let { it != 0 } ?: true
        val defaultTab = if (failed && stderr.isNotBlank() && stdout.isBlank()) 1 else 0
        val tab = picked ?: defaultTab

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "输出",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            val exit = payload.opt("exit_code")
            Text(
                text = buildString {
                    append(if (exit == null || exit == JSONObject.NULL) "被信号终止" else "退出码 $exit")
                    if (payload.optBoolean("timed_out", false)) append(" · 超时")
                    if (payload.optBoolean("truncated", false)) append(" · 已截断")
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(modifier = Modifier.padding(top = 4.dp)) {
            TabChip("stdout", selected = tab == 0) { picked = 0 }
            Spacer(Modifier.width(6.dp))
            TabChip("stderr", selected = tab == 1) { picked = 1 }
        }

        val body = if (tab == 0) stdout else stderr
        MonoBox(text = body.ifEmpty { "（无输出）" })
        CopyHint(text = body, onCopied = { putClipboard(context, if (tab == 0) "stdout" else "stderr", it) })
    }
}

@Composable
private fun TabChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** 等宽的一块内容。太长就截断显示——**复制复制的是全文**，不是这一屏。 */
@Composable
private fun MonoBox(text: String) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    ) {
        Text(
            text = if (text.length > 4000) {
                text.take(4000) + "\n…（还有 ${text.length - 4000} 字，复制能拿到全文）"
            } else {
                text
            },
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun CopyHint(text: String, onCopied: (String) -> Unit) {
    if (text.isEmpty()) return
    Text(
        text = "复制",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clickable { onCopied(text) }
            .padding(top = 4.dp, bottom = 2.dp),
    )
}

/** 聊天页顶部那条"现在用哪个模型"。点开是选择面板（D161）。 */
@Composable
private fun ModelBar(label: String, percent: Int?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 60.dp, end = 16.dp)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = ActionIcons.Cpu,
            contentDescription = "当前模型",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(12.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 4.dp).weight(1f, fill = false),
        )
        // 占用百分比：数字型信息用文字（ui-taste 的"图标优先、数字场景用文字"）。
        // 它和压缩判据读的是同一个窗口，所以这两个数字不会各说各话。
        if (percent != null) {
            Text(
                text = "$percent%",
                style = MaterialTheme.typography.labelSmall,
                color = if (percent >= 80) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.padding(start = 6.dp),
            )
        }
        Icon(
            imageVector = ActionIcons.ChevronDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(12.dp).padding(start = 2.dp),
        )
    }
}

/** 思考强度档位：wire 值必须跟 `crates/ffi/src/turn.rs` 的 `apply_thinking` 对表。 */
private val THINKING_LEVELS = listOf(
    "auto" to "自动（不注字段）",
    "off" to "关闭",
    "low" to "低",
    "medium" to "中",
    "high" to "高",
    "max" to "最大",
)

/**
 * 一条消息的思考块（D162）。折叠起来只占一行，点开看全文。
 *
 * 为什么不做成"气泡旁边的小字"：思考经常几百字，铺开之后正文就被挤到屏外，
 * 而那才是他要读的东西。
 */
@Composable
private fun ReasoningCard(text: String) {
    var expanded by remember(text) { mutableStateOf(false) }
    ReasoningShell(text = text, expanded = expanded, writing = false) { expanded = !expanded }
}

/**
 * 流式期间的思考块（0.15ah）：**展开状态不能跟着增量重置**。
 *
 * `ReasoningCard` 的 `remember(text)` 在正文每长一个字时就换一次键，展开会被收回——
 * 100 ms 一次，等于永远打不开。所以这里用不带键的 `remember`，只有换会话才复位。
 */
@Composable
private fun LiveReasoningCard(text: String) {
    var expanded by remember { mutableStateOf(false) }
    ReasoningShell(text = text, expanded = expanded, writing = true) { expanded = !expanded }
}

@Composable
private fun ReasoningShell(
    text: String,
    expanded: Boolean,
    /** 还在写：标题里说清楚，别让人以为这就是全部思考。 */
    writing: Boolean,
    onToggle: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = ActionIcons.ChevronDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(12.dp),
                )
                Text(
                    text = when {
                        expanded -> "思考（点一下收起）"
                        writing -> "思考 · ${text.length} 字（还在写）"
                        else -> "思考 · ${text.length} 字"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
            if (expanded) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun DialogChip(label: String, primary: Boolean = false, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (primary) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (primary) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/**
 * `turn_busy.calls` → 正在跑的那几条（0.15ak）。
 *
 * 空 id 直接丢掉：那种行点「关闭」什么也关不掉，画出来就是"点了没反应"。
 */
private fun runningCallsOf(info: JSONObject): List<RunningCall> {
    val array = info.optJSONArray("calls") ?: return emptyList()
    return buildList {
        for (index in 0 until array.length()) {
            val row = array.optJSONObject(index) ?: continue
            val id = row.text("call_id")
            if (id.isEmpty()) continue
            add(
                RunningCall(
                    callId = id,
                    name = row.text("name").ifBlank { "工具" },
                    elapsedMs = row.optInt("elapsed_ms", 0),
                ),
            )
        }
    }
}

/** 跑了多久。毫秒级只在这一行的头一秒出现，之后按秒走——一直跳小数看着像在闪。 */
private fun elapsedLabel(ms: Int): String = when {
    ms < 1_000 -> "${ms} 毫秒"
    ms < 60_000 -> "${ms / 1_000} 秒"
    else -> "${ms / 60_000} 分 ${(ms % 60_000) / 1_000} 秒"
}

/**
 * 正在跑的工具调用（0.15ak）：一行一条，右边一颗「关闭」。
 *
 * 为什么在这里而不是工具卡里：工具卡是**落库之后**才有的，而"正在跑"这一段还没落库
 * （D229 那条"没有单条调用的实时输出通道"就是它）。所以它挂在正在生成的那一块上，
 * 收尾后整块被库里那份替掉，这一行自然消失。
 *
 * 「关闭」是文字按钮不是图标：这个动作收不回来（命令会被杀），字比图形更该被读清楚。
 * 热区 48：一行只有一句名字 + 一个动作，手指偏一点就点到名字上了。
 */
@Composable
private fun RunningCallRows(calls: List<RunningCall>, onCancel: (String) -> Unit) {
    if (calls.isEmpty()) return
    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        calls.forEach { call ->
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${call.name} · ${elapsedLabel(call.elapsedMs)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (call.cancelled) {
                    // 已经点过了：换成一句状态，不再给第二颗按钮——再点一次没有任何新效果。
                    Text(
                        text = "已请求关闭",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                } else {
                    Text(
                        text = "关闭",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .clickable { onCancel(call.callId) }
                            .padding(horizontal = 12.dp, vertical = 14.dp),
                    )
                }
            }
        }
    }
}
