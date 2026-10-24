package com.nmoi.agent.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 「关于 → 开源许可」。
 *
 * 读的是**包里真实存在的那几个文件**（`assets/notices/…`），不是写死的字符串：
 * 这些文件由 Gradle 的 `syncNotices` 从仓库根同步，CI 还有一道 `unzip -l` 的闸。
 * 所以这一页能打开，就意味着"声明确实进包了"——它本身就是 D106 的验收手段之一。
 *
 * 反过来，缺文件时**明说缺哪个**，不当成空页：那正是要人去查打包流程的时刻。
 */

/** 一份可查看的文档。`assetPath` 是相对 `assets/` 的路径。 */
internal data class LicenseDoc(
    val assetPath: String,
    val title: String,
    val subtitle: String,
    /** Markdown 原文：界面上不做渲染，加一行说明，免得被当成渲染器坏了。 */
    val markdown: Boolean = false,
)

internal object LicenseAssets {
    private const val Root = "notices"

    /** 本项目自己的许可（GPL-3.0-only 全文）。 */
    const val Own = "$Root/LICENSE"

    /** 第三方组件声明。 */
    const val ThirdParty = "$Root/THIRD-PARTY-NOTICES.md"

    /** 各许可全文所在目录。 */
    const val LicensesDir = "$Root/LICENSES"

    fun read(context: Context, path: String): String? = runCatching {
        context.assets.open(path).bufferedReader().use { it.readText() }
    }.getOrNull()

    fun listLicenseFiles(context: Context): List<String> = runCatching {
        (context.assets.list(LicensesDir) ?: emptyArray()).sorted()
    }.getOrElse { emptyList() }
}

/** 包里有没有、读得出来读不出来，三态分开——"空"和"失败"不能长一个样。 */
private sealed interface DocState {
    data object Loading : DocState

    data object Missing : DocState

    data class Ready(val text: String) : DocState
}

@Composable
internal fun LicensesScreen(onOpenDoc: (LicenseDoc) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    // 列目录也碰 assets，放 IO 上做；返回前先给空列表，界面不至于闪。
    val files by produceState(initialValue = emptyList<String>(), context) {
        value = withContext(Dispatchers.IO) { LicenseAssets.listLicenseFiles(context) }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackButton(onBack = onBack)
                Text(
                    text = "开源许可",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
            }

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item {
                    Text(
                        text = "本页读的是安装包里真实存在的文件（assets/notices/），不联网。" +
                            "它们由构建时从仓库根同步进包，缺一个包就不该发出去。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 10.dp),
                    )
                }

                item {
                    DocRow(
                        title = "本项目许可",
                        subtitle = "GPL-3.0-only 全文（LICENSE）",
                        onClick = {
                            onOpenDoc(
                                LicenseDoc(
                                    assetPath = LicenseAssets.Own,
                                    title = "GPL-3.0-only",
                                    subtitle = "本项目自己的许可全文",
                                ),
                            )
                        },
                    )
                }

                item {
                    DocRow(
                        title = "第三方组件声明",
                        subtitle = "用了什么、什么许可、全文在哪、我们改过什么",
                        onClick = {
                            onOpenDoc(
                                LicenseDoc(
                                    assetPath = LicenseAssets.ThirdParty,
                                    title = "第三方组件声明",
                                    subtitle = "THIRD-PARTY-NOTICES.md 原文",
                                    markdown = true,
                                ),
                            )
                        },
                    )
                }

                item {
                    Text(
                        text = "许可全文（LICENSES/）",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 4.dp),
                    )
                }

                if (files.isEmpty()) {
                    item {
                        Text(
                            text = "包里没找到许可全文：assets/notices/LICENSES/ 是空的。" +
                                "这说明构建时的同步没跑或文件没进包，不是这里显示坏了。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp),
                        )
                    }
                }

                items(files) { name ->
                    DocRow(
                        title = name,
                        subtitle = "逐字副本",
                        onClick = {
                            onOpenDoc(
                                LicenseDoc(
                                    assetPath = "${LicenseAssets.LicensesDir}/$name",
                                    title = name,
                                    subtitle = "许可全文",
                                ),
                            )
                        },
                    )
                }

                item {
                    Text(
                        text = "改名、删行都会让这份声明和上游对不上，所以这些文件是逐字副本（D106）。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 28.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun DocRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Text(
            text = "›",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/**
 * 全文页。
 *
 * 两件刻意的选择：
 * - **按段落切进 `LazyColumn`**，不用一个巨大的 `Text` 套 `verticalScroll`：
 *   GPL 全文 681 行，一次性量一遍会在中低端机上卡出白屏。
 * - **等宽字体**：许可证正文是有换行语义的文本，比例字体下条款编号会散架。
 *   Markdown 原文（声明那份）也一样原样显示——渲染器还没接，不假装渲染过。
 */
@Composable
internal fun LicenseDocScreen(doc: LicenseDoc, onBack: () -> Unit) {
    val context = LocalContext.current
    val state by produceState<DocState>(initialValue = DocState.Loading, doc.assetPath) {
        val text = withContext(Dispatchers.IO) { LicenseAssets.read(context, doc.assetPath) }
        value = if (text == null) DocState.Missing else DocState.Ready(text)
    }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1600)
            copied = false
        }
    }

    val ready = state as? DocState.Ready

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackButton(onBack = onBack)
                Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                    Text(
                        text = doc.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                    )
                    Text(
                        text = if (copied) "已复制全文" else doc.subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (copied) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                    )
                }
                if (ready != null) {
                    IconActionButton(
                        image = ActionIcons.Copy,
                        description = "复制全文",
                        onClick = {
                            val manager = context.getSystemService(ClipboardManager::class.java)
                            manager?.setPrimaryClip(
                                ClipData.newPlainText(doc.title, ready.text),
                            )
                            copied = true
                        },
                    )
                }
            }

            when (val now = state) {
                DocState.Loading -> StateMessage(message = "正在读…", error = false)
                DocState.Missing -> StateMessage(
                    message = "包里没有这份文件：assets/${doc.assetPath}\n" +
                        "不是这一页坏了，是构建时没把它同步进包（见 docs/packaging.md 的 syncNotices）。",
                    error = true,
                )

                is DocState.Ready -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    if (doc.markdown) {
                        item {
                            Text(
                                text = "下面是 Markdown 原文，没有渲染——渲染器还没接，不做假渲染。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            )
                        }
                    }
                    items(now.text.split("\n\n")) { paragraph ->
                        Text(
                            text = paragraph,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

/** 加载中 / 缺文件：两句话各说各的，别合成一句"暂无内容"。 */
@Composable
private fun StateMessage(message: String, error: Boolean) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = if (error) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}
