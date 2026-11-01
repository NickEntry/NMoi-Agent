package com.nmoi.agent.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 模型服务：列表 → 编辑（配置 / 模型两张）→ 保存（D157）。
 *
 * 形状上是照别的客户端那套"供应商列表 + 供应商详情"来的，但**不做**它们那些按钮：
 *
 * - **没有「当前 / 设为当前」**（D161）：「用哪一套」归助手，供应商页只是目录。
 *   这里只有一个"默认模型"标记——新建助手时拿它当初始值。
 * - 没有「获取账户余额」「网络代理」「多 Key 模式」「自定义 Header」：这些在本项目里
 *   一条代码都没有读它们。要做也是先把那条路接上，再放控件。
 * - 没有「API 路径」：路径由 `kind` 决定（`crates/core/src/transport.rs`），
 *   让用户填一个不生效的路径比不填更坏。
 * - 模型只有四样"真会被读"的东西：**类型**（`chat` / `embedding` / `image`，只决定它出现在哪类
 *   选择器里）、上下文窗口（压缩判据与占用百分比）、最大输出、温度（后两项随请求发出去）。
 *   **没有**「定价 / 输入输出模态 / 能力位 / 推理方言」：这些字段现在一条代码都不读，
 *   摆上来就是"存了不生效"（用户最反感的那类，D191）。
 *
 * 密钥**从不回显**：只显示打码尾巴，输入框永远从空开始，留空就是不换。
 */
