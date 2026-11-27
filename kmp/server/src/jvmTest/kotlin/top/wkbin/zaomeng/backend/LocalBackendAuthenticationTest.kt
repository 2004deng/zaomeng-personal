package top.wkbin.zaomeng.backend

import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import top.wkbin.zaomeng.platform.ServerPlatform

class LocalBackendAuthenticationTest {
    // Construction must not start the server or access private application storage.
    private val platform = Proxy.newProxyInstance(
        ServerPlatform::class.java.classLoader,
        arrayOf(ServerPlatform::class.java),
    ) { _, method, _ -> error("Unexpected platform access: ${method.name}") } as ServerPlatform

    @Test
    fun `default and legacy platform tokens generate private per-controller credentials`() {
        val suppliedTokens = listOf("", "dev-token", "desktop-dev-token", "android-dev-token")
        val controllers = suppliedTokens.map { LocalBackendController(platform, token = it) }
        val tokens = controllers.map { it.authToken } + LocalBackendController(platform).authToken

        assertEquals(tokens.size, tokens.toSet().size)
        tokens.forEach { token ->
            assertTrue(token.startsWith("local-"))
            assertTrue(token.length >= 40)
            assertTrue(token !in suppliedTokens)
        }
    }

    @Test
    fun `client endpoint receives the same generated credential as its server`() {
        val controller = LocalBackendController(platform)
        val endpoint = LocalBackendEndpointProvider(controller, controller.authToken)

        assertEquals(controller.authToken, endpoint.currentToken())
        assertNotEquals("android-dev-token", endpoint.currentToken())
    }

    @Test
    fun `explicit non-development credential is retained`() {
        val controller = LocalBackendController(platform, token = "explicit-test-credential")

        assertEquals("explicit-test-credential", controller.authToken)
    }
}
