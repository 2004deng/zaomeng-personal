package top.wkbin.zaomeng.feature.importbook

internal data class EpubManifestItem(
    val href: String,
    val mediaType: String,
    val properties: Set<String>,
)

internal fun epubPackagePath(containerXml: String): String {
    var rootName = ""
    var packagePath = ""
    parseEpubXml(containerXml) { name, parent, attributes ->
        if (parent.isEmpty()) rootName = name
        if (name == "rootfile" && parent == "rootfiles" &&
            attributes["media-type"] == EPUB_PACKAGE_MEDIA_TYPE
        ) {
            val fullPath = attributes["full-path"].orEmpty()
            if (packagePath.isBlank()) packagePath = resolveEpubPath("", fullPath)
        }
    }
    require(rootName == "container" && packagePath.isNotBlank()) { "EPUB 缺少有效的 package 文档路径。" }
    return packagePath
}

internal fun epubSpinePaths(packageXml: String, packagePath: String): List<String> {
    var rootName = ""
    val manifest = linkedMapOf<String, EpubManifestItem>()
    val spine = mutableListOf<Pair<String, Boolean>>()
    parseEpubXml(packageXml) { name, parent, attributes ->
        if (parent.isEmpty()) rootName = name
        if (name == "item" && parent == "manifest") {
            val id = attributes["id"].orEmpty()
            val href = attributes["href"].orEmpty()
            require(id.isNotBlank() && href.isNotBlank()) { "EPUB manifest 条目无效。" }
            require(manifest.put(id, EpubManifestItem(
                href = href,
                mediaType = attributes["media-type"].orEmpty(),
                properties = attributes["properties"].orEmpty().split(Regex("\\s+")).filter(String::isNotBlank).toSet(),
            )) == null) { "EPUB manifest 中存在重复 id。" }
        }
        if (name == "itemref" && parent == "spine") {
            val idref = attributes["idref"].orEmpty()
            require(idref.isNotBlank()) { "EPUB spine 条目无效。" }
            spine += idref to attributes["linear"].equals("no", ignoreCase = true)
        }
    }
    require(rootName == "package" && manifest.isNotEmpty() && spine.isNotEmpty()) {
        "EPUB package 文档缺少 manifest 或 spine。"
    }
    return spine.asSequence()
        .filterNot { it.second }
        .map { (idref, _) -> manifest[idref] ?: throw IllegalArgumentException("EPUB spine 引用了不存在的 manifest 条目。") }
        .filter { item ->
            item.properties.none { it == "nav" } &&
                item.mediaType in EPUB_TEXT_MEDIA_TYPES
        }
        .map { item -> resolveEpubPath(packagePath, item.href) }
        .toList()
        .also { require(it.isNotEmpty()) { "EPUB spine 中没有可导入的正文。" } }
}

internal fun resolveEpubPath(basePackagePath: String, reference: String): String {
    val rawPath = reference.substringBefore('#').substringBefore('?')
    require(rawPath.isNotBlank() && !rawPath.startsWith('/') && '\\' !in rawPath) {
        "EPUB 包含不安全的文件路径。"
    }
    val decodedPath = decodeUriPath(rawPath)
    require(!decodedPath.startsWith('/') && '\\' !in decodedPath && '\u0000' !in decodedPath) {
        "EPUB 包含不安全的文件路径。"
    }
    val firstSegment = decodedPath.substringBefore('/')
    require(!firstSegment.contains(':')) { "EPUB 包含不安全的文件路径。" }

    val segments = if (basePackagePath.isBlank()) {
        mutableListOf()
    } else {
        basePackagePath.substringBeforeLast('/', "").split('/').filter(String::isNotBlank).toMutableList()
    }
    for (segment in decodedPath.split('/')) {
        when (segment) {
            "" -> throw IllegalArgumentException("EPUB 包含不安全的文件路径。")
            "." -> Unit
            ".." -> {
                require(segments.isNotEmpty()) { "EPUB 文件路径越过了归档根目录。" }
                segments.removeAt(segments.lastIndex)
            }
            else -> segments += segment
        }
    }
    require(segments.isNotEmpty()) { "EPUB 文件路径为空。" }
    return segments.joinToString("/")
}

