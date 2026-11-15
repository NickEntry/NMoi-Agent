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
