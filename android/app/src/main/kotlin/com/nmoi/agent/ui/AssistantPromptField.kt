package com.nmoi.agent.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 系统提示词的输入框（D435）。用户 2026-11-26 的原话：
 *
 * 「助手设置里面记得把系统提示词的输入框放好，输入框右上方两个按钮，
 *   左边的是放大(图标样式是一个叉号，然后四段都是箭头)右边的是导入文件按钮」
 *
 * # 两个按钮都放在框内右上角
 *
 * 放在框外会把卡片撑高一行，而这一页已经有十来张卡；放在框内右上角，视觉上属于这个框。
 * 按钮热区 32（框内空间有限），视觉 16——比正文小一档，不抢内容。
 *
 * - **放大**（[ActionIcons.Expand]）：叉号加四段朝外的箭头。点开整屏编辑——
 *   系统提示词常常是几十行，在助手页那个 4 行的框里改是折磨。
 * - **导入**（[ActionIcons.FileUp]）：从文件读一份文本。**不覆盖式静默替换**：
 *   已经有内容时先问一句，因为「我点了导入，写了一半的那段没了」是收不回来的。
 *
 * # 为什么整屏那个用 [MessageEditField]
 *
 * 与编辑消息那页同一份：长文本走原生 `EditText`（Compose 的 IME 镜像在几千字上会露馅）。
 * 不重写第二个编辑器。
 */
@Composable
internal fun AssistantPromptField(
    value: String,
    onChange: (String) -> Unit,
    /** 提示词里能用的站位字符串（这一层现在没有，留参数是为了以后加了不用改调用点）。 */
    vars: List<String> = emptyList(),
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    /** 已经选了文件、还没确认替换的那一份（确认之后才读）。 */
    var confirmReplace by remember { mutableStateOf<String?>(null) }
    /** 确认过了、正在读的那一份。读完清空——它只活一次。 */
    var reading by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    // 读一份文本文件。上限 256 KB：再大就不是"一段提示词"了，
    // 而整段塞进 settings 之后每一轮请求都要把它带上。
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        confirmReplace = uri.toString()
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (value.isBlank()) "留空 = 这一层不加东西" else "${value.length} 字",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                // 左边：放大。图标是叉号 + 四段朝外的箭头。
                PromptIconButton(
                    image = ActionIcons.Expand,
                    description = "放大编辑",
                    onClick = { expanded = true },
                )
                Spacer(Modifier.size(2.dp))
                // 右边：导入文件。
                PromptIconButton(
                    image = ActionIcons.FileUp,
                    description = "从文件导入",
                    onClick = { pickFile.launch("text/*") },
                )
            }
            Box(modifier = Modifier.fillMaxWidth()) {
                if (value.isBlank()) {
                    Text(
                        text = "例如：只输出结果，不复述要求",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp, bottom = 2.dp),
                    )
                }
                // 只读的一段预览：真编辑在整屏那一页（框里放不下几十行）。
                if (value.isNotBlank()) {
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { expanded = true }
                            .padding(top = 2.dp, bottom = 2.dp),
                    )
                }
            }
            if (vars.isNotEmpty()) {
                Text(
                    text = "变量：" + vars.joinToString(" "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            notice?.let { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }

    if (expanded) {
        AssistantPromptFullScreen(
            value = value,
            onChange = onChange,
            onDismiss = { expanded = false },
        )
    }

    // 导入前问一句：已经有内容时它是"替换"，不是"追加"。
    val pending = confirmReplace
    if (pending != null) {
        val hasText = value.isNotBlank()
        ConfirmDialog(
            title = if (hasText) "替换现有的系统提示词？" else "导入这份文件？",
            detail = if (hasText) {
                "现在这段（${value.length} 字）会被文件里的内容整段替换，收不回来。"
            } else {
                "文件里的文本会成为这一层的系统提示词。"
            },
            confirmLabel = "导入",
            onCancel = { confirmReplace = null },
            onConfirm = {
                confirmReplace = null
                expanded = false
                notice = null
                // 读文件在 IO 上：一份 200 KB 的文本在主线程读会掉帧。
                reading = pending
            },
        )
    }

    // 真正读文件的那一下。key 是 uri：同一份文件连选两次也会重读（用户可能在外面改过它）。
    LaunchedEffect(reading) {
        val target = reading ?: return@LaunchedEffect
        val result = withContext(Dispatchers.IO) { readText(context, Uri.parse(target)) }
        reading = null
        result.fold(
            onSuccess = { text ->
                onChange(text)
                notice = null
            },
            onFailure = { notice = "读不出来：${it.message}" },
        )
    }
}

/**
 * 从 `uri` 读一份文本。**两道闸**：大小上限与编码。
 *
 * 上限 256 KB 与 `read_file` 那一套一致；超了报清"多少 KB、上限多少"，
 * 不截断——截一半的提示词比没导入更难发现。
 */
private fun readText(context: Context, uri: Uri): Result<String> = runCatching {
    val stream = context.contentResolver.openInputStream(uri) ?: error("打不开这个文件")
    val bytes = stream.use { it.readBytes() }
    val limit = 256 * 1024
    if (bytes.size > limit) {
        error("这份文件 ${bytes.size / 1024} KB，超过 ${limit / 1024} KB 上限；先裁一段再导入")
    }
    // BOM 去掉：留着它，请求里第一行会多一个看不见的字符。
    String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
}

/** 框内右上角那一颗小按钮：热区 32、视觉 16。 */
@Composable
private fun PromptIconButton(
    image: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = image,
            contentDescription = description,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * 整屏编辑系统提示词。
 *
 * 顶栏只有「收起」与字数：这一页没有保存按钮——`AssistantEditScreen` 那边是
 * 停手 300 ms 落库的，这里改的也是同一个 `value`，退出去就跟着存。
 */
@Composable
private fun AssistantPromptFullScreen(
    value: String,
    onChange: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler { onDismiss() }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) { Text("收起") }
                Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(
                        text = "系统提示词",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        text = "${value.length} 字 · 停手就存",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            MessageEditField(
                value = value,
                onValueChange = onChange,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp),
            )
        }
    }
}

