package top.wkbin.zaomeng.feature.redistill

import androidx.lifecycle.viewModelScope
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import top.wkbin.zaomeng.data.RunRepository
import top.wkbin.zaomeng.data.api.RedistillSuggestionsDto
import top.wkbin.zaomeng.data.api.RunManifestDto
import top.wkbin.zaomeng.data.api.SamplingPlanDto
import top.wkbin.zaomeng.domain.distill.DistillPlanningGateway
import top.wkbin.zaomeng.domain.distill.EstimateDistillSamplingUseCase
import top.wkbin.zaomeng.domain.distill.SuggestRedistillSegmentsUseCase
import top.wkbin.zaomeng.platform.NoopDistillationForeground

@OptIn(ExperimentalCoroutinesApi::class)
class RedistillCharacterDiscoveryTest {
    private val dispatcher = StandardTestDispatcher()
    private val viewModels = mutableListOf<RedistillViewModel>()

    @BeforeTest
    fun setup() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    @Test
    fun `existing characters are preserved until explicit discovery from stored source`() = runTest(dispatcher) {
        val fixture = fixture()
        val viewModel = fixture.viewModel
        advanceUntilIdle()

        assertEquals("原有人物", viewModel.state.value.characters)
        assertTrue(fixture.storedRequests.isEmpty())
        viewModel.discoverCharacters()
        advanceUntilIdle()
        assertEquals(listOf("run-1"), fixture.storedRequests)
        assertTrue(fixture.selectedRequests.isEmpty())
        assertEquals("林晚、沈照", viewModel.state.value.characters)
        assertEquals(setOf("林晚", "沈照"), viewModel.state.value.recommendationCharacters)
    }

    @Test
    fun `empty character list automatically discovers from existing run source`() = runTest(dispatcher) {
        val fixture = fixture(characters = emptyList())
        advanceUntilIdle()

        assertEquals(listOf("run-1"), fixture.storedRequests)
        assertTrue(fixture.selectedRequests.isEmpty())
        assertEquals("林晚、沈照", fixture.viewModel.state.value.characters)
        assertFalse(fixture.viewModel.state.value.discoveringCharacters)
        assertTrue(fixture.viewModel.state.value.characterDiscoveryMessage.contains("2"))
    }

    @Test
    fun `automatic failure is shown and explicit retry succeeds`() = runTest(dispatcher) {
        var attempts = 0
        val fixture = fixture(characters = emptyList(), discoverStored = {
            if (++attempts == 1) error("小说源文件暂时不可读")
            listOf("林晚")
        })
        val viewModel = fixture.viewModel
        advanceUntilIdle()
        assertEquals("小说源文件暂时不可读", viewModel.state.value.characterDiscoveryError)
        assertEquals("", viewModel.state.value.characters)
        assertFalse(viewModel.state.value.discoveringCharacters)

        viewModel.discoverCharacters()
        advanceUntilIdle()
        assertEquals(2, attempts)
        assertEquals("林晚", viewModel.state.value.characters)
        assertEquals("", viewModel.state.value.characterDiscoveryError)
    }

    @Test
    fun `newly selected text is used for discovery without requesting stored source`() = runTest(dispatcher) {
        val fixture = fixture(discoverSelected = { _, _ -> listOf("新正文人物") })
        val viewModel = fixture.viewModel
        advanceUntilIdle()
        viewModel.loadDocument("追加正文.txt", "新正文人物走进房间。".encodeToByteArray())
        viewModel.awaitState { it.fileName == "追加正文.txt" && !it.readingFile }
        advanceUntilIdle()
        assertTrue(fixture.selectedRequests.isEmpty())
        assertEquals("原有人物", viewModel.state.value.characters)

        viewModel.discoverCharacters()
        advanceUntilIdle()
        assertEquals("追加正文.txt", fixture.selectedRequests.single().first)
        assertTrue(fixture.selectedRequests.single().second.contains("新正文人物"))
        assertTrue(fixture.storedRequests.isEmpty())
        assertEquals("新正文人物", viewModel.state.value.characters)
    }

