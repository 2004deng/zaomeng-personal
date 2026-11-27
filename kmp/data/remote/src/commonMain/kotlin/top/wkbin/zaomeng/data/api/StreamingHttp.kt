package top.wkbin.zaomeng.data.api

class StreamingHttpException(
    val statusCode: Int,
    message: String,
    val responseBody: String,
) : IllegalStateException(message)

interface StreamingHttpResponse {
    suspend fun readUtf8Line(): String?
    fun close()
}

/** Open an SSE response without buffering it to EOF; callers must close it. */
expect suspend fun openStreamingResponse(
    url: String,
    jsonBody: String,
    token: String,
): StreamingHttpResponse
