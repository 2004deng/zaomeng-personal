package top.wkbin.zaomeng.feature.importbook

import androidx.lifecycle.viewModelScope
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.startCoroutineUninterceptedOrReturn
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import top.wkbin.zaomeng.data.AppRuntimeRepository
import top.wkbin.zaomeng.data.ModelSettingsRepository
import top.wkbin.zaomeng.data.RunRepository
import top.wkbin.zaomeng.data.api.ModelSettingsDto
import top.wkbin.zaomeng.data.api.RedistillSuggestionsDto
import top.wkbin.zaomeng.data.api.RunManifestDto
import top.wkbin.zaomeng.data.api.SamplingPlanDto
import top.wkbin.zaomeng.data.preferences.AppPreferences
import top.wkbin.zaomeng.domain.distill.DistillPlanningGateway
import top.wkbin.zaomeng.domain.distill.EstimateDistillSamplingUseCase
import top.wkbin.zaomeng.platform.NoopDistillationForeground

@OptIn(ExperimentalCoroutinesApi::class)
class ImportBookCharacterDiscoveryTest {
    private val dispatcher = StandardTestDispatcher()
    private val viewModels = mutableListOf<ImportBookViewModel>()

    @BeforeTest
    fun setup() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    @Test
    fun `selecting novel automatically discovers its own characters instead of previous defaults`() = runTest(dispatcher) {
        val fixture = fixture()
        val viewModel = fixture.viewModel
        advanceUntilIdle()
        assertEquals("", viewModel.state.value.characters)

        viewModel.loadDocument("客栈.txt", "林晚走进客栈。沈照向她点头。".encodeToByteArray(), ImportDocumentKind.NovelText)
        viewModel.awaitState { it.characters == "林晚、沈照" && !it.discoveringCharacters }
        advanceUntilIdle()

        assertEquals(1, fixture.requests.size)
        assertEquals("客栈.txt", fixture.requests.single().first)
        assertTrue(fixture.requests.single().second.contains("林晚"))
        assertTrue(fixture.requests.single().second.length <= 24_000)
        assertTrue(viewModel.state.value.characterDiscoveryMessage.contains("2"))
        viewModel.updateAutoDistill(true)
        viewModel.refreshModelConfiguration()
        advanceUntilIdle()
        assertEquals(1, fixture.requests.size)
    }

    @Test
    fun `import without immediate distillation does not discover until enabled`() = runTest(dispatcher) {
        val fixture = fixture(autoDistill = false)
        val viewModel = fixture.viewModel
        advanceUntilIdle()
        viewModel.loadDocument("客栈.txt", "林晚与沈照说话。".encodeToByteArray(), ImportDocumentKind.NovelText)
        viewModel.awaitState { it.fileName == "客栈.txt" && !it.readingFile }
        advanceUntilIdle()

        assertFalse(viewModel.state.value.autoDistill)
        assertEquals("", viewModel.state.value.characters)
        assertTrue(fixture.requests.isEmpty())
        viewModel.updateAutoDistill(true)
        advanceUntilIdle()
        assertEquals("林晚、沈照", viewModel.state.value.characters)
        assertEquals(1, fixture.requests.size)
    }

    @Test
    fun `unconfigured model does not trigger automatic discovery and can refresh after configuration`() = runTest(dispatcher) {
        val fixture = fixture(configured = false)
        val viewModel = fixture.viewModel
        advanceUntilIdle()
        viewModel.loadDocument("客栈.txt", "林晚与沈照说话。".encodeToByteArray(), ImportDocumentKind.NovelText)
        viewModel.awaitState { it.fileName == "客栈.txt" && !it.readingFile }
        advanceUntilIdle()

        assertTrue(fixture.requests.isEmpty())
        viewModel.discoverCharacters()
        assertTrue(viewModel.state.value.characterDiscoveryError.contains("配置模型"))
        fixture.configured = true
        viewModel.refreshModelConfiguration()
        advanceUntilIdle()
        assertEquals("林晚、沈照", viewModel.state.value.characters)
        assertEquals(1, fixture.requests.size)
    }