@Composable
internal fun ProviderListScreen(
    onBack: () -> Unit,
    onCreate: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val context = LocalContext.current
    val databasePath = remember { ProviderRepository.databasePath(context) }
    var page by remember { mutableStateOf<ProviderList?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { ProviderRepository.list(databasePath) }.fold(
            onSuccess = { page = it },
            onFailure = { notice = "读配置失败：${it.message}" },
        )
    }

    val rows = page?.rows.orEmpty()
    val shown = if (query.isBlank()) {
        rows
    } else {
        val needle = query.trim()
        rows.filter {
            it.name.contains(needle, ignoreCase = true) ||
                it.baseUrl.contains(needle, ignoreCase = true) ||
                ProviderKinds.label(it.kind).contains(needle, ignoreCase = true)
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            ScreenTopBar(title = "供应商", onBack = onBack) {
                IconActionButton(
                    image = ActionIcons.Plus,
                    description = "新建供应商",
                    onClick = onCreate,
                )
            }

            if (rows.isNotEmpty()) {
                SearchRow(
                    value = query,
                    onChange = { query = it },
                    placeholder = "搜索供应商或端点",
                )
            }

            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                when {
                    page == null && notice == null -> Hint("正在读库…")
                    rows.isEmpty() -> EmptyProviders(onCreate = onCreate)
                    shown.isEmpty() -> Hint("没有匹配「${query.trim()}」的供应商")
                    else -> GroupCard {
                        shown.forEachIndexed { index, entry ->
                            if (index > 0) {
                                RowDivider(leading = 52.dp)
                            }
                            ProviderRow(entry = entry, onClick = { onOpen(entry.id) })
                        }
                    }
                }

                val line = notice
                if (line != null) {
                    Text(
                        text = line,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
                Text(
                    text = "这里只是目录：有什么端点、有什么模型。用哪一个由助手决定，" +
                        "在聊天页顶部那条切换。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
                )
            }
        }
    }
}

/**
 * 供应商编辑页。`providerId == null` = 新建。
 *
 * 两张页签共用**一个保存按钮**：字段是表单，改一半就落库会留下"半份配置"。
 * 「用哪一个」不在这里改：那是助手的配置（聊天页顶部那条）。
 */
@Composable
internal fun ProviderEditScreen(
    providerId: String?,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val databasePath = remember { ProviderRepository.databasePath(context) }
    // 新建时立刻定下 id：保存两次也只会有一份配置（key 稳定）。
    val id = remember(providerId) { providerId ?: ProviderRepository.newId() }

    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(ProviderKinds.DEFAULT) }
    var baseUrl by remember { mutableStateOf("") }
    var models by remember { mutableStateOf(listOf<ProviderModel>()) }
    var activeModel by remember { mutableStateOf("") }
    /** 每个模型每个字段的来源（D201）：只用来在界面上挂徽标，落盘由仓库那边再算一遍。 */
    var sources by remember { mutableStateOf(emptyMap<String, Map<String, FieldSource>>()) }
    var keyDraft by remember { mutableStateOf("") }
    var keyVisible by remember { mutableStateOf(false) }
    var keyHint by remember { mutableStateOf<String?>(null) }
    var keyOnDisk by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var modelDraft by remember { mutableStateOf<ModelDraft?>(null) }
    var resourceOpen by remember { mutableStateOf(false) }
    /**
     * 资源更新页写进来、**还没保存**的那些字段：`模型 → 字段 → 写进去的值`。
     *
     * 它跟着「保存」一起去落库（`ProviderRepository.save` 的 `inferred`）：
     * 值的比对分不清"用户改的"与"快照写的"，所以这条路必须自报家门。
     */
    var pendingInferred by remember { mutableStateOf<Map<String, Map<String, String>>>(emptyMap()) }
    // 从端点读模型清单：只读一次 GET，改配置仍然只由「保存」落库。
    var fetching by remember { mutableStateOf(false) }
    var fetchNote by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(providerId) {
        if (providerId != null) {
            // 读库和 Keystore 都在 IO 上：解密一次要几十毫秒，放主线程就是掉一帧。
            val loaded = withContext(Dispatchers.IO) {
                ProviderRepository.load(databasePath, providerId)
            }
            loaded.fold(
                onSuccess = { entry ->
                    name = entry.name
                    kind = entry.kind
                    baseUrl = entry.baseUrl
                    models = entry.models
                    activeModel = entry.activeModel
                    sources = entry.fieldSources
                },
                onFailure = { notice = "读不到这份配置：${it.message}" },
            )
            keyOnDisk = withContext(Dispatchers.IO) { ProviderRepository.hasKey(context, providerId) }
            keyHint = withContext(Dispatchers.IO) {
                ProviderRepository.keyHint(context, providerId)
            }
        }
    }

    fun persist() {
        val cleaned = models
            .map { it.copy(id = it.id.trim()) }
            .filter { it.id.isNotEmpty() }
            .distinctBy { it.id }
        when {
            name.isBlank() -> {
                notice = "给它起个名字（列表里认这一栏）"
                return
            }
            !baseUrl.startsWith("http://") && !baseUrl.startsWith("https://") -> {
                notice = "API Base URL 要以 http:// 或 https:// 开头"
                return
            }
            cleaned.isEmpty() -> {
                notice = "至少填一个模型（切到「模型」页）"
                tab = 1
                return
            }
        }
        val chosen = activeModel.takeIf { candidate -> cleaned.any { it.id == candidate } }
            ?: cleaned.first().id
        val entry = ProviderEntry(
            id = id,
            name = name.trim(),
            kind = kind,
            baseUrl = baseUrl.trim(),
            models = cleaned,
            activeModel = chosen,
        )
        saving = true
        scope.launch {
            val stored = ProviderRepository.save(databasePath, entry, pendingInferred)
            val keyed = if (keyDraft.isBlank()) {
                Result.success(Unit)
            } else {
                withContext(Dispatchers.IO) { Vault.save(context, id, keyDraft.trim()) }
            }
            saving = false

            if (stored.isFailure) {
                notice = "保存失败：${stored.exceptionOrNull()?.message}"
                return@launch
            }
            models = cleaned
            activeModel = chosen
            // 落盘之后**现读库**：来源标记是仓库比出来的，界面照抄一份算是两份规矩。
            withContext(Dispatchers.IO) { ProviderRepository.load(databasePath, id) }
                .onSuccess { fresh ->
                    models = fresh.models
                    sources = fresh.fieldSources
                }
            if (keyed.isFailure) {
                notice = "配置存了，但密钥没存进去：${keyed.exceptionOrNull()?.message}"
                return@launch
            }
            if (keyDraft.isNotBlank()) {
                keyDraft = ""
                keyVisible = false
                keyOnDisk = true
                keyHint = withContext(Dispatchers.IO) { ProviderRepository.keyHint(context, id) }
            }
            pendingInferred = emptyMap()
            notice = "已保存"
        }
    }

    /**
     * 拉一次模型清单。
     *
     * 密钥用**当前表单里的值**优先（用户可能刚填完还没保存），没有再读磁盘上那把。
     * [merge] = true 是"拉取模型"（并进列表），false 是"测试连接"（只看通不通）。
     */
    fun pull(reason: String, merge: Boolean) {
        if (fetching) return
        val base = baseUrl.trim()
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            notice = "先填 API Base URL（要以 http:// 或 https:// 开头）"
            return
        }
        fetching = true
        fetchNote = null
        scope.launch {
            val key = withContext(Dispatchers.IO) {
                keyDraft.trim().ifEmpty { Vault.load(context, id) }
            }
            val fetched = withContext(Dispatchers.IO) {
                ModelCatalog.fetch(base, kind, key)
            }
            fetching = false
            if (!fetched.ok) {
                // **只报一句**：以前红字写"失败：…"、灰字又写"拉模型失败（HTTP 0）"，
                // 两句说的是同一件事，而"HTTP 0"本身就是没信息。
                fetchNote = "拉模型失败：" + (fetched.error ?: "HTTP ${fetched.status}")
                notice = null
                return@launch
            }
            if (!merge) {
                fetchNote = "通了：HTTP ${fetched.status} · ${fetched.ids.size} 个模型 · ${fetched.durationMs} ms"
                notice = null
                return@launch
            }
            val (next, added) = ModelCatalog.merge(models, fetched.ids)
            models = next
            if (activeModel.isBlank()) activeModel = next.firstOrNull()?.id.orEmpty()
            fetchNote = when {
                fetched.ids.isEmpty() -> "端点回了 HTTP ${fetched.status}，但清单是空的"
                added == 0 -> "一共 ${fetched.ids.size} 个模型，都是已有的（没有覆盖你填的参数）"
                else -> "新增 $added 个（共 ${next.size} 个）· ${fetched.durationMs} ms · 记得点保存"
            }
            notice = null
            NLog.i("Provider", "${reason}：新增 $added，共 ${next.size}")
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            ScreenTopBar(
                title = if (providerId == null) "新建供应商" else name.ifBlank { "供应商" },
                onBack = onBack,
            ) {
                if (providerId != null) {
                    IconActionButton(
                        image = ActionIcons.Database,
                        description = "资源更新（用随包快照对齐模型字段）",
                        onClick = { resourceOpen = true },
                    )
                    IconActionButton(
                        image = ActionIcons.Close,
                        description = "删除这个供应商",
                        onClick = { confirmDelete = true },
                    )
                }
            }

            Segmented(
                options = listOf("配置", "模型"),
                selected = tab,
                onSelect = { tab = it },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                if (tab == 0) {
                    GroupCard(modifier = Modifier.padding(top = 8.dp)) {
                        SectionLabel("名称", modifier = Modifier.padding(start = 14.dp, top = 12.dp))
                        ProviderField(
                            value = name,
                            onChange = { name = it },
                            placeholder = "例如：本机中转 / 官方",
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                    }

                    GroupCard(modifier = Modifier.padding(top = 12.dp)) {
                        SectionLabel("供应商类型", modifier = Modifier.padding(start = 14.dp, top = 12.dp))
                        KindGrid(selected = kind, onSelect = { kind = it })
                        Hint("决定请求体长什么样。选错了不会报错，只会回一段读不懂的话。")
                    }

                    GroupCard(modifier = Modifier.padding(top = 12.dp)) {
                        SectionLabel("API Base URL", modifier = Modifier.padding(start = 14.dp, top = 12.dp))
                        ProviderField(
                            value = baseUrl,
                            onChange = { baseUrl = it },
                            placeholder = "https://example.com/v1",
                            keyboard = KeyboardType.Uri,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                        Hint("带不带 /v1 看供应商怎么说，这里不替你补。")
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "只发一个 GET /models：证明地址、钥匙、网络三样都对。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            TextAction(
                                label = if (fetching) "测着…" else "测试连接",
                                onClick = { pull("测试连接", merge = false) },
                            )
                        }
                    }

                    GroupCard(modifier = Modifier.padding(top = 12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SectionLabel("API Key", modifier = Modifier.weight(1f))
                            TextAction(
                                label = if (keyVisible) "隐藏" else "显示",
                                onClick = { keyVisible = !keyVisible },
                            )
                        }
                        ProviderField(
                            value = keyDraft,
                            onChange = { keyDraft = it },
                            placeholder = if (keyHint == null) "填了才存；从不回显" else "留空 = 不换",
                            secret = !keyVisible,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                        Hint(
                            when {
                                keyHint != null -> "已存：$keyHint。要换就在上面重填，留空不动它。"
                                keyOnDisk -> "存过但解不开（换机或清了数据），要重填一次。"
                                else -> "存在 vault/provider-<id>.bin，Keystore 加密，不进库、不进备份。"
                            },
                        )
                    }

                    PrimaryButton(
                        label = if (saving) "保存中…" else "保存",
                        enabled = !saving,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        onClick = { persist() },
                    )

                } else {
                    GroupCard(modifier = Modifier.padding(top = 8.dp)) {
                        models.forEachIndexed { index, model ->
                            if (index > 0) RowDivider(leading = 14.dp)
                            ModelRow(
                                model = model,
                                current = model.id == activeModel,
                                onSelect = { activeModel = model.id },
                                onEdit = {
                                    modelDraft = ModelDraft(stored = model, model = model)
                                },
                            )
                        }
                        if (models.isNotEmpty()) RowDivider(leading = 14.dp)
                        AddRow(
                            label = "添加模型",
                            onClick = { modelDraft = ModelDraft() },
                        )
                        RowDivider(leading = 14.dp)
                        AddRow(
                            label = if (fetching) "从端点拉取模型…" else "从端点拉取模型",
                            onClick = { pull("拉取模型", merge = true) },
                        )
                    }

                    Hint("点一行 = 把这一行设成这个供应商的**默认模型**；右边的铅笔是改它。真正发请求用哪个，由助手决定。")

                    val pulled = fetchNote
                    if (pulled != null) {
                        Text(
                            text = pulled,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (pulled.startsWith("失败")) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                    }

                    PrimaryButton(
                        label = if (saving) "保存中…" else "保存",
                        enabled = !saving,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        onClick = { persist() },
                    )
                }

                val line = notice
                if (line != null) {
                    Text(
                        text = line,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (resourceOpen) {
        ModelResourceScreen(
            models = models,
            sources = sources,
            onClose = { resourceOpen = false },
            onApply = { updated, inferred ->
                models = updated
                // 界面上的来源也要跟着变：不然刚更新完，徽标还写着「默认」，
                // 用户会以为这页什么都没干（真正落库由「保存」那一下完成）。
                sources = sources.toMutableMap().apply {
                    inferred.forEach { (modelId, fields) ->
                        val row = (this[modelId] ?: emptyMap()).toMutableMap()
                        fields.keys.forEach { key -> row[key] = FieldSource.Inferred }
                        this[modelId] = row
                    }
                }
                pendingInferred = pendingInferred.toMutableMap().apply {
                    inferred.forEach { (modelId, fields) ->
                        val row = (this[modelId] ?: emptyMap()).toMutableMap()
                        row.putAll(fields)
                        this[modelId] = row
                    }
                }
                resourceOpen = false
                notice = "从快照更新了 ${inferred.values.sumOf { it.size }} 处，记得点「保存」"
            },
        )
    }

    val draft = modelDraft
    if (draft != null) {
        ModelEditPage(
            draft = draft,
            siblings = models.filterNot { it.id == draft.originalId }.map { it.id },
            // 来源徽标的"打开时那一份"：老数据没有这段，全是空的 → 一律「默认」。
            storedSources = sources[draft.originalId].orEmpty(),
            onClose = { modelDraft = null },
            onSave = { updated ->
                // 改名字就按原位替换，新加就追加：顺序是用户排的，别按 id 重排。
                val index = if (draft.existing) models.indexOfFirst { it.id == draft.originalId } else -1
                models = if (index >= 0) {
                    models.toMutableList().apply { set(index, updated) }
                } else {
                    models + updated
                }
                if (activeModel == draft.originalId || activeModel.isBlank()) {
                    activeModel = updated.id
                }
                modelDraft = null
            },
            onDelete = {
                models = models.filterNot { it.id == draft.originalId }
                // 来源行别留着：删掉的模型在界面上已经指不出来了（铁律 ②）。
                sources = sources - draft.originalId
                if (activeModel == draft.originalId) {
                    activeModel = models.firstOrNull()?.id.orEmpty()
                }
                modelDraft = null
            },
        )
    }

    if (confirmDelete) {
        ConfirmProviderDelete(
            name = name.ifBlank { id },
            modelCount = models.size,
            onCancel = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                scope.launch {
                    ProviderRepository.delete(context, databasePath, id).fold(
                        onSuccess = { onDeleted() },
                        onFailure = { notice = "删除失败：${it.message}" },
                    )
                }
            },
        )
    }
}

// ---------------------------------------------------------------------------
// 小控件（这一页自己画的，形状跟别的设置页一套）
// ---------------------------------------------------------------------------

/** 一行页顶：返回 + 标题 + 右侧动作。 */
@Composable
private fun ScreenTopBar(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BackButton(onBack = onBack)
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            modifier = Modifier.weight(1f).padding(start = 4.dp),
        )
        actions()
    }
}

/** 一组行装在一张卡里：**不拆成一排小卡**，那是最典型的"卡片汤"。 */
@Composable
private fun GroupCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
    ) {
        Column { content() }
    }
}

/** 行线从文字起点开始（不是从卡片边缘），跟分组列表的规矩一致。 */
@Composable
private fun RowDivider(leading: androidx.compose.ui.unit.Dp) {
    HorizontalDivider(
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        modifier = Modifier.padding(start = leading),
    )
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(bottom = 2.dp),
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 10.dp),
    )
}

