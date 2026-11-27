package top.wkbin.zaomeng.platform

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

actual fun readZipFileEntries(
    bytes: ByteArray,
    limits: ZipReadLimits,
    includeEntry: (String) -> Boolean,
): List<ZipFileEntryData> {
    val centralEntries = inspectZipCentralDirectory(bytes, limits, includeEntry)
    val expectedNames = centralEntries.mapTo(HashSet()) { it.name }
    val seenNames = HashSet<String>()
    val entries = mutableListOf<ZipFileEntryData>()
    var entryCount = 0
    var totalBytes = 0L
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val entry = zip.nextEntry ?: break
            entryCount++
            require(entryCount <= limits.maxEntries) { "EPUB 条目过多，无法安全导入。" }
            validateSafeZipEntryName(entry.name)
            require(entry.name in expectedNames && seenNames.add(entry.name)) { "ZIP 条目与目录不匹配。" }
            val shouldKeep = !entry.isDirectory && includeEntry(entry.name)
            val output = if (shouldKeep) ByteArrayOutputStream() else null
            var entryBytes = 0L
            while (true) {
                val count = zip.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                val nextEntryBytes = entryBytes + count
                val nextTotalBytes = totalBytes + count
                require(nextEntryBytes <= limits.maxEntryBytes) {
                    "EPUB 单个文件解压后过大，无法安全导入。"
                }
                require(nextTotalBytes <= limits.maxTotalBytes) {
                    "EPUB 解压后的内容过大，无法安全导入。"
                }
                entryBytes = nextEntryBytes
                totalBytes = nextTotalBytes
                output?.write(buffer, 0, count)
            }
            if (output != null) entries += ZipFileEntryData(entry.name, output.toByteArray())
            zip.closeEntry()
        }
    }
    require(seenNames == expectedNames) { "ZIP 条目与目录不匹配。" }
    return entries
}

private const val BUFFER_SIZE = 8 * 1024
