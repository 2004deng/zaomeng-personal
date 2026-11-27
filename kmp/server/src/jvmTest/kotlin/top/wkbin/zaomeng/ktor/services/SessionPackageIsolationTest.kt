package top.wkbin.zaomeng.ktor.services

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okio.Path.Companion.toPath
import top.wkbin.zaomeng.data.api.ImportRunPackageRequest
import top.wkbin.zaomeng.db.DomainStore
import top.wkbin.zaomeng.db.RoomDocumentStore
import top.wkbin.zaomeng.db.buildZaomengDatabase
import top.wkbin.zaomeng.db.getDatabaseBuilder
import java.io.File
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 导入含会话的书卷副本后，两份同名会话必须分别可列出、修改和删除。 */
class SessionPackageIsolationTest {
    @Test
    fun `imported dialogue keeps source and copy isolated`() = runBlocking {
        val path = File.createTempFile("zaomeng-session-isolation-", ".db").apply { deleteOnExit() }.absolutePath.toPath()
        val db = buildZaomengDatabase(getDatabaseBuilder(path))
        try {
            val documents = RoomDocumentStore(db)
            val storage = StorageService(path, documents, DomainStore(path, db.domainDao(), documents))
            val packages = RunPackageService(storage)
            val exporter = RunOperationsService(
                storage,
                RunManagementService(storage),
                packages,
                DistillExecutor(storage, llm = null, promptLoader = null),
            )
            storage.writeRunManifest(RUN_A, buildJsonObject {
                put("run_id", RUN_A)
                put("title", "原书")
                put("novel_id", "novel-a")
                put("status", "ready")
                put("entrypoint", "import")
            })
            val sessionFile = storage.getDialogueSessionManifestFile(RUN_A, SESSION)
            storage.mkdirs(requireNotNull(sessionFile.parent))
            storage.writeTextAtomically(sessionFile, sessionManifest(RUN_A, "原始会话").toString())
            assertEquals(listOf(SESSION), db.domainDao().sessionIdsOf(RUN_A))

            val (archive, filename) = exporter.exportRunPackage(RUN_A, builtin = false, includeDialogue = true)
            val imported = packages.importPackage(
                ImportRunPackageRequest(filename, Base64.getEncoder().encodeToString(archive)),
            )
            val runB = imported.getValue("run_id").jsonPrimitive.content
            assertTrue(runB != RUN_A)

            // The same session id now exists under both runs and both projections are listable.
            assertEquals(listOf(SESSION), storage.listDialogueSessionManifests(RUN_A).map { sessionId(it) })
            assertEquals(listOf(SESSION), storage.listDialogueSessionManifests(runB).map { sessionId(it) })
            assertEquals(listOf(SESSION), db.domainDao().sessionIdsOf(RUN_A))
            assertEquals(listOf(SESSION), db.domainDao().sessionIdsOf(runB))

            // Updating the copy must not touch the source document or projection.
            val sourceManifestBefore = storage.readText(sessionFile)
            storage.updateSessionManifest(runB, SESSION) { current ->
                buildJsonObject {
                    current.forEach { (key, value) -> put(key, value) }
                    put("title", "导入副本已改名")
                }
            }
            assertEquals("原始会话", storage.listDialogueSessionManifests(RUN_A).single()["title"]?.jsonPrimitive?.content)
            assertEquals("导入副本已改名", storage.listDialogueSessionManifests(runB).single()["title"]?.jsonPrimitive?.content)
            assertEquals(sourceManifestBefore, storage.readText(sessionFile))
            assertEquals(
                listOf("原始内容"),
                db.domainDao().messagesFrom(RUN_A, SESSION, 0).map { it.message },
            )

            // Deleting the copy keeps the source conversation and its message rows intact.
            assertTrue(storage.deleteDialogueSessionDocuments(runB, SESSION))
            assertTrue(db.domainDao().sessionIdsOf(runB).isEmpty())
            assertTrue(db.domainDao().messagesFrom(runB, SESSION, 0).isEmpty())
            assertEquals(listOf(SESSION), storage.listDialogueSessionManifests(RUN_A).map { sessionId(it) })
            assertEquals(listOf("原始内容"), db.domainDao().messagesFrom(RUN_A, SESSION, 0).map { it.message })
            assertEquals(sourceManifestBefore, storage.readText(sessionFile))
        } finally {
            db.close()
        }
    }

    private fun sessionManifest(runId: String, title: String) = buildJsonObject {
        put("session_id", SESSION)
        put("run_id", runId)
        put("title", title)
        put("mode", "insert")
        put("status", "ready")
        put("transcript_start", 0)
        put("transcript_count", 1)
        put("transcript", buildJsonArray {
            add(buildJsonObject {
                put("turn_id", "turn-1")
                put("speaker", "旁白")
                put("role", "character")
                put("message", "原始内容")
            })
        })
    }

    private fun sessionId(manifest: kotlinx.serialization.json.JsonObject): String =
        manifest.getValue("session_id").jsonPrimitive.content

    private companion object {
        const val RUN_A = "run-source"
        const val SESSION = "shared-session"
    }
}
