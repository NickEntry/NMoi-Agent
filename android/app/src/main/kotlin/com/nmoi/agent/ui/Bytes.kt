package com.nmoi.agent.ui

import java.util.Locale

/**
 * 人看的体积。日志页上出现 `8380416 字节` 没人愿意换算，而"还剩多少"正是用户点进去的理由。
 *
 * 固定 `Locale.US`：有些区域用逗号当小数点，`1,5 MB` 在中文界面里会被读成两个数。
 */
internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}
