package com.nmoi.agent.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 隔离环境（D165）：装 / 看 / 删 / 自检。
 *
 * 这一页的三条规矩，都是被前面的坑逼出来的：
 *
 * 1. **不给假开关**：`WorkSpace-EnvirShell` 那个工具还没接，所以页面上没有它的开关；
 *    这里只有"装了什么"和"能不能跑"。
 * 2. **每个数字都能对上**：文件数、链接数、跳过数、三个二进制的字节数都摆在界面上——
 *    出了问题用户能直接念出来，不用先教他 adb。
 * 3. **收不回来的先说清**：删除会把 rootfs（110 MB）和运行时一起清掉，红色实心确认。
 */
@Composable
internal fun EnvirScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var installed by remember { mutableStateOf(EnvirRepository.installed(context)) }
    var customSelected by remember { mutableStateOf(false) }
    var customUrl by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var phaseText by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf<Float?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var probe by remember { mutableStateOf<EnvirRepository.Probe?>(null) }
    var probing by remember { mutableStateOf(false) }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackButton(onBack = onBack)
                Text(
                    text = "隔离环境",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
            }

            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                SectionLabel("状态")
                val current = installed
                GroupCard {
                    if (current == null) {
                        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                            Text(
                                text = "还没装",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "装一次 = 一个能在里面跑 Ubuntu 的私有目录。" +
                                    "运行时（proot + 两个 .so，约 300 KB）从 APK 里复制，rootfs（33.4 MB）联网下载。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    } else {
                        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                            Text(
                                text = current.label,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            MonoText(current.rootfs)
                            Text(
                                text = buildString {
                                    append("下载 ${mb(current.archiveBytes)}")
                                    append(" · 解包 ${current.files} 文件 / ${current.dirs} 目录")
                                    append(" · 链接 ${current.symlinks}+${current.hardLinks}")
                                    append(" · ${time(current.installedAt)}")
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                            Text(
                                text = current.skippedNote,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                            if (!current.sha256.isNullOrEmpty()) {
                                MonoText("sha256 ${current.sha256.take(16)}…")
                            } else {
                                Text(
                                    text = "自定义来源：没做校验和核对",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                    }

                    RowDivider()
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Text(
                            text = "运行时",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        for ((file, bytes) in EnvirRepository.runtimeBytes(context)) {
                            Text(
                                text = "${file.name} · ${file.version} · " +
                                    if (bytes >= 0) "$bytes 字节" else "还没复制",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 3.dp),
                            )
                        }
                    }

                    if (current != null) {
                        RowDivider()
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "删除隔离环境",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable(onClick = { confirmDelete = true }),
                            )
                            Text(
                                text = "连运行时一起清掉",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                SectionLabel("自检")
                GroupCard {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Text(
                            text = if (current == null) {
                                "装完再自检。`proot --version` 只证明三件套能被链接器拉起来；" +
                                    "进容器那句才会告诉你 rootfs 到底能不能跑。"
                            } else {
                                "两份证据：① `proot --version`；② 进容器读 `os-release` + `uname -r`。"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(modifier = Modifier.padding(top = 8.dp)) {
                            DialogChip(
                                label = if (probing) "跑着呢…" else "proot --version",
                                enabled = current != null && !probing,
                            ) {
                                probing = true
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        EnvirRepository.probe(context, EnvirRepository.versionCommand(context))
                                    }
                                    probe = result.getOrElse {
                                        EnvirRepository.Probe(
                                            command = "proot --version",
                                            exitCode = null,
                                            stdout = "",
                                            stderr = it.message ?: "起不来",
                                            durationMs = 0,
                                            timedOut = false,
                                        )
                                    }
                                    probing = false
                                }
                            }
                            Spacer(Modifier.size(8.dp))
                            DialogChip(
                                label = "进容器",
                                enabled = current != null && !probing,
                            ) {
                                probing = true
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        EnvirRepository.probe(
                                            context,
                                            EnvirRepository.rootfsCommand(context, current!!.rootfs),
                                        )
                                    }
                                    probe = result.getOrElse {
                                        EnvirRepository.Probe(
                                            command = "proot -r <rootfs>",
                                            exitCode = null,
                                            stdout = "",
                                            stderr = it.message ?: "起不来",
                                            durationMs = 0,
                                            timedOut = false,
                                        )
                                    }
                                    probing = false
                                }
                            }
                        }
                        probe?.let { result ->
                            Text(
                                text = "退出码 ${result.exitCode ?: "无"} · ${result.durationMs} ms" +
                                    if (result.timedOut) " · **超时**" else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (result.exitCode == 0) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                                modifier = Modifier.padding(top = 10.dp),
                            )
                            val body = (result.stdout + result.stderr).trim()
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceContainer,
                                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                            ) {
                                Text(
                                    text = body.ifEmpty { "（没有输出）" },
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.padding(10.dp),
                                )
                            }
                        }
                    }
                }

                SectionLabel("来源")
                GroupCard {
                    SourceRow(
                        title = "Ubuntu Base 26.04.1 LTS",
                        subtitle = "arm64 · 33.4 MB · 校验 SHA-256",
                        selected = !customSelected,
                        enabled = !running,
                        onClick = { customSelected = false },
                    )
                    RowDivider()
                    SourceRow(
                        title = "自定义 .tar.gz 地址",
                        subtitle = "不校验哈希（装之前自己确认来源）",
                        selected = customSelected,
                        enabled = !running,
                        onClick = { customSelected = true },
                    )
                    if (customSelected) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                        ) {
                            Box(modifier = Modifier.padding(horizontal = 10.dp, vertical = 9.dp)) {
                                if (customUrl.isEmpty()) {
                                    Text(
                                        text = "https://…/arm64-rootfs.tar.gz",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                BasicTextField(
                                    value = customUrl,
                                    onValueChange = { customUrl = it },
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    ),
                                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                    Hint("只认 `.tar.gz`：XZ 没有 JVM 自带解码器（`.deb` 那条路就是这么断的，见 D164 修正）。")
                }

                if (running || phaseText.isNotEmpty()) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text(
                            text = phaseText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val value = progress
                        if (value != null) {
                            LinearProgressIndicator(
                                progress = { value },
                                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                            )
                        } else if (running) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                        }
                    }
                }

                PrimaryButton(
                    label = when {
                        running -> "正在装…"
                        installed != null -> "重新安装"
                        else -> "安装"
                    },
                    enabled = !running,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    val source = if (customSelected) {
                        val url = customUrl.trim()
                        if (!url.startsWith("http://") && !url.startsWith("https://")) {
                            notice = "地址要以 http:// 或 https:// 开头"
                            return@PrimaryButton
                        }
                        EnvirRepository.Source(
                            label = "自定义来源",
                            url = url,
                            sha256 = null,
                            bytes = 0L,
                        )
                    } else {
                        EnvirRepository.UbuntuBase
                    }
                    notice = null
                    running = true
                    progress = null
                    phaseText = "复制运行时…"
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            EnvirRepository.install(context, source) { phase ->
                                when (phase) {
                                    EnvirRepository.Phase.Runtime -> phaseText = "复制运行时…"
                                    is EnvirRepository.Phase.Download -> {
                                        phaseText = buildString {
                                            append("下载 rootfs ")
                                            append(mb(phase.bytes))
                                            if (phase.total > 0) {
                                                append(" / ")
                                                append(mb(phase.total))
                                                append("（")
                                                append(phase.bytes * 100 / phase.total)
                                                append("%）")
                                                progress = (phase.bytes.toFloat() / phase.total).coerceIn(0f, 1f)
                                            }
                                        }
                                    }
                                    is EnvirRepository.Phase.Extract ->
                                        phaseText = "解包中… 已处理 ${phase.entries} 条"
                                    EnvirRepository.Phase.Cleanup -> phaseText = "收尾…"
                                }
                            }
                        }
                        running = false
                        progress = null
                        result.fold(
                            onSuccess = { done ->
                                installed = done
                                phaseText = "装好了"
                                notice = null
                            },
                            onFailure = { error ->
                                installed = EnvirRepository.installed(context)
                                phaseText = ""
                                notice = "没装成：${error.message}"
                            },
                        )
                    }
                }

                val line = notice
                if (line != null) {
                    Text(
                        text = line,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }

                SectionLabel("要知道的限制")
                GroupCard {
                    Text(
                        text = EnvirRepository.NO_KERNEL_NOTE,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    )
                    RowDivider()
                    Text(
                        text = "装好之后 `WorkSpace-EnvirShell` 才能接（下一步）；现在模型还用不到这个环境，" +
                            "页面上也没有那个开关。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    )
                }

                Spacer(Modifier.height(28.dp))
            }
        }
    }

    if (confirmDelete) {
        val size = installed?.let { "rootfs（约 110 MB）和运行时（约 300 KB）" } ?: "已装的东西"
        ConfirmDeleteDialog(
            name = installed?.label ?: "隔离环境",
            detail = "会删掉 $size，下次要重新下载 33.4 MB。工作区里的文件不受影响。",
            onCancel = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                running = true
                phaseText = "删除中…"
                scope.launch {
                    val result = withContext(Dispatchers.IO) { EnvirRepository.delete(context) }
                    running = false
                    phaseText = ""
                    probe = null
                    installed = EnvirRepository.installed(context)
                    result.onFailure { notice = "删除失败：${it.message}" }
                }
            },
        )
    }
}