@Composable
private fun SearchRow(value: String, onChange: (String) -> Unit, placeholder: String) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                androidx.compose.material3.Icon(
                    imageVector = ActionIcons.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
            Spacer(Modifier.width(10.dp))
            Box(modifier = Modifier.weight(1f)) {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** 一行输入。自绘：系统 `TextField` 的内边距与圆角跟这套页面里其它控件对不上。 */
@Composable
private fun ProviderField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    secret: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Box(contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = keyboard),
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                visualTransformation = if (secret) {
                    PasswordVisualTransformation()
                } else {
                    VisualTransformation.None
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            )
        }
    }
}

/** 两行两列的互斥选项（4 个 wire 形态全摆出来，不做下拉）。 */
@Composable
private fun KindGrid(selected: String, onSelect: (String) -> Unit) {
    val rows = ProviderKinds.ALL.chunked(2)
    Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
        rows.forEach { pair ->
            Row(modifier = Modifier.fillMaxWidth()) {
                pair.forEach { kind ->
                    SegmentCell(
                        label = kind.label,
                        selected = kind.wire == selected,
                        modifier = Modifier.weight(1f),
                        onClick = { onSelect(kind.wire) },
                    )
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SegmentCell(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        modifier = modifier.padding(4.dp).clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        )
    }
}

/** 页签：2 项互斥，全部可见 → 分段控件，不用下拉、不用底栏。 */
@Composable
private fun Segmented(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(4.dp)) {
            options.forEachIndexed { index, label ->
                val active = index == selected
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = if (active) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        Color.Transparent
                    },
                    modifier = Modifier.weight(1f).clickable { onSelect(index) },
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (active) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
                    )
                }
            }
        }
    }
}

