package com.nmoi.agent.ui

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
 * - 模型没有「上下文窗口 / 定价 / 输入输出模态」：这一版只发 `max_tokens` 与
 *   `temperature`，别的字段存下来没有任何一处读。
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
    var keyDraft by remember { mutableStateOf("") }
    var keyVisible by remember { mutableStateOf(false) }
    var keyHint by remember { mutableStateOf<String?>(null) }
    var keyOnDisk by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var modelDraft by remember { mutableStateOf<ModelDraft?>(null) }
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
            val stored = ProviderRepository.save(databasePath, entry)
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
                fetchNote = "失败：${fetched.error ?: "HTTP ${fetched.status}"}"
                notice = "拉模型失败（HTTP ${fetched.status}）"
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
                                    modelDraft = ModelDraft(
                                        existing = true,
                                        originalId = model.id,
                                        id = model.id,
                                        maxTokens = model.maxTokens?.toString().orEmpty(),
                                        temperature = model.temperature?.toString().orEmpty(),
                                    )
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

    val draft = modelDraft
    if (draft != null) {
        ModelDialog(
            draft = draft,
            onDismiss = { modelDraft = null },
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
                text = model.summary,
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

/** 一屏只有一个主按钮（填色）。 */
@Composable
private fun PrimaryButton(
    label: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (enabled) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        modifier = modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 13.dp),
        )
    }
}

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

/** 行内文字按钮（「显示 / 隐藏」这种只需一个字的地方）。 */
@Composable
private fun TextAction(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
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

/** 模型编辑草稿：**字符串**，因为用户正在打字，这个阶段不该被解析。 */
private data class ModelDraft(
    val existing: Boolean = false,
    val originalId: String = "",
    val id: String = "",
    val maxTokens: String = "",
    val temperature: String = "",
)

@Composable
private fun ModelDialog(
    draft: ModelDraft,
    onDismiss: () -> Unit,
    onSave: (ProviderModel) -> Unit,
    onDelete: () -> Unit,
) {
    var id by remember { mutableStateOf(draft.id) }
    var maxTokens by remember { mutableStateOf(draft.maxTokens) }
    var temperature by remember { mutableStateOf(draft.temperature) }
    var error by remember { mutableStateOf<String?>(null) }

    OverlayCard(onDismiss = onDismiss) {
        Text(
            text = if (draft.existing) "改模型" else "添加模型",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        SectionLabel("模型 ID", modifier = Modifier.padding(top = 12.dp))
        ProviderField(
            value = id,
            onChange = { id = it },
            placeholder = "供应商那边怎么写的就怎么填",
            modifier = Modifier.padding(vertical = 2.dp),
        )
        SectionLabel("最大输出（可空）", modifier = Modifier.padding(top = 10.dp))
        ProviderField(
            value = maxTokens,
            onChange = { maxTokens = it },
            placeholder = "例如 8192；空 = 不传",
            keyboard = KeyboardType.Number,
            modifier = Modifier.padding(vertical = 2.dp),
        )
        SectionLabel("温度（可空）", modifier = Modifier.padding(top = 10.dp))
        ProviderField(
            value = temperature,
            onChange = { temperature = it },
            placeholder = "例如 0.7；空 = 不传",
            keyboard = KeyboardType.Decimal,
            modifier = Modifier.padding(vertical = 2.dp),
        )
        val line = error
        if (line != null) {
            Text(
                text = line,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (draft.existing) {
                DialogButton(label = "删除", danger = true) { onDelete() }
                Spacer(Modifier.weight(1f))
            }
            DialogButton(label = "取消") { onDismiss() }
            Spacer(Modifier.width(8.dp))
            DialogButton(label = "保存", primary = true) {
                val cleanId = id.trim()
                val tokens = maxTokens.trim()
                val heat = temperature.trim()
                val parsedTokens = if (tokens.isEmpty()) null else tokens.toIntOrNull()
                val parsedHeat = if (heat.isEmpty()) null else heat.toDoubleOrNull()
                when {
                    cleanId.isEmpty() -> error = "模型 ID 不能是空的"
                    tokens.isNotEmpty() && (parsedTokens == null || parsedTokens <= 0) ->
                        error = "最大输出要填正整数"
                    heat.isNotEmpty() && (parsedHeat == null || parsedHeat < 0.0 || parsedHeat > 2.0) ->
                        error = "温度是 0 到 2 之间的小数"
                    else -> onSave(
                        ProviderModel(
                            id = cleanId,
                            maxTokens = parsedTokens,
                            temperature = parsedHeat,
                        ),
                    )
                }
            }
        }
    }
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