// ---------------------------------------------------------------------------
// 小控件（形状跟别的设置页一套；这一页自己留着，不改公共文件）
// ---------------------------------------------------------------------------

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 4.dp),
    )
}

@Composable
private fun GroupCard(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
    ) {
        Column { content() }
    }
}

@Composable
private fun RowDivider() {
    HorizontalDivider(
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        modifier = Modifier.padding(start = 14.dp),
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
private fun MonoText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/** 选中态靠**底色 + 勾**两个信号，不是整片灰（ui-taste 1.2 / 形制偏好）。 */
@Composable
private fun SourceRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(start = 14.dp, end = 14.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            if (selected) {
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "✓",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                }
            }
        }
    }
}

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
        modifier = modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled, onClick = onClick)
                .padding(vertical = 13.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun DialogChip(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (enabled) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            },
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

/** 删除确认：红色实心 + 写清会没掉什么（ui-taste 2026-10-26 第三报）。 */
@Composable
private fun ConfirmDeleteDialog(
    name: String,
    detail: String,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.32f))
                .clickable(onClick = onCancel),
        )
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "删除“$name”？",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        DialogChip("取消", onClick = onCancel)
                        Spacer(Modifier.size(8.dp))
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.clickable(onClick = onConfirm),
                        ) {
                            Text(
                                text = "删除",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onError,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun mb(bytes: Long): String = String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)

private fun time(millis: Long): String =
    if (millis <= 0) "时间未知" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(millis))