/** 列表里的一行供应商：首字圆标 + 名字 + 类型/模型数 + 「当前」标记。 */
@Composable
private fun ProviderRow(entry: ProviderEntry, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = entry.name.take(1).ifEmpty { "?" },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                text = entry.name.ifBlank { entry.id },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Text(
                text = buildString {
                    append(ProviderKinds.label(entry.kind))
                    append(" · ")
                    append(if (entry.models.isEmpty()) "没有模型" else "${entry.models.size} 个模型")
                    entry.model?.let { append(" · ${it.id}") }
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Text(
            text = "›",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

/** 模型一行：主行动是"选它"，铅笔才是"改它"。 */
@Composable
private fun ModelRow(
    model: ProviderModel,
    current: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            if (current) {
                androidx.compose.material3.Icon(
                    imageVector = ActionIcons.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Column(modifier = Modifier.weight(1f).padding(vertical = 10.dp)) {
            Text(
                text = model.id,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = if (model.isChat) {
                    model.summary
                } else {
                    // 非聊天型在这里标出来：它在聊天页的选择器里**不会出现**，
                    // 用户得能在这一行看出原因（D191）。
                    "${ModelKinds.label(model.kind)} · ${model.summary}"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        IconActionButton(
            image = ActionIcons.Pencil,
            description = "改这个模型",
            onClick = onEdit,
            compact = true,
        )
    }
}

@Composable
private fun AddRow(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            androidx.compose.material3.Icon(
                imageVector = ActionIcons.Plus,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(vertical = 12.dp),
        )
    }
}

/**
 * 一屏只有一个主按钮（填色）。页面底部那颗主按钮。**实现只有一份**（[WideButton]，在 Controls.kt）：
 * 资源更新页与供应商页都要用它，两边各画一颗，圆角 / 高度 / 禁用色迟早就对不上
 * （0.15m 那次"新页面自己画一组控件"就是这么歪的）。
 */
@Composable
private fun PrimaryButton(
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) = WideButton(label = label, enabled = enabled, modifier = modifier, onClick = onClick)

@Composable
private fun SecondaryButton(
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 13.dp),
        )
    }
}

/** 行内文字按钮（「显示 / 隐藏」这种只需一个字的地方）。`danger` 只给"删"这类动作用。 */
@Composable
private fun TextAction(label: String, onClick: () -> Unit, danger: Boolean = false) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

@Composable
private fun EmptyProviders(onCreate: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "还没有供应商",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "建一份：填端点、填模型名、存一把钥匙。之后聊天页的 ↑ 就真的会发请求。",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
        )
        PrimaryButton(
            label = "新建供应商",
            enabled = true,
            modifier = Modifier.padding(horizontal = 40.dp),
            onClick = onCreate,
        )
    }
}

/**
 * 模型编辑草稿（D201 第二片）：页面上正在改的那一份 + 打开时库里那一份。
 *
 * 两份都要。只有"打开时的那一份"才能回答**这一格改没改**——每个字段右边那个
 * 来源徽标全靠这个判断（改了 = `自定义`，没改 = 库里记的 `推断` / `默认`）。
 * `stored == null` = 新加的模型：没有"上一份"可比，填了值的格子一律算自定义。
 */
private data class ModelDraft(
    val stored: ProviderModel? = null,
    val model: ProviderModel = ProviderModel(id = ""),
) {
    val existing: Boolean get() = stored != null
    val originalId: String get() = stored?.id.orEmpty()
}

/**
 * 模型编辑页（D201 第二片）：**整页、三个页签、每格带出处**。
 *
 * 为什么是一层整页浮层而不是路由里的一条：模型是**供应商草稿**的一部分，
 * 拆成路由就有两个写者（模型页直接落库 / 供应商页按「保存」），谁后按谁赢，
 * 而用户看不到这件事——他在供应商页改了一半的端点会被下一次保存盖掉。
 *
 * 三页按"这个问题属于哪一类"分，不按字段多少分：
 *
 * - **基本**：这个模型是什么（ID / 名字 / 类型 / 模态 / 能力 / 限额 / 定价）；
 * - **推理**：它怎么想（方言 / 档位 / 能不能关 / 默认档）；
 * - **高级**：这个请求要不要额外带东西（Headers / Body / 图片 / 内置工具）。
 *
 * 每一格右边是一个来源徽标（`自定义` / `推断` / `默认`），改过的格子多一个 ↺：
 * 点它回到"打开这一页时库里的值"。**现在没有"数据源给的原始值"可回**——
 * 那要等资源更新页把快照读进来，那时 ↺ 才真的回到"推断值"（见 docs/decisions.md D201）。
 *
 * 一部分字段**请求里还不会用到**（Headers / Body / 推理那一批 / 价格 / 模态 / 能力位），
 * 所以每一页底部都写了一句话把这件事说明白：宁可写"现在只记录"，也不做成假开关。
 */
@Composable
private fun ModelEditPage(
    draft: ModelDraft,
    /** 同一个供应商下**别的**模型的 id：用来拦重名。 */
    siblings: List<String>,
    /** 库里这个模型每个字段的来源（D201 第一片）。新模型是空的。 */
    storedSources: Map<String, FieldSource>,
    onClose: () -> Unit,
    onSave: (ProviderModel) -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val base = draft.model

    var tab by remember { mutableStateOf(0) }
    var id by remember { mutableStateOf(base.id) }
    var name by remember { mutableStateOf(base.name) }
    var kind by remember { mutableStateOf(base.kind) }
    var window by remember { mutableStateOf(base.contextWindow?.toString().orEmpty()) }
    var maxOut by remember { mutableStateOf(base.maxTokens?.toString().orEmpty()) }
    var heat by remember { mutableStateOf(base.temperature?.toString().orEmpty()) }
    var inputs by remember { mutableStateOf(base.inputModalities) }
    var outputs by remember { mutableStateOf(base.outputModalities) }
    var tools by remember { mutableStateOf(base.tools) }
    var canReason by remember { mutableStateOf(base.reasoning) }
    var structured by remember { mutableStateOf(base.structuredOutput) }
    var inPrice by remember { mutableStateOf(base.inputPrice?.toString().orEmpty()) }
    var outPrice by remember { mutableStateOf(base.outputPrice?.toString().orEmpty()) }
    var cacheRead by remember { mutableStateOf(base.cacheReadPrice?.toString().orEmpty()) }
    var cacheWrite by remember { mutableStateOf(base.cacheWritePrice?.toString().orEmpty()) }
    var currency by remember { mutableStateOf(base.currency) }
    var dialect by remember { mutableStateOf(base.reasoningDialect) }
    var efforts by remember { mutableStateOf(base.efforts) }
    var allowDisable by remember { mutableStateOf(base.allowDisable) }
    var defaultEffort by remember { mutableStateOf(base.defaultEffort) }
    var headers by remember { mutableStateOf(base.headers) }
    var body by remember { mutableStateOf(base.bodyOverride) }
    var imageUrl by remember { mutableStateOf(base.remoteImageUrl) }
    var builtinTools by remember { mutableStateOf(base.builtinTools) }
    var error by remember { mutableStateOf<String?>(null) }

    // 返回键先关这一层（后注册者优先，轮不到页面栈那条）。
    BackHandler(enabled = true) { onClose() }

    /** 把页面上的字符串**尽量**拼回一个模型：解析不了的数值给 `null`。 */
    fun editing(): ProviderModel = ProviderModel(
        id = id.trim(),
        name = name.trim(),
        kind = kind,
        contextWindow = window.trim().toIntOrNull(),
        maxTokens = maxOut.trim().toIntOrNull(),
        temperature = heat.trim().toDoubleOrNull(),
        inputModalities = Modalities.normalize(inputs, Modalities.INPUT),
        outputModalities = Modalities.normalize(outputs, Modalities.OUTPUT),
        tools = tools,
        reasoning = canReason,
        structuredOutput = structured,
        inputPrice = inPrice.trim().toDoubleOrNull(),
        outputPrice = outPrice.trim().toDoubleOrNull(),
        cacheReadPrice = cacheRead.trim().toDoubleOrNull(),
        cacheWritePrice = cacheWrite.trim().toDoubleOrNull(),
        currency = currency.trim().uppercase(),
        reasoningDialect = dialect,
        efforts = Efforts.normalize(efforts),
        allowDisable = allowDisable,
        defaultEffort = defaultEffort,
        headers = headers.filter { it.name.isNotBlank() },
        bodyOverride = body.trim(),
        remoteImageUrl = imageUrl.trim(),
        builtinTools = builtinTools,
    )

    val editingModel = editing()
    // 与仓库保存时**完全同一条**规则（ModelFields.liveSources）：界面说"自定义"的，
    // 存下去也一定是自定义。
    val live = ModelField.liveSources(draft.stored, storedSources, editingModel)
    fun source(key: String): FieldSource = live[key] ?: FieldSource.Default
    fun dirty(key: String): Boolean = draft.stored != null && source(key) == FieldSource.Custom

    /** ↺：把这一格改回"打开这一页时库里的值"。 */
    fun reset(key: String) {
        when (key) {
            ModelField.ID -> id = base.id
            ModelField.NAME -> name = base.name
            ModelField.KIND -> kind = base.kind
            ModelField.CONTEXT_WINDOW -> window = base.contextWindow?.toString().orEmpty()
            ModelField.MAX_TOKENS -> maxOut = base.maxTokens?.toString().orEmpty()
            ModelField.TEMPERATURE -> heat = base.temperature?.toString().orEmpty()
            ModelField.INPUT_MODALITIES -> inputs = base.inputModalities
            ModelField.OUTPUT_MODALITIES -> outputs = base.outputModalities
            ModelField.TOOLS -> tools = base.tools
            ModelField.REASONING -> canReason = base.reasoning
            ModelField.STRUCTURED_OUTPUT -> structured = base.structuredOutput
            ModelField.INPUT_PRICE -> inPrice = base.inputPrice?.toString().orEmpty()
            ModelField.OUTPUT_PRICE -> outPrice = base.outputPrice?.toString().orEmpty()
            ModelField.CACHE_READ_PRICE -> cacheRead = base.cacheReadPrice?.toString().orEmpty()
            ModelField.CACHE_WRITE_PRICE -> cacheWrite = base.cacheWritePrice?.toString().orEmpty()
            ModelField.CURRENCY -> currency = base.currency
            ModelField.REASONING_DIALECT -> dialect = base.reasoningDialect
            ModelField.EFFORTS -> efforts = base.efforts
            ModelField.ALLOW_DISABLE -> allowDisable = base.allowDisable
            ModelField.DEFAULT_EFFORT -> defaultEffort = base.defaultEffort
            ModelField.HEADERS -> headers = base.headers
            ModelField.BODY -> body = base.bodyOverride
            ModelField.REMOTE_IMAGE -> imageUrl = base.remoteImageUrl
            ModelField.BUILTIN_TOOLS -> builtinTools = base.builtinTools
        }
    }

    // 保存（页顶右上角那个 ＋ / ✓）。校验与落点都在这儿，别在按钮里再写一遍。
    val save: () -> Unit = {
        val cleanId = id.trim()
        val parsedWindow = window.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
        val parsedTokens = maxOut.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
        val parsedHeat = heat.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()
        // 价格：空 = 没填（允许）；填了就得是 0 或更大的数。
        val badPrice = listOf(inPrice, outPrice, cacheRead, cacheWrite).any { raw ->
            val text = raw.trim()
            text.isNotEmpty() && (text.toDoubleOrNull()?.let { it >= 0.0 } != true)
        }
        val bodyText = body.trim()
        when {
            cleanId.isEmpty() -> {
                tab = 0
                error = "模型 ID 不能是空的"
            }

            siblings.any { it == cleanId } -> {
                tab = 0
                error = "这个供应商下已经有「$cleanId」了"
            }

            window.trim().isNotEmpty() && (parsedWindow == null || parsedWindow <= 0) -> {
                tab = 0
                error = "上下文窗口要填正整数"
            }

            maxOut.trim().isNotEmpty() && (parsedTokens == null || parsedTokens <= 0) -> {
                tab = 0
                error = "最大输出要填正整数"
            }

            heat.trim().isNotEmpty() && (parsedHeat == null || parsedHeat < 0.0 || parsedHeat > 2.0) -> {
                tab = 0
                error = "温度是 0 到 2 之间的小数"
            }

            badPrice -> {
                tab = 0
                error = "定价要填 0 或更大的数字（每 100 万 token）"
            }

            defaultEffort.isNotEmpty() && defaultEffort !in Efforts.normalize(efforts) -> {
                tab = 1
                error = "默认级别要先在「支持的级别」里勾上"
            }

            bodyText.isNotEmpty() && !isJsonObject(bodyText) -> {
                tab = 2
                error = "自定义 Body 要是一个 JSON 对象，现在这段解析不了"
            }

            headers.any { it.name.isBlank() && it.value.isNotBlank() } -> {
                tab = 2
                error = "有 Header 只填了值、没填名字"
            }

            headers.any { it.name.contains(':') || it.name.contains('\n') } -> {
                tab = 2
                error = "Header 名里不能有冒号或换行"
            }

            else -> onSave(editingModel.copy(id = cleanId))
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackButton(onBack = onClose)
                Text(
                    text = if (draft.existing) "改模型" else "添加模型",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
                // 添加 = ＋、编辑 = ✓（用户 2026-10-31 给的图）。
                // 两个动作都是"提交这一页"，只是图标不同——**不写文字**，但读屏要能念出来。
                IconActionButton(
                    image = if (draft.existing) ActionIcons.Check else ActionIcons.Plus,
                    description = if (draft.existing) "保存这个模型" else "添加这个模型",
                    onClick = save,
                )
            }

            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Segmented(
                    options = listOf("基本", "推理", "高级"),
                    selected = tab,
                    onSelect = { tab = it },
                )
            }

            // 红字放在页签下面、内容上面：它与"哪一页"无关，切页之后也还在。
            val line = error
            if (line != null) {
                Text(
                    text = line,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                )
            }

            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                when (tab) {
                    0 -> {
                        GroupCard(modifier = Modifier.padding(top = 6.dp)) {
                            ModelFieldHead("模型 ID", source(ModelField.ID), dirty(ModelField.ID), top = true) { reset(ModelField.ID) }
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(end = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                ProviderField(
                                    value = id,
                                    onChange = { id = it },
                                    placeholder = "供应商那边怎么写的就怎么填",
                                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                                )
                                IconActionButton(
                                    image = ActionIcons.Copy,
                                    description = "复制模型 ID",
                                    compact = true,
                                    onClick = { putClipboard(context, "模型 ID", id.trim()) },
                                )
                            }
                            Hint("改 ID 等于换成另一个模型；同一个供应商下不能重名。")
                        }

                        GroupCard(modifier = Modifier.padding(top = 12.dp)) {
                            ModelFieldHead("显示名（可空）", source(ModelField.NAME), dirty(ModelField.NAME), top = true) { reset(ModelField.NAME) }
                            ProviderField(
                                value = name,
                                onChange = { name = it },
                                placeholder = "聊天页顶部显示它；空 = 用 ID",
                                modifier = Modifier.padding(horizontal = 8.dp),
                            )
                        }

                        GroupCard(modifier = Modifier.padding(top = 12.dp)) {
                            ModelFieldHead("类型", source(ModelField.KIND), dirty(ModelField.KIND), top = true) { reset(ModelField.KIND) }
                            Row(modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 6.dp)) {
                                ModelKinds.ALL.forEachIndexed { index, option ->
                                    if (index > 0) Spacer(Modifier.width(8.dp))
                                    DialogButton(
                                        label = option.label,
                                        primary = kind == option.wire,
                                        onClick = { kind = option.wire },
                                    )
                                }
                            }
                            Hint(
                                if (kind == ModelKinds.CHAT) {
                                    "只有「聊天」型的会出现在聊天页与助手页的模型选择器里。"
                                } else {
                                    "「${ModelKinds.label(kind)}」不会出现在聊天页的选择器里——那里只列聊天模型。"
                                },
                            )
                        }

                        GroupCard(modifier = Modifier.padding(top = 12.dp)) {
                            ModelFieldHead("输入模式", source(ModelField.INPUT_MODALITIES), dirty(ModelField.INPUT_MODALITIES), top = true) { reset(ModelField.INPUT_MODALITIES) }
                            ChipGrid(
                                options = Modalities.INPUT,
                                selected = inputs,
                                onToggle = { wire ->
                                    inputs = toggle(inputs, wire, Modalities.INPUT)
                                },
                            )
                            Hint("勾出来的是**这个模型收得下**的东西。空着 = 不知道，按纯文本处理。")
                        }

                        GroupCard(modifier = Modifier.padding(top = 12.dp)) {
                            ModelFieldHead("输出模式", source(ModelField.OUTPUT_MODALITIES), dirty(ModelField.OUTPUT_MODALITIES), top = true) { reset(ModelField.OUTPUT_MODALITIES) }
                            ChipGrid(
                                options = Modalities.OUTPUT,
                                selected = outputs,
                                onToggle = { wire ->
                                    outputs = toggle(outputs, wire, Modalities.OUTPUT)
                                },
                            )
                            Hint("输出侧没有 PDF / 视频：现在没有哪家生成得了它们，摆上去就是点不出东西的选项。")
                        }

                        GroupCard(modifier = Modifier.padding(top = 12.dp)) {
                            ModelFieldHead("工具调用", source(ModelField.TOOLS), dirty(ModelField.TOOLS), top = true) { reset(ModelField.TOOLS) }
                            TriChoices(tools, "支持", "不支持") { tools = it }
                            ModelFieldHead("推理", source(ModelField.REASONING), dirty(ModelField.REASONING), top = false) { reset(ModelField.REASONING) }
                            TriChoices(canReason, "支持", "不支持") { canReason = it }
                            ModelFieldHead("结构化输出", source(ModelField.STRUCTURED_OUTPUT), dirty(ModelField.STRUCTURED_OUTPUT), top = false) { reset(ModelField.STRUCTURED_OUTPUT) }
                            TriChoices(structured, "支持", "不支持") { structured = it }
                            Hint(
                                "三个都是三态：「不知道」不是「不支持」——没数据时不要替模型说它能或不能。" +
                                    "勾成「不支持」的地方，界面上对应功能该置灰（现在还没有置灰的读取点）。",
                            )
                        }

                        GroupCard(modifier = Modifier.padding(top = 12.dp)) {
                            ModelFieldHead("上下文窗口（可空）", source(ModelField.CONTEXT_WINDOW), dirty(ModelField.CONTEXT_WINDOW), top = true) { reset(ModelField.CONTEXT_WINDOW) }
                            ProviderField(
                                value = window,
                                onChange = { window = it },
                                placeholder = "例如 128000",
                                keyboard = KeyboardType.Number,
                                modifier = Modifier.padding(horizontal = 8.dp),
                            )
                            ModelFieldHead("最大输出（可空）", source(ModelField.MAX_TOKENS), dirty(ModelField.MAX_TOKENS), top = false) { reset(ModelField.MAX_TOKENS) }
                            ProviderField(
                                value = maxOut,
                                onChange = { maxOut = it },
                                placeholder = "例如 8192；空 = 不传",
                                keyboard = KeyboardType.Number,
                                modifier = Modifier.padding(horizontal = 8.dp),
                            )
                            ModelFieldHead("温度（可空）", source(ModelField.TEMPERATURE), dirty(ModelField.TEMPERATURE), top = false) { reset(ModelField.TEMPERATURE) }
                            ProviderField(
                                value = heat,
                                onChange = { heat = it },
                                placeholder = "例如 0.7；空 = 不传",
                                keyboard = KeyboardType.Decimal,
                                modifier = Modifier.padding(horizontal = 8.dp),
                            )
                            Hint(
                                "窗口填了才显示占用百分比，压缩判据也按它算；输出与温度每次请求都会带" +
                                    "（助手页也填了的话，那里填的优先——模型这份是这个模型的常态）。",
                            )
                        }

                        GroupCard(modifier = Modifier.padding(top = 12.dp)) {
                            ModelFieldHead("定价：输入（每 100 万 token）", source(ModelField.INPUT_PRICE), dirty(ModelField.INPUT_PRICE), top = true) { reset(ModelField.INPUT_PRICE) }
                            ProviderField(
                                value = inPrice,
                                onChange = { inPrice = it },
                                placeholder = "例如 0.27",
                                keyboard = KeyboardType.Decimal,
                                modifier = Modifier.padding(horizontal = 8.dp),
                            )
                            ModelFieldHead("定价：输出", source(ModelField.OUTPUT_PRICE), dirty(ModelField.OUTPUT_PRICE), top = false) { reset(ModelField.OUTPUT_PRICE) }
                            ProviderField(
                                value = outPrice,
                                onChange = { outPrice = it },
                                placeholder = "例如 1.1",
                                keyboard = KeyboardType.Decimal,
                                modifier = Modifier.padding(horizontal = 8.dp),
                            )
                            ModelFieldHead("定价：缓存读取（可空）", source(ModelField.CACHE_READ_PRICE), dirty(ModelField.CACHE_READ_PRICE), top = false) { reset(ModelField.CACHE_READ_PRICE) }
                            ProviderField(
                                value = cacheRead,
                                onChange = { cacheRead = it },
                                placeholder = "没有缓存价就留空",
                                keyboard = KeyboardType.Decimal,
                                modifier = Modifier.padding(horizontal = 8.dp),
                            )
                            ModelFieldHead("定价：缓存写入（可空）", source(ModelField.CACHE_WRITE_PRICE), dirty(ModelField.CACHE_WRITE_PRICE), top = false) { reset(ModelField.CACHE_WRITE_PRICE) }
                            ProviderField(
                                value = cacheWrite,
                                onChange = { cacheWrite = it },
                                placeholder = "没有缓存价就留空",
                                keyboard = KeyboardType.Decimal,
                                modifier = Modifier.padding(horizontal = 8.dp),
                            )
                            ModelFieldHead("货币", source(ModelField.CURRENCY), dirty(ModelField.CURRENCY), top = false) { reset(ModelField.CURRENCY) }
                            ProviderField(
                                value = currency,
                                onChange = { currency = it },
                                placeholder = "USD",
                                modifier = Modifier.padding(horizontal = 8.dp),
                            )
                            Hint(
                                "**现在只记录**：没有哪一处按它算过钱。等你真要看花费时，它才变成读取点——" +
                                    "所以这一格空着也不算错，别为了填满而猜。",
                            )
                        }

                        Hint(
                            "这一页里，类型 / 窗口 / 最大输出 / 温度真的会进请求；" +
                                "模态、能力位、定价现在只跟着配置一起存（导出、备份会带走），请求里还不会用到。",
                        )
                    }

                    1 -> {
                        GroupCard(modifier = Modifier.padding(top = 6.dp)) {
                            ModelFieldHead("推理方言", source(ModelField.REASONING_DIALECT), dirty(ModelField.REASONING_DIALECT), top = true) { reset(ModelField.REASONING_DIALECT) }
                            ChoiceRow(
                                value = dialect,
                                options = ReasoningDialects.ALL,
                                onSelect = { dialect = it },
                            )
                            Hint(
                                "思考这一档**注到哪个字段上**。留「自动」就按供应商类型选——" +
                                    "选错了不会报错，只会得到一段读不懂的话。要写自定义 JSON，" +
                                    "去「高级 → 自定义 Body」，别指望这里。",
                            )
                        }

                        GroupCard(modifier = Modifier.padding(top = 12.dp)) {
                            ModelFieldHead("支持的级别", source(ModelField.EFFORTS), dirty(ModelField.EFFORTS), top = true) { reset(ModelField.EFFORTS) }
                            ChipGrid(
                                options = Efforts.LEVELS,
                                selected = efforts,
                                onToggle = { wire -> efforts = toggle(efforts, wire, Efforts.LEVELS) },
                                perRow = 3,
                            )
                            Hint("只勾这个模型真接受的档。空着 = 没数据，界面上就只给「自动 / 关闭」。")
                        }

                        GroupCard(modifier = Modifier.padding(top = 12.dp)) {
                            ModelFieldHead("允许关闭", source(ModelField.ALLOW_DISABLE), dirty(ModelField.ALLOW_DISABLE), top = true) { reset(ModelField.ALLOW_DISABLE) }
                            TriChoices(allowDisable, "可以关", "不能关") { allowDisable = it }
                            Hint("有些模型只能开、不能关（传了 `none` 会 400）。不知道就留「不知道」。")
                        }

                        GroupCard(modifier = Modifier.padding(top = 12.dp)) {
                            ModelFieldHead("默认级别", source(ModelField.DEFAULT_EFFORT), dirty(ModelField.DEFAULT_EFFORT), top = true) { reset(ModelField.DEFAULT_EFFORT) }
                            val chosen = Efforts.normalize(efforts)
                            if (chosen.isEmpty()) {
                                Hint("先在上一块里勾出支持的级别，这里才有得选。")
                            } else {
                                ChipGrid(
                                    options = Efforts.LEVELS.filter { it.wire in chosen },
                                    selected = listOf(defaultEffort),
                                    onToggle = { wire ->
                                        defaultEffort = if (defaultEffort == wire) "" else wire
                                    },
                                    perRow = 3,
                                )
                                Hint("不选 = 每次都由助手页那份「思考」决定。")
                            }
                            Hint(
                                "这一页真的会进请求：助手页选了一档就用它，助手页留「自动」时才用这里的" +
                                    "「默认级别」；方言决定它注到哪个字段上。勾了「支持的级别」之后，发请求时" +
                                    "选了没勾的那一档会当场报错并点名——不会悄悄改成最近的一档。",
                            )
                        }
                    }

                    else -> {
                        GroupCard(modifier = Modifier.padding(top = 6.dp)) {
                            ModelFieldHead("自定义 Headers", source(ModelField.HEADERS), dirty(ModelField.HEADERS), top = true) { reset(ModelField.HEADERS) }
                            headers.forEachIndexed { index, header ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(end = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    ProviderField(
                                        value = header.name,
                                        onChange = { value ->
                                            headers = headers.toMutableList().also {
                                                it[index] = it[index].copy(name = value)
                                            }
                                        },
                                        placeholder = "名字",
                                        modifier = Modifier.weight(1f),
                                    )
                                    ProviderField(
                                        value = header.value,
                                        onChange = { value ->
                                            headers = headers.toMutableList().also {
                                                it[index] = it[index].copy(value = value)
                                            }
                                        },
                                        placeholder = "值",
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextAction(
                                        label = "删",
                                        danger = true,
                                        onClick = { headers = headers.filterIndexed { i, _ -> i != index } },
                                    )
                                }
                            }
                            AddRow(
                                label = "添加一行",
                                onClick = { headers = headers + HeaderRow("", "") },
                            )
                            Hint(
                                "每次请求都会带上。host / content-length / content-type / accept 这类" +
                                    "由核心自己写的头部不能覆盖，重名也会在保存前被拦住。",
                            )
                        }

                        GroupCard(modifier = Modifier.padding(top = 12.dp)) {
                            ModelFieldHead("自定义 Body", source(ModelField.BODY), dirty(ModelField.BODY), top = true) { reset(ModelField.BODY) }
                            BodyField(
                                value = body,
                                onChange = { body = it },
                                placeholder = """{"temperature":0.2}""",
                            )
                            Hint(
                                "一个 JSON 对象，保存时校验：解析不了就整页不写。每次请求都会并进去，" +
                                    "排在你填的温度/输出上限之后（同名的键以这里为准）。" +
                                    "messages / model / stream / tools 由核心写，填了会被拒。",
                            )
                        }

                        GroupCard(modifier = Modifier.padding(top = 12.dp)) {
                            ModelFieldHead("远程图片链接", source(ModelField.REMOTE_IMAGE), dirty(ModelField.REMOTE_IMAGE), top = true) { reset(ModelField.REMOTE_IMAGE) }
                            ProviderField(
                                value = imageUrl,
                                onChange = { imageUrl = it },
                                placeholder = "https://…/icon.png",
                                keyboard = KeyboardType.Uri,
                                modifier = Modifier.padding(horizontal = 8.dp),
                            )
                            Hint("**还没有读取点**：界面上没有哪一处画它。等你说「图标走链接」时再接。")
                        }

                        GroupCard(modifier = Modifier.padding(top = 12.dp)) {
                            ModelFieldHead("内置工具", source(ModelField.BUILTIN_TOOLS), dirty(ModelField.BUILTIN_TOOLS), top = true) { reset(ModelField.BUILTIN_TOOLS) }
                            TriChoices(builtinTools, "允许", "不允许") { builtinTools = it }
                            Hint(
                                "供应商侧自带的那类工具（联网搜索之类）。**现在只记录**，" +
                                    "请求里还不会带；不知道就留「不知道」。",
                            )
                        }
                    }
                }

                if (draft.existing) {
                    // 删的是"这一行配置"（id + 参数），不碰别的模型、不碰供应商。
                    Row(modifier = Modifier.padding(start = 6.dp)) {
                        TextAction(
                            label = "删除这个模型",
                            danger = true,
                            onClick = onDelete,
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** 来源徽标（D201 第 3 条）：三种颜色，不写装饰、不加图标。 */
@Composable
private fun SourceBadge(source: FieldSource) {
    val ink = when (source) {
        FieldSource.Custom -> MaterialTheme.colorScheme.primary
        FieldSource.Inferred -> MaterialTheme.colorScheme.tertiary
        FieldSource.Default -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val plate = when (source) {
        FieldSource.Custom -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        FieldSource.Inferred -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f)
        FieldSource.Default -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    Surface(shape = RoundedCornerShape(999.dp), color = plate) {
        Text(
            text = source.label,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            color = ink,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
        )
    }
}

/** 多选 chips：每行 [perRow] 个，选中用主色实心（与「类型」那一排同形）。 */
@Composable
private fun ChipGrid(
    options: List<Kind>,
    selected: List<String>,
    onToggle: (String) -> Unit,
    perRow: Int = 3,
) {
    Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) {
        options.chunked(perRow).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                row.forEach { option ->
                    DialogButton(
                        label = option.label,
                        primary = option.wire in selected,
                        onClick = { onToggle(option.wire) },
                    )
                    Spacer(Modifier.width(8.dp))
                }
            }
        }
    }
}

/** 三态：不知道 / 支持 / 不支持。**不许省掉"不知道"**，它是"没数据"的唯一出口。 */
@Composable
private fun TriChoices(value: Boolean?, yes: String, no: String, onChange: (Boolean?) -> Unit) {
    Row(modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 6.dp)) {
        DialogButton(label = "不知道", primary = value == null, onClick = { onChange(null) })
        Spacer(Modifier.width(8.dp))
        DialogButton(label = yes, primary = value == true, onClick = { onChange(true) })
        Spacer(Modifier.width(8.dp))
        DialogButton(label = no, primary = value == false, onClick = { onChange(false) })
    }
}

/** 单选下拉（就地展开，不用弹窗）：选项多、标签长的时候用它，chips 会挤成一团。 */
@Composable
private fun ChoiceRow(value: String, options: List<Kind>, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = options.firstOrNull { it.wire == value } ?: options.firstOrNull()
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 2.dp)
            .clickable { open = !open },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = current?.label ?: value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            androidx.compose.material3.Icon(
                imageVector = ActionIcons.ChevronDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }
    if (!open) return
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)) {
        options.forEach { option ->
            val active = option.wire == value
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onSelect(option.wire)
                        open = false
                    }
                    .padding(horizontal = 12.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = option.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (active) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier.weight(1f),
                )
                if (active) {
                    androidx.compose.material3.Icon(
                        imageVector = ActionIcons.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            if (option != options.last()) RowDivider(leading = 12.dp)
        }
    }
}

/** 多行文本框：只给自定义 Body 用。等宽字体，缩进才对得齐。 */
@Composable
private fun BodyField(value: String, onChange: (String) -> Unit, placeholder: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
    ) {
        Box(contentAlignment = Alignment.TopStart) {
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontFamily = FontFamily.Monospace,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 96.dp)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            )
        }
    }
}

