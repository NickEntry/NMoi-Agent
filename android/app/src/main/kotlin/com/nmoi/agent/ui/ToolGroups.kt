package com.nmoi.agent.ui

/**
 * 工具面板的分组与排序（0.17g）。
 *
 * 顺序照人的习惯：读 → 写 → 语音 → 命令。清单本身仍从 native 注册表来；
 * 这里只决定**摆在哪儿**，不决定有没有——注册表里多一个名字而这里没列，
 * 它会掉进最后的「其他」，不会消失。
 */
internal data class ToolGroup(val title: String, val names: List<String>)

private val GROUPS: List<Pair<String, List<String>>> = listOf(
    "读" to listOf("read_file", "list_dir", "find_files", "search", "View-Image"),
    "写" to listOf("write_file", "edit_file", "edit_unit"),
    "语音" to listOf("Voice-Caps", "Speak"),
    "命令" to listOf("System-Shell", "WorkSpace-EnvirShell", "Session-Files"),
)

internal fun toolGroups(names: List<String>): List<ToolGroup> {
    val present = names.filter { it.isNotBlank() }.distinct().toSet()
    val out = mutableListOf<ToolGroup>()
    val listed = mutableSetOf<String>()
    for ((title, members) in GROUPS) {
        val hit = members.filter { present.contains(it) }
        if (hit.isEmpty()) continue
        out.add(ToolGroup(title, hit))
        listed.addAll(hit)
    }
    val rest = names.filter { it.isNotBlank() && !listed.contains(it) }.distinct()
    if (rest.isNotEmpty()) out.add(ToolGroup("其他", rest))
    return out
}

/**
 * 工具名的中文显示名。
 *
 * 放在这里而不是 ChatScreen：工具面板与工作区「工具」页签都要用它，
 * 两处各写一份就会有两个说法（用户看到的是"运行命令"和"System-Shell"）。
 * 认不出的名字原样返回——注册表里多一个工具时，界面上至少还认得出它叫什么。
 */
internal fun toolDisplayName(name: String): String = when (name) {
    "System-Shell" -> "运行命令"
    "WorkSpace-EnvirShell" -> "运行命令（容器）"
    "View-Image" -> "查看图像"
    "read_file" -> "读取文件"
    "write_file" -> "写入文件"
    "edit_file" -> "修改文件"
    "list_dir" -> "列出目录"
    "find_files" -> "查找文件"
    "search" -> "搜索内容"
    "edit_unit" -> "修改消息"
    "Session-Files" -> "读别的会话"
    "Voice-Caps" -> "语音能力"
    "Speak" -> "文字转语音"
    else -> name
}