    @Test
    fun `automatic failure is visible and explicit retry can populate characters`() = runTest(dispatcher) {
        var attempts = 0
        val fixture = fixture(discover = { _, _ ->
            if (++attempts == 1) error("模型暂时不可用")
            listOf("林晚", "沈照")
        })
        val viewModel = fixture.viewModel
        advanceUntilIdle()
        viewModel.loadDocument("客栈.txt", "林晚与沈照说话。".encodeToByteArray(), ImportDocumentKind.NovelText)
        viewModel.awaitState { it.characterDiscoveryError == "模型暂时不可用" }
        assertFalse(viewModel.state.value.discoveringCharacters)
        assertEquals("", viewModel.state.value.characters)

        viewModel.discoverCharacters()
        advanceUntilIdle()
        assertEquals(2, attempts)
        assertEquals("林晚、沈照", viewModel.state.value.characters)
        assertEquals("", viewModel.state.value.characterDiscoveryError)
    }

    @Test
    fun `manual edit cancels discovery and late result cannot replace manual characters`() = runTest(dispatcher) {
        val started = CompletableDeferred<Unit>()
        val response = CompletableDeferred<List<String>>()
        val fixture = fixture(discover = { _, _ ->
            started.complete(Unit)
            withContext(NonCancellable) { response.await() }
        })
        val viewModel = fixture.viewModel
        advanceUntilIdle()
        viewModel.loadDocument("客栈.txt", "林晚与沈照说话。".encodeToByteArray(), ImportDocumentKind.NovelText)
        awaitStarted(started)
        viewModel.updateCharacters("手填角色")
        response.complete(listOf("旧结果"))
        advanceUntilIdle()

        assertEquals("手填角色", viewModel.state.value.characters)
        assertFalse(viewModel.state.value.discoveringCharacters)
        assertEquals("", viewModel.state.value.characterDiscoveryError)
        assertEquals("", viewModel.state.value.characterDiscoveryMessage)
    }

    @Test
    fun `changing novel discards old discovery and does not reuse its character list`() = runTest(dispatcher) {
        val oldStarted = CompletableDeferred<Unit>()
        val oldResponse = CompletableDeferred<List<String>>()
        val fixture = fixture(discover = { filename, _ ->
            if (filename == "旧书.txt") {
                oldStarted.complete(Unit)
                withContext(NonCancellable) { oldResponse.await() }
            } else {
                listOf("新书角色")
            }
        })
        val viewModel = fixture.viewModel
        advanceUntilIdle()
        viewModel.loadDocument("旧书.txt", "旧书角色走了过来。".encodeToByteArray(), ImportDocumentKind.NovelText)
        awaitStarted(oldStarted)
        viewModel.loadDocument("新书.txt", "新书角色走了过来。".encodeToByteArray(), ImportDocumentKind.NovelText)
        viewModel.awaitState { it.fileName == "新书.txt" && it.characters == "新书角色" }
        oldResponse.complete(listOf("旧书角色"))
        advanceUntilIdle()

        assertEquals(listOf("旧书.txt", "新书.txt"), fixture.requests.map { it.first })
        assertEquals("新书角色", viewModel.state.value.characters)
        assertEquals("", viewModel.state.value.characterDiscoveryError)
    }

    @Test
    fun `clearing document discards late failure without restoring stale file or error`() = runTest(dispatcher) {
        val started = CompletableDeferred<Unit>()
        val response = CompletableDeferred<List<String>>()
        val fixture = fixture(discover = { _, _ ->
            started.complete(Unit)
            withContext(NonCancellable) { response.await() }
        })
        val viewModel = fixture.viewModel
        advanceUntilIdle()
        viewModel.loadDocument("客栈.txt", "林晚与沈照说话。".encodeToByteArray(), ImportDocumentKind.NovelText)
        awaitStarted(started)
        viewModel.clearDocument()
        response.completeExceptionally(IllegalStateException("已经过期的错误"))
        advanceUntilIdle()

        assertEquals("", viewModel.state.value.fileName)
        assertEquals("", viewModel.state.value.characters)
        assertEquals("", viewModel.state.value.characterDiscoveryError)
        assertFalse(viewModel.state.value.discoveringCharacters)
    }

    @Test
    fun `submit uses automatically discovered characters for current novel instead of previous book`() = runTest(dispatcher) {
        val fixture = fixture(discover = { filename, _ ->
            if (filename == "旧书.txt") listOf("旧书角色") else listOf("林晚", "沈照")
        })
        val viewModel = fixture.viewModel
        advanceUntilIdle()
        viewModel.loadDocument("旧书.txt", "旧书角色走了过来。".encodeToByteArray(), ImportDocumentKind.NovelText)
        viewModel.awaitState { it.characters == "旧书角色" && !it.discoveringCharacters }
        val currentText = "林晚走进客栈。沈照向她点头。".encodeToByteArray()
        viewModel.loadDocument("客栈.txt", currentText, ImportDocumentKind.NovelText)
        viewModel.awaitState { it.fileName == "客栈.txt" && it.characters == "林晚、沈照" && !it.discoveringCharacters }
        advanceUntilIdle()

        viewModel.submit()
        advanceUntilIdle()

        val created = fixture.createRequests.single()
        assertEquals("客栈.txt", created.filename)
        assertContentEquals(currentText, created.bytes)
        assertEquals(listOf("林晚", "沈照"), created.characters)
        assertTrue(created.autoRun)
        assertEquals(listOf("林晚、沈照" to true), fixture.savedDefaults)
        assertEquals("created-run", viewModel.state.value.createdRunId)
        assertFalse(viewModel.state.value.submitting)
        assertEquals("", viewModel.state.value.error)
    }