    @Test
    fun `manual character edit invalidates late discovery from stored source`() = runTest(dispatcher) {
        val started = CompletableDeferred<Unit>()
        val response = CompletableDeferred<List<String>>()
        val fixture = fixture(discoverStored = {
            started.complete(Unit)
            withContext(NonCancellable) { response.await() }
        })
        val viewModel = fixture.viewModel
        advanceUntilIdle()
        viewModel.discoverCharacters()
        awaitStarted(started)
        viewModel.updateCharacters("手填角色")
        response.complete(listOf("旧识别结果"))
        advanceUntilIdle()

        assertEquals("手填角色", viewModel.state.value.characters)
        assertEquals(setOf("手填角色"), viewModel.state.value.recommendationCharacters)
        assertFalse(viewModel.state.value.discoveringCharacters)
        assertEquals("", viewModel.state.value.characterDiscoveryMessage)
        assertEquals("", viewModel.state.value.characterDiscoveryError)
    }

    @Test
    fun `changing selected source prevents its old result from replacing newer discovery`() = runTest(dispatcher) {
        val oldStarted = CompletableDeferred<Unit>()
        val oldResponse = CompletableDeferred<List<String>>()
        val fixture = fixture(discoverSelected = { filename, _ ->
            if (filename == "旧正文.txt") {
                oldStarted.complete(Unit)
                withContext(NonCancellable) { oldResponse.await() }
            } else {
                listOf("新正文人物")
            }
        })
        val viewModel = fixture.viewModel
        advanceUntilIdle()
        viewModel.loadDocument("旧正文.txt", "旧正文人物出现。".encodeToByteArray())
        viewModel.awaitState { it.fileName == "旧正文.txt" && !it.readingFile }
        viewModel.discoverCharacters()
        awaitStarted(oldStarted)
        viewModel.loadDocument("新正文.txt", "新正文人物出现。".encodeToByteArray())
        viewModel.awaitState { it.fileName == "新正文.txt" && !it.readingFile }
        viewModel.discoverCharacters()
        advanceUntilIdle()
        assertEquals("新正文人物", viewModel.state.value.characters)
        oldResponse.complete(listOf("旧正文人物"))
        advanceUntilIdle()

        assertEquals(listOf("旧正文.txt", "新正文.txt"), fixture.selectedRequests.map { it.first })
        assertEquals("新正文人物", viewModel.state.value.characters)
        assertEquals("", viewModel.state.value.characterDiscoveryError)
    }

    @Test
    fun `clearing selected file ignores late failure and next discovery returns to stored source`() = runTest(dispatcher) {
        val started = CompletableDeferred<Unit>()
        val response = CompletableDeferred<List<String>>()
        val fixture = fixture(discoverSelected = { _, _ ->
            started.complete(Unit)
            withContext(NonCancellable) { response.await() }
        })
        val viewModel = fixture.viewModel
        advanceUntilIdle()
        viewModel.loadDocument("追加正文.txt", "林晚走进客栈。".encodeToByteArray())
        viewModel.awaitState { it.fileName == "追加正文.txt" && !it.readingFile }
        viewModel.discoverCharacters()
        awaitStarted(started)
        viewModel.clearFile()
        response.completeExceptionally(IllegalStateException("已经过期的错误"))
        advanceUntilIdle()

        assertEquals("", viewModel.state.value.fileName)
        assertEquals("原有人物", viewModel.state.value.characters)
        assertFalse(viewModel.state.value.discoveringCharacters)
        assertEquals("", viewModel.state.value.characterDiscoveryError)
        viewModel.discoverCharacters()
        advanceUntilIdle()
        assertEquals(listOf("run-1"), fixture.storedRequests)
        assertEquals("林晚、沈照", viewModel.state.value.characters)
    }