private fun parseEpubXml(
    xml: String,
    onStartTag: (name: String, parent: String, attributes: Map<String, String>) -> Unit,
) {
    val stack = mutableListOf<String>()
    var cursor = 0
    while (cursor < xml.length) {
        val open = xml.indexOf('<', cursor)
        if (open < 0) break
        when {
            xml.startsWith("<!--", open) -> {
                val end = xml.indexOf("-->", open + 4)
                require(end >= 0) { "EPUB XML 注释不完整。" }
                cursor = end + 3
            }
            xml.startsWith("<![CDATA[", open) -> {
                val end = xml.indexOf("]]>", open + 9)
                require(end >= 0) { "EPUB XML CDATA 不完整。" }
                cursor = end + 3
            }
            xml.startsWith("<?", open) -> {
                val end = xml.indexOf("?>", open + 2)
                require(end >= 0) { "EPUB XML 声明不完整。" }
                cursor = end + 2
            }
            xml.startsWith("<!", open) -> throw IllegalArgumentException("EPUB XML 不允许 DTD 或实体声明。")
            else -> {
                val end = findXmlTagEnd(xml, open + 1)
                val tag = parseXmlTag(xml.substring(open + 1, end))
                if (tag.closing) {
                    require(stack.lastOrNull() == tag.name) { "EPUB XML 标签嵌套无效。" }
                    stack.removeAt(stack.lastIndex)
                } else {
                    onStartTag(tag.name, stack.lastOrNull().orEmpty(), tag.attributes)
                    if (!tag.selfClosing) stack += tag.name
                }
                cursor = end + 1
            }
        }
    }
    require(stack.isEmpty()) { "EPUB XML 标签未闭合。" }
}

private fun findXmlTagEnd(xml: String, start: Int): Int {
    var quote = '\u0000'
    for (index in start until xml.length) {
        val char = xml[index]
        if (quote != '\u0000') {
            if (char == quote) quote = '\u0000'
        } else {
            when (char) {
                '\'', '"' -> quote = char
                '>' -> return index
            }
        }
    }
    throw IllegalArgumentException("EPUB XML 标签不完整。")
}

private data class ParsedXmlTag(
    val name: String,
    val attributes: Map<String, String>,
    val closing: Boolean,
    val selfClosing: Boolean,
)

