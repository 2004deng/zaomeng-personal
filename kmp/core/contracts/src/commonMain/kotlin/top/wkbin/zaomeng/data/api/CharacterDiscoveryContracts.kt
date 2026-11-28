package top.wkbin.zaomeng.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val CHARACTER_DISCOVERY_MAX_EXCERPT_CHARS = 24_000

@Serializable
data class DiscoverCharactersRequest(
    @SerialName("novel_name") val novelName: String,
    val excerpt: String,
)

@Serializable
data class DiscoveredCharactersDto(val characters: List<String> = emptyList())

/** Samples the beginning, middle and end without sending an entire novel to the model. */
fun sampleNovelCharacterExcerpt(text: String): String {
    if (text.length <= CHARACTER_DISCOVERY_MAX_EXCERPT_CHARS) return text.trim()
    val windows = 9
    val separator = "\n\n"
    val windowSize = (CHARACTER_DISCOVERY_MAX_EXCERPT_CHARS - separator.length * (windows - 1)) / windows
    val lastStart = text.length - windowSize
    return (0 until windows).joinToString(separator) { index ->
        var start = (lastStart.toLong() * index / (windows - 1)).toInt()
        var end = start + windowSize
        // Keep UTF-16 surrogate pairs intact at both sample boundaries.
        if (start > 0 && text[start].isLowSurrogate() && text[start - 1].isHighSurrogate()) start++
        if (end < text.length && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        text.substring(start, end).trim()
    }
}
