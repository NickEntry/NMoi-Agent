package com.nmoi.agent.ui.Files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「对话文件」三页签的归属判据（D393）。
 *
 * 分错了不报错：重叠（同一份两边都出现）看着像重复，漏掉（两边都看不到）
 * 看着像"这个文件本来就不存在"。后者破坏铁律②，所以两个方向都要试。
 */
class DialogFilesTest {

    private fun chat(name: String, size: Long = 10) =
        ChatFile(path = name, name = name, sizeBytes = size, modifiedMs = 1_000L)

    /** 带子目录的那一种：`path` 是相对 `chat/` 的整段，`name` 只是文件名。 */
    private fun chatAt(path: String) =
        ChatFile(path = path, name = path.substringAfterLast('/'), sizeBytes = 10, modifiedMs = 1_000L)

    private fun ref(id: String, name: String, kind: String = "file", path: String? = null) =
        AttachmentRef(id = id, name = name, kind = kind, path = path)

    private val id = "018f0a3c-6c2e-7b41-9a55-1c2d3e4f5a6b"

    @Test
    fun `落盘名是 id 后 8 位加原名`() {
        assertEquals("3e4f5a6b-a.txt", DialogFiles.diskName(id, "a.txt"))
    }

    @Test
    fun `用户发来的落附件，AI 写的落输出`() {
        val onDisk = listOf(
            chat("3e4f5a6b-a.txt"),
            chat("notes.md"),
            chat("3e4f5a6b-b.py"),
        )
        val attachments = listOf(ref(id, "a.txt"), ref(id.replace("3e4f5a6b", "00000000"), "b.py"))
        val split = DialogFiles.split(onDisk, attachments)
        assertEquals(
            listOf("3e4f5a6b-a.txt", "3e4f5a6b-b.py"),
            split[DialogFileSide.Attachment]?.map { it.name },
        )
        assertEquals(listOf("notes.md"), split[DialogFileSide.Output]?.map { it.name })
    }

    @Test
    fun `不重叠也不漏`() {
        val onDisk = listOf(chat("3e4f5a6b-a.txt"), chat("x.txt"), chat("y.rs"))
        val attachments = listOf(ref(id, "a.txt"))
        val split = DialogFiles.split(onDisk, attachments)
        val attach = split[DialogFileSide.Attachment].orEmpty()
        val output = split[DialogFileSide.Output].orEmpty()
        // 不重叠
        assertTrue(attach.map { it.name }.intersect(output.map { it.name }.toSet()).isEmpty())
        // 不漏：两边加起来等于盘上全部
        assertEquals(onDisk.size, attach.size + output.size)
    }

    @Test
    fun `AI 写的同名文件不会被认成附件`() {
        // 这一条是第一版按后缀匹配时的真 bug：`xxa.txt` 以 `a.txt` 结尾。
        val onDisk = listOf(chat("xxa.txt"), chat("3e4f5a6b-a.txt"))
        val attachments = listOf(ref(id, "a.txt"))
        val split = DialogFiles.split(onDisk, attachments)
        assertEquals(listOf("3e4f5a6b-a.txt"), split[DialogFileSide.Attachment]?.map { it.name })
        assertEquals(listOf("xxa.txt"), split[DialogFileSide.Output]?.map { it.name })
    }

    @Test
    fun `落盘名被改过时按形状仍认得出`() {
        // `sanitize_file_name` 把控制字符换成 `_`，于是精确比对得上"洗过"的那一份。
        val onDisk = listOf(chat("3e4f5a6b-a_.txt"))
        val attachments = listOf(ref(id, "a\u0001.txt"))
        val split = DialogFiles.split(onDisk, attachments)
        assertEquals(1, split[DialogFileSide.Attachment]?.size)
    }

    @Test
    fun `清洗规则与 Rust 那一份一致`() {
        assertEquals("a_b.txt", DialogFiles.sanitizeName("a\u0001b.txt"))
        assertEquals("a_b.txt", DialogFiles.sanitizeName("dir/a?b.txt"))
        assertEquals("file", DialogFiles.sanitizeName("   "))
        assertEquals("a.txt", DialogFiles.sanitizeName(".a.txt"))
    }

