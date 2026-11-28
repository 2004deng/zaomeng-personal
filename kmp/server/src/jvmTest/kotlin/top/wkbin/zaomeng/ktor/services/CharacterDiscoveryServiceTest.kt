package top.wkbin.zaomeng.ktor.services

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import top.wkbin.zaomeng.data.api.DiscoverCharactersRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CharacterDiscoveryServiceTest {
    @Test
    fun `discovery sends bounded excerpt separately from system rules`() = runBlocking {
        var captured = emptyList<LlmClient.ChatMessage>()
        val opening = "林晚走进客栈。沈照向她点头。"
        val excerpt = opening + "夜".repeat(24_000 - opening.length)
        val request = DiscoverCharactersRequest(novelName = "书".repeat(240), excerpt = excerpt)
        val service = CharacterDiscoveryService { messages ->
            captured = messages
            response("林晚", "沈照")
        }

        assertEquals(listOf("林晚", "沈照"), service.discover(request).characters)
        assertEquals(listOf("system", "user"), captured.map { it.role })
        val input = Json.parseToJsonElement(requireNotNull(captured[1].content)).jsonObject
        assertEquals(request.novelName, input.getValue("novel_name").jsonPrimitive.content)
        assertEquals(excerpt, input.getValue("excerpt").jsonPrimitive.content)
        val system = requireNotNull(captured[0].content)
        assertTrue(system.contains("不得使用作品常识"))
        assertTrue(system.contains("禁止 Markdown 围栏"))
    }

    @Test
    fun `invalid input never invokes model`() = runBlocking {
        var calls = 0
        val service = CharacterDiscoveryService {
            calls++
            response("林晚")
        }
        val invalidRequests = listOf(
            request(excerpt = " \n\t "),
            request(excerpt = "文".repeat(24_001)),
            request(novelName = "名".repeat(241)),
        )

        for (invalid in invalidRequests) {
            val error = assertFailsWith<IllegalArgumentException> { service.discover(invalid) }
            assertTrue(error.message.orEmpty().isNotBlank())
        }
        assertEquals(0, calls)
    }

    @Test
    fun `names retain original order and are trimmed and deduplicated`() = runBlocking {
        val service = CharacterDiscoveryService { response(" 沈照 ", "林晚", "沈照", "林晚") }

        assertEquals(listOf("沈照", "林晚"), service.discover(request()).characters)
    }

    @Test
    fun `names require literal excerpt evidence even when novel title names a character`() = runBlocking {
        val service = CharacterDiscoveryService { response("贾宝玉", "林晚", "林 晚", "沈照") }
        val result = service.discover(request(novelName = "贾宝玉传"))

        assertEquals(listOf("林晚", "沈照"), result.characters)
    }

    @Test
    fun `overlong multiline control and empty names are discarded`() = runBlocking {
        val longName = "甲".repeat(65)
        val service = CharacterDiscoveryService {
            response(longName, "林晚\n沈照", "林晚\r", "沈照\u2028", "林晚\t", " ", "林晚")
        }
        val result = service.discover(request(excerpt = "林晚与沈照说话。$longName"))

        assertEquals(listOf("林晚"), result.characters)
    }

    @Test
    fun `stable appellations and non chinese names with exact evidence are supported`() = runBlocking {
        val service = CharacterDiscoveryService { response("老船长", "Alice Smith") }

        assertEquals(
            listOf("老船长", "Alice Smith"),
            service.discover(request(excerpt = "老船长叫住了 Alice Smith。 ")).characters,
        )
    }

    @Test
    fun `result contains at most twelve distinct grounded names`() = runBlocking {
        val names = (1..15).map { "人物${it.toString().padStart(2, '0')}" }
        val service = CharacterDiscoveryService { response(*names.toTypedArray()) }

        assertEquals(names.take(12), service.discover(request(excerpt = names.joinToString("、"))).characters)
    }

    @Test
    fun `malformed or wrong shaped model replies fail without exposing raw output`() = runBlocking {
        val malformed = listOf(
            "not JSON private-text",
            "[{\"characters\":[\"林晚\"]}]",
            "{\"characters\":\"林晚\"}",
            "{\"characters\":[1]}",
            "{\"characters\":[null]}",
            "{\"characters\":[{\"name\":\"林晚\"}]}",
            "{\"characters\":[\"林晚\"],\"reason\":\"private-text\"}",
            "{}",
            "```json\n{\"characters\":[\"林晚\"]}\n```",
            "解释：{\"characters\":[\"林晚\"]}",
        )
        for (raw in malformed) {
            val service = CharacterDiscoveryService { raw }
            val error = assertFailsWith<IllegalStateException> { service.discover(request()) }
            assertTrue(error.message.orEmpty().contains("格式不正确"))
            assertTrue(!error.message.orEmpty().contains("private-text"))
            assertEquals(null, error.cause)
        }
    }

    @Test
    fun `empty or entirely ungrounded lists provide actionable failure`() = runBlocking {
        for (raw in listOf(response(), response("不存在的人物"))) {
            val service = CharacterDiscoveryService { raw }
            val error = assertFailsWith<IllegalStateException> { service.discover(request()) }

            assertTrue(error.message.orEmpty().contains("正文"))
            assertTrue(error.message.orEmpty().contains("手动填写"))
        }
    }

    @Test
    fun `oversized model response fails before decoding`() = runBlocking {
        val service = CharacterDiscoveryService { "文".repeat(8_193) }
        val error = assertFailsWith<IllegalStateException> { service.discover(request()) }

        assertTrue(error.message.orEmpty().contains("过长"))
    }

    @Test
    fun `model cancellation propagates without conversion or retry`() = runBlocking {
        var calls = 0
        val cancelled = CancellationException("discovery cancelled")
        val service = CharacterDiscoveryService {
            calls++
            throw cancelled
        }
        val error = assertFailsWith<CancellationException> { service.discover(request()) }

        assertSame(cancelled, error)
        assertEquals(1, calls)
    }

    private fun request(
        novelName: String = "客栈.txt",
        excerpt: String = "林晚走进客栈。沈照向她点头。",
    ) = DiscoverCharactersRequest(novelName = novelName, excerpt = excerpt)

    private fun response(vararg names: String): String = buildJsonObject {
        put("characters", JsonArray(names.map(::JsonPrimitive)))
    }.toString()
}
