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
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject
import android.util.Base64
import kotlinx.coroutines.CancellationException
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
    var toolSheet by remember(sessionId) { mutableStateOf<ToolSheetTarget?>(null) }
    // 正在跑的那条调用点开的弹窗（0.15ak）。**只存 id**：对象是这一拍的副本，
    // 而秒数每 100 ms 在变——存对象的话弹窗里的时间会冻在点开那一刻。
    // 这一轮的上下文图片（D248）：`View-Image` 拉进来的那些。界面只读它、只能移出。
    var contextImages by remember(sessionId) { mutableStateOf(listOf<ContextImageRow>()) }
    var contextImagesOpen by remember(sessionId) { mutableStateOf(false) }
    // 收尾之后还要等一次重读：库里那份到了才把内存里这条换掉，否则气泡会闪一下。
    var settling by remember(sessionId) { mutableStateOf(false) }
    // 已经请求停止、这一轮还没真的收尾（2026-11-06 用户）：停止键灰着，别让他连点。
    // 判据是"这一轮还在不在跑"，不是"点过没有"——收尾（`busy` 变 false）就恢复。
    var stopping by remember(sessionId) { mutableStateOf(false) }
    LaunchedEffect(busy) {
        if (!busy) stopping = false
    }
    // 重试落在哪条消息上（D227）：这一轮的部分正文**就地**画在它身上，不再在底部另起一个
    // 「助手 / 正在生成…」的块。`null` = 这一轮不是重试，或者目标为空（结果会落成新消息）。
    var regenTarget by remember(sessionId) { mutableStateOf<String?>(null) }
    // 这一轮是"只换这一条"（D284）：只藏目标那一条、正文就画在它自己的位置上，
    // 后面的消息一条都不动。整轮替换时仍然是"整轮不画 + 底部画"（D263）。
    var regenInPlace by remember(sessionId) { mutableStateOf(false) }
    // 重试失败 → 三选一（D228）。它决定库里留下哪一版，所以是"必须处理"的那种，点外面不关。
    var regenFailure by remember { mutableStateOf<RegenFailure?>(null) }
    // 重试一条旧消息、而设置里选的是"删掉后面的"（D284）：先问一句会没掉几条。
    var truncateAsk by remember { mutableStateOf<RegenAsk?>(null) }
    // 手动停止（重试/续话）→ 问一句"这一版留不留"。停止不是失败，可它同样把残句写成了
    // 新版本；不给一句话，用户只会发现"我明明按了停止，回答却变了"（2026-11-06 真机）。
    var stopPrompt by remember { mutableStateOf<StoppedTurn?>(null) }
    // 「工具」面板（D271）：这一条会话绑哪个工作区。
    // 名字别叫 toolSheet——那是工具详情弹窗（D261）的 state，撞上就变成两个类型抢一个名字。
    var toolsPanel by remember { mutableStateOf(false) }
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
    // 图片可以**多选**（2026-11-08）：一次挑几张，逐张压缩、逐张算上限。
    // 上限按"这一轮总共几张"算，不是"这一次挑了几张"——分两次挑也不该超。
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents(),
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val room = MAX_PENDING_IMAGES - pendingImages.size
        if (room <= 0) {
            notice = "一轮最多带 $MAX_PENDING_IMAGES 张图"
            return@rememberLauncherForActivityResult
        }
        val take = uris.take(room)
        scope.launch {
            val picked = withContext(Dispatchers.IO) {
                val loaded = ArrayList<PendingImage>()
                var failure: String? = null
                for (uri in take) {
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
                    }.onSuccess { loaded.add(it) }
                        .onFailure { failure = it.message }
                }
                loaded to failure
            }
            val (loaded, failure) = picked
            if (loaded.isNotEmpty()) pendingImages = pendingImages + loaded
            notice = when {
                loaded.isEmpty() -> "选图失败：${failure ?: "没有可用的图"}"
                uris.size > take.size ->
                    "已加 ${loaded.size} 张；一轮最多 $MAX_PENDING_IMAGES 张，多出来的没加"
                failure != null -> "已加 ${loaded.size} 张，有 ${uris.size - loaded.size} 张没读出来"
                else -> "已加 ${loaded.size} 张（这一轮共 ${pendingImages.size + loaded.size} 张）"
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

    // 界面刷新周期（D277）：流式轮询与"跑了多久"的刷新节奏。默认 50 ms，设置里可调。
    val refreshMs = remember { AppSettings.uiRefreshMs(context) }

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
                done = doneCallsOf(info),
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
                waited += refreshMs
                if (waited > 15_000) break
            }
            delay(refreshMs.toLong())
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

    // 上下文图片清单（D248）：会话变了、或者这一轮收尾重读之后现问一次。
    // 不在界面里自己维护这份清单——`View-Image` 是 native 那边加的，界面数一遍必然对不上。
    LaunchedEffect(sessionId, reloadKey) {
        val sid = sessionId ?: run {
            contextImages = emptyList()
            return@LaunchedEffect
        }
        contextImages = withContext(Dispatchers.IO) {
            NmoiFfi.contextImages(sid).getOrNull()?.let { parseContextImages(it) }.orEmpty()
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
        // 用户那条**已经落库了**就把「发送中…」收掉（D261）。
        //
        // 以前只在一轮结束时收：一轮十个工具调用能跑二十几秒，这中间屏幕上一直写着
        // 「发送中…」，像没发出去（2026-11-05 真机报的"发送后没有即时显示"）。
        // 判据是"库里真有这一条"——按正文和发送时刻对，不按顺序猜。
        val sending = pending ?: return@LaunchedEffect
        val page = snapshot?.page ?: return@LaunchedEffect
        val arrived = page.units
            .flatMap { it.messages }
            .any { it.role == "user" && it.content == sending.text && it.createdAt >= sending.at - 5_000 }
        if (arrived) pending = null
    }

    // 发出去之后盯一小段：那条用户消息一进库就换掉乐观气泡。
    //
    // 为什么是轮询而不是等事件：`Started` 在**落库之前**就发出来了（TurnRunner 先报开场，
    // 再进 native 那一轮），所以那一刻读库还读不到。每 300ms 重读一次，通常一两拍就够。
    LaunchedEffect(pending, busy) {
        if (pending == null || !busy) return@LaunchedEffect
        repeat(12) {
            delay(300)
            if (pending == null) return@LaunchedEffect
            reloadKey += 1
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
        selecting || modelSheet || thinkingSheet || queueSheet || plusMenu || regenFailure != null ||
        stopPrompt != null || toolsPanel
    BackHandler(enabled = overlayOpen) {
        when {
            // 三选一**必须处理**（D228）：它决定库里留下哪一版，返回键在这里什么也不做。
            // 点外面也不关——不是"关掉就回到原样"，关掉等于替用户选了"保留上一版"。
            regenFailure != null -> Unit
            // 停止之后那一问同理：关掉等于替他选了"留着这一版"。
            stopPrompt != null -> Unit
            // 顺序 = 叠放顺序，从上往下退：先关最后打开的那一层。
            plusMenu -> plusMenu = false
            queueSheet -> queueSheet = false
            toolsPanel -> toolsPanel = false
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
    val runRegen: (String, String, String?, Boolean, String?) -> Unit =
        { sid, upTo, target, append, regenScope ->
        scope.launch {
            busy = true
            notice = null
            runningSession = sid
            // 就地渲染（D227）：这一轮的正文画在**目标消息**上，不再在底部另起一块。
            // 目标为空（用户消息重试、下面没有回答可挂）时仍然在底部——结果会落成一条新消息，
            // 那个位置就是它要去的地方，提前摆一个气泡在那里不算撒谎。
            regenTarget = target
            regenInPlace = regenScope == AppSettings.REGEN_OLD_IN_PLACE
            // 续话（0.15av）：落库那份是「原正文 + 续文」，流里只有续文那一截。
            // 这里把原正文预置成起点——native 那边的缓冲也垫了同一段，两边的字数才对得上。
            val before = if (append && target != null) {
                allMessages.firstOrNull { it.id == target }?.content.orEmpty()
            } else {
                ""
            }
            live = LiveReply(round = 0, running = true, text = before, prefix = before, regen = true)
            settling = false
            // 和发送同一条记录：切走再切回来时"还在跑"要能显示（0.15c）。
            GeneratingTracker.mark(sid, true)
            try {
                // 「允许生成新版本」（D185）。读的是应用设置，不是页面里的缓存。
                val allowNewVersion = AppSettings.allowNewVersion(context)
                val result = TurnRepository.regen(
                    context = context,
                    databasePath = databasePath,
                    sessionId = sid,
                    upTo = upTo,
                    target = target,
                    // **永远先留着两版**（D273）：停止时要问"留哪一版"，问之前上一版不能已经没了。
                    // 关着"允许生成新版本"的话，成功之后由下面把上一版删掉（覆盖语义不变）。
                    allowNewVersion = true,
                    append = append,
                    regenScope = regenScope,
                    stream = assistant.stream,
                )
                result.fold(
                    onSuccess = { outcome ->
                        notice = (if (append) "续话：" else "重试：") + outcome.describe()
                        // 停止（D273）：开着"允许生成新版本"就**不弹**——两版都在，跟这个设置一致；
                        // 关着才问"留哪一版"，因为那种情况下默认会覆盖，而覆盖是用户看不见的丢东西。
                        if (outcome.cancelled && !allowNewVersion && target != null) {
                            stopPrompt = StoppedTurn(
                                messageId = target,
                                length = outcome.assistant?.length ?: 0,
                            )
                        }
                        // 成功 + 设置是"覆盖"：把上一版删掉（D273 把覆盖从 core 挪到这儿，
                        // 因为停止时要先留着它）。版号现读，不猜。
                        if (!outcome.cancelled && !allowNewVersion && target != null) {
                            val info = ChatRepository.versions(databasePath, target).getOrNull()
                            val previous = ((info?.active ?: 1) - 1).toLong()
                            if (previous >= 1L) {
                                ChatRepository.deleteVersion(databasePath, target, previous)
                                reloadKey += 1
                            }
                        }
                    },
                    onFailure = { error ->
                        // 残文要在清掉 `live` 之前取：三选一里「保留这一版」存的就是这一份。
                        val partial = live?.text.orEmpty()
                        val partialReasoning = live?.reasoning.orEmpty()
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
            } catch (cancelled: CancellationException) {
                // 页面走了：状态跟着一起销毁，不用收尾（也不该在这里写 state）。
                throw cancelled
            } catch (failure: Throwable) {
                // **这条 catch 是补出来的**（2026-11-04）：以前只要 `regen` 抛出来（不是回
                // `Result.failure`），协程就死在这一行，下面的 `busy = false` 永远不执行——
                // 界面上"正在生成"一直转，而原生那边早就没有请求了。
                notice = (if (append) "续话失败：" else "重试失败：") +
                    (failure.message ?: failure.javaClass.simpleName)
            } finally {
                // 收尾只写一次，放在 finally 里：抛不抛都得把界面收干净。
                runningSession = null
                GeneratingTracker.mark(sid, false)
                busy = false
                live = null
                regenTarget = null
        regenInPlace = false
                regenInPlace = false
                settling = false
                reloadKey += 1
            }
        }
    }

    /**
     * 点「重试」：按这一条在列表里的位置算出 `upTo` / `target`，算不出来就说原因。
     *
     * **落在哪儿**（D284）：这一条之后还有没有新的一轮。
     * - 没有（它就是最新的那条）→ 整轮替换，跟以前一样。
     * - 有（点的是中间某条）→ 默认**只换这一条**，后面的消息一条都不动；
     *   应用设置里选成「删掉后面的」才走整轮替换，而且先问一句"会没掉几条"。
     *
     * 以前一律整轮替换：点 a1 会把 u2 / a2 一起删掉，屏幕上还看着像"在 a2 的位置重试"。
     */
    val retryTurn: (ChatMessage) -> Unit = { message ->
        val sid = sessionId
        val index = allMessages.indexOfFirst { it.id == message.id }
        val hasLaterTurn = allMessages.drop(index + 1).any { it.isUser }
        val truncate = AppSettings.regenOldMessage(context) == AppSettings.REGEN_OLD_TRUNCATE
        when {
            busy -> notice = "上一轮还没结束（要打断就先点停止）"
            sid == null -> notice = "这条会话还没落库，没法重发"
            index < 0 -> notice = "这条消息已经不在列表里了：先刷新再看"
            message.isUser -> {
                // 用户消息重试：这一句自己就是最后一条发出去的东西。
                val reply = allMessages.getOrNull(index + 1)?.takeIf { !it.isUser }
                val scope = if (hasLaterTurn && !truncate) {
                    AppSettings.REGEN_OLD_IN_PLACE
                } else {
                    null
                }
                if (hasLaterTurn && truncate) {
                    truncateAsk = RegenAsk(sid, message.id, reply?.id, allMessages.size - index - 1)
                } else {
                    runRegen(sid, message.id, reply?.id, false, scope)
                }
            }

            else -> {
                // `up_to` 取的是**这一轮的用户消息**，不是列表里的上一条。
                // 列表里上一条很可能是这一轮自己的工具结果——那样重发等于把"已经跑过的 9 步"
                // 一起递过去，模型看见结果就只补一句总结，看着像"根本没重测"（用户 2026-11-04 报的）。
                val user = allMessages.take(index).lastOrNull { it.isUser }
                when {
                    user == null -> notice = "这条前面没有用户消息，没法重发"
                    hasLaterTurn && truncate ->
                        truncateAsk = RegenAsk(sid, user.id, message.id, allMessages.size - index - 1)
                    else -> runRegen(
                        sid,
                        user.id,
                        message.id,
                        false,
                        if (hasLaterTurn) AppSettings.REGEN_OLD_IN_PLACE else null,
                    )
                }
            }
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
            message.isUser -> runRegen(sid, message.id, null, false, null)
            else -> runRegen(sid, message.id, message.id, true, null)
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
                try {
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
                } finally {
                    // 与重试那条同一个道理（D249）：抛出去也得把 `busy` 收回来，
                    // 否则整页再也发不出东西。
                    busy = false
                }
            }
        }
    }

    /**
     * 把"以为在跑、其实没在跑"的状态收干净（D249）。
     *
     * watchdog 与「停止」键共用这一份：两处各写一遍，迟早一边忘了清 `pending` 或 `live`。
     * 收完补领一次结果——那一轮可能刚好在这一段里结束。
     */
    fun clearStuckTurn() {
        val sid = sessionId ?: return
        busy = false
        runningSession = null
        GeneratingTracker.mark(sid, false)
        settling = false
        live = null
        regenTarget = null
        regenInPlace = false
        pending = null
        reloadKey += 1
        notice = when (val missed = TurnRunner.takeRecent(sid)) {
            is TurnEvent.Finished -> missed.message
            is TurnEvent.Failed -> missed.message
            else -> "这一轮在原生那边已经结束了（界面刚才没收到收尾）：没有新消息就是它失败了，原因在 设置 → 调试 → 日志"
        }
    }

    /**
     * 状态对账（D249）：**`busy` 只能由"原生那一轮真的结束了"来清**。
     *
     * 为什么要有这一条：`busy` 原来只有两个清的地方——发送那条路等 `TurnEvent.Finished`，
     * 重试那条路等 `regen(...)` 返回。两个都可能漏：事件丢了（页面刚重组完）、
     * 协程死在中途（抛在 `Result` 之外）、native 卡住不返回。任何一种都让界面永远停在
     * "正在生成"，而原生那边早就没有请求了——用户看到的就是"卡住了，也不发请求"。
     *
     * 判据跟 D236 一样：**以原生为准**。连续三拍（约 3 秒）都说没在跑才收——
     * 刚点下去那一小段 native 还没登记，一看见 false 就收会误杀刚起的一轮。
     */
    LaunchedEffect(sessionId, busy) {
        val sid = sessionId ?: return@LaunchedEffect
        if (!busy) return@LaunchedEffect
        var idle = 0
        while (busy) {
            delay(1_000)
            if (!busy) break
            val running = withContext(Dispatchers.IO) {
                NmoiFfi.turnBusy(sid).getOrNull()?.optBoolean("running") ?: true
            }
            if (running) {
                idle = 0
                continue
            }
            idle += 1
            if (idle < 3) continue
            clearStuckTurn()
            break
        }
    }

    // 进页面时跟原生对一次账（0.15c）：本地缓存可能是上一轮的残留。
    // 查不到就当他还在跑——宁可多显示一次"正在生成"，也别把真在跑的那一轮当成结束了。
    LaunchedEffect(sessionId) {
        val sid = sessionId ?: return@LaunchedEffect
        if (!GeneratingTracker.isRunning(sid)) return@LaunchedEffect
        // 刚点发送那一小段：native 还没登记，`turnBusy` 一定是 false。
        // 一拍就判"没在跑"会把刚发出去的那一轮连 `pending` 一起清掉——屏幕上就是
        // "发完什么都没有"（2026-11-05 真机第一张图）。跟 watchdog 一样连看三拍。
        var alive = false
        for (attempt in 0 until 3) {
            alive = withContext(Dispatchers.IO) {
                NmoiFfi.turnBusy(sid).getOrNull()?.optBoolean("running") ?: true
            }
            if (alive) break
            delay(700)
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
                        inPlaceRegen = regenInPlace,
                        // 过程块（D229）：展开状态只活在这个会话的这次浏览里。
                        openGroups = openGroups,
                        onToggleGroup = { key -> openGroups[key] = openGroups[key] != true },
                        onOpenTool = { _, call -> toolSheet = ToolSheetTarget(call.callId, call) },
                        liveThinkingOpen = openGroups["live:think"] == true,
                        onToggleLiveThinking = {
                            openGroups["live:think"] = openGroups["live:think"] != true
                        },
                        onOpenRunningCall = { call ->
                            toolSheet = ToolSheetTarget(
                                callId = call.callId,
                                call = ToolCallArg(
                                    callId = call.callId,
                                    part = "",
                                    name = call.name,
                                    arguments = call.arguments,
                                    output = "",
                                    status = "running",
                                ),
                                running = true,
                                elapsedMs = call.elapsedMs,
                            )
                        },
                        onOpenDoneCall = { done ->
                            toolSheet = ToolSheetTarget(
                                callId = done.callId,
                                call = ToolCallArg(
                                    callId = done.callId,
                                    part = "",
                                    name = done.name,
                                    arguments = done.arguments,
                                    output = done.output,
                                    status = if (done.ok) "ok" else "error",
                                ),
                            )
                        },
                        finishedThinkingOpen = { index -> openGroups["live:fin:$index"] == true },
                        onToggleFinishedThinking = { index ->
                            val key = "live:fin:$index"
                            openGroups[key] = openGroups[key] != true
                        },
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
                    stopping = stopping,
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
                            // 界面以为在跑、原生那边其实早就没了（D249）：把状态收干净再说话。
                            clearStuckTurn()
                            notice = "这一轮已经收尾了"
                        } else {
                            scope.launch {
                                TurnRepository.cancel(running).fold(
                                    onSuccess = { cancelled ->
                                        if (cancelled) {
                                            // 灰掉那颗键，直到这一轮真的收尾——连点只会重复请求同一次取消。
                                            stopping = true
                                            notice = "已请求停止：当前这一次请求回来之后就不再往下走了"
                                        } else {
                                            // 点晚了一拍：原生那边没有在跑的这一轮。别把界面留在"生成中"。
                                            clearStuckTurn()
                                            notice = "这一轮已经收尾了"
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
                    onTools = { toolsPanel = true },
                    imageCapability = imageCapability,
                    contextImageCount = contextImages.size,
                    contextImageLimit = AppSettings.imageLimit(context),
                    onOpenContextImages = { contextImagesOpen = true },
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
                        label = "技能",
                        icon = ActionIcons.GitBranch,
                        hint = "技能待接（P5.5）；世界书已经能用：设置 → 世界书",
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

        // 工具块点开的那一条（D243 / D261）：**一次调用一个弹窗**。
        // 以前点开哪一行都弹同一个块、把这一轮的全部调用铺在一起，用户分不清自己点的是哪一条。
        // 弹窗里的那一次调用**每一拍现查**（D264）：跑着的看实时输出，跑完了看结果，
        // 整轮收尾之后看库里那一份。点开那一刻抓的那份只当兜底——
        // 拿快照画，用户看到的就是"工具跑完了，小窗口还写着正在运行"。
        toolSheet?.let { opened ->
            val target = resolveToolSheet(opened, live?.calls, live?.done, allMessages)
            ToolDetailSheet(
                target = target,
                onCancel = { cancelCall(target.callId) },
                onDismiss = { toolSheet = null },
            )
        }

        // 图片上下文（D248）：这一轮会发出去的图，能看、能移出一张、能清空。
        if (contextImagesOpen) {
            ContextImagesSheet(
                images = contextImages,
                limit = AppSettings.imageLimit(context),
                onRemove = { id ->
                    val sid = sessionId
                    if (sid != null) {
                        scope.launch {
                            val result = NmoiFfi.removeContextImage(sid, id)
                            result.getOrNull()?.let { contextImages = parseContextImages(it) }
                            notice = if (result.isSuccess) "已移出上下文（图还在消息里）" else "移出失败"
                        }
                    }
                },
                onClear = {
                    val sid = sessionId
                    if (sid != null) {
                        scope.launch {
                            val result = NmoiFfi.clearContextImages(sid)
                            result.getOrNull()?.let { contextImages = parseContextImages(it) }
                            notice = if (result.isSuccess) "已清空这一轮的图片上下文" else "清空失败"
                        }
                    }
                },
                onDismiss = { contextImagesOpen = false },
            )
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

        if (toolsPanel) {
            ToolSheet(
                sessionId = sessionId,
                assistantWorkspaceId = assistant?.workspaceId,
                onDismiss = { toolsPanel = false },
                onNotice = { notice = it },
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

        val pendingTruncate = truncateAsk
        if (pendingTruncate != null) {
            ConfirmDialog(
                title = "重试这一条会删掉后面的对话？",
                detail = "这条之后还有 ${pendingTruncate.later} 条消息：整轮替换会把它们删掉，" +
                    "重跑出来的回答落在这里。只想换这一条的话，去设置 → 应用设置 → 生成，" +
                    "把「重试旧消息」改成「只换这一条」。",
                confirmLabel = "删掉并重试",
                onCancel = { truncateAsk = null },
                onConfirm = {
                    truncateAsk = null
                    runRegen(
                        pendingTruncate.sessionId,
                        pendingTruncate.upTo,
                        pendingTruncate.target,
                        false,
                        null,
                    )
                },
            )
        }

        // 停止之后那一问（D273）：两版都在库里，留哪一版由用户定。
        // 只在"允许生成新版本"关着的时候弹——开着的话两版本来就都留着，没什么可问的。
        val pendingStop = stopPrompt
        if (pendingStop != null) {
            StopPromptDialog(
                stopped = pendingStop,
                onKeepPrevious = {
                    stopPrompt = null
                    scope.launch {
                        val info = ChatRepository.versions(databasePath, pendingStop.messageId).getOrNull()
                        val active = (info?.active ?: 1).toLong()
                        val dropped = if (active >= 2L) {
                            ChatRepository.deleteVersion(databasePath, pendingStop.messageId, active)
                        } else {
                            Result.success(0)
                        }
                        reloadKey += 1
                        notice = if (dropped.isSuccess) {
                            "已回到上一版：这一版删掉了"
                        } else {
                            "上一版没切回去：${dropped.exceptionOrNull()?.message}"
                        }
                    }
                },
                onKeepThis = {
                    stopPrompt = null
                    scope.launch {
                        val info = ChatRepository.versions(databasePath, pendingStop.messageId).getOrNull()
                        val previous = ((info?.active ?: 1) - 1).toLong()
                        val dropped = if (previous >= 1L) {
                            ChatRepository.deleteVersion(databasePath, pendingStop.messageId, previous)
                        } else {
                            Result.success(0)
                        }
                        reloadKey += 1
                        notice = if (dropped.isSuccess) {
                            "只留这一版（上一版已删）"
                        } else {
                            "这一版留着，但上一版没删掉：${dropped.exceptionOrNull()?.message}"
                        }
                    }
                },
                onKeepBoth = {
                    stopPrompt = null
                    notice = "两版都留着：这一版是停止时那一段，切得回上一版"
                },
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
                // 思考与工具就在这一条消息里（D258）：不再去同单元里找"另一条过程消息"。
                val extra = buildList {
                    message.reasoning?.takeIf { it.isNotBlank() }?.let { add("思考" to it) }
                    message.toolArgs.forEach { call ->
                        add("工具调用" to "${call.name} ${call.arguments}")
                        if (call.output.isNotBlank()) add("工具结果" to call.output)
                    }
                }
                if (extra.isNotEmpty()) {
                    append("\n<details><summary>思考与工具调用（")
                    append(extra.size)
                    append(" 段）</summary>\n\n")
                    extra.forEach { (label, body) ->
                        append("- **")
                        append(label)
                        append("**：")
                        append(body.replace("\n", "\n  "))
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
    /** 这一轮是"只换这一条"（D284）：只藏目标那一条，正文画在它自己的位置。 */
    inPlaceRegen: Boolean = false,
    /** 过程块（D229）展开状态：键是组的第一项 id。 */
    openGroups: Map<String, Boolean> = emptyMap(),
    onToggleGroup: (String) -> Unit = {},
    /** 工具块点开：在**半屏弹窗**里看**那一次调用**（D261），不把整轮铺在一起。 */
    onOpenTool: (ChatUnit, ToolCallArg) -> Unit = { _, _ -> },
    /** 这一轮正在写的思考展开没有（0.15ak）。 */
    liveThinkingOpen: Boolean = false,
    onToggleLiveThinking: () -> Unit = {},
    /** 点开一条正在跑的调用（0.15ak）。 */
    onOpenRunningCall: (RunningCall) -> Unit = {},
    /** 点开一条**跑完了、还没落库**的调用（D261）：结果要等收尾才进库，这中间也得能看。 */
    onOpenDoneCall: (DoneCall) -> Unit = {},
    /** 第 N 轮（已经收口的）思考展开没有。 */
    finishedThinkingOpen: (Int) -> Boolean = { false },
    onToggleFinishedThinking: (Int) -> Unit = {},
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
    // 重试/续话期间（D251）：**这一轮的过程块不画**。
    //
    // 用户 2026-11-04：「UI 上在生成的时候不显示旧的」。那些块属于上一版的那一轮，
    // 而屏幕上正在写的是新的一版——旧的挂在那儿，看起来就像"新版本带着旧块"。
    // 判据：目标消息之前、最后一条用户消息之后的那几块就是这一轮的。
    val regenFrom = if (regenTarget == null || inPlaceRegen) {
        -1
    } else {
        rows.indexOfLast { row -> row is ChatRow.Single && row.unit.messages.any { it.isUser } }
    }
    val truncated = page?.truncated == true
    val listState = rememberLazyListState()
    val headerCount = if (truncated) 1 else 0
    val maxBubble = (LocalConfiguration.current.screenWidthDp * 0.78f).dp

    // 打开会话应当看到最新的一条，而不是最早的一条。
    // 发出去那一刻 `pending` 会插在末尾，所以它也要进 key——否则新气泡在屏幕外面。
    // 重试那一轮的目标行被藏起来了，所以 live 一律画在底部——那个位置就是它要去的地方。
    val liveItem = if (live != null) 1 else 0
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
            // 重试/续话期间（D263）：这一轮**整轮**都不画。
            // 折行之后一轮回答是好几条消息，只藏「过程块」会把工具那半行留下——
            // 屏幕上就是"新正文 + 上一轮的工具块"（2026-11-06 真机报的）。
            if (regenFrom >= 0 && index > regenFrom) {
                return@itemsIndexed
            }
            // 只换这一条（D284）：目标那一条让位给正在长的那份，其余照常画。
            val streamingHere = inPlaceRegen && live != null &&
                (row as? ChatRow.Single)?.unit?.messages?.any { it.id == regenTarget } == true
            if (streamingHere) {
                LiveReplyRow(
                    reply = live,
                    onDismissCut = onDismissCut,
                    thinkingOpen = liveThinkingOpen,
                    onToggleThinking = onToggleLiveThinking,
                    onOpenCall = onOpenRunningCall,
                    onOpenDoneCall = onOpenDoneCall,
                    finishedThinkingOpen = finishedThinkingOpen,
                    onToggleFinishedThinking = onToggleFinishedThinking,
                )
                return@itemsIndexed
            }
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
                slice = row.slice,
                onOpenTool = onOpenTool,
                liveThinkingOpen = liveThinkingOpen,
                onToggleLiveThinking = onToggleLiveThinking,
                onOpenRunningCall = onOpenRunningCall,
                onOpenDoneCall = onOpenDoneCall,
                finishedThinkingOpen = finishedThinkingOpen,
                onToggleFinishedThinking = onToggleFinishedThinking,
                maxBubble = maxBubble,
                gapAbove = gap,
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
        val streaming = if (inPlaceRegen) null else live
        // 重试与发送同一个画法：这一轮画在**底部**。目标那一条属于旧的那一轮，
        // 它已经整轮藏掉了（D263），再往它身上画就等于把旧的又放出来一半。
        // 「只换这一条」（D284）是例外：那份已经画在目标自己的位置上了，底部不再重复。
        if (streaming != null) {
            item(key = "live-assistant") {
                LiveReplyRow(
                    reply = streaming,
                    onDismissCut = onDismissCut,
                    thinkingOpen = liveThinkingOpen,
                    onToggleThinking = onToggleLiveThinking,
                    onOpenCall = onOpenRunningCall,
                    onOpenDoneCall = onOpenDoneCall,
                    finishedThinkingOpen = finishedThinkingOpen,
                    onToggleFinishedThinking = onToggleFinishedThinking,
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
    thinkingOpen: Boolean = false,
    onToggleThinking: () -> Unit = {},
    onOpenCall: (RunningCall) -> Unit = {},
    /** 点开一条跑完了、还没落库的调用（D261）。 */
    onOpenDoneCall: (DoneCall) -> Unit = {},
    /** 第 N 轮（已经收口的那几轮）的思考展开没有。每轮各记各的，不共用一个开关。 */
    finishedThinkingOpen: (Int) -> Boolean = { false },
    onToggleFinishedThinking: (Int) -> Unit = {},
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
        // 工具往返之后模型重新开口：上一轮那段话**留在这里**，不能因为换轮就消失
        // （用户 2026-11-04 报的"流式过程吞消息"）。落库之后这一段会并进同一条消息，
        // 所以屏幕上它也该一直在。
        // 每一轮 = **一个过程块 + 一段正文**，按发生顺序交替排开：
        // 块A → 正文 → 块B → 正文 → 思考块 → 正文 → 块C → 正文（用户 2026-11-04 给的结构）。
        // 以前这里把 `finished` 的思考画成一张独立的思考卡、正文排在它后面，
        // 于是"上一轮的正文"看起来像被并进思考里了。
        reply.finished.forEachIndexed { index, done ->
            LiveProcessCard(
                reasoning = done.reasoning,
                calls = emptyList(),
                thinkingOpen = finishedThinkingOpen(index),
                onToggleThinking = { onToggleFinishedThinking(index) },
                onOpenCall = {},
                live = false,
            )
            if (done.text.isNotBlank()) {
                MarkdownBody(
                    text = done.text,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
            }
        }
        LiveProcessCard(
            reasoning = reply.reasoning,
            calls = reply.calls,
            done = reply.done,
            thinkingOpen = thinkingOpen,
            onToggleThinking = onToggleThinking,
            onOpenCall = onOpenCall,
            onOpenDone = onOpenDoneCall,
        )
        if (reply.text.isNotBlank()) {
            MarkdownBody(
                text = reply.text,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
        }
        val note = when {
            reply.cut -> "这一轮没写完：上面是已经收到的部分（没存进库里，点一下收掉）"
            reply.regen && reply.text.isBlank() && reply.reasoning.isBlank() ->
                "正在重写这一版…（旧的留着，切得回去）"
            reply.regen -> "正在重写这一版…（旧的留着；停止键随时可用）"
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
    onOpenTool: (ChatUnit, ToolCallArg) -> Unit,
    /** 某一项的思考展开没有。键由调用方加前缀（`think:`），这里只管问、只管翻。 */
    thinkingOpen: (String) -> Boolean = { false },
    onToggleThinking: (String) -> Unit = {},
) {
    val running = group.items.any { it.running }
    val foldable = foldable(group.items) && !running
    val open = expanded && foldable
    val visible = visibleItems(group.items, open)
    val hidden = hiddenCount(group.items, open)

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
                    .padding(horizontal = 10.dp, vertical = 4.dp),
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
                visible.forEachIndexed { index, item ->
                    // 每项前面一个圆点节点（用户 2026-11-04 给的截图）：原来那条贯穿整块的竖线
                    // 又长又直，看着像表格边框；圆点加短连线才看得出"这是几步"。
                    ProcessNode(index = index, last = index == visible.lastIndex) {
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
                                onOpen = { call -> onOpenTool(item.unit, call) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 一次工具往返的那张卡（D262）：圆角底色 + 左侧一个节点。
 *
 * 折行之后工具那半行从 `ProcessGroupRow` 里拆出来了（正文要单独一行），
 * 拆出来就丢了那层壳——屏幕上只剩光秃秃几行。这里按同一形状补回去：
 * 同一轮多次调用仍然在**一张卡**里，一行一次调用。
 */
@Composable
private fun ToolCard(unit: ChatUnit, onOpen: (ToolCallArg) -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            ProcessNode(index = 0, last = true) {
                ToolProcessRow(unit = unit, onOpen = onOpen)
            }
        }
    }
}

/** 一个原子单元：`turn`（一条文本）或 `tool_pair`（assistant(tool_calls) + 全部 tool_result）。 *//** 一个原子单元：`turn`（一条文本）或 `tool_pair`（assistant(tool_calls) + 全部 tool_result）。 */
@Composable
private fun UnitBlock(
    unit: ChatUnit,
    /** 这一段是不是"这一轮助手的头一块"。连续的工具卡/正文只画一次抬头。 */
    showHeader: Boolean,
    /** 思考已经并进上面那个大块了：这里别再画一遍（见 `ProcessRows.buildRows`）。 */
    hideReasoning: Boolean = false,
    /** 这一行画单元的哪一半（D258 折行之后，工具那一轮里也有正文与思考）。 */
    slice: UnitSlice = UnitSlice.Whole,
    /** 单独一条工具单元：点开也是弹窗，不是就地展开。 */
    onOpenTool: (ChatUnit, ToolCallArg) -> Unit = { _, _ -> },
    /** 这一轮正在写的思考展开没有（0.15ak）。 */
    liveThinkingOpen: Boolean = false,
    onToggleLiveThinking: () -> Unit = {},
    /** 点开一条正在跑的调用（0.15ak）。 */
    onOpenRunningCall: (RunningCall) -> Unit = {},
    /** 点开一条**跑完了、还没落库**的调用（D261）：结果要等收尾才进库，这中间也得能看。 */
    onOpenDoneCall: (DoneCall) -> Unit = {},
    finishedThinkingOpen: (Int) -> Boolean = { false },
    onToggleFinishedThinking: (Int) -> Unit = {},
    maxBubble: Dp,
    gapAbove: Dp,
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
        if (unit.isToolPair && slice != UnitSlice.TextOnly) {
            // 旧版那个样子（D262）：圆角底色 + 左侧一个节点，工具行在里面。
            // 折行之后这半行单独成行，`ProcessGroupRow` 那层壳没跟过来，这里补上。
            ToolCard(unit = unit, onOpen = { call -> onOpenTool(unit, call) })
            // 纯工具那一轮没有正文，动作行没地方挂——但**版本控件不能跟着一起消失**：
            // 这一轮有 `<n/n>` 的话，用户得能切回去（D273）。
            val last = unit.messages.lastOrNull()
            if (slice == UnitSlice.ToolsOnly && last != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    // `VersionControl` 自己会判断"只有一版且没被改过"就不画。
                    VersionControl(message = last) { no, view -> onVersionMessage(last.id, no, view) }
                }
            }
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
                            liveThinkingOpen = liveThinkingOpen,
                            onToggleLiveThinking = onToggleLiveThinking,
                            onOpenRunningCall = onOpenRunningCall,
                            onOpenDoneCall = onOpenDoneCall,
                            finishedThinkingOpen = finishedThinkingOpen,
                            onToggleFinishedThinking = onToggleFinishedThinking,
                            // 动作行跟着**正文**走（D273）：折行之后正文单独一行，
                            // 它就是这一条消息的正文，复制 / 更多 / 版本都该在这里。
                            // 只有"纯工具那半行"（ToolsOnly）不画——那里没有正文可复制。
                            showActions = slice != UnitSlice.ToolsOnly,
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

/** 一轮最多带几张图（多选之后按总数算，不是按"这一次挑了几张"）。 */
private const val MAX_PENDING_IMAGES = 4
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
    // 「发送中」的呼吸：它是"还在路上"的唯一信号，静态一行字看着像已经存好了。
    val pulse = rememberInfiniteTransition(label = "sending")
    val sendingAlpha by pulse.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 900), RepeatMode.Reverse),
        label = "sending-alpha",
    )
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
                    if (sending) {
                        // 「发送中」属于**这条消息**，所以画在气泡里、跟着气泡右对齐，
                        // 不是气泡外面飘一行灰字（用户 2026-11-04：「这个"发送中"谁教你的，渲染出来啊」）。
                        Text(
                            text = "发送中…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = sendingAlpha),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            textAlign = TextAlign.End,
                        )
                    }
                }
            }
        }

        // 动作行在"发送中"没有意义：这条还没 id，复制/更多都指不到库里的东西。
        if (!sending) {
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
    /** 这一轮正在写的思考展开没有（0.15ak：和落库那条共用一份展开状态）。 */
    liveThinkingOpen: Boolean = false,
    onToggleLiveThinking: () -> Unit = {},
    /** 点开一条正在跑的调用（弹窗底部才有「取消这次调用」）。 */
    onOpenRunningCall: (RunningCall) -> Unit = {},
    /** 点开一条跑完了、还没落库的调用（D261）。 */
    onOpenDoneCall: (DoneCall) -> Unit = {},
    /** 第 N 轮（已经收口的）思考展开没有。 */
    finishedThinkingOpen: (Int) -> Boolean = { false },
    onToggleFinishedThinking: (Int) -> Unit = {},
    /**
     * 动作行画不画在这一条里（D262）。折行之后一轮有正文也有工具：
     * 正文那半行不画（动作行收在整个块的最后），整条消息只有正文时才在这里画。
     */
    showActions: Boolean = true,
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
        // 空正文什么都不画（2026-11-06 用户："（空消息）"不该出现）。
        // 工具那一轮的正文本来就是空的——它只有思考与工具，那两样已经画在上面了。
        if (message.content.isNotBlank()) {
            MarkdownBody(
                text = message.content,
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
        }
        if (message.incomplete) {
            Text(
                text = "未完成 · 这一轮被中断",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (showActions) {
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
/** 重试一条旧消息、而设置是「删掉后面的」时的那一问（D284）。 */
private data class RegenAsk(
    val sessionId: String,
    val upTo: String,
    val target: String?,
    /** 这条之后还有几条消息会一起没掉。 */
    val later: Int,
)

private data class RegenFailure(
    val messageId: String,
    val text: String,
    val reasoning: String,
    val reason: String,
)

/** 失败三选一里选中的那一项。 */
private enum class KeepMode { Previous, This, Both }

/**
 * 手动停止之后要问的那一份东西。
 *
 * 和 [RegenFailure] 的区别：这一版**已经落库了**（core 在停止时照样收尾落版本），
 * 所以"回到上一版"是删掉刚落的那一版，不是"这次收到的内容不存"。
 */
private data class StoppedTurn(
    val messageId: String,
    val length: Int,
)

/**
 * 停止之后问一句（D273）：留哪一版。
 *
 * 只在"允许生成新版本"关着时弹——开着的话两版本来就都留着，没什么可问的。
 * 三项和失败三选一同形：保留上一版 / 保留这一版 / 都保留。
 *
 * 默认「保留上一版」：停止留下的是半截，回到能看的那一版是最常想做的事（D228 同一条）。
 */
@Composable
private fun StopPromptDialog(
    stopped: StoppedTurn,
    onKeepPrevious: () -> Unit,
    onKeepThis: () -> Unit,
    onKeepBoth: () -> Unit,
) {
    var mode by remember(stopped) { mutableStateOf(KeepMode.Previous) }
    Box(modifier = Modifier.fillMaxSize()) {
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
                        text = "已停止",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "你按了停止，这一轮写到一半就收了尾。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Text(
                        text = "这一版已经存进库里（正文 ${stopped.length} 字，工具与思考也一起），上一版还在。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                    KeepChoice(
                        title = "保留上一版",
                        note = "回到停止之前那一版，这次写到的内容不留。",
                        selected = mode == KeepMode.Previous,
                        enabled = true,
                        onPick = { mode = KeepMode.Previous },
                    )
                    KeepChoice(
                        title = "保留这一版",
                        note = "只留这次写到的（${stopped.length} 字正文，工具与思考也在里面），上一版删掉。",
                        selected = mode == KeepMode.This,
                        enabled = true,
                        onPick = { mode = KeepMode.This },
                    )
                    KeepChoice(
                        title = "都保留",
                        note = "两版都留着，当前显示这一版，可以切回上一版。",
                        selected = mode == KeepMode.Both,
                        enabled = true,
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
private fun ToolProcessRow(unit: ChatUnit, onOpen: (ToolCallArg) -> Unit) {
    val calls = unit.messages.flatMap { it.toolArgs }

    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) {
        // **一次调用一行、一行一个弹窗**（D261）：点哪一行就只看那一次调用。
        // 以前点开的是整个单元，两条调用铺在同一个弹窗里，分不清自己点的是哪条。
        if (calls.isEmpty()) {
            ToolCallLine(name = "工具调用", summary = "", preview = "")
        } else {
            calls.forEachIndexed { index, call ->
                // 两次调用之间留一条线：挨着排的时候看着像一次调用写了两行（D261）。
                if (index > 0) {
                    HorizontalDivider(
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        modifier = Modifier.padding(start = 24.dp, top = 2.dp, bottom = 2.dp),
                    )
                }
                val summary = toolSummaryOf(call)
                ToolCallLine(
                    name = call.name.takeIf { it.isNotBlank() }?.let { toolDisplayName(it) } ?: "工具调用",
                    summary = summary.text,
                    preview = toolArgumentPreview(call.arguments),
                    icon = toolIcon(call.name),
                    failed = summary.failed,
                    onClick = { onOpen(call) },
                )
            }
        }
    }
}

/** 一行的摘要 + 它该不该标红。 */
private data class ToolSummary(val text: String, val failed: Boolean)

/**
 * 一行的摘要：从结果 JSON 里取 `exit_code` / `duration_ms`，拿不到就写"结果 N 字"。
 *
 * `failed` 的判据是**退出码非 0**（用户 2026-11-06："状态码非 0，这个块的 exit 连着数字都显示红色，
 * 这样比较直观能看出来哪个是正常退出"）。注意它跟 `status` 不是一回事——shell 跑完就是 `ok`，
 * 退出码 1 只是命令自己这么退的；那一行照样该红，因为用户要一眼分出正常/不正常。
 */
private fun toolSummaryOf(call: ToolCallArg): ToolSummary {
    if (call.status == "pending") return ToolSummary("还没结果", false)
    if (call.status == "killed") return ToolSummary("没跑", true)
    val text = call.output.trim()
    if (text.isEmpty()) return ToolSummary("（空）", false)
    val exit = Regex("\"exit_code\"\\s*:\\s*(-?\\d+)").find(text)?.groupValues?.get(1)
    val millis = Regex("\"duration_ms\"\\s*:\\s*(\\d+)").find(text)?.groupValues?.get(1)
    val failed = call.status == "error" || (exit != null && exit != "0")
    if (exit != null && millis != null) return ToolSummary("exit $exit · ${millis}ms", failed)
    if (exit != null) return ToolSummary("exit $exit", failed)
    // 查看图像：它没有退出码，写"N 字"等于什么都没说（2026-11-06 用户提的）。
    val at = Regex("\"in_context\"\\s*:\\s*(\\d+)").find(text)?.groupValues?.get(1)
    val limit = Regex("\"limit\"\\s*:\\s*(\\d+)").find(text)?.groupValues?.get(1)
    if (at != null && limit != null) return ToolSummary("第 $at/$limit 张", false)
    return ToolSummary("${text.length} 字", failed)
}

/** 工具行的图标：图像类的不该挂一把扳手（图标是扫读锚点，不是装饰）。 */
private fun toolIcon(name: String): androidx.compose.ui.graphics.vector.ImageVector =
    if (name == "View-Image") ActionIcons.Image else ActionIcons.Wrench

/** 过程块里的一行：扳手 + 名字 + 结果摘要 + chevron，下面一行参数预览。 */
@Composable
private fun ToolCallLine(
    name: String,
    summary: String,
    preview: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector = ActionIcons.Wrench,
    failed: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
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
                    color = if (failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
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

/**
 * 工具名在界面上写什么。
 *
 * 工具名是给端点看的（OpenAI / Anthropic / Gemini 的函数名规则），界面上不该让人读
 * `WorkSpace-EnvirShell`——用户 2026-11-04 给的参考图里那一行写的是「运行命令」。
 * 认不出的名字原样显示：编一个中文名比显示原名更糟。
 */
private fun toolDisplayName(name: String): String = when (name) {
    "System-Shell" -> "运行命令"
    "WorkSpace-EnvirShell" -> "运行命令（容器）"
    "View-Image" -> "查看图像"
    "read_file" -> "读取文件"
    "write_file" -> "写入文件"
    "edit_file" -> "修改文件"
    "list_dir" -> "列出目录"
    "find_files" -> "查找文件"
    "search" -> "搜索内容"
    else -> name
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
    // 思考单独成单元时 `content` 就是思考；跟着正文一起进来时它在 `reasoning` 里。
    // 以前一律读 `content`，于是"深度思考 · N 字"数的是正文——两类消息混在一张卡里时看不出来。
    val body = message.reasoning?.takeIf { it.isNotBlank() } ?: message.content
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
                text = "深度思考 · ${body.length} 字",
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
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, bottom = 6.dp),
            )
        }
    }
}

/**
 * 一次调用的详情弹窗（D243 / D261）：**初始最高半屏**，里面自己滚。
 *
 * 为什么半屏而不是整屏：它是"看一眼这次调用干了什么"，看完就收；
 * 整屏会让人以为换了一页，还得再找回去。
 *
 * 为什么是"一次"而不是"一轮"：点哪一行就该看哪一行。两条调用铺在同一个弹窗里，
 * 用户分不清自己点的是哪一条（2026-11-05 真机报的"两个调用粘一块了"）。
 */
@Composable
private fun ToolDetailSheet(target: ToolSheetTarget, onCancel: () -> Unit, onDismiss: () -> Unit) {
    val call = target.call
    val screen = LocalConfiguration.current.screenHeightDp
    val maxHeight = (screen * 0.5f).dp
    SheetFrame(title = call.name.takeIf { it.isNotBlank() }?.let { toolDisplayName(it) } ?: "工具调用", onDismiss = onDismiss) {
        val scroll = rememberScrollState()
        // 边跑边跟（D264）：输出在长的时候停在最底下，否则用户看到的是三分钟前的第一行。
        LaunchedEffect(
            target.live?.stdout?.length ?: 0,
            target.live?.stderr?.length ?: 0,
            target.running,
        ) {
            if (target.running && scroll.canScrollForward) scroll.scrollTo(scroll.maxValue)
        }
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeight)
                    .verticalScroll(scroll)
                    .padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
            ) {
                if (target.running) {
                    Column {
                        Text(
                            text = "正在运行 · ${elapsedLabel(target.elapsedMs)}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = "参数",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                        )
                        MonoBox(text = call.arguments.ifEmpty { "（空）" })
                        // 实时输出也分两页（用户 2026-11-06："有些输出是抛出的错误输出"）。
                        // 默认页签的规则与结果那份一致：stdout 空、stderr 有东西就停在 stderr。
                        val out = target.live?.stdout.orEmpty()
                        val err = target.live?.stderr.orEmpty()
                        var pickedTab by remember(target.callId) { mutableStateOf<Int?>(null) }
                        val tab = pickedTab ?: if (out.isEmpty() && err.isNotBlank()) 1 else 0
                        Text(
                            text = "实时输出",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                        )
                        Row(modifier = Modifier.padding(bottom = 4.dp)) {
                            TabChip("stdout", selected = tab == 0) { pickedTab = 0 }
                            Spacer(Modifier.width(6.dp))
                            TabChip("stderr", selected = tab == 1) { pickedTab = 1 }
                        }
                        val body = if (tab == 0) out else err
                        val cut = if (tab == 0) {
                            target.live?.stdoutCut == true
                        } else {
                            target.live?.stderrCut == true
                        }
                        if (body.isEmpty()) {
                            Text(
                                text = "（还没有输出）",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            MonoBox(
                                text = (if (cut) "…（只留了最近的 8 KiB）\n" else "") + body,
                            )
                        }
                        Text(
                            text = "结果回来之后，这一块会换成库里那次调用（带退出码与输出）。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    }
                } else {
                    ToolCallBlock(command = call, result = call)
                }
            }
            if (target.running) {
                Text(
                    text = "取消这次调用",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable(onClick = onCancel)
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                )
            }
        }
    }
}

/** 弹窗要画的那一次调用：库里的、正在跑的、跑完还没落库的，都是这一份形状。 */
private data class ToolSheetTarget(
    /** 按它现查：跑着的、跑完的、库里的，三处都认这一个键。 */
    val callId: String,
    val call: ToolCallArg,
    val running: Boolean = false,
    val elapsedMs: Int = 0,
    /** 跑着的那一份（实时 stdout / stderr 在它身上）。 */
    val live: RunningCall? = null,
)

/**
 * 弹窗这一刻该画什么（D264）。**现查**，不用点开那一刻的快照。
 *
 * 顺序就是事实的顺序：还在跑（带实时输出）→ 这一轮跑完了（还没落库）→ 库里那一份。
 * 三处都没有（比如整轮失败、这一条没跑）就退回点开时那一份。
 */
private fun resolveToolSheet(
    opened: ToolSheetTarget,
    running: List<RunningCall>?,
    done: List<DoneCall>?,
    messages: List<ChatMessage>,
): ToolSheetTarget {
    val id = opened.callId
    if (id.isEmpty()) return opened
    running?.firstOrNull { it.callId == id }?.let { call ->
        return opened.copy(
            call = ToolCallArg(
                callId = id,
                part = "",
                name = call.name,
                arguments = call.arguments,
                output = "",
                status = "running",
            ),
            running = true,
            elapsedMs = call.elapsedMs,
            live = call,
        )
    }
    done?.firstOrNull { it.callId == id }?.let { call ->
        return opened.copy(
            call = ToolCallArg(
                callId = id,
                part = "",
                name = call.name,
                arguments = call.arguments,
                output = call.output,
                status = if (call.ok) "ok" else "error",
            ),
            running = false,
        )
    }
    messages.asSequence()
        .flatMap { it.toolArgs.asSequence() }
        .firstOrNull { it.callId == id }
        ?.let { stored -> return opened.copy(call = stored, running = false) }
    return opened
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
    /** 已经请求停止、这一轮还没真的收尾：按钮灰着，别让他连点（2026-11-06 用户）。 */
    stopping: Boolean = false,
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
    /** 「工具」面板（D271）：这一条会话绑哪个工作区。 */
    onTools: () -> Unit = {},
    /** 「＋」面板：附件入口。 */
    onPlus: () -> Unit,
    /** `false` = 这个模型明确不收图（灰掉「图片」那一项并给原因）。 */
    imageCapability: Boolean?,
    /** 这一轮上下文里已经有几张图（D248）：`View-Image` 拉进来的那些。 */
    contextImageCount: Int = 0,
    contextImageLimit: Int = 12,
    onOpenContextImages: () -> Unit = {},
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
                // 上下文里已经有图（D248）：一行小字 + 入口。没有就不摆——
                // 常驻一行"0 张"等于让人以为哪里坏了。
                if (contextImageCount > 0) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onOpenContextImages)
                            .padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = ActionIcons.Image,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            text = "图片上下文 $contextImageCount/$contextImageLimit",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f).padding(start = 6.dp),
                        )
                        Icon(
                            imageVector = ActionIcons.ChevronRight,
                            contentDescription = "查看这一轮的图片上下文",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(14.dp),
                        )
                    }
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
                        description = "工具",
                        onClick = onTools,
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
                        stopping = stopping,
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
    stopping: Boolean = false,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onQueue: () -> Unit,
    queued: Int,
    onSaveAssistant: () -> Unit,
    onSaveUser: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val active = enabled && !busy
    // 已经请求停止：灰着，直到这一轮真的收尾（`busy` 变 false）。
    // 停止是"停在这一轮之后"，不是立刻断——这段窗口里再点几下都只是重复请求同一次取消。
    val background = when {
        stopping -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.30f)
        active || busy -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.30f)
    }

    Box {
        Box(
            modifier = Modifier
                .size(44.dp)
                .pointerInput(active, busy, stopping) {
                    detectTapGestures(
                        onTap = {
                            when {
                                stopping -> Unit
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
                        contentDescription = when {
                            stopping -> "正在停止"
                            busy -> "停止"
                            else -> "发送"
                        },
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
private fun ToolCallBlock(command: ToolCallArg, result: ToolCallArg?) {
    val context = LocalContext.current
    val payload = result?.output?.let { text ->
        runCatching { JSONObject(text) }.getOrNull()?.takeIf { it.has("stdout") || it.has("stderr") }
    }
    val shown = if (payload != null) {
        payload.optString("command", "")
    } else {
        runCatching { JSONObject(command.arguments).optString("command", "") }.getOrNull().orEmpty()
    }
    val label = if (command.name == "System-Shell") "运行命令" else command.name
    // 用「选过没有」+ 默认页签，而不是在组合过程里改状态：组合里赋值会多走一轮重组。
    var picked by remember(result?.part) { mutableStateOf<Int?>(null) }

    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (result == null || result.status == "pending" || result.status == "killed") {
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
                MonoBox(text = result.output.ifEmpty { "（空）" })
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
                    arguments = row.text("arguments"),
                    stdout = row.text("stdout"),
                    stderr = row.text("stderr"),
                    stdoutCut = row.optBoolean("stdout_cut", false),
                    stderrCut = row.optBoolean("stderr_cut", false),
                ),
            )
        }
    }
}

/** `turn_busy.done` → 这一轮跑完的那些（D251）。 */
private fun doneCallsOf(info: JSONObject): List<DoneCall> {
    val array = info.optJSONArray("done") ?: return emptyList()
    return buildList {
        for (index in 0 until array.length()) {
            val row = array.optJSONObject(index) ?: continue
            val name = row.text("name")
            if (name.isEmpty()) continue
            add(
                DoneCall(
                    callId = row.text("call_id"),
                    name = name,
                    arguments = row.text("arguments"),
                    output = row.text("output"),
                    ok = row.optBoolean("ok", true),
                    summary = row.text("summary"),
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
 * 过程块里的一项：左边一个**圆点节点**加一段连接线，右边是内容。
 *
 * 换掉了原来那条贯穿整块、从头画到尾的竖线（用户 2026-11-04：「大块左边的竖线就没必要画了，
 * 你画的有点丑，没有它这种节点」）。现在的样子是照他给的截图：每项前面一个点，
 * 点与点之间一段线，最后一项下面不拖尾巴。
 *
 * 线用 `drawBehind` 画而不是摆 `Box`：一项的高度可能是两行（标题 + 命令预览），
 * 也可能展开成几十行（思考），用布局去拼"从顶到点、从点到尾"两段要量高度，画反而简单。
 */
@Composable
private fun ProcessNode(
    index: Int,
    last: Boolean,
    content: @Composable () -> Unit,
) {
    val line = MaterialTheme.colorScheme.outlineVariant
    Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(
            modifier = Modifier
                .width(18.dp)
                .fillMaxHeight()
                .drawBehind {
                    val x = size.width / 2f
                    // 点对着**标题那一行**的中间，不是整项的正中：展开的思考有几十行，
                    // 点落在正中间会跑到正文里去。
                    val y = 14.dp.toPx()
                    val stroke = 1.dp.toPx()
                    if (index > 0) {
                        drawLine(line, Offset(x, 0f), Offset(x, y), stroke)
                    }
                    if (!last) {
                        drawLine(line, Offset(x, y), Offset(x, size.height), stroke)
                    }
                    drawCircle(line, radius = 2.5.dp.toPx(), center = Offset(x, y))
                },
        )
        Box(modifier = Modifier.weight(1f)) { content() }
    }
}

/**
 * 这一轮正在长的过程块：思考 + 正在跑的工具调用，**一个块**（0.15ak）。
 *
 * 形状照用户 2026-11-04 给的截图：一块圆角卡，里面每项前面一个圆点节点，
 * 思考一项、每条工具调用一项。工具那一行点开是半屏弹窗，弹窗底部才有「取消这次调用」——
 * 挂在聊天页里一行「关闭」不是他要的样子。
 *
 * 为什么它属于"正在生成"这一块：这些调用还没落库（D229），收尾后整块被库里那份替掉。
 */
@Composable
private fun LiveProcessCard(
    reasoning: String,
    calls: List<RunningCall>,
    thinkingOpen: Boolean,
    onToggleThinking: () -> Unit,
    onOpenCall: (RunningCall) -> Unit,
    /** 这一轮已经跑完的调用（D251）：跑完就消失的话，屏幕上只剩"模型在思考"。 */
    done: List<DoneCall> = emptyList(),
    /** 点开一条跑完了、还没落库的调用（D261）。 */
    onOpenDone: (DoneCall) -> Unit = {},
    /** 这一块是**当前这一轮**还是已经收口的那几轮（决定思考那行写不写"还在写"）。 */
    live: Boolean = true,
) {
    if (reasoning.isBlank() && calls.isEmpty() && done.isEmpty()) return
    val total = (if (reasoning.isNotBlank()) 1 else 0) + done.size + calls.size
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)) {
            var index = 0
            if (reasoning.isNotBlank()) {
                ProcessNode(index = index, last = index == total - 1) {
                    LiveThinkingRow(
                        text = reasoning,
                        expanded = thinkingOpen,
                        onToggle = onToggleThinking,
                        live = live,
                    )
                }
                index += 1
            }
            done.forEach { call ->
                ProcessNode(index = index, last = index == total - 1) {
                    DoneCallRow(call = call, onOpen = { onOpenDone(call) })
                }
                index += 1
            }
            calls.forEach { call ->
                ProcessNode(index = index, last = index == total - 1) {
                    RunningCallRow(call = call, onOpen = { onOpenCall(call) })
                }
                index += 1
            }
        }
    }
}

/** 正在写的思考：一行标题 + 展开（和落库那条同一个形状，只是标题写「还在写」）。 */
@Composable
private fun LiveThinkingRow(
    text: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    /** `false` = 这一轮已经收口，标题不写"还在写"。 */
    live: Boolean = true,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = ActionIcons.Gauge,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = if (live) {
                    "深度思考 · ${text.length} 字（还在写）"
                } else {
                    "深度思考 · ${text.length} 字"
                },
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
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, bottom = 8.dp),
            )
        }
    }
}

/**
 * 这一轮**已经跑完**的一条调用（D251）：扳手 + 名字 + 摘要（`exit 0 · 121ms`）。
 *
 * 不可点：它的详情要等整轮收尾落库之后才有（那一条会带完整的参数与输出）。
 * 这一行只回答"刚才那一串到底跑了什么"。
 */
@Composable
private fun DoneCallRow(call: DoneCall, onOpen: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = ActionIcons.Wrench,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = toolDisplayName(call.name),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
        )
        if (call.summary.isNotEmpty()) {
            // 跑完还没落库的这一行：摘要里的退出码非 0 也标红（与库里那份同一套判据）。
            val exit = Regex("exit (-?\\d+)").find(call.summary)?.groupValues?.get(1)
            val failed = !call.ok || (exit != null && exit != "0")
            Text(
                text = call.summary,
                style = MaterialTheme.typography.labelSmall,
                color = if (failed) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

/**
 * 正在跑的一条调用：一行 + 命令预览，点开是弹窗。
 *
 * 形状照落库后的 `ToolProcessRow`（同一个扳手、同一个 chevron、同样一行 mono 预览）——
 * 收尾换成库里那份时不跳。秒数在涨，那是"它真的在跑"的唯一证据。
 */
@Composable
private fun RunningCallRow(call: RunningCall, onOpen: () -> Unit) {
    val preview = toolArgumentPreview(call.arguments)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = ActionIcons.Wrench,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = toolDisplayName(call.name),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
            Text(
                text = if (call.cancelled) "正在关闭" else elapsedLabel(call.elapsedMs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Icon(
                imageVector = ActionIcons.ChevronRight,
                contentDescription = "查看这次调用",
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

/** 一组图的总大小：`1.2 MB` / `860 KB`。整数 KB 就够，这里不是账本。 */
private fun imageBytesLabel(bytes: Long): String {
    val total = bytes
    return when {
        total < 1024 -> "$total B"
        total < 1024 * 1024 -> "${total / 1024} KB"
        else -> String.format(Locale.US, "%.1f MB", total / 1024.0 / 1024.0)
    }
}

/** 上下文里的一张图（D248）。只从 native 那份清单来，界面不自己数。 */
internal data class ContextImageRow(val id: String, val mime: String, val bytes: Long)

/** `nmoi_images_json` 的信封 → 清单。 */
private fun parseContextImages(info: JSONObject): List<ContextImageRow> {
    val array = info.optJSONArray("images") ?: return emptyList()
    return buildList {
        for (index in 0 until array.length()) {
            val row = array.optJSONObject(index) ?: continue
            val id = row.text("id")
            if (id.isEmpty()) continue
            add(ContextImageRow(id = id, mime = row.text("mime"), bytes = row.optLong("bytes", 0)))
        }
    }
}

/**
 * 图片上下文（D248）：这一轮会发出去的图，一张一行。
 *
 * 为什么它不在消息里：这些图是 `View-Image` **拉进上下文**的，不属于任何一条消息——
 * 原图还在它原来那条消息里，这一层只管"这一轮要发哪几张"。
 *
 * 能做的三件事：看（缩略图 / 格式 / 大小 / 附件 id）、移出一张、清空。
 * 移出**不删文件**，也不改消息：下一轮不带它而已。
 */
/**
 * 「工具」面板（D271）：**这一条会话**绑哪个工作区。
 *
 * 为什么入口在这里：工作区决定"这一轮的工具根在哪"，它跟工具是同一件事；
 * 而它又是**会话的属性**（不是全局设置），所以放在聊天页的工具按钮里，
 * 不放进设置页——放进设置页，用户改一次会以为全变了。
 *
 * 与助手那一份的关系：助手绑一个（默认），这条会话可以覆盖它。`null` = 跟助手。
 */
@Composable
private fun ToolSheet(
    sessionId: String?,
    assistantWorkspaceId: String?,
    onDismiss: () -> Unit,
    onNotice: (String) -> Unit,
) {
    val context = LocalContext.current
    val databasePath = remember(context) { ProviderRepository.databasePath(context) }
    val scope = rememberCoroutineScope()
    // `null` = 跟助手。**先当"读到了空"**，`loaded` 才决定要不要显示"读…"——
    // 一上来就画"跟随助手"会让人以为这就是库里的答案。
    var bound by remember(sessionId) { mutableStateOf<String?>(null) }
    var loaded by remember(sessionId) { mutableStateOf(sessionId == null) }
    var picking by remember(sessionId) { mutableStateOf(false) }
    val workspaces = remember { WorkspaceRepository.list(context) }
    val assistantName = remember(assistantWorkspaceId) {
        WorkspaceRepository.resolve(context, assistantWorkspaceId).name
    }
    LaunchedEffect(sessionId) {
        val sid = sessionId ?: return@LaunchedEffect
        bound = withContext(Dispatchers.IO) {
            ConversationRepository.workspaceOf(databasePath, sid)
        }
        loaded = true
    }
    val current = bound?.let { id -> workspaces.firstOrNull { it.id == id } }
    val label = when {
        sessionId == null -> "这条会话还没落库"
        !loaded -> "读…"
        bound == null -> "跟随助手 · $assistantName"
        current == null -> "独立 · 那个工作区不在了"
        else -> "独立 · ${current.name}"
    }
    SheetFrame(title = "工具", onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.5f).dp)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
        ) {
            if (!picking) {
                Text(
                    text = "这一轮的工具根",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clickable(enabled = sessionId != null && loaded) { picking = true }
                        .padding(top = 4.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "该会话独立绑定工作区",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (sessionId == null) {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    Icon(
                        imageVector = ActionIcons.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Text(
                    text = "换工作区不搬文件：老文件留在原处，新的写到新的那个。" +
                        "绑了哪个，这一轮的读写与命令就落在哪个目录里。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "原生命令 / 容器命令是**全局**开关，在 设置 → 工具与工作区 里改。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
            } else {
                Text(
                    text = "这条会话用哪个工作区",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                WorkspaceChoice(
                    title = "跟随助手",
                    note = "现在跟的是「$assistantName」；助手换了它就跟着换。",
                    selected = bound == null,
                    onClick = {
                        val sid = sessionId ?: return@WorkspaceChoice
                        scope.launch {
                            ConversationRepository.setWorkspace(databasePath, sid, null).fold(
                                onSuccess = {
                                    bound = null
                                    picking = false
                                    onNotice("这条会话改回跟随助手：${assistantName}")
                                },
                                onFailure = { onNotice("改回跟随助手失败：${it.message}") },
                            )
                        }
                    },
                )
                workspaces.forEach { workspace ->
                    WorkspaceChoice(
                        title = workspace.name + if (workspace.isDefault) "（默认）" else "",
                        note = "${workspace.files} 个文件 · ${formatBytes(workspace.bytes)}",
                        selected = bound == workspace.id,
                        onClick = {
                            val sid = sessionId ?: return@WorkspaceChoice
                            scope.launch {
                                ConversationRepository.setWorkspace(databasePath, sid, workspace.id)
                                    .fold(
                                        onSuccess = {
                                            bound = workspace.id
                                            picking = false
                                            onNotice("这条会话独立绑到「${workspace.name}」：老文件不搬")
                                        },
                                        onFailure = { onNotice("绑定失败：${it.message}") },
                                    )
                            }
                        },
                    )
                }
            }
        }
    }
}

/** 工作区选择里的一行：标题 + 一句说明 + 选中打勾。 */
@Composable
private fun WorkspaceChoice(
    title: String,
    note: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClick = onClick)
            .padding(top = 4.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = note,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (selected) {
            Icon(
                imageVector = ActionIcons.Check,
                contentDescription = "当前",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun ContextImagesSheet(
    images: List<ContextImageRow>,
    limit: Int,
    onRemove: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val databasePath = remember(context) { ProviderRepository.databasePath(context) }
    val screen = LocalConfiguration.current.screenHeightDp
    val maxHeight = (screen * 0.5f).dp
    val loaded by produceState(initialValue = null as List<Pair<ContextImageRow, android.graphics.Bitmap?>>?, images) {
        value = withContext(Dispatchers.IO) {
            images.map { image -> image to AttachmentStore.bitmap(databasePath, image.id) }
        }
    }
    SheetFrame(title = "图片上下文", onDismiss = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, bottom = 8.dp),
            ) {
                Column {
                    Text(
                        text = "这一轮发送 ${images.size} / $limit 张 · 新的在前，满了先丢最旧的",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (images.isEmpty()) {
                        Text(
                            text = "这一轮还没有图片。让模型用「查看图像」把图加进来，" +
                                "或者直接在聊天页发一张。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                    val rows = loaded
                    if (rows == null && images.isNotEmpty()) {
                        Text(
                            text = "读图…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                    rows?.forEach { (image, bitmap) ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (bitmap != null) {
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = "上下文里的图片",
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier
                                        .size(width = 56.dp, height = 56.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(width = 56.dp, height = 56.dp)
                                        .background(
                                            MaterialTheme.colorScheme.surfaceContainerHigh,
                                            RoundedCornerShape(8.dp),
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = "读不出",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(
                                    text = "${imageBytesLabel(image.bytes)} · ${image.mime.ifBlank { "格式不明" }}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = image.id,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                            Text(
                                text = "移出",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .clickable { onRemove(image.id) }
                                    .padding(horizontal = 10.dp, vertical = 14.dp),
                            )
                        }
                    }
                    Text(
                        text = "移出只是下一轮不再带它：原图还在那条消息里，文件也没删。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 14.dp),
                    )
                }
            }
            if (images.isNotEmpty()) {
                Text(
                    text = "清空这一轮的图片上下文",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable(onClick = onClear)
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                )
            }
        }
    }
}
