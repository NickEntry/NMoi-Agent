package com.nmoi.agent.ui

/**
 * 设置的分组（索引页上的六行）。**一页一件事**：索引页只摆入口和"现在是什么"，
 * 开关与表单都在子页里。
 *
 * 为什么不分得更细：这六个分组各自对应一类"用户会一起想的事"。
 * 拆成十几个子页会让索引页本身变成需要滚动的东西，跟"一页一件事"正好相反。
 */
internal enum class SettingsGroup(val title: String, val hint: String) {
    Appearance("外观", "启动器图标：4 设计 × 黑/白底"),
    Context("会话与上下文", "压缩两条线、视觉覆盖、上下文窗口默认值"),
    Models("模型与提示词", "模型与供应商、七条压缩提示词"),
    Tools("工具与工作区", "工作区根目录、技能、MCP、世界书"),
    Debug("调试", "日志（三种通道）、原生层状态、版本"),
    About("关于", "许可、第三方声明、构建号"),
}

/** 一行"还没接线"的灰行。**不给假开关**：点了没反应比没有这个开关更坏。 */
internal data class PendingRow(val title: String, val subtitle: String, val pending: String)

/** 各分组里那些还没接的功能。接了哪一项，就从这里删掉一行，别留在界面上当装饰。 */
internal fun pendingRows(group: SettingsGroup): List<PendingRow> = when (group) {
    SettingsGroup.Appearance -> listOf(
        PendingRow("主题", "现在只有深色；浅色要跟图标包的 backing: surface 一起调（D105）", "待接 P6.6"),
    )

    SettingsGroup.Context -> listOf(
        PendingRow("自动压缩建议线", "到 75% 时先压一轮（D90 / D97）", "待接 P4.2"),
        PendingRow("自动压缩绝对线", "到 92% 时先压再发；生成中过线则截断并标 incomplete", "待接 P4.2"),
        PendingRow("视觉覆盖", "开着时主模型一个媒体块都不收，先给视觉模型看（D62）", "待接 P3.3"),
        PendingRow("上下文窗口默认值", "声明窗口 / 有效窗口 / 预留输出三段（P2.7）", "待接 P3"),
    )

    SettingsGroup.Models -> listOf(
        PendingRow("模型列表", "按供应商分组；每行带上下文窗口与能力位", "待接 P3"),
        PendingRow("供应商与密钥", "密钥隔离在独立存储（D28），不进会话文档", "待接 P3"),
    )

    SettingsGroup.Tools -> listOf(
        PendingRow("工作区根目录", "绑定一个文件夹后文件工具才可用（D85 / P7.6）", "待接 P7"),
        PendingRow("技能", "只注入 name + description（D80），按对话开关", "待接 P5.5"),
        PendingRow("MCP", "两级门：设置级加载 + 会话级生效", "待接 P7"),
        PendingRow("世界书", "酒馆格式（D93），现在还没动工", "待接 P4.3"),
    )

    SettingsGroup.Debug -> emptyList()

    SettingsGroup.About -> listOf(
        PendingRow("许可", "GPL-3.0-only；第三方便用不并进 LICENSE", "已定 D15"),
        PendingRow(
            "第三方声明",
            "随每个分发物走：APK 的 assets/notices/、安装目录、图标包（D106）",
            "已实现",
        ),
        PendingRow("构建号与提交", "骨架阶段 0.0.1-dev；等 release 链路接上再显示提交号", "待接 P9"),
    )
}
