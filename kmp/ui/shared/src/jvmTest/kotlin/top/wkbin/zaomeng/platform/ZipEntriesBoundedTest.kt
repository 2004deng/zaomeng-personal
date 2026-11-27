package top.wkbin.zaomeng.platform

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ZipEntriesBoundedTest {
    @Test
    fun `declared uncompressed size is rejected before content is kept`() {
        val archive = zipOf(
            "huge.txt" to ByteArray(1_024),
            "small.txt" to "ok".encodeToByteArray(),
        )

        assertFailsWith<IllegalArgumentException> {
            readZipFileEntries(
                bytes = archive,
                limits = ZipReadLimits(maxEntries = 10, maxEntryBytes = 64, maxTotalBytes = 4_096),
                includeEntry = { true },
            )
        }
    }

    @Test
    fun `entries excluded by the predicate do not consume the content budget`() {
        val archive = zipOf(
            "attachment.bin" to ByteArray(1_024),
            "book.xhtml" to "正文".encodeToByteArray(),
        )

        val entries = readZipFileEntries(
            bytes = archive,
            limits = ZipReadLimits(maxEntries = 10, maxEntryBytes = 64, maxTotalBytes = 64),
            includeEntry = { it.endsWith(".xhtml") },
        )

        assertEquals(listOf("book.xhtml"), entries.map { it.name })
        assertEquals("正文", entries.single().content.decodeToString())
    }

    @Test
    fun `archive entry count is bounded`() {
        val archive = zipOf(
            "a.txt" to "a".encodeToByteArray(),
            "b.txt" to "b".encodeToByteArray(),
        )

        assertFailsWith<IllegalArgumentException> {
            readZipFileEntries(
                bytes = archive,
                limits = ZipReadLimits(maxEntries = 1, maxEntryBytes = 64, maxTotalBytes = 64),
                includeEntry = { true },
            )
        }
    }

    @Test
    fun `path traversal entries are rejected`() {
        val archive = zipOf("../outside.txt" to "x".encodeToByteArray())

        assertFailsWith<IllegalArgumentException> {
            readZipFileEntries(
                bytes = archive,
                limits = ZipReadLimits(maxEntries = 10, maxEntryBytes = 64, maxTotalBytes = 64),
                includeEntry = { true },
            )
        }
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
