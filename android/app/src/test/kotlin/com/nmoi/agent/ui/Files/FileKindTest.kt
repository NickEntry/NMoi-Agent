package com.nmoi.agent.ui.Files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文件类型判定（D392）。用户 2026-11-22 定的判据是**按类型给页签**，
 * 判错的表现是"点 html 进去看到一屏源码"或"点 txt 看到一个空白渲染页"——
 * 两种都不报错，所以钉在单测里。
 */
class FileKindTest {

    @Test
    fun `html 与 md 是两个页签、默认渲染`() {
        for (path in listOf("a.html", "a.htm", "/w/dir/index.HTML")) {
            assertEquals(path, FileKind.Html, FileKinds.kindOf(path))
            assertEquals(
                path,
                listOf(FileTab.Render, FileTab.Source),
                FileKinds.tabs(FileKinds.kindOf(path)),
            )
            assertEquals(path, FileTab.Render, FileKinds.defaultTab(FileKinds.kindOf(path)))
        }
        for (path in listOf("a.md", "notes/readme.markdown", "B.MD")) {
            assertEquals(path, FileKind.Markdown, FileKinds.kindOf(path))
            assertEquals(path, FileTab.Render, FileKinds.defaultTab(FileKinds.kindOf(path)))
        }
    }

    @Test
    fun `图片只有看图，没有页签条`() {
        for (path in listOf("a.png", "b.JPG", "c.jpeg", "d.webp", "e.gif")) {
            assertEquals(path, FileKind.Image, FileKinds.kindOf(path))
            assertTrue(path, FileKinds.tabs(FileKinds.kindOf(path)).isEmpty())
        }
    }

    @Test
    fun `文本与代码只有源码页`() {
        for (path in listOf("a.txt", "b.log", "c.rs", "d.kt", "e.py", "f.sh", "g.json", "h.yaml")) {
            assertEquals(path, FileKind.Text, FileKinds.kindOf(path))
            assertEquals(
                path,
                listOf(FileTab.Source),
                FileKinds.tabs(FileKinds.kindOf(path)),
            )
        }
    }

    @Test
    fun `没有后缀的常见文本文件也走源码页`() {
        for (path in listOf("Makefile", "Dockerfile", "LICENSE", "README", "dir/Gemfile")) {
            assertEquals(path, FileKind.Text, FileKinds.kindOf(path))
        }
    }

    @Test
    fun `认不出的落到详情页`() {
        for (path in listOf("a.apk", "b.exe", "c.zip", "d.so", "e.7z", "f.bin", "无后缀的奇怪东西")) {
            assertEquals(path, FileKind.Binary, FileKinds.kindOf(path))
            assertTrue(path, FileKinds.tabs(FileKinds.kindOf(path)).isEmpty())
        }
    }

    @Test
    fun `点开头的文件不算后缀`() {
        // `.bashrc` 的"后缀"是 bashrc，但它是一份 shell 配置——按白名单它认不出来，
        // 于是落到详情页。这是可接受的：详情页至少能看见大小与时间，不会白屏。
        assertEquals(FileKind.Binary, FileKinds.kindOf(".bashrc"))
        // 而 `.nmoi-workspace.json` 的最后一个点之后是 json，应当认得出。
        assertEquals(FileKind.Text, FileKinds.kindOf(".nmoi-workspace.json"))
        assertEquals("json", FileKinds.extensionOf(".nmoi-workspace.json"))
        assertEquals("", FileKinds.extensionOf(".bashrc"))
        assertEquals("", FileKinds.extensionOf("Makefile"))
    }

    @Test
    fun `路径只取最后一段`() {
        assertEquals("a.rs", FileKinds.baseName("/workspace/src/a.rs"))
        assertEquals("a.rs", FileKinds.baseName("src\\a.rs"))
        assertEquals(FileKind.Text, FileKinds.kindOf("/workspace/src/a.rs"))
        assertEquals(FileKind.Html, FileKinds.kindOf("/workspace/site/index.html"))
    }

    @Test
    fun `hasSource 与 tabs 一致`() {
        for (kind in FileKind.entries) {
            assertEquals(kind.name, FileTab.Source in FileKinds.tabs(kind), FileKinds.hasSource(kind))
        }
    }

    @Test
    fun `扩展名大小写不敏感`() {
        assertEquals("rs", FileKinds.extensionOf("A.RS"))
        assertEquals(FileKind.Image, FileKinds.kindOf("PHOTO.PNG"))
    }

    @Test
    fun `空名字不崩`() {
        assertEquals("", FileKinds.extensionOf(""))
        assertEquals(FileKind.Binary, FileKinds.kindOf(""))
        assertNull(SourceLexer.languageOf(""))
    }
}
