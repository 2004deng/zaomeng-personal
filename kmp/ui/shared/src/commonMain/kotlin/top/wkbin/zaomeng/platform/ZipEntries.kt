package top.wkbin.zaomeng.platform

/** ZIP archive entry (directory entries are excluded). */
data class ZipFileEntryData(
    val name: String,
    val content: ByteArray,
)

data class ZipReadLimits(
    val maxEntries: Int,
    val maxEntryBytes: Long,
    val maxTotalBytes: Long,
)

/** Reads only selected entries while enforcing archive and streamed-output limits. */
expect fun readZipFileEntries(
    bytes: ByteArray,
    limits: ZipReadLimits,
    includeEntry: (String) -> Boolean,
): List<ZipFileEntryData>

internal data class ZipCentralEntry(
    val name: String,
    val uncompressedSize: Long,
    val isDirectory: Boolean,
)

/**
 * Checks central-directory claims before any platform decoder allocates entry contents.
 * Size limits only count entries that [includeEntry] accepts, so a large unused attachment
 * cannot reject or exhaust an archive that only needs a few documents.
 */
internal fun inspectZipCentralDirectory(
    bytes: ByteArray,
    limits: ZipReadLimits,
    includeEntry: (String) -> Boolean,
): List<ZipCentralEntry> {
    require(limits.maxEntries > 0 && limits.maxEntryBytes >= 0 && limits.maxTotalBytes >= 0) {
        "ZIP 限制参数无效。"
    }
    require(bytes.size >= EOCD_FIXED_SIZE) { "ZIP 文件结构不完整。" }

    val searchStart = (bytes.size - EOCD_FIXED_SIZE - MAX_EOCD_COMMENT).coerceAtLeast(0)
    var eocdOffset = -1
    for (offset in bytes.size - EOCD_FIXED_SIZE downTo searchStart) {
        if (bytes.readU32(offset) == EOCD_SIGNATURE) {
            val commentLength = bytes.readU16(offset + 20)
            if (offset + EOCD_FIXED_SIZE + commentLength == bytes.size) {
                eocdOffset = offset
                break
            }
        }
    }
    require(eocdOffset >= 0) { "ZIP 目录缺失或损坏。" }
    require(bytes.readU16(eocdOffset + 4) == 0 && bytes.readU16(eocdOffset + 6) == 0) {
        "不支持分卷 ZIP 文件。"
    }
    val entriesOnDisk = bytes.readU16(eocdOffset + 8)
    val entryCount = bytes.readU16(eocdOffset + 10)
    val centralSize = bytes.readU32(eocdOffset + 12)
    val centralOffset = bytes.readU32(eocdOffset + 16)
    require(entriesOnDisk == entryCount && entryCount != ZIP16_SENTINEL &&
        centralSize != ZIP32_SENTINEL && centralOffset != ZIP32_SENTINEL
    ) { "不支持 ZIP64 或分卷 ZIP 文件。" }
    require(entryCount <= limits.maxEntries) { "EPUB 条目过多，无法安全导入。" }
    require(centralOffset + centralSize <= eocdOffset.toLong()) { "ZIP 目录范围无效。" }

    val entries = ArrayList<ZipCentralEntry>(entryCount)
    val names = HashSet<String>(entryCount)
    var totalBytes = 0L
    var cursor = centralOffset.toInt()
    val centralEnd = (centralOffset + centralSize).toInt()
    repeat(entryCount) {
        require(cursor <= centralEnd - CENTRAL_HEADER_SIZE && bytes.readU32(cursor) == CENTRAL_SIGNATURE) {
            "ZIP 目录条目损坏。"
        }
        val flags = bytes.readU16(cursor + 8)
        val compressedSize = bytes.readU32(cursor + 20)
        val uncompressedSize = bytes.readU32(cursor + 24)
        val nameLength = bytes.readU16(cursor + 28)
        val extraLength = bytes.readU16(cursor + 30)
        val commentLength = bytes.readU16(cursor + 32)
        require(compressedSize != ZIP32_SENTINEL && uncompressedSize != ZIP32_SENTINEL) {
            "不支持 ZIP64 条目。"
        }
        val end = cursor.toLong() + CENTRAL_HEADER_SIZE + nameLength + extraLength + commentLength
        require(end <= centralEnd.toLong()) { "ZIP 目录条目范围无效。" }
        require(flags and UTF8_FLAG != 0 || bytes.isAscii(cursor + CENTRAL_HEADER_SIZE, nameLength)) {
            "ZIP 文件名编码不受支持。"
        }
        val name = bytes.copyOfRange(cursor + CENTRAL_HEADER_SIZE, cursor + CENTRAL_HEADER_SIZE + nameLength)
            .decodeToString(throwOnInvalidSequence = true)
        validateSafeZipEntryName(name)
        require(names.add(name)) { "ZIP 中存在重复条目。" }
        val directory = name.endsWith('/')
        if (!directory && includeEntry(name)) {
            require(uncompressedSize <= limits.maxEntryBytes) { "EPUB 单个文件解压后过大，无法安全导入。" }
            require(totalBytes <= limits.maxTotalBytes - uncompressedSize) { "EPUB 解压后的内容过大，无法安全导入。" }
            totalBytes += uncompressedSize
        }
        entries += ZipCentralEntry(name, uncompressedSize, directory)
        cursor = end.toInt()
    }
    require(cursor == centralEnd) { "ZIP 目录长度与条目数量不匹配。" }
    return entries
}

internal fun validateSafeZipEntryName(name: String) {
    require(name.isNotBlank() && '\u0000' !in name && '\\' !in name && !name.startsWith('/')) {
        "ZIP 中包含不安全的文件路径。"
    }
    val path = name.removeSuffix("/")
    require(path.isNotBlank() && path.split('/').none { it.isBlank() || it == "." || it == ".." }) {
        "ZIP 中包含不安全的文件路径。"
    }
    require(!path.substringBefore('/').contains(':')) { "ZIP 中包含不安全的文件路径。" }
}

private fun ByteArray.readU16(offset: Int): Int {
    require(offset >= 0 && offset <= size - 2) { "ZIP 结构范围无效。" }
    return (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)
}

private fun ByteArray.readU32(offset: Int): Long {
    require(offset >= 0 && offset <= size - 4) { "ZIP 结构范围无效。" }
    return (this[offset].toLong() and 0xFF) or
        ((this[offset + 1].toLong() and 0xFF) shl 8) or
        ((this[offset + 2].toLong() and 0xFF) shl 16) or
        ((this[offset + 3].toLong() and 0xFF) shl 24)
}

private fun ByteArray.isAscii(offset: Int, length: Int): Boolean =
    (offset until offset + length).all { this[it].toInt() and 0x80 == 0 }

private const val EOCD_SIGNATURE = 0x06054B50L
private const val CENTRAL_SIGNATURE = 0x02014B50L
private const val EOCD_FIXED_SIZE = 22
private const val CENTRAL_HEADER_SIZE = 46
private const val MAX_EOCD_COMMENT = 65_535
private const val ZIP16_SENTINEL = 0xFFFF
private const val ZIP32_SENTINEL = 0xFFFFFFFFL
private const val UTF8_FLAG = 1 shl 11
