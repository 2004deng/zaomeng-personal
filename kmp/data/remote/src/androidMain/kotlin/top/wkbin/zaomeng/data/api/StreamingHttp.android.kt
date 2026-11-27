package top.wkbin.zaomeng.data.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private val streamingClient = OkHttpClient.Builder()
    .connectTimeout(3, TimeUnit.SECONDS)
    .readTimeout(5, TimeUnit.MINUTES)
    .writeTimeout(30, TimeUnit.SECONDS)
    .build()

actual suspend fun openStreamingResponse(
    url: String,
    jsonBody: String,
    token: String,
): StreamingHttpResponse {
    val request = Request.Builder()
        .url(url)
        .post(jsonBody.toRequestBody("application/json; charset=utf-8".toMediaType()))
        .header("Authorization", "Bearer $token")
        .build()
    val call = streamingClient.newCall(request)
    val response = awaitResponse(call)
    if (!response.isSuccessful) {
        val code = response.code
        val message = response.message
        val body = try {
            readCancellable(call, response) { response.body.string() }
        } finally {
            runCatching { response.close() }
        }
        throw StreamingHttpException(code, "Streaming request failed: $code $message", body)
    }
    return OkHttpStreamingHttpResponse(response, call)
}

private suspend fun awaitResponse(call: Call): Response {
    val pendingResponse = AtomicReference<Response?>()
    val response = suspendCancellableCoroutine<Response> { continuation ->
        continuation.invokeOnCancellation {
            call.cancel()
            runCatching { pendingResponse.getAndSet(null)?.close() }
        }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                continuation.resumeWith(Result.failure(error))
            }

            override fun onResponse(call: Call, response: Response) {
                pendingResponse.set(response)
                if (continuation.isActive) {
                    continuation.resume(response) { _, value, _ -> runCatching { value.close() } }
                } else {
                    runCatching { pendingResponse.getAndSet(null)?.close() }
                }
            }
        })
    }
    pendingResponse.compareAndSet(response, null)
    return response
}

private suspend fun <T> readCancellable(
    call: Call,
    response: Response,
    block: () -> T,
): T = coroutineScope {
    val read = async(Dispatchers.IO) { block() }
    try {
        read.await()
    } catch (cancelled: CancellationException) {
        call.cancel()
        runCatching { response.close() }
        read.cancel()
        throw cancelled
    }
}

private class OkHttpStreamingHttpResponse(
    private val response: Response,
    private val call: Call,
) : StreamingHttpResponse {
    private val source = requireNotNull(response.body).source()

    override suspend fun readUtf8Line(): String? = readCancellable(call, response) {
        source.readUtf8Line()
    }

    override fun close() {
        call.cancel()
        runCatching { response.close() }
    }
}