    @Test
    fun `re-enabling auto distill after cancelling in-flight discovery retries and ignores stale result`() = runTest(dispatcher) {
        val firstStarted = CompletableDeferred<Unit>()
        val firstResponse = CompletableDeferred<List<String>>()
        var calls = 0
        val fixture = fixture(discover = { _, _ ->
            if (++calls == 1) {
                firstStarted.complete(Unit)
                withContext(NonCancellable) { firstResponse.await() }
            } else {
                listOf("林晚")
            }
        })
        val viewModel = fixture.viewModel
        advanceUntilIdle()
        viewModel.loadDocument("客栈.txt", "林晚与沈照说话。".encodeToByteArray(), ImportDocumentKind.NovelText)
        awaitStarted(firstStarted)

        viewModel.updateAutoDistill(false)
        assertFalse(viewModel.state.value.discoveringCharacters)
        assertFalse(viewModel.state.value.autoDistill)

        viewModel.updateAutoDistill(true)
        viewModel.awaitState { it.characters == "林晚" && !it.discoveringCharacters }
        firstResponse.complete(listOf("旧识别结果"))
        advanceUntilIdle()

        assertEquals("林晚", viewModel.state.value.characters)
        assertEquals(2, calls)
        assertEquals("", viewModel.state.value.characterDiscoveryError)
    }

    private fun fixture(
        autoDistill: Boolean = true,
        configured: Boolean = true,
        discover: suspend (String, String) -> List<String> = { _, _ -> listOf("林晚", "沈照") },
    ): Fixture = Fixture(autoDistill, configured, discover).also { viewModels += it.viewModel }

    private data class CreateRequest(
        val filename: String,
        val bytes: ByteArray,
        val characters: List<String>,
        val autoRun: Boolean,
    )

    private class Fixture(
        autoDistill: Boolean,
        var configured: Boolean,
        discover: suspend (String, String) -> List<String>,
    ) {
        val requests = mutableListOf<Pair<String, String>>()
        val createRequests = mutableListOf<CreateRequest>()
        val savedDefaults = mutableListOf<Pair<String, Boolean>>()
        private val runs = repository<RunRepository> { method, arguments ->
            when (method) {
                "listRuns" -> emptyList<RunManifestDto>()
                "saveImportDefaults" -> {
                    savedDefaults += (arguments[0] as String) to (arguments[1] as Boolean)
                    Unit
                }
                "createNovel" -> {
                    val request = CreateRequest(
                        filename = arguments[0] as String,
                        bytes = arguments[1] as ByteArray,
                        characters = (arguments[2] as List<*>).map { it as String },
                        autoRun = arguments[5] as Boolean,
                    )
                    createRequests += request
                    RunManifestDto(runId = "created-run", status = "running", lockedCharacters = request.characters)
                }
                "discoverCharacters" -> suspendResult(arguments) {
                    val filename = arguments[0] as String
                    val excerpt = arguments[1] as String
                    requests += filename to excerpt
                    discover(filename, excerpt)
                }
                else -> error("Unexpected RunRepository call: $method")
            }
        }
        private val runtime = repository<AppRuntimeRepository> { method, _ ->
            check(method == "getPreferences")
            flowOf(AppPreferences(defaultCharacters = "上一本小说人物", autoDistill = autoDistill))
        }
        private val settings = repository<ModelSettingsRepository> { method, _ ->
            check(method == "getModelSettings")
            ModelSettingsDto(configured = configured)
        }
        val viewModel = ImportBookViewModel(
            appRuntime = runtime,
            modelSettings = settings,
            runs = runs,
            distillationForeground = NoopDistillationForeground,
            estimateDistillSampling = EstimateDistillSamplingUseCase(SamplingGateway),
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

    private suspend fun ImportBookViewModel.awaitState(predicate: (ImportBookUiState) -> Boolean) {
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
