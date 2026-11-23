package com.nmoi.agent.ui.Files

/**
 * 一个文件该用哪种方式打开（D392）。
 *
 * 判据**只看后缀**，不看内容：内容那一半由 `workspace_read` 回答（它认得 NUL 字节与非法
 * UTF-8，回 `BinaryFile`）。两处各判一半是有意的——后缀决定"给不给渲染页签"，
 * 内容决定"源码页画不画得出来"。只看内容的话，`a.png` 被读成文本时会画出一屏乱码，
 * 而用户点的是图片，他期待看图。
 */
internal enum class FileKind {
    /** `.html` / `.htm`：渲染 + 源码，默认渲染。 */
    Html,

    /** `.md` / `.markdown`：渲染 + 源码，默认渲染。 */
    Markdown,

    /** 图片：看图，没有页签条。 */
    Image,

    /** 文本与代码：只有源码页。 */
    Text,

    /** 其余（APK / EXE / 压缩包 / 二进制）：详情页。 */
    Binary,
}

/** 预览页的两个页签。图片与二进制没有页签条，所以它们不出现在这两个里。 */
internal enum class FileTab {
    Render,
    Source,
}

/**
 * 预览页顶栏下面那一排页签（D392）。
 *
 * 空列表 = 不画页签条：图片走看图、二进制走详情，它们只有一种看法，
 * 摆一条只有一个页签的条子只会让人以为另一个页签加载失败。
 */
internal object FileKinds {
    private val HTML = setOf("html", "htm")
    private val MARKDOWN = setOf("md", "markdown")
    private val IMAGE = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "heif")

    /**
     * 认作"文本"的后缀。
     *
     * 这是一张白名单，不是"除了图片都是文本"：后者会把 `.apk` `.so` `.zip` 全送进源码页，
     * 而它们读出来是二进制——用户看到的是一屏乱码加一句"不是 UTF-8"，不如直接给详情页。
     */
    private val TEXT = setOf(
        // 纯文本与日志
        "txt", "log", "csv", "tsv", "ini", "conf", "cfg", "env", "properties",
        // 标记与数据
        "json", "jsonl", "yaml", "yml", "toml", "xml", "svg", "html5", "mdx",
        // 代码
        "rs", "kt", "kts", "java", "py", "rb", "go", "c", "h", "cc", "cpp", "hpp",
        "cs", "swift", "m", "mm", "js", "jsx", "ts", "tsx", "css", "scss", "less",
        "sh", "bash", "zsh", "fish", "ps1", "bat", "cmd", "sql", "graphql", "proto",
        "lua", "pl", "php", "r", "dart", "vue", "svelte", "astro", "zig", "nim",
        "cmake", "gradle", "mk", "make", "dockerfile", "gitignore", "editorconfig",
    )

    /**
     * 没有后缀但确实是文本的文件名（小写比较）。
     *
     * `Makefile` / `Dockerfile` / `LICENSE` 这类没有点，只按后缀判会全部落到详情页——
     * 而它们恰恰是最常被打开看一眼的几个。
     */
    private val BARE_NAMES = setOf(
        "makefile", "dockerfile", "license", "licence", "readme", "changelog",
        "authors", "notice", "copying", "gemfile", "rakefile", "procfile", "justfile",
    )

    /** 后缀（小写，不含点）。`.` 开头且没有第二个点的名字（`.bashrc`）算"没有后缀"。 */
    fun extensionOf(name: String): String {
        val dot = name.lastIndexOf('.')
        if (dot <= 0) return ""
        return name.substring(dot + 1).lowercase()
    }

    /** 文件名最后一段（路径进来也认）。 */
    fun baseName(path: String): String = path.substringAfterLast('/').substringAfterLast('\\')

    fun kindOf(path: String): FileKind {
        val name = baseName(path)
        val extension = extensionOf(name)
        if (extension.isEmpty()) {
            return if (name.lowercase() in BARE_NAMES) FileKind.Text else FileKind.Binary
        }
        return when {
            extension in HTML -> FileKind.Html
            extension in MARKDOWN -> FileKind.Markdown
            extension in IMAGE -> FileKind.Image
            extension in TEXT -> FileKind.Text
            else -> FileKind.Binary
        }
    }

    fun tabs(kind: FileKind): List<FileTab> = when (kind) {
        FileKind.Html, FileKind.Markdown -> listOf(FileTab.Render, FileTab.Source)
        FileKind.Text -> listOf(FileTab.Source)
        FileKind.Image, FileKind.Binary -> emptyList()
    }

    /**
     * 打开时停在哪一页。
     *
     * html 与 md 默认**渲染**（用户 2026-11-22 的原话："html 有渲染和源码两个子页，
     * 默认渲染；md 也一样"）。文本只有源码，`Render` 在这里不会被用到。
     */
    fun defaultTab(kind: FileKind): FileTab = when (kind) {
        FileKind.Html, FileKind.Markdown -> FileTab.Render
        else -> FileTab.Source
    }

    /**
     * 源码页要不要给行号。二进制与图片没有源码页，问它们没有意义，但接口要一致。
     */
    fun hasSource(kind: FileKind): Boolean = FileTab.Source in tabs(kind)
}
