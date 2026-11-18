package com.nmoi.agent.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 「本地这份跟上游什么关系」的三态：跟随 / 已改 / 过时。
 *
 * 安全页拿它判内置组（目录换了版本没有），预设页那四态由 core 的 `core::preset` 算
 * （词条有 version 与 hash、有真正的上游，规则组两样都没有）——两边共用的是这三个名字
 * 与 [StateBadge] 这一份渲染，判定各在自己那一侧，界面不替 core 推四态。
 */
internal enum class PresetState(val label: String) {
    Follow("跟随"),
    Modified("已改"),
    Stale("过时"),
}

/**
 * `base` 是"写下这一行时上游长什么样"的指纹，`current` 是现在上游的指纹。
 * 没有 base（旧文件）时按已改处理：宁可留着用户的改动，也不冒充"过时"骗他重置。
 */
internal fun presetStateOf(edited: Boolean, base: String, current: String): PresetState = when {
    !edited -> PresetState.Follow
    base.isBlank() || base == current -> PresetState.Modified
    else -> PresetState.Stale
}

internal enum class BadgeTone { Quiet, Edited, Fresh, Warn }

/** core 给的四态字符串 → 徽标色。预设页两处徽标都走这一份，免得哪天只改了一处。 */
internal fun badgeToneOf(wire: String): BadgeTone = when (wire) {
    "conflict" -> BadgeTone.Warn
    "upstream_newer" -> BadgeTone.Fresh
    "local_edited" -> BadgeTone.Edited
    else -> BadgeTone.Quiet
}

/** 四态徽标（预设页）与三态徽标（安全页）共用这一份，四种状态必须长得不一样。 */
@Composable
internal fun StateBadge(label: String, tone: BadgeTone) {
    val colors = MaterialTheme.colorScheme
    val (foreground, background) = when (tone) {
        BadgeTone.Warn -> colors.error to colors.error.copy(alpha = 0.12f)
        BadgeTone.Fresh -> colors.primary to colors.primary.copy(alpha = 0.12f)
        BadgeTone.Edited -> colors.onSurface to colors.onSurface.copy(alpha = 0.08f)
        BadgeTone.Quiet -> colors.onSurfaceVariant to colors.onSurfaceVariant.copy(alpha = 0.08f)
    }
    Surface(shape = RoundedCornerShape(999.dp), color = background) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = foreground,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
}