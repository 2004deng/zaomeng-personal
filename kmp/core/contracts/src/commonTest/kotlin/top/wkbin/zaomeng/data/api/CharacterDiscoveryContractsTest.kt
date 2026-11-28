package top.wkbin.zaomeng.data.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class CharacterDiscoveryContractsTest {
    @Test
    fun `short source is retained verbatim apart from outer whitespace`() {
        assertEquals("林晚叫住沈照。", sampleNovelCharacterExcerpt(" \n林晚叫住沈照。\n "))
        assertEquals("", sampleNovelCharacterExcerpt(" \n\t"))
    }

    @Test
    fun `long source samples beginning middle and end within a fixed budget`() {
        val source = "林晚在开篇出场。" + "甲".repeat(40_000) + "沈照在中部出场。" + "乙".repeat(40_000) + "老船长在结尾出场。"
        val excerpt = sampleNovelCharacterExcerpt(source)
        assertTrue(excerpt.length <= CHARACTER_DISCOVERY_MAX_EXCERPT_CHARS)
        assertTrue(excerpt.startsWith("林晚在开篇出场。"))
        assertTrue(excerpt.endsWith("老船长在结尾出场。"))
        assertTrue(excerpt.contains("沈照在中部出场。"))
        assertTrue(excerpt.contains("甲") && excerpt.contains("乙"))
        assertTrue(excerpt.length < source.length)
    }

    @Test
    fun `sample boundaries preserve supplementary characters`() {
        val excerpt = sampleNovelCharacterExcerpt("😀".repeat(30_001))
        assertTrue(excerpt.length <= CHARACTER_DISCOVERY_MAX_EXCERPT_CHARS)
        assertEquals(excerpt, excerpt.encodeToByteArray().decodeToString())
    }

    @Test
    fun `character discovery contract round trips with snake case novel name`() {
        val request = DiscoverCharactersRequest("客栈.txt", "林晚与沈照相见。")
        val encoded = Json.encodeToString(request)
        assertTrue(encoded.contains("\"novel_name\""))
        assertEquals(request, Json.decodeFromString<DiscoverCharactersRequest>(encoded))
        assertEquals(listOf("林晚"), Json.decodeFromString<DiscoveredCharactersDto>("""{"characters":["林晚"]}""").characters)
    }
}
