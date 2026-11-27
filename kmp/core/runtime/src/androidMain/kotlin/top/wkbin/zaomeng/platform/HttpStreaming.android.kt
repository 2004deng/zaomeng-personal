package top.wkbin.zaomeng.platform

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
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

actual fun createHttpClientEngine(): HttpClientEngine = OkHttp.create()

private val streamingHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
}

actual suspend fun <T> withStreamingHttpPost(
    url: String,
    headers: Map<String, String>,
    body: String,
    consume: suspend (PlatformStreamingResponse) -> T,
): T {
    val requestBuilder = Request.Builder()
        .url(url)
        .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
    headers.forEach { (name, value) -> requestBuilder.header(name, value) }
    val call = streamingHttpClient.newCall(requestBuilder.build())
    val response = awaitResponse(call)
    return response.use { consume(OkHttpStreamingResponse(it, call)) }
}

private suspend fun awaitResponse(call: Call): Response {
    val pendingResponse = AtomicReference<Response?>()
    return suspendCancellableCoroutine { continuation ->
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
                    continuation.resume(response) { _, value, _ -> value.close() }
                } else {
                    runCatching { pendingResponse.getAndSet(null)?.close() }
                }
            }
        })
    }
}

private class OkHttpStreamingResponse(
    private val response: Response,
    private val call: Call,
) : PlatformStreamingResponse {
    private val source = requireNotNull(response.body).source()

    override val statusCode: Int = response.code
    override val statusDescription: String = response.message

    override suspend fun readUtf8Line(): String? = readCancellable {
        source.readUtf8Line()
    }

    override suspend fun readRemainingText(): String = readCancellable {
        source.readUtf8()
    }

    private suspend fun <T> readCancellable(block: () -> T): T = coroutineScope {
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
}
