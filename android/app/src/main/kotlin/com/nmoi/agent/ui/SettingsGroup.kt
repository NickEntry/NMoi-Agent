package com.nmoi.agent.ui

/**
 * 设置根页的入口（2026-11-07 定）。
 *
 * 一条规矩：**根页只摆"用户会一起想的事"的入口，不摆开关**。
 * 助手 / 供应商 / 世界书 / 网络代理都提到根页——它们各自是一整件事，
 * 塞进「模型与提示词」那种中间层，用户得先猜它在哪一组里。
 *
 * `keywords` 只给根页的搜索用，不上界面。
 */
internal enum class SettingsGroup(val title: String, val keywords: String) {
    Defaults("默认模型&提示词", "默认 模型 提示词 压缩 上下文 窗口 水位线 档位 预设 视觉"),
    Providers("供应商", "供应商 密钥 端点 模型 中转 拉模型"),
    Assistants("助手", "助手 子AGENT 模型 提示词 默认"),
    Lorebook("世界书", "世界书 册 条目 关键词 注入 导入 导出 常驻"),
    Proxy("网络代理", "代理 网络 端口 绕过 容器"),
    Tools("工具与工作区", "工具 工作区 文件 技能 MCP 隔离环境 容器"),
    Appearance("外观", "外观 图标 主题 深色 浅色"),
    App("应用设置", "应用设置 生成 上下文 图片 界面 刷新 档位 阈值"),
    Storage("存储", "存储 附件 图片 导出 删除"),
    Debug("调试", "调试 日志 原生 版本"),
    About("关于", "关于 许可 开源 声明 构建号"),
}

/** 一行"还没接线"的灰行。**不给假开关**：点了没反应比没有这个开关更坏。 */
internal data class PendingRow(val title: String, val subtitle: String, val pending: String)

/** 各页里那些还没接的功能。接了哪一项，就从这里删掉一行，别留在界面上当装饰。 */
internal fun pendingRows(group: SettingsGroup): List<PendingRow> = when (group) {
    SettingsGroup.Defaults -> listOf(
        PendingRow("视觉覆盖", "开着时主模型一个媒体块都不收，先给视觉模型看（D62）", "待接 P3.3"),
    )

    SettingsGroup.Appearance -> listOf(
        PendingRow("主题", "现在只有深色；浅色要跟图标包的 backing: surface 一起调（D105）", "待接 P6.6"),
    )

    SettingsGroup.Tools -> listOf(
        PendingRow("工作区根目录", "绑定一个文件夹后文件工具才可用（D85 / P7.6）", "待接 P7"),
        PendingRow("技能", "只注入 name + description（D80），按对话开关", "待接 P5.5"),
        PendingRow("MCP", "两级门：设置级加载 + 会话级生效", "待接 P7"),
    )

    SettingsGroup.About -> listOf(
        PendingRow("构建号与提交", "骨架阶段 0.0.1-dev；等 release 链路接上再显示提交号", "待接 P9"),
    )

    else -> emptyList()
}
