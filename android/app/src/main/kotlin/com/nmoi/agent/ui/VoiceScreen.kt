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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * 语音服务（0.17b）。
 *
 * 现在只有「歌唱声库」这一段：装、选、删。声库本体**不随包发**——各家授权都禁止再分发，
 * 所以来源是作者发布页的 zip 链接或者你自己打的包。
 *
 * 底下那句"运行时未接"必须留着，而且**不留占位开关**：装上了也还不能唱，
 * 摆一个能拨的开关等于骗人。等引擎运行时进仓，那一行才让人拨。
 */
@Composable
internal fun VoiceScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var banks by remember { mutableStateOf(VoiceBankRepository.installed(context)) }
    var selected by remember { mutableStateOf(VoiceBankRepository.selected(context)) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var sha by remember { mutableStateOf("") }
    var langs by remember { mutableStateOf("zh") }
    var busy by remember { mutableStateOf(false) }
    var phase by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        banks = VoiceBankRepository.installed(context)
        selected = VoiceBankRepository.selected(context)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackButton(onBack = onBack)
            Text(
                text = "语音服务",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 28.dp),
        ) {
            VoiceSectionLabel("歌唱声库")
            VoiceCard {
                Text(
                    text = "DiffSinger / UTAU 的声库不能随包发（授权禁止再分发），" +
                        "所以这里只管装：给一个 zip 的地址，或者自己打一个包放进来。" +
                        "包里要有 dsconfig.yaml 或者 oto.ini。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                )
            }

            if (banks.isEmpty()) {
                VoiceSectionLabel(" ")
                VoiceCard {
                    Text(
                        text = "还没有装声库。下面填一条地址就能装第一个。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
                    )
                }
            }

            for (bank in banks) {
                VoiceSectionLabel(
                    buildString {
                        append(if (bank.kind == "diffsinger") "DiffSinger" else "UTAU")
                        if (bank.languages.isNotBlank()) append(" · ${bank.languages}")
                        append(" · ${mb(bank.bytes)}")
                    },
                )
                VoiceCard {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = bank.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = if (selected == bank.id) "当前用这一个" else "点右边的对号切到它",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                        Text(
                            text = if (selected == bank.id) "✓" else "○",
                            style = MaterialTheme.typography.titleMedium,
                            color = if (selected == bank.id) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier
                                .clickable {
                                    VoiceBankRepository.select(context, bank.id)
                                    selected = bank.id
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                        Text(
                            text = "删除",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .clickable { confirmDelete = bank.id }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                        )
                    }
                }
            }

            VoiceSectionLabel("装一个")
            VoiceCard {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("名字") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("zip 地址") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    )
                    OutlinedTextField(
                        value = langs,
                        onValueChange = { langs = it },
                        label = { Text("语言（zh / ja / en，可留空）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    )
                    OutlinedTextField(
                        value = sha,
                        onValueChange = { sha = it },
                        label = { Text("sha256（可空）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (busy) {
                            Text(
                                text = phase,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                        } else {
                            Spacer(Modifier.size(1.dp))
                        }
                        ChipButton("安装", primary = true, enabled = !busy && name.isNotBlank() && url.isNotBlank()) {
                            busy = true
                            phase = "开始…"
                            scope.launch {
                                val result = VoiceBankRepository.install(
                                    context = context,
                                    name = name,
                                    url = url,
                                    sha256 = sha,
                                    languages = langs,
                                ) { step ->
                                    phase = when (step) {
                                        is VoiceBankRepository.Phase.Download ->
                                            "下载 ${mb(step.bytes)}" + if (step.total > 0) " / ${mb(step.total)}" else ""
                                        is VoiceBankRepository.Phase.Extract -> "解包 ${step.entries} 个条目"
                                        VoiceBankRepository.Phase.Verify -> "校验…"
                                    }
                                }
                                busy = false
                                phase = ""
                                result.fold(
                                    onSuccess = {
                                        notice = "装好了：${it.name}"
                                        name = ""
                                        url = ""
                                        sha = ""
                                        refresh()
                                    },
                                    onFailure = { notice = it.message ?: "装失败" },
                                )
                            }
                        }
                    }
                }
            }

            VoiceSectionLabel("状态")
            VoiceCard {
                Text(
                    text = "声库装得进来，但**引擎运行时还没接进这个 App**——" +
                        "所以「唱歌」那个开关现在仍然是灰的。等运行时进仓，这里装好的声库直接可用。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                )
            }
        }
    }

    notice?.let { text ->
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.32f))
                    .clickable { notice = null },
            )
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            ChipButton("知道了", primary = true) { notice = null }
                        }
                    }
                }
            }
        }
    }

    confirmDelete?.let { id ->
        val bank = banks.firstOrNull { it.id == id }
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.32f))
                    .clickable { confirmDelete = null },
            )
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "删掉「${bank?.name ?: id}」？",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "会删掉这个声库的 ${if (bank != null) mb(bank.bytes) else "全部文件"}，" +
                                "已经渲染出来的音频不受影响。要再用就重新装一次。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            ChipButton("取消", primary = false) { confirmDelete = null }
                            Spacer(Modifier.size(8.dp))
                            ChipButton("删除", primary = true) {
                                VoiceBankRepository.uninstall(context, id).onFailure {
                                    notice = it.message
                                }
                                confirmDelete = null
                                refresh()
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun mb(bytes: Long): String =
    if (bytes >= 1024L * 1024L * 1024L) {
        "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
    } else {
        "%.0f MB".format(bytes / 1024.0 / 1024.0)
    }

@Composable
private fun VoiceSectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 4.dp),
    )
}

@Composable
private fun VoiceCard(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
    ) {
        Column { content() }
    }
}
