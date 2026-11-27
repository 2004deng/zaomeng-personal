package top.wkbin.zaomeng.platform

import io.github.yuroyami.kitearchive.KiteArchive
import io.github.yuroyami.kitearchive.archive.ByteArrayRandomAccessSource

actual fun readZipFileEntries(
    bytes: ByteArray,
    limits: ZipReadLimits,
    includeEntry: (String) -> Boolean,
): List<ZipFileEntryData> {
    val centralEntries = inspectZipCentralDirectory(bytes, limits, includeEntry)
    val byName = centralEntries.associateBy(ZipCentralEntry::name)
    val reader = KiteArchive.open(ByteArrayRandomAccessSource(bytes))
    val archiveEntries = reader.entries()
    require(archiveEntries.size == centralEntries.size) { "ZIP 条目与目录不匹配。" }
    val seenNames = HashSet<String>()
    val output = mutableListOf<ZipFileEntryData>()
    for (entry in archiveEntries) {
        validateSafeZipEntryName(entry.name)
        val central = byName[entry.name]
        require(central != null && seenNames.add(entry.name)) { "ZIP 条目与目录不匹配。" }
        if (entry.isDirectory || entry.name.endsWith('/') || !includeEntry(entry.name)) continue
        val content = reader.read(entry)
        require(content.size.toLong() == central.uncompressedSize) { "ZIP 条目解压长度与目录不匹配。" }
        output += ZipFileEntryData(entry.name, content)
    }
    require(seenNames == byName.keys) { "ZIP 条目与目录不匹配。" }
    return output
}
