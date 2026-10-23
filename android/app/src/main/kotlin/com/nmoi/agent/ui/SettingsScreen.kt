package com.nmoi.agent.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

/**
 * 设置页。目前三节：外观（启动器图标）、调试（日志开关）、关于。
 *
 * 顺序是刻意的：外观在第一位——它是用户唯一会主动进来改的东西；
 * 调试开关放中间，别让它喧宾夺主；关于放最后，只有一串版本信息。
 */
@Composable
internal fun SettingsScreen(
    choice: IconChoice,
    warning: String?,
    nativeStatus: NativeStatus,
    logEnabled: Boolean,
    onPick: (IconChoice) -> Unit,
    onLogToggle: (Boolean) -> Unit,
    onOpenLogs: () -> Unit,
    onOpenPrompts: () -> Unit,
    onBack: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackButton(onBack = onBack)
                Text(
                    text = "设置",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
            }

            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                if (warning != null) {
                    Text(
                        text = warning,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }

                SectionTitle("外观 · 启动器图标")
                Text(
                    text = "切完部分桌面要过几秒才刷新，必要时重启桌面。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    IconPlate.entries.forEach { plate ->
                        PlateChip(
                            plate = plate,
                            selected = choice.plate == plate,
                            onClick = { onPick(IconChoice(choice.design, plate)) },
                        )
                    }
                }
                // 网格固定两列、高度自撑：图标只有 4 个，不需要滚动容器，
                // 用 LazyVerticalGrid 会被套进外层 verticalScroll 里（两者都无限高），所以换成手排。
                Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    IconDesign.entries.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            pair.forEach { design ->
                                Box(modifier = Modifier.weight(1f)) {
                                    DesignCard(
                                        design = design,
                                        plate = choice.plate,
                                        selected = choice.design == design,
                                        onClick = { onPick(IconChoice(design, choice.plate)) },
                                    )
                                }
                            }
                            if (pair.size == 1) Box(modifier = Modifier.weight(1f))
                        }
                    }
                }

                SectionTitle("会话与上下文")
                SettingRow(
                    title = "自动压缩建议线",
                    subtitle = "已到 ${"75"}% 时先压一轮（D90 / D97）",
                    pending = "待接 P4.2",
                )
                SettingRow(
                    title = "自动压缩绝对线",
                    subtitle = "已到 ${"92"}% 时先压再发；生成中过线则截断并标 incomplete",
                    pending = "待接 P4.2",
                )
                SettingRow(
                    title = "视觉覆盖",
                    subtitle = "开着时主模型一个媒体块都不收，先给视觉模型看（D62）",
                    pending = "待接 P3.3",
                )

                SectionTitle("模型与场景提示词")
                // 这一行能点：进去看七条提示词的键名与必填占位符（只读，PromptsScreen）。
                Box(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenPrompts)) {
                    SettingRow(
                        title = "默认模型与七条压缩提示词",
                        subtitle = "draft / fallback.strip / fallback.merge / single / segment / merge_segments / verify",
                        pending = "查看",
                    )
                }
                SettingRow(
                    title = "五类角色（标题 / 压缩 / 翻译 / 视觉 / 建议）",
                    subtitle = "角色配置与默认值已在 core::roles，界面未接",
                    pending = "待接 P3",
                )

                SectionTitle("工具与工作区")
                SettingRow(
                    title = "工作区根目录",
                    subtitle = "绑定一个文件夹后文件工具才可用（D85 / P7.6）",
                    pending = "待接 P7",
                )
                SettingRow(
                    title = "技能与 MCP",
                    subtitle = "技能只注入 name + description（D80）；MCP 两级门",
                    pending = "待接 P5.5 / P7",
                )

                SectionTitle("调试 · 日志")
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        // D103 的三个例外之一：设置里的开关项标题必须是文字。
                        Text(
                            text = "写入调试日志",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = if (logEnabled) {
                                "只关/开「调试日志」这一路。它双写：logcat + 应用内文件（ROM 吞 I 级时只剩文件能看）。"
                            } else {
                                "调试日志已关：logcat 与文件都不写。另外两路在「日志」页的齿轮里各自开关。"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = logEnabled, onCheckedChange = onLogToggle)
                }
                NavRow(
                    title = "日志",
                    subtitle = "上下文 / 请求 / 调试三种通道各自一个文件，默认上限 8 MB；点进去看、复制、导出、清除",
                    onClick = onOpenLogs,
                )

                SectionTitle("关于")
                Text(
                    text = nativeStatus.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (nativeStatus.ok) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                Text(
                    text = "骨架阶段 0.0.1-dev · GPL-3.0-only · 第三方声明在 APK 的 assets/notices/",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 24.dp),
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 6.dp),
    )
}

@Composable
private fun PlateChip(plate: IconPlate, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Surface(
        shape = shape,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        modifier = Modifier.clip(shape).clickable(onClick = onClick),
    ) {
        Text(
            text = plate.label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun DesignCard(
    design: IconDesign,
    plate: IconPlate,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.clip(shape).clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(14.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 预览用进包的那层前景矢量叠在底色上，不另画缩略图——
            // 否则"预览好看、装上不是那个样"（D114）。
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .clip(RoundedCornerShape(19.dp))
                    .background(if (plate == IconPlate.Black) Color(0xFF14121C) else Color.White)
                    .then(
                        if (selected) {
                            Modifier.border(
                                2.dp,
                                MaterialTheme.colorScheme.primary,
                                RoundedCornerShape(19.dp),
                            )
                        } else {
                            Modifier
                        },
                    ),
            ) {
                if (plate == IconPlate.Black) {
                    Image(
                        painter = painterResource(design.foregroundFor(plate)),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    // 白底是渐变（中心纯白→外圈淡银），Compose 里用两层 Box 近似：
                    // 这里只做预览，真正进包的是 res/drawable/bg_appicon_light.xml。
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                androidx.compose.ui.graphics.Brush.radialGradient(
                                    listOf(Color.White, Color(0xFFF0F1F6), Color(0xFFE4E5EC)),
                                ),
                            ),
                    ) {
                        Image(
                            painter = painterResource(design.foregroundFor(plate)),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
            Text(
                text = design.label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 10.dp),
            )
            Text(
                text = if (selected) "当前使用" else " ",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * 一行设置项。
 *
 * 目前 [SettingRow] 只用来摆**还没接线**的项：整行灰、右边写"待接 XX"——**不给假开关**：
 * 一个点了没反应的 Switch 比没有这个开关更坏，用户会以为设置坏了。
 * 接了核心的项（日志开关）走它自己的那个 Row 与真的 Switch，
 * 标题必须是文字（D103 的三个例外之一）。等某项接线了，就从 SettingRow 换成能点的控件。
 */
/**
 * 能点进去的一行：跟 [SettingRow] 长得一样，但**不许**带"待接"字样——
 * 点进去没东西可看的口子比没有入口更坏。
 */
@Composable
private fun NavRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
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
            )
        }
        Text(
            text = "›",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String,
    pending: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = pending,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}
