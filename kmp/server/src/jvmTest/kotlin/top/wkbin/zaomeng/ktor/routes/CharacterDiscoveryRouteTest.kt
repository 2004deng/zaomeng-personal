package top.wkbin.zaomeng.ktor.routes

import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.Path.Companion.toPath
import top.wkbin.zaomeng.data.api.DiscoverCharactersRequest
import top.wkbin.zaomeng.data.api.DiscoveredCharactersDto
import top.wkbin.zaomeng.ktor.services.CharacterDiscoveryService
import top.wkbin.zaomeng.ktor.services.ModelRequestException
import top.wkbin.zaomeng.ktor.services.ModelRequestFailure
import top.wkbin.zaomeng.ktor.services.RunManagementService
import top.wkbin.zaomeng.ktor.services.RunPackageService
import top.wkbin.zaomeng.ktor.services.StorageService
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CharacterDiscoveryRouteTest {
    @Test
    fun `discovery endpoints return names without creating or mutating runs`() = testApplication {
        val directory = createTempDirectory("character-discovery-route")
        try {
            val storage = StorageService(directory.toString().toPath())
            val runDir = storage.getRunDirectory("book")
            storage.writeTextAtomically(runDir / "novel.txt", "林晚与沈照相见。")
            storage.writeRunManifest("book", Json.parseToJsonElement("""{"run_id":"book","novel_name":"客栈.txt","status":"pending"}""") as kotlinx.serialization.json.JsonObject)
            val original = storage.readRunManifest("book")
            var calls = 0
            application {
                install(ContentNegotiation) { json() }
                routing {
                    runManagementRoutes(RunManagementService(storage), RunPackageService(storage), CharacterDiscoveryService {
                        calls++
                        """{"characters":["林晚","沈照"]}"""
                    })
                }
            }
            val initial = client.post("/api/web/runs/characters/discover") {
                contentType(ContentType.Application.Json)
                setBody(Json.encodeToString(DiscoverCharactersRequest("客栈.txt", "林晚与沈照相见。")))
            }
            assertEquals(HttpStatusCode.OK, initial.status)
            assertEquals(listOf("林晚", "沈照"), Json.decodeFromString<DiscoveredCharactersDto>(initial.bodyAsText()).characters)
            assertEquals(HttpStatusCode.OK, client.post("/api/web/runs/book/characters/discover").status)
            assertEquals(2, calls)
            assertEquals(original, storage.readRunManifest("book"))
            assertEquals(listOf("book"), storage.listRunManifests().map { it["run_id"].toString().trim('"') })
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `invalid input missing source and malformed model output expose actionable status codes`() = testApplication {
        val directory = createTempDirectory("character-discovery-errors")
        try {
            val storage = StorageService(directory.toString().toPath())
            storage.writeRunManifest("empty", Json.parseToJsonElement("""{"run_id":"empty","status":"pending"}""") as kotlinx.serialization.json.JsonObject)
            application {
                install(ContentNegotiation) { json() }
                routing {
                    runManagementRoutes(RunManagementService(storage), RunPackageService(storage), CharacterDiscoveryService { "{}" })
                }
            }
            val invalid = client.post("/api/web/runs/characters/discover") {
                contentType(ContentType.Application.Json)
                setBody(Json.encodeToString(DiscoverCharactersRequest("客栈.txt", " ")))
            }
            assertEquals(HttpStatusCode.BadRequest, invalid.status)
            assertEquals(HttpStatusCode.NotFound, client.post("/api/web/runs/missing/characters/discover").status)
            val missingSource = client.post("/api/web/runs/empty/characters/discover")
            assertEquals(HttpStatusCode.BadRequest, missingSource.status)
            assertTrue(missingSource.bodyAsText().contains("正文"))
            val malformed = client.post("/api/web/runs/characters/discover") {
                contentType(ContentType.Application.Json)
                setBody(Json.encodeToString(DiscoverCharactersRequest("客栈.txt", "林晚走来。")))
            }
            assertEquals(HttpStatusCode.BadGateway, malformed.status)
            assertTrue(malformed.bodyAsText().contains("格式"))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `classified model failures return actionable status and message without leaking raw error`() {
        val cases = listOf(
            Triple(ModelRequestFailure.Timeout, HttpStatusCode.GatewayTimeout, "超时"),
            Triple(ModelRequestFailure.Network, HttpStatusCode.BadGateway, "无法连接模型服务"),
            Triple(ModelRequestFailure.ProviderUnavailable, HttpStatusCode.ServiceUnavailable, "暂时不可用"),
            Triple(ModelRequestFailure.InvalidResponse, HttpStatusCode.BadGateway, "无法解析"),
        )
        cases.forEach { (failure, expectedStatus, expectedText) ->
            testApplication {
                val directory = createTempDirectory("character-discovery-failure")
                try {
                    val storage = StorageService(directory.toString().toPath())
                    application {
                        install(ContentNegotiation) { json() }
                        routing {
                            runManagementRoutes(
                                RunManagementService(storage),
                                RunPackageService(storage),
                                CharacterDiscoveryService {
                                    throw ModelRequestException(
                                        failure,
                                        "提示：$expectedText，请重试或手动填写。",
                                        cause = IllegalStateException("raw-provider-body-500"),
                                    )
                                },
                            )
                        }
                    }
                    val response = client.post("/api/web/runs/characters/discover") {
                        contentType(ContentType.Application.Json)
                        setBody(Json.encodeToString(DiscoverCharactersRequest("客栈.txt", "林晚走来。")))
                    }
                    assertEquals(expectedStatus, response.status, failure.name)
                    val body = response.bodyAsText()
                    assertTrue(body.contains(expectedText), "$failure -> $body")
                    assertTrue(!body.contains("raw-provider-body-500"), "raw error leaked: $body")
                } finally {
                    directory.toFile().deleteRecursively()
                }
            }
        }
    }
}