private fun parseXmlTag(source: String): ParsedXmlTag {
    var cursor = 0
    while (cursor < source.length && source[cursor].isWhitespace()) cursor++
    val closing = cursor < source.length && source[cursor] == '/'
    if (closing) cursor++
    val nameStart = cursor
    while (cursor < source.length && !source[cursor].isWhitespace() && source[cursor] != '/' && source[cursor] != '>') cursor++
    val qualifiedName = source.substring(nameStart, cursor)
    require(qualifiedName.isNotBlank()) { "EPUB XML 标签名无效。" }
    val name = qualifiedName.substringAfter(':')
    if (closing) {
        while (cursor < source.length && source[cursor].isWhitespace()) cursor++
        require(cursor == source.length) { "EPUB XML 结束标签无效。" }
        return ParsedXmlTag(name, emptyMap(), closing = true, selfClosing = false)
    }

    val attributes = linkedMapOf<String, String>()
    var selfClosing = false
    while (cursor < source.length) {
        while (cursor < source.length && source[cursor].isWhitespace()) cursor++
        if (cursor == source.length) break
        if (source[cursor] == '/') {
            selfClosing = true
            cursor++
            while (cursor < source.length && source[cursor].isWhitespace()) cursor++
            require(cursor == source.length) { "EPUB XML 空元素标签无效。" }
            break
        }
        val attributeStart = cursor
        while (cursor < source.length && !source[cursor].isWhitespace() && source[cursor] != '=' && source[cursor] != '/') cursor++
        val qualifiedAttribute = source.substring(attributeStart, cursor)
        require(qualifiedAttribute.isNotBlank()) { "EPUB XML 属性名无效。" }
        while (cursor < source.length && source[cursor].isWhitespace()) cursor++
        require(cursor < source.length && source[cursor] == '=') { "EPUB XML 属性缺少值。" }
        cursor++
        while (cursor < source.length && source[cursor].isWhitespace()) cursor++
        require(cursor < source.length && (source[cursor] == '\'' || source[cursor] == '"')) {
            "EPUB XML 属性值必须使用引号。"
        }
        val quote = source[cursor++]
        val valueStart = cursor
        while (cursor < source.length && source[cursor] != quote) cursor++
        require(cursor < source.length) { "EPUB XML 属性值未闭合。" }
        val attributeName = qualifiedAttribute.substringAfter(':')
        val value = decodeXmlEntities(source.substring(valueStart, cursor))
        cursor++
        if (qualifiedAttribute != "xmlns" && !qualifiedAttribute.startsWith("xmlns:")) {
            require(attributes.put(attributeName, value) == null) { "EPUB XML 属性重复。" }
        }
    }
    return ParsedXmlTag(name, attributes, closing = false, selfClosing = selfClosing)
}

private fun decodeXmlEntities(value: String): String {
    val result = StringBuilder(value.length)
    var cursor = 0
    while (cursor < value.length) {
        if (value[cursor] != '&') {
            result.append(value[cursor++])
            continue
        }
        val end = value.indexOf(';', cursor + 1)
        require(end >= 0) { "EPUB XML 实体不完整。" }
        val entity = value.substring(cursor + 1, end)
        when (entity) {
            "amp" -> result.append('&')
            "lt" -> result.append('<')
            "gt" -> result.append('>')
            "quot" -> result.append('"')
            "apos" -> result.append('\'')
            else -> {
                require(entity.startsWith('#')) { "EPUB XML 包含不支持的实体。" }
                val codePoint = if (entity.startsWith("#x", ignoreCase = true)) {
                    entity.substring(2).toIntOrNull(16)
                } else {
                    entity.substring(1).toIntOrNull()
                }
                require(codePoint != null && codePoint in 1..0x10FFFF && codePoint !in 0xD800..0xDFFF) {
                    "EPUB XML 字符实体无效。"
                }
                if (codePoint <= 0xFFFF) {
                    result.append(codePoint.toChar())
                } else {
                    val valueAboveBmp = codePoint - 0x10000
                    result.append((0xD800 + (valueAboveBmp shr 10)).toChar())
                    result.append((0xDC00 + (valueAboveBmp and 0x3FF)).toChar())
                }
            }
        }
        cursor = end + 1
    }
    return result.toString()
}

private fun decodeUriPath(path: String): String {
    val result = StringBuilder(path.length)
    var cursor = 0
    while (cursor < path.length) {
        if (path[cursor] != '%') {
            result.append(path[cursor++])
            continue
        }
        val bytes = mutableListOf<Byte>()
        while (cursor < path.length && path[cursor] == '%') {
            require(cursor + 2 < path.length) { "EPUB URI 转义无效。" }
            val byte = path.substring(cursor + 1, cursor + 3).toIntOrNull(16)
            require(byte != null) { "EPUB URI 转义无效。" }
            bytes += byte.toByte()
            cursor += 3
        }
        result.append(bytes.toByteArray().decodeToString(throwOnInvalidSequence = true))
    }
    return result.toString()
}

private const val EPUB_PACKAGE_MEDIA_TYPE = "application/oebps-package+xml"
private val EPUB_TEXT_MEDIA_TYPES = setOf("application/xhtml+xml", "text/html")
