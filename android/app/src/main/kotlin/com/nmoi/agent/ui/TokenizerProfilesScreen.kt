package com.nmoi.agent.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * tokenizer 词表（D438）：一列条目，**顺序就是优先级**。
 *
 * 用户 2026-11-26 的原话：
 * 「能新建项，然后配置每一项的名称、模型匹配关键词/正则、然后导入 tokenizer.json」
 * 「必须要有关键词/正则，且必须有 tokenizer.json，条目才能成功创建，然后可以编辑和删除条目，
 *   有适配的 tokenizer.json 的就用，按照条目排序来决定匹配的优先级，最上方的条目最先匹配，
 *   内置 tokenizer.json 是作为没有命中规则下的默认回退的 token 计算用的」
 *
 * # 顺序怎么改
 *
 * 先给**上移 / 下移**两个动作（行尾那两个箭头）。拖拽排序要跟横滑、长按抢手势，
 * 值得单独一轮；而"顺序就是优先级"这件事，两个箭头已经能表达清楚。
 *
 * # 现在还没接的一格
 *
 * 词表本身已经能导进来、能校验、能算"命中哪一条"；**真正拿它数 token 的接线还没做**
 * （Rust 侧要按条目加载词表）。所以这一页底部有一句诚实的说明，而不是让用户以为
 * 导完就生效了——"点了没反应"比"还没接"更糟。
 */
@Composable
internal fun TokenizerProfilesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var profiles by remember { mutableStateOf(emptyList<TokenizerProfile>()) }
    var notice by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<TokenizerProfile?>(null) }
    var creating by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<TokenizerProfile?>(null) }

    fun reload() {
        profiles = TokenizerProfileStore.load(context)
    }

    LaunchedEffect(Unit) {
        reload()
        PageReady.notifyReadyAfterFrame()
    }

    fun persist(next: List<TokenizerProfile>) {
        TokenizerProfileStore.save(context, next).fold(
            onSuccess = { profiles = next },
            onFailure = { notice = "没存下来：${it.message}" },
        )
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackButton(onBack = onBack)
                Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(
                        text = "tokenizer 词表",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = if (profiles.isEmpty()) "还没有条目：全部模型用内置那份" else "${profiles.size} 条 · 上方的先匹配",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "新建",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable(enabled = !busy) { creating = true }
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                )
            }

            notice?.let { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (profiles.isEmpty()) {
                    Text(
                        text = "没有条目时，所有模型都用内置那份词表算。加一条就能让某一类模型用上它自己的词表。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                profiles.forEachIndexed { index, profile ->
                    ProfileCard(
                        profile = profile,
                        first = index == 0,
                        last = index == profiles.lastIndex,
                        onUp = { persist(TokenizerProfileStore.move(profiles, profile.id, up = true)) },
                        onDown = { persist(TokenizerProfileStore.move(profiles, profile.id, up = false)) },
                        onEdit = { editing = profile },
                        onDelete = { confirmDelete = profile },
                    )
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    text = "没命中任何条目时用内置那份词表（随包带，DeepSeek-V4.1-Flash-1017，MIT）。" +
                        "词表算出来的数一律标「预估」；端点回了 usage 的标「实际」。\n" +
                        "现在这一批只做到「导入与匹配」——真正拿词表数 token 的接线还没接，界面上暂时不会变。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (creating || editing != null) {
        ProfileEditor(
            existing = editing,
            onDismiss = {
                creating = false
                editing = null
            },
            onSave = { draft, bytes ->
                scope.launch {
                    busy = true
                    val id = draft.id
                    val imported = if (bytes != null) {
                        withContext(Dispatchers.IO) { TokenizerProfileStore.import(context, id, bytes) }
                    } else {
                        Result.success(draft.bytes)
                    }
                    busy = false
                    imported.fold(
                        onSuccess = { size ->
                            val saved = draft.copy(bytes = size, importedAt = draft.importedAt)
                            val next = if (editing == null) profiles + saved else {
                                profiles.map { if (it.id == saved.id) saved else it }
                            }
                            persist(next)
                            notice = if (editing == null) "已加一条「${saved.name}」" else "已更新「${saved.name}」"
                            creating = false
                            editing = null
                        },
                        onFailure = { notice = "词表没导进来：${it.message}" },
                    )
                }
            },
        )
    }

    val doomed = confirmDelete
    if (doomed != null) {
        ConfirmDialog(
            title = "删掉「${doomed.name}」？",
            detail = "它导进来的词表文件也会一起删掉（${formatBytes(doomed.bytes)}）。" +
                "之后这一类模型会退回内置那份词表。",
            confirmLabel = "删除",
            onCancel = { confirmDelete = null },
            onConfirm = {
                confirmDelete = null
                scope.launch {
                    TokenizerProfileStore.delete(context, doomed.id).fold(
                        onSuccess = {
                            reload()
                            notice = "已删掉「${doomed.name}」"
                        },
                        onFailure = { notice = "删除失败：${it.message}" },
                    )
                }
            },
        )
    }
}

/** 一条件目：名字 + 规则 + 词表（大小 · 加载时间）+ 四个动作。 */
@Composable
private fun ProfileCard(
    profile: TokenizerProfile,
    first: Boolean,
    last: Boolean,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val broken = !profile.usable
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = profile.name,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = buildString {
                            append(if (profile.matchType == TokenizerMatch.Regex) "正则 " else "关键词 ")
                            append(profile.matchValue)
                            append(" · ")
                            append(
                                if (profile.bytes > 0) {
                                    "${formatBytes(profile.bytes)} · 加载于 ${stamp(profile.importedAt)}"
                                } else {
                                    "词表不见了"
                                },
                            )
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (broken) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 上移 / 下移：顺序就是优先级，所以这两个动作是语义动作，不是装饰。
                SmallText("上移", enabled = !first, onClick = onUp)
                SmallText("下移", enabled = !last, onClick = onDown)
                Spacer(Modifier.weight(1f))
                SmallText("编辑", onClick = onEdit)
                SmallText("删除", danger = true, onClick = onDelete)
            }
        }
    }
}