/** 列表里的多选：勾上就加，勾掉就减，顺序按词表走（不按点击顺序）。 */
private fun toggle(current: List<String>, wire: String, choices: List<Kind>): List<String> {
    val next = if (wire in current) current - wire else current + wire
    return choices.map { it.wire }.filter { it in next }
}

/** 价格：空 = 没填（允许），填了就得是 0 或更大的数。 */
private fun priceOk(value: Double?): Boolean = value != null && value >= 0.0
/**
 * 一行"标签 + 来源徽标 + ↺"。
 *
 * 徽标贴在这一行右端、不另占一行：它是**这个标签的**一个属性（"这个数是谁说的"），
 * 不是这个字段的第二行内容。↺ 只在"改过"时出现——没改的格子没有可回的东西，
 * 摆一个点不动的图标就是假开关。
 */
@Composable
private fun ModelFieldHead(
    label: String,
    source: FieldSource,
    dirty: Boolean,
    top: Boolean = false,
    onReset: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 8.dp, top = if (top) 12.dp else 10.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SectionLabel(label, modifier = Modifier.weight(1f))
        SourceBadge(source)
        if (dirty) {
            IconActionButton(
                image = ActionIcons.RotateCcw,
                description = "把「$label」改回保存前那一份",
                compact = true,
                onClick = onReset,
            )
        }
    }
}