    @Test
    fun `图片不进 chat 目录，不参与分堆`() {
        val onDisk = listOf(chat("3e4f5a6b-a.png"))
        val attachments = listOf(ref(id, "a.png", kind = "image"))
        val split = DialogFiles.split(onDisk, attachments)
        // 图片记录不该把 chat 里同名的文件认成附件。
        assertTrue(split[DialogFileSide.Attachment].isNullOrEmpty())
        assertEquals(1, split[DialogFileSide.Output]?.size)
    }

    @Test
    fun `前缀不是十六进制就不算附件`() {
        val onDisk = listOf(chat("zzzzzzzz-a.txt"))
        val attachments = listOf(ref(id, "a.txt"))
        val split = DialogFiles.split(onDisk, attachments)
        assertTrue(split[DialogFileSide.Attachment].isNullOrEmpty())
    }

    @Test
    fun `空盘与空库都不崩`() {
        assertTrue(DialogFiles.split(emptyList(), emptyList())[DialogFileSide.Attachment].isNullOrEmpty())
        assertTrue(DialogFiles.split(emptyList(), emptyList())[DialogFileSide.Output].isNullOrEmpty())
        val onlyDisk = listOf(chat("a.txt"))
        assertEquals(1, DialogFiles.split(onlyDisk, emptyList())[DialogFileSide.Output]?.size)
    }

    @Test
    fun `两堆各自按名字排`() {
        val onDisk = listOf(chat("b.txt"), chat("A.txt"), chat("c.txt"))
        val split = DialogFiles.split(onDisk, emptyList())
        assertEquals(
            listOf("A.txt", "b.txt", "c.txt"),
            split[DialogFileSide.Output]?.map { it.name },
        )
    }

    @Test
    fun `空态与角标`() {
        assertTrue(DialogFiles.emptyHint(DialogFileSide.Attachment).contains("＋"))
        assertTrue(DialogFiles.emptyHint(DialogFileSide.Output).contains("写"))
        assertNull(DialogFiles.badge(0))
        assertEquals("3", DialogFiles.badge(3))
    }

    // ---- D423：落盘分层之后的三条 ----

    @Test
    fun `attachments 那一层的文件按路径判成附件`() {
        // 落盘名与库里名字故意写得对不上：只有 path 这一条判据能认出来。
        val onDisk = listOf(chatAt("attachments/3e4f5a6b-改过名.txt"), chatAt("notes.md"))
        val attachments = listOf(
            ref(id, "原名.txt", path = "attachments/3e4f5a6b-改过名.txt"),
        )
        val split = DialogFiles.split(onDisk, attachments)
        assertEquals(
            listOf("3e4f5a6b-改过名.txt"),
            split[DialogFileSide.Attachment]?.map { it.name },
        )
        assertEquals(listOf("notes.md"), split[DialogFileSide.Output]?.map { it.name })
    }

    @Test
    fun `没有 path 的老行退回按落盘名判`() {
        // 老行（BLOB 那批 / 写库与落盘之间断过）path 是 null，名字仍要对得上。
        val onDisk = listOf(chat("3e4f5a6b-a.txt"), chat("b.txt"))
        val attachments = listOf(ref(id, "a.txt", path = null))
        val split = DialogFiles.split(onDisk, attachments)
        assertEquals(listOf("3e4f5a6b-a.txt"), split[DialogFileSide.Attachment]?.map { it.name })
        assertEquals(listOf("b.txt"), split[DialogFileSide.Output]?.map { it.name })
    }

    @Test
    fun `子目录里的 AI 输出也算输出`() {
        // 递归之后 `path` 带子目录：`attachments/` 底下的附件不许因为前缀相同被误认。
        val onDisk = listOf(
            chatAt("attachments/3e4f5a6b-a.txt"),
            chatAt("attachments/我自己建的.md"),
            chatAt("src/main.rs"),
        )
        val attachments = listOf(ref(id, "a.txt", path = "attachments/3e4f5a6b-a.txt"))
        val split = DialogFiles.split(onDisk, attachments)
        assertEquals(
            listOf("3e4f5a6b-a.txt"),
            split[DialogFileSide.Attachment]?.map { it.name },
        )
        assertEquals(
            listOf("main.rs", "我自己建的.md"),
            split[DialogFileSide.Output]?.map { it.name },
        )
        // 不重叠也不漏：三份都各自归了一边。
        assertEquals(onDisk.size, split.values.sumOf { it.size })
    }
}
