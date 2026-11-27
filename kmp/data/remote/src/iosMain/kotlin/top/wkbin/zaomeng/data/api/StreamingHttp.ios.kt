package top.wkbin.zaomeng.data.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line

/**
 * iOS SSE streaming uses Darwin's suspendable response channel directly.
 */
private val darwinStreamingClient: HttpClient by lazy {
    HttpClient(Darwin) {
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = 3_000
            requestTimeoutMillis = 0
            socketTimeoutMillis = 5 * 60 * 1000
        }
    }
}

actual suspend fun openStreamingResponse(
    url: String,
    jsonBody: String,
    token: String,
): StreamingHttpResponse {
    val response = darwinStreamingClient.preparePost(url) {
        contentType(ContentType.Application.Json)
        setBody(jsonBody)
        header(HttpHeaders.Authorization, "Bearer $token")
    }.execute()
    if (!response.status.isSuccess()) {
        throw StreamingHttpException(
            response.status.value,
            "Streaming request failed: ${response.status}",
            response.bodyAsText(),
        )
    }
    return DarwinStreamingHttpResponse(response.bodyAsChannel())
}

private class DarwinStreamingHttpResponse(
    private val channel: ByteReadChannel,
) : StreamingHttpResponse {
    override suspend fun readUtf8Line(): String? = channel.readUTF8Line()

    override fun close() {
        channel.cancel(null)
    }
}
