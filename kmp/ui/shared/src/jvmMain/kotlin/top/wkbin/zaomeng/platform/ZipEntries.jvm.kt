package top.wkbin.zaomeng.platform

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

actual fun readZipFileEntries(
    bytes: ByteArray,
    limits: ZipReadLimits,
    includeEntry: (String) -> Boolean,
): List<ZipFileEntryData> {
    val centralEntries = inspectZipCentralDirectory(bytes, limits, includeEntry)
    val entriesByName = centralEntries.associateBy(ZipCentralEntry::name)
    val expectedNames = entriesByName.keys
    val seenNames = HashSet<String>()
    val entries = mutableListOf<ZipFileEntryData>()
    var entryCount = 0
    var totalBytes = 0L
    var scannedBytes = 0L
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val entry = zip.nextEntry ?: break
            entryCount++
            require(entryCount <= limits.maxEntries) { "EPUB 条目过多，无法安全导入。" }
            validateSafeZipEntryName(entry.name)
            require(entry.name in expectedNames && seenNames.add(entry.name)) { "ZIP 条目与目录不匹配。" }
            val declared = requireNotNull(entriesByName[entry.name])
            val shouldKeep = !entry.isDirectory && includeEntry(entry.name)
            val output = if (shouldKeep) ByteArrayOutputStream() else null
            var entryBytes = 0L
            while (true) {
                val count = zip.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                entryBytes += count
                scannedBytes += count
                require(scannedBytes <= maxOf(MAX_SCANNED_BYTES, limits.maxTotalBytes)) {
                    "EPUB 解压后的总内容过大，无法安全导入。"
                }
                require(entryBytes <= declared.uncompressedSize) { "ZIP 条目解压长度与目录不匹配。" }
                // Metadata passes select only container/OPF: unused chapters and images have
                // their own scan bound, rather than consuming the selected document budget.
                if (shouldKeep) {
                    totalBytes += count
                    require(entryBytes <= limits.maxEntryBytes) {
                        "EPUB 单个文件解压后过大，无法安全导入。"
                    }
                    require(totalBytes <= limits.maxTotalBytes) {
                        "EPUB 解压后的内容过大，无法安全导入。"
                    }
                    output?.write(buffer, 0, count)
                }
            }
            require(entryBytes == declared.uncompressedSize) { "ZIP 条目解压长度与目录不匹配。" }
            if (output != null) entries += ZipFileEntryData(entry.name, output.toByteArray())
            zip.closeEntry()
        }
    }
    require(seenNames == expectedNames) { "ZIP 条目与目录不匹配。" }
    return entries
}

private const val BUFFER_SIZE = 8 * 1024
private const val MAX_SCANNED_BYTES = 256L * 1024 * 1024
