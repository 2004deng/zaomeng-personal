package top.wkbin.zaomeng.feature.chat

import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import top.wkbin.zaomeng.data.CardRepository
import top.wkbin.zaomeng.data.DialogueRepository
import top.wkbin.zaomeng.data.PluginRepository
import top.wkbin.zaomeng.data.SessionRepository
import top.wkbin.zaomeng.data.api.DialogueSessionDto
import top.wkbin.zaomeng.data.api.TranscriptItemDto

class ChatToolsDelegateTest {
    @Test
    fun `opening a reply version requests navigation while preserving the source session`() = runBlocking {
        val original = DialogueSessionDto(runId = "run", sessionId = "original", status = "ready")
        val branch = original.copy(sessionId = "branch")
        var state = ChatUiState(runId = original.runId, sessionId = original.sessionId, session = original, loading = false)
        val tools = ChatToolsDelegate(
            dialogue = repository<DialogueRepository> { method ->
                check(method == "branchImportedSwipe")
                branch
            },
            sessions = repository<SessionRepository>(),
            plugins = repository<PluginRepository>(),
            cardsRepository = repository<CardRepository>(),
            scope = this,
            currentState = { state },
            updateState = { transform -> state = transform(state) },
        )

        tools.branchFromImportedSwipe("1", 1)
        coroutineContext.job.children.toList().joinAll()

        assertEquals(branch, state.navigationSession)
        assertEquals(original, state.session)
        assertEquals(original.sessionId, state.sessionId)
        assertTrue(state.toolBusy.isBlank())
        assertTrue(state.error.isBlank())
    }

    @Test
    fun `unrelated newer session does not turn a failed review into success`() = runBlocking {
        val original = DialogueSessionDto(
            runId = "run", sessionId = "session", status = "ready", updatedAt = "2026-11-27T10:00:00Z",
        )
        val unrelatedUpdate = original.copy(updatedAt = "2026-11-27T10:01:00Z")
        var state = ChatUiState(runId = original.runId, sessionId = original.sessionId, session = original, loading = false)
        val tools = ChatToolsDelegate(
            dialogue = repository<DialogueRepository> { method ->
                check(method == "deepReviewLatestReply")
                error("模型接口不可用")
            },
            sessions = repository<SessionRepository> { method ->
                check(method == "getSession")
                unrelatedUpdate
            },
            plugins = repository<PluginRepository>(),
            cardsRepository = repository<CardRepository>(),
            scope = this,
            currentState = { state },
            updateState = { transform -> state = transform(state) },
        )

        tools.deepReviewLatest()
        coroutineContext.job.children.toList().joinAll()

        assertEquals("模型接口不可用", state.error)
        assertTrue(state.notice.isBlank())
        assertTrue(state.toolBusy.isBlank())
        assertEquals(original, state.session)
    }

    @Test
    fun `enhancer update synchronizes timestamp without replacing the loaded history`() = runBlocking {
        val original = DialogueSessionDto(
            runId = "run", sessionId = "session", status = "ready", updatedAt = "2026-11-27T10:00:00Z",
            transcript = listOf(TranscriptItemDto(message = "已经加载的历史")),
        )
        val saved = original.copy(
            updatedAt = "2026-11-27T10:01:00Z",
            pluginEnhancerStates = mapOf("plugin" to mapOf("enhancer" to true)),
            transcript = emptyList(),
        )
        var state = ChatUiState(runId = original.runId, sessionId = original.sessionId, session = original, loading = false)
        val tools = ChatToolsDelegate(
            dialogue = repository<DialogueRepository>(),
            sessions = repository<SessionRepository>(),
            plugins = repository<PluginRepository> { method ->
                check(method == "setGenerationEnhancerState")
                saved
            },
            cardsRepository = repository<CardRepository>(),
            scope = this,
            currentState = { state },
            updateState = { transform -> state = transform(state) },
        )

        tools.toggleGenerationEnhancer(ChatGenerationEnhancer("plugin", "插件", "enhancer", "增强器"))
        coroutineContext.job.children.toList().joinAll()

        assertEquals(saved.updatedAt, state.session?.updatedAt)
        assertEquals(saved.pluginEnhancerStates, state.session?.pluginEnhancerStates)
        assertEquals(original.transcript, state.session?.transcript)
        assertTrue(state.error.isBlank())
    }

    private inline fun <reified T> repository(noinline invoke: (String) -> Any = { error("Unexpected call to $it") }): T {
        val type = T::class.java
        return type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ -> invoke(method.name) })
    }
}
