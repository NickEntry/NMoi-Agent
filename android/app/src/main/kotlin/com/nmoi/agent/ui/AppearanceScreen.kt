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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.nmoi.agent.ui.theme.ThemeMode

/**
 * 外观子页：启动器图标（4 设计 × 黑/白底，D114）。
 *
 * 从设置首页搬过来的——首页现在只留分组入口，一页一件事。
 * 预览用的是**进包的那层前景矢量**叠在底色上，不另画缩略图：
 * 否则会出现"预览好看、装上不是那个样"。
 */
@Composable
internal fun AppearanceScreen(
    choice: IconChoice,
    warning: String?,
    themeMode: ThemeMode,
    onPick: (IconChoice) -> Unit,
    onThemePick: (ThemeMode) -> Unit,
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
                    text = "外观 · 启动器图标",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f).padding(start = 4.dp),
                )
            }

            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                // ---- 主题（D120）----
                SectionLabel("主题")
                Text(
                    text = "「跟随系统」跟着手机的深/浅切换走，不用每天来手动点。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ThemeMode.entries.forEach { mode ->
                        Chip(
                            label = mode.label,
                            selected = themeMode == mode,
                            onClick = { onThemePick(mode) },
                        )
                    }
                }

                // ---- 启动器图标（D114）----
                SectionLabel("启动器图标")
                Text(
                    text = "切完部分桌面要过几秒才刷新，必要时重启桌面。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
                if (warning != null) {
                    Text(
                        text = warning,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }

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
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
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

                Text(
                    text = "没有做 monochrome 层；白底是中心纯白→外圈淡银的径向渐变（drawable，不是 @color）。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
                )

                // ---- 背景（D122–D124，未实现）----
                SectionLabel("聊天页背景")
                Text(
                    text = "纯色 / 图片 / 视频，「跟着当前助手走」：换助手就换背景。视频只用平台硬解，" +
                        "换掉之后旧文件会被清掉。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Text(
                    text = "待接 0.14d（先把「库存在哪、谁拥有、怎么清」定死再画）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )

                // ---- 自定义主题：只留位（D121）----
                SectionLabel("自定义主题")
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // 三个色块只是"将来能改成什么样"的示意，不是当前配色；
                    // 现在点不动，也不写"待接"以外的承诺。
                    listOf(Color(0xFF101014), Color(0xFF1E1E26), Color(0xFF8AB4F8)).forEach { color ->
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(color),
                        )
                    }
                    Text(
                        text = "5 个颜色封顶（背景 / 卡片 / 主色 / 正文 / 次要文字）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
                Text(
                    text = "待接：要先给 settings 加一个 appearance scope（D86 现在是四类）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 6.dp),
    )
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
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
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
        )
    }
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
                                Brush.radialGradient(
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
