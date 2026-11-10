package com.nmoi.agent.ui

import android.content.Context

/**
 * 默认模型提示词里的站位字符串（D316）。
 *
 * 提示词里写 `<ContextMode>` 这样的占位符，**发请求之前**换成当前状态，于是同一段提示词
 * 能按档位分叉：F1 老老实实写摘要，F3 / F4 因为原文还在、会话里也有编辑工具能补救，
 * 就可以写得极短甚至不写。一份提示词套所有档位，只能按最保守的那档写，等于白花 token。
 *
 * 三条规矩：
 * 1. 只替换**声明过的**变量；没声明的原样留着，不猜、也不吞成空串——
 *    提示词里写错一个字母，宁可让它在请求里显形，也不要静默变成空。
 * 2. **一遍扫完，不递归**：替换进去的值里就算又出现 `<X>`，也不会被二次替换。
 *    递归替换是注入的入口，也是"为什么我的提示词被吃了"的来源。
 * 3. 界面上那一行「变量：…」只列这个角色真的会被填的那些——列了不填就是假开关。
 */
internal object PromptVars {
    /** 当前的上下文档位（F1 全文 / F2 截断 / F3 占位 / F4 指针）。 */
    const val CONTEXT_MODE = "<ContextMode>"

    private val TOKEN = Regex("<[A-Za-z][A-Za-z0-9_]*>")

    /**
     * 这个角色的提示词支持哪些变量。空表 = 没有可填的，界面也不摆那一行。
     *
     * 现在只给**处理"往上下文里留多少"的那两个角色**：压缩（把窗口压小）与摘要（把原文挪出上下文）。
     * 标题 / 建议 / 翻译 / 视觉跟档位无关，给了就是噪声。
     */
    fun names(role: ModelRole): List<String> = when (role) {
        ModelRole.Compress, ModelRole.Summary -> listOf(CONTEXT_MODE)
        else -> emptyList()
    }

    /** 这一次要填的值。没声明的角色给空表。 */
    fun values(context: Context, role: ModelRole): Map<String, String> {
        val names = names(role)
        if (names.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>(names.size)
        names.forEach { name ->
            when (name) {
                CONTEXT_MODE -> out[name] = AppSettings.formLevel(context)
            }
        }
        return out
    }

    /**
     * 把声明过的占位符换掉。**一遍扫完**：替换结果不再被扫（见文件头第 2 条）。
     */
    fun fill(text: String, values: Map<String, String>): String {
        if (text.isEmpty() || values.isEmpty()) return text
        return TOKEN.replace(text) { match -> values[match.value] ?: match.value }
    }
}
