package com.nmoi.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 存储空间分项账本（D426）。
 *
 * 这一层错起来不显眼：占比短一截用户看不出；而"其他"算错会**把某一类文件藏起来**，
 * 那正是铁律②要挡的东西。所以每条判据都钉在"加起来对不对"上。
 */
class StorageUsageTest {

    private fun entry(path: String, bytes: Long) =
        UsageEntry(kind = StorageUsage.kindOf(path), relative = path, bytes = bytes)

    @Test
    fun `十项之和等于总和`() {
        val entries = listOf(
            entry("sessions/0199/images/a.jpg", 100),
            entry("sessions/0199/chat/attachments/1-a.txt", 200),
            entry("sessions/0199/chat/out.md", 300),
            entry("sessions/0199/archive/ab.txt", 400),
            entry("sessions/0199/tmp/x", 500),
            entry("workspace/a.rs", 600),
            entry("workspaces/w1/b.rs", 700),
            entry("envir/ubuntu/rootfs", 800),
            entry("skills/s1/SKILL.md", 900),
            entry("logs/context.log", 1_000),
            entry("cache/x.bin", 1_100),
            entry("nmoi.db", 1_200),
            entry("nmoi.db-wal", 1_300),
            entry("vault/provider-1.bin", 1_400),
            entry("voice/svs/runtime.json", 1_500),
        )
        val buckets = StorageUsage.buckets(entries)
        assertEquals(StorageKind.entries.size, buckets.size)
        assertEquals(StorageUsage.total(entries), buckets.sumOf { it.bytes })
        // 每一项都在（哪怕 0）——少一行会让用户以为那一类东西不存在。
        assertEquals(StorageKind.entries.toList(), buckets.map { it.kind })
    }

    @Test
    fun `用户发来的附件不算会话文件`() {
        // D423 起落在 `chat/attachments/` 那一层。判错了它会被算成 AI 的输出。
        assertEquals(
            StorageKind.Files,
            StorageUsage.kindOf("sessions/0199/chat/attachments/3e4f-a.txt"),
        )
        assertEquals(
            StorageKind.SessionFiles,
            StorageUsage.kindOf("sessions/0199/chat/notes.md"),
        )
        assertEquals(StorageKind.Images, StorageUsage.kindOf("sessions/0199/images/a.jpg"))
        assertEquals(StorageKind.SessionFiles, StorageUsage.kindOf("sessions/0199/archive/ab.txt"))
        assertEquals(StorageKind.SessionFiles, StorageUsage.kindOf("sessions/0199/tmp/x"))
    }

    @Test
    fun `库的三个文件都算聊天记录`() {
        for (name in listOf("nmoi.db", "nmoi.db-wal", "nmoi.db-shm")) {
            assertEquals(name, StorageKind.Chat, StorageUsage.kindOf(name))
        }
    }

    @Test
    fun `认不出的归其他而不是丢掉`() {
        assertEquals(StorageKind.Other, StorageUsage.kindOf("vault/provider-1.bin"))
        assertEquals(StorageKind.Other, StorageUsage.kindOf("voice/svs/runtime.json"))
        assertEquals(StorageKind.Other, StorageUsage.kindOf(""))
        // 归"其他"之后仍然在账里：总和不变。
        val entries = listOf(entry("奇怪的东西.bin", 42))
        assertEquals(42L, StorageUsage.buckets(entries).sumOf { it.bytes })
    }

    @Test
    fun `占比在总数为零时是零而不是 NaN`() {
        val bucket = UsageBucket(StorageKind.Images, bytes = 0, files = 0)
        assertEquals(0f, bucket.share(0), 0.0001f)
        assertEquals(0f, bucket.share(-1), 0.0001f)
        assertEquals(0.25f, UsageBucket(StorageKind.Images, 25, 1).share(100), 0.0001f)
    }

    @Test
    fun `占比条只画非零项并按大小降序`() {
        val buckets = listOf(
            UsageBucket(StorageKind.Images, 0, 0),
            UsageBucket(StorageKind.Files, 10, 1),
            UsageBucket(StorageKind.Chat, 90, 1),
        )
        val segments = StorageUsage.segments(buckets, 100)
        assertEquals(listOf(StorageKind.Chat, StorageKind.Files), segments.map { it.kind })
    }

    @Test
    fun `图例超过五行时并进其他`() {
        val buckets = StorageKind.entries.mapIndexed { index, kind ->
            UsageBucket(kind, bytes = (index + 1) * 10L, files = 1)
        }
        val legend = StorageUsage.legend(buckets)
        assertEquals(StorageUsage.LEGEND_ROWS, legend.size)
        // 前四行是最大的四项，最后一行是"其他"（合并了剩下的）。
        assertEquals(StorageKind.entries.size - StorageUsage.LEGEND_ROWS + 1, legend.last().files)
        // 图例加起来仍等于总和。
        assertEquals(buckets.sumOf { it.bytes }, legend.sumOf { it.bytes })
    }

    @Test
    fun `图例不超过五行时原样返回`() {
        val buckets = listOf(
            UsageBucket(StorageKind.Images, 10, 1),
            UsageBucket(StorageKind.Files, 20, 1),
        )
        assertEquals(2, StorageUsage.legend(buckets).size)
    }

    @Test
    fun `可清理只算缓存与日志`() {
        val buckets = listOf(
            UsageBucket(StorageKind.Images, 1_000, 5),
            UsageBucket(StorageKind.Cache, 20, 1),
            UsageBucket(StorageKind.Logs, 30, 1),
        )
        assertEquals(50L, StorageUsage.reclaimable(buckets))
        // 用户的图片与文件不算"可清理"：那是他自己的东西。
        assertTrue(StorageUsage.reclaimable(buckets) < buckets.sumOf { it.bytes })
    }
}