@Composable
private fun SmallText(
    label: String,
    danger: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        color = when {
            !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
            danger -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.primary
        },
        modifier = Modifier
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
    )
}

/** `2026-11-26 22:41`。用户要能凭这个认出"我导的是不是那一份"。 */
private fun stamp(millis: Long): String {
    if (millis <= 0L) return "未知时间"
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
}

/**
 * 新建 / 编辑一条。
 *
 * **两道门槛都是硬的**：名字、规则、词表缺一条，右上角那颗「保存」就是灰的，
 * 并且下面写着缺什么。用户的原话是"条目才能成功创建"——不是"建了之后再补"。
 */
@Composable
private fun ProfileEditor(
    existing: TokenizerProfile?,
    onDismiss: () -> Unit,
    onSave: (TokenizerProfile, ByteArray?) -> Unit,
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var type by remember { mutableStateOf(existing?.matchType ?: TokenizerMatch.Keyword) }
    var rule by remember { mutableStateOf(existing?.matchValue.orEmpty()) }
    /** 这一次选的词表（还没落盘）：名字与字节数。 */
    var pickedBytes by remember { mutableStateOf<ByteArray?>(null) }
    var pickedName by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("打不开这个文件")
        }.fold(
            onSuccess = { bytes ->
                pickedBytes = bytes
                pickedName = uri.lastPathSegment?.substringAfterLast('/') ?: "tokenizer.json"
                error = null
            },
            onFailure = { error = "读不出来：${it.message}" },
        )
    }

    val ruleProblem = TokenizerProfiles.missingRule(name, rule, type)
    val vocabProblem = TokenizerProfiles.missingVocabulary(
        bytes = pickedBytes?.size?.toLong() ?: existing?.bytes ?: 0L,
        importedAt = if (pickedBytes != null) 1L else existing?.importedAt ?: 0L,
    )

    SheetFrame(title = if (existing == null) "新建词表条目" else "编辑词表条目", onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp),
        ) {
            Text("名字", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SettingField(
                value = name,
                onChange = { name = it.take(TokenizerProfiles.MAX_NAME) },
                placeholder = "例如：Qwen2.5",
                modifier = Modifier.padding(top = 6.dp),
            )

            Text(
                text = "怎么匹配模型",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 14.dp),
            )
            Row(modifier = Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingChip("关键词", selected = type == TokenizerMatch.Keyword) {
                    type = TokenizerMatch.Keyword
                }
                SettingChip("正则", selected = type == TokenizerMatch.Regex) {
                    type = TokenizerMatch.Regex
                }
            }
            SettingField(
                value = rule,
                onChange = { rule = it },
                placeholder = if (type == TokenizerMatch.Regex) "例如：^qwen2\\.5" else "例如：qwen",
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                text = "比的是模型 id（不区分大小写）。带供应商前缀时两边都试：`p1/qwen2.5` 也能命中 `qwen`。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )

            Text(
                text = "tokenizer.json",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 14.dp),
            )
            val have = pickedBytes?.size?.toLong() ?: existing?.bytes ?: 0L
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = ActionIcons.FileUp,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = when {
                            pickedName != null -> "$pickedName · ${formatBytes(have)}（还没保存）"
                            have > 0 -> "${formatBytes(have)} · 加载于 ${stamp(existing?.importedAt ?: 0L)}"
                            else -> "还没选文件"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f).padding(start = 10.dp),
                    )
                    SmallText("选文件") { pickFile.launch("application/json") }
                }
            }
            Text(
                text = "上限 ${TokenizerProfiles.MAX_BYTES / (1024 * 1024)} MB；" +
                    "必须是真的 HuggingFace tokenizer.json（有 model 段、能真切出 token）。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )

            val problem = ruleProblem ?: vocabProblem ?: error
            if (problem != null) {
                Text(
                    text = problem,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(Modifier.weight(1f))
                if (problem != null) {
                    // 两条门槛没过时不给按：用户要的是"必须……才能成功创建"。
                    Text(
                        text = "还差一步",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                } else {
                    ChipButton("保存", primary = true) {
                        val draft = TokenizerProfile(
                            id = existing?.id ?: TokenizerProfileStore.newId(),
                            name = name.trim(),
                            matchType = type,
                            matchValue = rule.trim(),
                            bytes = have,
                            importedAt = if (pickedBytes != null) System.currentTimeMillis()
                            else existing?.importedAt ?: 0L,
                            source = pickedName?.let { "file:$it" } ?: existing?.source.orEmpty(),
                        )
                        onSave(draft, pickedBytes)
                    }
                }
            }
        }
    }
}