    @Test
    fun `clearing while replacement text is still decoding does not restore the removed source`() = runTest(dispatcher) {
        val fixture = fixture(discoverSelected = { _, _ -> listOf("新正文人物") })
        val viewModel = fixture.viewModel
        advanceUntilIdle()
        // 读取任务已排队但尚未开始：移除必须先取消它，否则解码结束后来源会被重新挂回。
        viewModel.loadDocument("待替换.txt", "林晚走进客栈。".encodeToByteArray())
        viewModel.clearFile()
        repeat(3) {
            advanceUntilIdle()
            withContext(Dispatchers.Default) { }
        }

        assertEquals("", viewModel.state.value.fileName)
        assertFalse(viewModel.state.value.readingFile)
        assertTrue(fixture.selectedRequests.isEmpty())
        assertEquals("原有人物", viewModel.state.value.characters)
    }

    private fun fixture(
        characters: List<String> = listOf("原有人物"),
        discoverStored: suspend () -> List<String> = { listOf("林晚", "沈照") },
        discoverSelected: suspend (String, String) -> List<String> = { _, _ -> listOf("林晚", "沈照") },
    ): Fixture = Fixture(characters, discoverStored, discoverSelected).also { viewModels += it.viewModel }

    private class Fixture(
        characters: List<String>,
        discoverStored: suspend () -> List<String>,
        discoverSelected: suspend (String, String) -> List<String>,
    ) {
        val storedRequests = mutableListOf<String>()
        val selectedRequests = mutableListOf<Pair<String, String>>()
        private val repository = repository<RunRepository> { method, arguments ->
            when (method) {
                "getRun" -> RunManifestDto(runId = "run-1", lockedCharacters = characters)
                "discoverRunCharacters" -> suspendResult(arguments) {
                    storedRequests += arguments[0] as String
                    discoverStored()
                }
                "discoverCharacters" -> suspendResult(arguments) {
                    val filename = arguments[0] as String
                    val excerpt = arguments[1] as String
                    selectedRequests += filename to excerpt
                    discoverSelected(filename, excerpt)
                }
                else -> error("Unexpected RunRepository call: $method")
            }
        }
        val viewModel = RedistillViewModel(
            repository = repository,
            runId = "run-1",
            distillationForeground = NoopDistillationForeground,
            estimateDistillSampling = EstimateDistillSamplingUseCase(SamplingGateway),
            suggestRedistillSegments = SuggestRedistillSegmentsUseCase(SamplingGateway),
        )
    }

    private object SamplingGateway : DistillPlanningGateway {
        override suspend fun estimateSampling(
            charCount: Int,
            sentenceCount: Int,
            characterCount: Int,
            maxSentences: Int,
            maxChars: Int,
        ) = SamplingPlanDto()

        override suspend fun suggestRedistillSegments(runId: String, character: String, maxSegments: Int): RedistillSuggestionsDto =
            error("Unexpected segment recommendation")
    }

    private suspend fun RedistillViewModel.awaitState(predicate: (RedistillUiState) -> Boolean) {
        // File decoding uses the real Default dispatcher; keep this wait off the virtual test clock.
        withContext(Dispatchers.Default) { withTimeout(5_000) { state.first(predicate) } }
    }

    private suspend fun awaitStarted(signal: CompletableDeferred<Unit>) {
        withContext(Dispatchers.Default) { withTimeout(5_000) { signal.await() } }
    }

    private companion object {
        inline fun <reified T> repository(noinline invoke: (String, Array<out Any?>) -> Any?): T {
            val type = T::class.java
            return type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, arguments ->
                invoke(method.name, arguments ?: emptyArray())
            })
        }

        @Suppress("UNCHECKED_CAST")
        fun <T> suspendResult(arguments: Array<out Any?>, block: suspend () -> T): Any? =
            block.startCoroutineUninterceptedOrReturn(arguments.last() as Continuation<T>)
    }
}