/** 自定义 Body 的 JSON 校验：只认**对象**（数组/标量不是"并进请求体"该有的形状）。 */
private fun isJsonObject(text: String): Boolean =
    runCatching { org.json.JSONObject(text) }.isSuccess

/** 复制到剪贴板：只复制、不弹自己的提示（系统自带的那条够了）。 */
private fun putClipboard(context: Context, label: String, text: String) {
    if (text.isEmpty()) return
    val manager = context.getSystemService(ClipboardManager::class.java) ?: return
    manager.setPrimaryClip(ClipData.newPlainText(label, text))
}

@Composable
private fun ConfirmProviderDelete(
    name: String,
    modelCount: Int,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    OverlayCard(onDismiss = onCancel) {
        Text(
            text = "删掉「$name」？",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "它的端点配置、$modelCount 个模型名，以及 vault 里那一个密钥文件一起删掉，" +
                "收不回来。别的供应商和聊天记录都不动。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DialogButton(label = "取消", onClick = onCancel)
            Spacer(Modifier.width(8.dp))
            DialogButton(label = "删除", danger = true, onClick = onConfirm)
        }
    }
}

/** 居中的自绘卡片（不用系统 `AlertDialog`：那套在这两套主题下都对不齐）。 */
@Composable
private fun OverlayCard(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.32f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
        )
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) { content() }
            }
        }
    }
}

@Composable
private fun DialogButton(
    label: String,
    primary: Boolean = false,
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
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}
