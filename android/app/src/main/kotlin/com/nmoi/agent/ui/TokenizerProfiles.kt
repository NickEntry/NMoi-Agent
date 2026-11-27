package com.nmoi.agent.ui

/**
 * 一个 tokenizer 条目（D437）：**名字 + 匹配规则 + 一份词表**。
 *
 * 用户 2026-11-26 的原话：
 * 「能新建项，然后配置每一项的名称、模型匹配关键词/正则、然后导入 tokenizer.json」
 * 「要按照条目排序来决定匹配的优先级，最上方的条目最先匹配，
 *   内置 tokenizer.json 是作为没有命中规则下的默认回退」
 *
 * # 为什么不是"全局一个 json"
 *
 * 一份词表只对一类模型成立。全局一份的结局是：Qwen 的会话用着 DeepSeek 的词表，
 * 中文上高估两成——而那两成恰好落在"该压没压"的边上（实测见
 * `notes/design/tokenizer-profiles.md` §1）。
 *
 * # 这个文件只放**纯逻辑**
 *
 * 匹配、优先级、必填校验都在这里，能单测；读文件、拨网络、落 `SharedPreferences`
 * 那一半在调用方。判据错起来是"用错了词表"——它不报错，只是所有数字悄悄偏一截。
 */
internal enum class TokenizerMatch {
    /** 不区分大小写的**包含**。`qwen` 命中 `Qwen2.5-72B`。 */
    Keyword,

    /** 不区分大小写的正则。编译失败在创建时就拦住，不留到匹配时。 */
    Regex,
}

/** 条目的元数据。**词表文件本身**按 [id] 落在 `files/tokenizers/<id>.json`。 */
internal data class TokenizerProfile(
    val id: String,
    val name: String,
    val matchType: TokenizerMatch,
    val matchValue: String,
    /** 词表字节数（界面上要显示"7.0 MB"）。0 = 还没导进来。 */
    val bytes: Long = 0,
    /** 导入时间（毫秒）。界面上要显示"加载于 …"。0 = 还没导进来。 */
    val importedAt: Long = 0,
    /** 从哪来：`url:…` 或 `file:<原文件名>`。只为说清来源，不参与匹配。 */
    val source: String = "",
) {
    /** 这一条能不能参与匹配：规则非空、词表已经在。 */
    val usable: Boolean get() = matchValue.isNotBlank() && bytes > 0
}

/**
 * 一次匹配的结果。**只有两种**：命中某个条目，或者回退内置那份。
 *
 * 没有"没有词表"这一档：内置那份永远在（它会打进 APK，许可见设计稿 §1）。
 */
internal sealed interface TokenizerPick {
    data class Profile(val profile: TokenizerProfile) : TokenizerPick

    /** 没有条目命中：用内置那份词表算。 */
    data object Builtin : TokenizerPick
}

internal object TokenizerProfiles {

    /** 词表上限。32 MB 已经比所有已知词表大四倍——再大就不是"一份词表"了。 */
    const val MAX_BYTES: Long = 32L * 1024 * 1024

    /** 名字上限：卡片上要能一行写完。 */
    const val MAX_NAME = 24

    /**
     * 建一条之前还缺什么。`null` = 可以建。
     *
     * 用户要的是"**必须**有关键词/正则，且**必须**有 tokenizer.json，条目才能成功创建"。
     * 所以这两条缺一条就不建——不建空壳，也不建"先占个位再来补"的条目：
     * 空条目会进列表参与匹配，而它什么都不命中，等于在优先级里插了一个空档。
     */
    fun missingRule(name: String, matchValue: String, matchType: TokenizerMatch): String? = when {
        name.trim().isEmpty() -> "给这一条起个名字"
        name.trim().length > MAX_NAME -> "名字不超过 $MAX_NAME 个字"
        matchValue.trim().isEmpty() ->
            if (matchType == TokenizerMatch.Regex) "写一个正则：空规则匹配不到任何模型" else "写一个关键词"
        matchType == TokenizerMatch.Regex && compile(matchValue) == null ->
            "这个正则编译不过：换个写法再试"
        else -> null
    }

    /** 词表那一半的门槛：大小、时间都得有。返回缺什么，`null` = 已经有了。 */
    fun missingVocabulary(bytes: Long, importedAt: Long): String? = when {
        bytes <= 0L || importedAt <= 0L -> "还没有导入 tokenizer.json"
        bytes > MAX_BYTES -> "这份词表 ${bytes / (1024 * 1024)} MB，超过 ${MAX_BYTES / (1024 * 1024)} MB 上限"
        else -> null
    }

    /**
     * 挑哪一份词表：**列表顺序就是优先级**，最上面那条最先匹配，命中就停。
     *
     * 不做"更具体的那条优先"：具体与否是人的判断，顺序是人的决定。
     * 两条都能命中时用上面那条——用户把"Qwen2.5"提到"Qwen"上面，就是他要的行为。
     *
     * 坏条目（规则空、词表没导、正则有语法错）**跳过而不是抛**：一份手改过的配置
     * 不该让整页崩，它只该安静地不参与匹配。
     */
    fun pick(modelId: String, profiles: List<TokenizerProfile>): TokenizerPick {
        val model = modelId.trim()
        if (model.isEmpty()) return TokenizerPick.Builtin
        for (profile in profiles) {
            if (!profile.usable) continue
            if (hits(profile, model)) return TokenizerPick.Profile(profile)
        }
        return TokenizerPick.Builtin
    }

    /**
     * 一条规则命中没有。
     *
     * 比两次：原样比一次，去掉供应商前缀（`p1/qwen2.5` → `qwen2.5`）再比一次。
     * 用户填规则时想的是模型名，而模型 id 里前面常挂着供应商 id。
     */
    fun hits(profile: TokenizerProfile, modelId: String): Boolean {
        val bare = modelId.substringAfterLast('/')
        return matches(profile, modelId) || (bare != modelId && matches(profile, bare))
    }

    private fun matches(profile: TokenizerProfile, text: String): Boolean {
        val rule = profile.matchValue.trim()
        if (rule.isEmpty()) return false
        return when (profile.matchType) {
            TokenizerMatch.Keyword -> text.contains(rule, ignoreCase = true)
            TokenizerMatch.Regex -> compile(rule)?.containsMatchIn(text) ?: false
        }
    }

    /** 编译正则；坏语法给 `null`（调用方拿去报错，不在匹配路径上抛）。 */
    fun compile(pattern: String): Regex? =
        runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrNull()
}