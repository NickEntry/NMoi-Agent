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
    var confirmReinstall by remember { mutableStateOf(false) }
    var probe by remember { mutableStateOf<EnvirRepository.Probe?>(null) }
    var probing by remember { mutableStateOf(false) }

    fun onPhase(phase: EnvirRepository.Phase) {
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
            EnvirRepository.Phase.Verify -> phaseText = "自检中…（/bin/sh + ldd）"
            EnvirRepository.Phase.Cleanup -> phaseText = "收尾…"
        }
    }

    fun start(overlay: Boolean) {
        val source = when {
            overlay -> null
            customSelected -> {
                val url = customUrl.trim()
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    notice = "地址要以 http:// 或 https:// 开头"
                    return
                }
                EnvirRepository.Source(
                    label = "自定义来源",
                    url = url,
                    sha256 = null,
                    bytes = 0L,
                )
            }
            else -> EnvirRepository.UbuntuBase
        }
        notice = null
        running = true
        progress = null
        phaseText = if (overlay) "覆盖安装：下载基础层…" else "复制运行时…"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                if (overlay) {
                    EnvirRepository.repair(context) { phase -> onPhase(phase) }
                } else {
                    EnvirRepository.install(context, source!!) { phase -> onPhase(phase) }
                }
            }
            running = false
            progress = null
            result.fold(
                onSuccess = { done ->
                    installed = done
                    phaseText = when (done.health) {
                        EnvirRepository.Health.Ok -> "装好了，自检通过"
                        EnvirRepository.Health.NeedsRepair -> "装完了，但自检没过"
                        EnvirRepository.Health.Unchecked -> "装好了"
                    }
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
                                    "运行时（proot + 两个 .so，约 300 KB）随 APK 装好、不用联网，" +
                                    "rootfs（33.4 MB）点安装时下载。",
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
                            val check = current.selfCheck
                            when {
                                check == null -> Text(
                                    text = "装完之后没自检过（这一版之前装的）：点「覆盖安装」会顺带自检",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                                check.ok -> Text(
                                    text = "自检通过 · ${time(check.at)} · /bin/sh 与 ldd 都正常",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                                else -> {
                                    Text(
                                        text = "需要修复：${check.reason}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.padding(top = 4.dp),
                                    )
                                    Text(
                                        text = buildString {
                                            append("点「覆盖安装」只重解基础层，已装的文件不删")
                                            if (current.repairedAt > 0) {
                                                append("；上次覆盖安装 ${time(current.repairedAt)}")
                                            }
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 2.dp),
                                    )
                                }
                            }
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
                            Text(
                                text = current.linkNote,
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
                            text = "运行时（随 APK 装在 lib 目录里）",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        for ((file, bytes) in EnvirRepository.runtimeBytes(context)) {
                            Text(
                                text = "${file.label} · ${file.version} · " +
                                    if (bytes >= 0) "$bytes 字节" else "这个 APK 里没有",
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
                                "四份证据：① `proot --version`；② 进容器读 `os-release` + `uname -r`；" +
                                    "③ 容器里的 DNS 与一次真的域名解析（`getent hosts archive.ubuntu.com`）；" +
                                    "④ `/bin/sh -c 'echo ok'` + `ldd /bin/sh`（装完自动跑一次，结果记进 state.json）。"
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
                        // 第三份证据单独一行：三个 chip 挤在一行，窄屏上第三个会被截掉半截字。
                        Row(modifier = Modifier.padding(top = 8.dp)) {
                            DialogChip(
                                label = "容器联网",
                                enabled = current != null && !probing,
                            ) {
                                probing = true
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        EnvirRepository.probe(
                                            context,
                                            EnvirRepository.networkCommand(context, current!!.rootfs),
                                        )
                                    }
                                    probe = result.getOrElse {
                                        EnvirRepository.Probe(
                                            command = "proot -r <rootfs> getent hosts",
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
                            Text(
                                text = if (EnvirRepository.dnsServers(context).isEmpty()) {
                                    "设备当前没有 DNS：这一条只会证明「解析不了」"
                                } else {
                                    "文件 ${EnvirRepository.resolvFile(context).name}，启动前现写"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .align(Alignment.CenterVertically)
                                    .padding(start = 10.dp)
                                    .weight(1f),
                            )
                        }
                        Row(modifier = Modifier.padding(top = 8.dp)) {
                            DialogChip(
                                label = "sh + ldd",
                                enabled = current != null && !probing,
                            ) {
                                probing = true
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        EnvirRepository.recheck(context)
                                    }
                                    result.fold(
                                        onSuccess = { verify -> probe = verify.probe },
                                        onFailure = { error ->
                                            probe = EnvirRepository.Probe(
                                                command = "sh -c 'echo ok'; ldd /bin/sh",
                                                exitCode = null,
                                                stdout = "",
                                                stderr = error.message ?: "起不来",
                                                durationMs = 0,
                                                timedOut = false,
                                            )
                                        },
                                    )
                                    installed = EnvirRepository.installed(context)
                                    probing = false
                                }
                            }
                            Text(
                                text = "装完自动跑；手动跑一次也会把结果写回 state.json",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .align(Alignment.CenterVertically)
                                    .padding(start = 10.dp)
                                    .weight(1f),
                            )
                        }
                        Row(modifier = Modifier.padding(top = 8.dp)) {
                            DialogChip(
                                label = "清容器垫片",
                                enabled = current != null && !probing,
                            ) {
                                probing = true
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        ProxyContainer.removeShim(context)
                                    }
                                    // 清完立刻复检：用户点这一颗就是为了"容器能不能起来"这个答案。
                                    val verify = withContext(Dispatchers.IO) {
                                        EnvirRepository.recheck(context)
                                    }
                                    probe = verify.getOrNull()?.probe
                                    installed = EnvirRepository.installed(context)
                                    notice = result.getOrElse { "清除失败：${it.message}" }
                                    probing = false
                                }
                            }
                            Text(
                                text = "容器起不来时先点这个：老版本往 /etc/ld.so.preload 写过一行，" +
                                    "那会让每个程序都起不来（D237）",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .align(Alignment.CenterVertically)
                                    .padding(start = 10.dp)
                                    .weight(1f),
                            )
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
                    if (installed != null) {
                        Hint("「覆盖安装」用的是已装那一份的来源（state.json 里那条）；换来源要走「重新安装」。")
                    }
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

                WideButton(
                    label = when {
                        running -> "正在装…"
                        installed == null -> "安装"
                        else -> "覆盖安装"
                    },
                    enabled = !running,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                ) {
                    start(overlay = installed != null)
                }

                if (installed != null) {
                    Text(
                        text = "覆盖安装 = 保留已装的文件，只把基础层重解一遍，装完自动自检。" +
                            "要重新下 33.4 MB（和装的时候一样）。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !running, onClick = { confirmReinstall = true })
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Text(
                            text = "重新安装（清空后重解）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        ConfirmDialog(
            title = "删除“${installed?.label ?: "隔离环境"}”？",
            detail = "会删掉 $size，下次要重新下载 33.4 MB。工作区里的文件不受影响。",
            confirmLabel = "删除",
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

    if (confirmReinstall) {
        ConfirmDialog(
            title = "重新安装“${installed?.label ?: "隔离环境"}”？",
            detail = "会先清空 rootfs（约 110 MB）再重解一遍：容器里你自己装的东西会没掉，" +
                "工作区里的文件不受影响。只想修好容器的话，用「覆盖安装」。",
            confirmLabel = "清空重装",
            onCancel = { confirmReinstall = false },
            onConfirm = {
                confirmReinstall = false
                start(overlay = false)
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

private fun mb(bytes: Long): String = String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)

private fun time(millis: Long): String =
    if (millis <= 0) "时间未知" else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(millis))
