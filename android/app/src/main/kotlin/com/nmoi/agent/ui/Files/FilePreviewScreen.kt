package com.nmoi.agent.ui.Files

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.nmoi.agent.ui.ActionIcons
import com.nmoi.agent.ui.BackButton
import com.nmoi.agent.ui.ChipButton
import com.nmoi.agent.ui.MenuSheet
import com.nmoi.agent.ui.NLog
import com.nmoi.agent.ui.SheetFrame
import com.nmoi.agent.ui.SheetItem
import java.io.File

/**
 * 文件预览页（D392）：**整页、压在页面栈上**，不是浮层。
 *
 * 为什么是整页：看一份文件要占满屏（源码要横向滚、网页要真渲染、图片要缩放），
 * 浮层只能给它半屏，剩下的半屏既不能用来干别的，又挡着它自己。
 *
 * 类型决定页签（`FileKinds.tabs`）：
 * - html / md → 「渲染 / 源码」，默认渲染；
 * - 文本与代码 → 只有源码；
 * - 图片 → 看图；
 * - 其余 → 详情（打开方式 / 分享 / 导出）。
 *
 * 三种读不出来的原因**分开说**：太大、不是文本、读失败。合成一句"读取失败"
 * 会让排查方向直接跑偏。
 */
@Composable
internal fun FilePreviewScreen(
    root: File,
    row: FileRow,
    onBack: () -> Unit,
) {
    val kind = remember(row.path) { FileKinds.kindOf(row.path) }
    val tabs = remember(kind) { FileKinds.tabs(kind) }
    var tab by remember(row.path) { mutableStateOf(FileKinds.defaultTab(kind)) }
    var wrap by remember(row.path) { mutableStateOf(false) }
    var text by remember(row.path) { mutableStateOf<FileText?>(null) }
    var stat by remember(row.path) { mutableStateOf<FileStat?>(null) }
    var reason by remember(row.path) { mutableStateOf<String?>(null) }
    var hint by remember(row.path) { mutableStateOf<String?>(null) }
    var loading by remember(row.path) {
        mutableStateOf(kind != FileKind.Image && kind != FileKind.Binary)
    }

    LaunchedEffect(row.path, kind) {
        when (kind) {
            FileKind.Image -> Unit
            FileKind.Binary -> FileRepository.stat(root, row.path).fold(
                onSuccess = { stat = it },
                onFailure = { FileRepository.warn("读文件信息失败 ${row.path}", it) },
            )

            else -> {
                loading = true
                FileRepository.read(root, row.path).fold(
                    onSuccess = {
                        text = it
                        reason = null
                    },
                    onFailure = {
                        // 二进制文件读文本会回 `BinaryFile`：这不是"出错"，是"它不该按文本看"。
                        // 于是说清是哪一种，而不是给一屏"读取失败"。
                        FileRepository.warn("读文本失败 ${row.path}", it)
                        reason = it.message ?: "读不出来"
                    },
                )
                loading = false
            }
        }
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
                        text = row.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = row.path,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (FileKinds.hasSource(kind)) {
                    WrapToggle(wrap = wrap, onToggle = { wrap = !wrap })
                }
            }

            // 页签条：两个等宽。只有一个页签（文本/代码）时不画——
            // 一条只有一个页签的条子会让人以为另一个加载失败了。
            if (tabs.size > 1) {
                TabStrip(
                    tabs = tabs,
                    selected = tab,
                    onSelect = { tab = it },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            val note = hint
            if (note != null) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            Box(modifier = Modifier.fillMaxSize()) {
                val page = text
                when {
                    loading -> LoadingPage()
                    kind == FileKind.Image -> ImagePage(file = File(root, row.path))
                    kind == FileKind.Binary ->
                        BinaryPage(row = row, stat = stat, onHint = { hint = it })
                    page == null -> UnreadablePage(reason = reason ?: "读不出来")
                    kind == FileKind.Html && tab == FileTab.Render ->
                        HtmlPage(file = File(root, row.path))
                    kind == FileKind.Markdown && tab == FileTab.Render ->
                        MarkdownPage(text = page)
                    else -> SourceView(
                        text = page,
                        language = SourceLexer.languageOf(row.path),
                        wrap = wrap,
                    )
                }
            }

            // 分页读到哪儿了：只有源码页需要（渲染页与图片没有"第几行"这件事）。
            val page = text
            if (page != null && tab == FileTab.Source && !loading) {
                Text(
                    text = page.rangeLabel() +
                        if (page.lossy) " · 有字节不是 UTF-8（按替换字符显示）" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/** 折行开关。只在有源码页的类型上出现。 */
@Composable
private fun WrapToggle(wrap: Boolean, onToggle: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (wrap) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        modifier = Modifier.clickable(onClick = onToggle),
    ) {
        Text(
            text = "折行",
            style = MaterialTheme.typography.labelSmall,
            color = if (wrap) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

/** 页签条：等宽两格，选中态用 `secondaryContainer` 底 + `onSecondaryContainer` 字。 */
@Composable
private fun TabStrip(
    tabs: List<FileTab>,
    selected: FileTab,
    onSelect: (FileTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(4.dp)) {
            for (tab in tabs) {
                val active = tab == selected
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = if (active) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        Color.Transparent
                    },
                    modifier = Modifier.weight(1f).clickable { onSelect(tab) },
                ) {
                    Text(
                        text = if (tab == FileTab.Render) "渲染" else "源码",
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

/**
 * 图片页：按比例塞进可用区域，**不留黑边**（`ContentScale.Fit`）。
 *
 * 只给宽度上限是不够的——一张竖屏截图会把高度顶到整屏。
 */
@Composable
private fun ImagePage(file: File) {
    val bitmap = remember(file.absolutePath, file.lastModified()) {
        runCatching {
            android.graphics.BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
        }.getOrNull()
    }
    Box(
        modifier = Modifier.fillMaxSize().padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap == null) {
            Text(
                text = "这张图解不开（可能不是图片，或者文件不完整）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Image(
                bitmap = bitmap,
                contentDescription = file.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * html 渲染页（`WebView`）。
 *
 * **安全边界，不是可选项**：`file://` 页面里的 JS 默认可能读到工作区里任意文件。
 * 一个模型写的 html 就能把整个工作区读走，所以两条都显式关掉：跨域读、`content://` 访问。
 *
 * base URL 给**所在目录**：页面里的 `<img src="a.png">` 才找得到——
 * 给文件本身的话，相对引用会解析成"文件路径 + a.png"。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun HtmlPage(file: File) {
    var failed by remember(file.absolutePath) { mutableStateOf(false) }
    if (failed) {
        UnreadablePage(reason = "这个网页打不开（${file.name}）")
        return
    }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.allowFileAccess = true
                settings.allowContentAccess = false
                @Suppress("DEPRECATION")
                settings.allowFileAccessFromFileURLs = false
                @Suppress("DEPRECATION")
                settings.allowUniversalAccessFromFileURLs = false
                webViewClient = object : WebViewClient() {
                    override fun onReceivedError(
                        view: WebView?,
                        request: android.webkit.WebResourceRequest?,
                        error: android.webkit.WebResourceError?,
                    ) {
                        // 只报主文档那一跳：页面里的图挂了不该让整页变成"打不开"。
                        if (request?.isForMainFrame == true) failed = true
                    }
                }
                val html = runCatching { file.readText() }.getOrNull()
                if (html == null) {
                    failed = true
                } else {
                    val base = file.parentFile?.toURI()?.toString() ?: "file:///"
                    NLog.i("Files", "渲染网页 ${file.name}（base=$base）")
                    loadDataWithBaseURL(base, html, "text/html", "utf-8", null)
                }
            }
        },
    )
}

/**
 * 改名 / 新建 共用的一张输入卡（D392）。
 *
 * 与工作区页那张同一个形状：底部浮层、一个输入框、取消 / 确定。
 * 用户 2026-10-24 定的「浮层统一用自己画的小窗口」。
 */
@Composable
internal fun NameInputSheet(
    title: String,
    placeholder: String,
    initial: String,
    onCancel: () -> Unit,
    onSubmit: (String) -> Unit,
) {
    var draft by remember(initial) { mutableStateOf(initial) }
    SheetFrame(title = title, onDismiss = onCancel) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp)) {
                    if (draft.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    BasicTextField(
                        value = draft,
                        onValueChange = { draft = it.take(120) },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                ChipButton(label = "取消", primary = false, onClick = onCancel)
                Spacer(Modifier.width(8.dp))
                ChipButton(
                    label = "确定",
                    primary = true,
                    enabled = draft.trim().isNotEmpty(),
                    onClick = { onSubmit(draft.trim()) },
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * 工作区里一行的 `⋯` 面板（D392）：改名 / 复制路径 / 删除。
 *
 * 三条都接上了（不像变更卡片那三个按钮）——这里的动作全在本地文件上，
 * 没有"等下一层"的理由。
 */
@Composable
internal fun FileActionsSheet(
    row: FileRow,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onCopyPath: () -> Unit,
    onDelete: () -> Unit,
) {
    MenuSheet(
        title = row.name,
        items = listOf(
            SheetItem(
                label = "改名",
                icon = ActionIcons.Pencil,
                onClick = {
                    onDismiss()
                    onRename()
                },
            ),
            SheetItem(
                label = "复制路径",
                icon = ActionIcons.Copy,
                onClick = {
                    onDismiss()
                    onCopyPath()
                },
            ),
            SheetItem(
                label = "删除",
                icon = ActionIcons.Trash2,
                onClick = {
                    onDismiss()
                    onDelete()
                },
            ),
        ),
        onHint = {},
        onDismiss = onDismiss,
    )
}
