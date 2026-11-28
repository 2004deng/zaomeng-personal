package top.wkbin.zaomeng.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import top.wkbin.zaomeng.data.api.KtorSessionRequestException

class DiscoveryErrorMappingTest {
    @Test
    fun `json error body becomes readable message without raw braces`() {
        val error = discoveryRequestError(
            KtorSessionRequestException(
                503,
                """{"error":"模型服务暂时不可用（API returned 503），请稍后重试。"}""",
            ),
        )

        assertEquals("模型服务暂时不可用（API returned 503），请稍后重试。", error.message)
        assertEquals(503, error.statusCode)
        assertTrue(!error.message.orEmpty().contains("{"))
    }

    @Test
    fun `non json body falls back to http status instead of showing markup`() {
        val error = discoveryRequestError(KtorSessionRequestException(502, "<html>bad gateway</html>"))

        assertEquals("本地接口返回 HTTP 502。", error.message)
        assertEquals(502, error.statusCode)
    }
}
