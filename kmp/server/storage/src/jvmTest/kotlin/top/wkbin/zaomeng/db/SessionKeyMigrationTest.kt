package top.wkbin.zaomeng.db

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import okio.Path.Companion.toPath
import top.wkbin.zaomeng.ktor.services.StorageService
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionKeyMigrationTest {
    @Test
    fun `same session id in different runs supports independent updates and deletion`() = runBlocking {
        val db = newDatabase(tempDbFile())
        try {
            val storage = newStorage(db)
            listOf("run-a", "run-b").forEach { runId ->
                storage.writeRunManifest(runId, JsonObject(mapOf("run_id" to JsonPrimitive(runId))))
                storage.writeTextAtomically(storage.getDialogueSessionManifestFile(runId, SESSION), manifest(runId, runId))
            }
            assertEquals(listOf(SESSION), storage.listDialogueSessionIds("run-a"))
            assertEquals(listOf(SESSION), storage.listDialogueSessionIds("run-b"))
            val originalB = storage.readText(storage.getDialogueSessionManifestFile("run-b", SESSION))
            val messagesB = db.domainDao().messagesFrom("run-b", SESSION, 0)

            storage.updateSessionManifest("run-a", SESSION) { current ->
                JsonObject(current + ("title" to JsonPrimitive("changed a")))
            }
            assertEquals("changed a", storage.listDialogueSessionManifests("run-a").single()["title"]?.jsonPrimitive?.content)
            assertEquals("run-b", storage.listDialogueSessionManifests("run-b").single()["title"]?.jsonPrimitive?.content)
            assertEquals(originalB, storage.readText(storage.getDialogueSessionManifestFile("run-b", SESSION)))

            assertTrue(storage.deleteDialogueSessionDocuments("run-a", SESSION))
            assertTrue(storage.listDialogueSessionIds("run-a").isEmpty())
            assertTrue(db.domainDao().messagesFrom("run-a", SESSION, 0).isEmpty())
            assertEquals(listOf(SESSION), storage.listDialogueSessionIds("run-b"))
            assertEquals(messagesB, db.domainDao().messagesFrom("run-b", SESSION, 0))
            assertEquals(originalB, storage.readText(storage.getDialogueSessionManifestFile("run-b", SESSION)))
        } finally {
            db.close()
        }
    }

    @Test
    fun `deleting a run retains another run with the same session id`() = runBlocking {
        val db = newDatabase(tempDbFile())
        try {
            val storage = newStorage(db)
            listOf("run-a", "run-b").forEach { runId ->
                storage.writeRunManifest(runId, JsonObject(mapOf("run_id" to JsonPrimitive(runId))))
                storage.writeTextAtomically(storage.getDialogueSessionManifestFile(runId, SESSION), manifest(runId, runId))
            }
            storage.deleteRecursively(storage.getRunDirectory("run-a"))

            assertFalse(storage.runExists("run-a"))
            assertTrue(storage.listDialogueSessionIds("run-a").isEmpty())
            assertEquals(listOf(SESSION), storage.listDialogueSessionIds("run-b"))
            assertEquals("run-b", db.domainDao().messagesFrom("run-b", SESSION, 0).single().message)
        } finally {
            db.close()
        }
    }

    @Test
    fun `v2 migration restores overwritten projections from documents and preserves all stored data`() = runBlocking {
        val file = tempDbFile()
        val originalA = manifest("run-a", "original a", start = 1)
        val originalB = manifest("run-b", "original b")
        val archive = """[{"speaker":"旁白","message":"archived a","role":"character","turn_id":"old"}]"""
        val opaqueBytes = byteArrayOf(0, 1, 2, -1)
        val connection = BundledSQLiteDriver().open(file.absolutePath)
        try {
            createVersionTwoSchema(connection)
            putDocument(connection, sessionPath("run-a"), originalA.encodeToByteArray(), 101)
            putDocument(connection, sessionPath("run-b"), originalB.encodeToByteArray(), 102)
            putDocument(connection, "$ROOT/runs/run-a/dialogue/sessions/$SESSION/transcript-archive/0000000000.json", archive.encodeToByteArray(), 103)
            putDocument(connection, "$ROOT/opaque.bin", opaqueBytes, 104)
            // 文档损坏或缺失时仍保留已有实体，避免一次恢复顺便丢失其他会话。
            putDocument(connection, "$ROOT/runs/run-a/dialogue/sessions/legacy/session_manifest.json", "invalid json".encodeToByteArray(), 105)
            putLegacySession(connection, "run-a", SESSION, "original a", originalA)
            putLegacySession(connection, "run-b", SESSION, "original b", originalB)
            putLegacySession(connection, "run-a", "legacy", "keep invalid document", "{}")
            putLegacySession(connection, "run-a", "entity-only", "keep missing document", "{}")
            connection.execSQL("INSERT INTO runs VALUES ('run-a', 'A', 'novel-a', 'ready', 1, '{}')")
            connection.execSQL("INSERT INTO runs VALUES ('run-b', 'B', 'novel-b', 'ready', 2, '{}')")
            connection.execSQL("INSERT INTO cards VALUES ('card-1', 'scene', 'card', 3, '{}')")
            connection.execSQL("INSERT INTO personas VALUES ('persona-1', 'run-a', 'novel-a', '人物', 4, 'profile')")
            connection.execSQL("INSERT INTO messages VALUES (41, 'run-a', '$SESSION', 'old', 0, '旁白', 'character', 'archived a', '')")
            connection.execSQL("INSERT INTO messages VALUES (42, 'run-a', '$SESSION', 'turn-1', 1, '旁白', 'character', 'original a', '')")
            connection.execSQL("INSERT INTO messages VALUES (43, 'run-b', '$SESSION', 'turn-1', 0, '旁白', 'character', 'original b', '')")
            connection.execSQL("PRAGMA user_version = 2")
        } finally {
            connection.close()
        }

        val db = newDatabase(file)
        try {
            val storage = newStorage(db)
            assertEquals(setOf(SESSION, "legacy", "entity-only"), storage.listDialogueSessionIds("run-a").toSet())
            assertEquals(listOf(SESSION), storage.listDialogueSessionIds("run-b"))
            assertEquals("original a", db.domainDao().sessionsOf("run-a").single { it.sessionId == SESSION }.title)
            assertEquals(101L, db.domainDao().sessionsOf("run-a").single { it.sessionId == SESSION }.updatedAtMillis)
            assertEquals("keep invalid document", db.domainDao().sessionsOf("run-a").single { it.sessionId == "legacy" }.title)
            assertEquals("keep missing document", db.domainDao().sessionsOf("run-a").single { it.sessionId == "entity-only" }.title)
            assertEquals(originalA, storage.readText(storage.getDialogueSessionManifestFile("run-a", SESSION)))
            assertEquals(originalB, storage.readText(storage.getDialogueSessionManifestFile("run-b", SESSION)))
            assertEquals(101L, storage.lastModifiedMillis(storage.getDialogueSessionManifestFile("run-a", SESSION)))
            assertContentEquals(opaqueBytes, storage.readBytes(ROOT.toPath() / "opaque.bin"))
            assertEquals(archive, storage.readText(ROOT.toPath() / "runs/run-a/dialogue/sessions/$SESSION/transcript-archive/0000000000.json"))
            assertEquals(listOf(41L, 42L), db.domainDao().messagesFrom("run-a", SESSION, 0).map { it.id })
            assertEquals(listOf(43L), db.domainDao().messagesFrom("run-b", SESSION, 0).map { it.id })
            assertEquals(2, db.domainDao().runCount())
            assertEquals(1, db.domainDao().cardCount())
            assertEquals(1, db.domainDao().personaCount())
            assertEquals(listOf("archived a", "original a"), storage.transcriptReader.readAll("run-a", SESSION).items.map {
                it.value["message"]?.jsonPrimitive?.content
            })
        } finally {
            db.close()
        }
        // 新 schema 的身份和数据在再次打开时也必须有效。
        val reopened = newDatabase(file)
        try {
            assertEquals(listOf(SESSION), reopened.domainDao().sessionIdsOf("run-b"))
            assertTrue(SESSION in reopened.domainDao().sessionIdsOf("run-a"))
        } finally {
            reopened.close()
        }
    }

    private fun newStorage(db: ZaomengDatabase): StorageService {
        val documents = RoomDocumentStore(db)
        return StorageService(ROOT.toPath(), documents, DomainStore(ROOT.toPath(), db.domainDao(), documents))
    }

    private fun newDatabase(file: File): ZaomengDatabase =
        buildZaomengDatabase(getDatabaseBuilder(file.absolutePath.toPath()))

    private fun tempDbFile(): File = File.createTempFile("zaomeng-session-v3-", ".db").apply { deleteOnExit() }

    private fun manifest(runId: String, title: String, start: Int = 0): String =
        """{"run_id":"$runId","session_id":"$SESSION","title":"$title","mode":"insert","status":"ready","transcript_start":$start,"transcript":[{"turn_id":"turn-1","speaker":"旁白","role":"character","message":"$title"}]}"""

    private fun sessionPath(runId: String): String = "$ROOT/runs/$runId/dialogue/sessions/$SESSION/session_manifest.json"

    private fun putDocument(connection: SQLiteConnection, path: String, bytes: ByteArray, modifiedAt: Long) {
        connection.prepare("INSERT INTO documents (path, bytes, updated_at) VALUES (?, ?, ?)").use { statement ->
            statement.bindText(1, path)
            statement.bindBlob(2, bytes)
            statement.bindLong(3, modifiedAt)
            statement.step()
        }
    }

    private fun putLegacySession(connection: SQLiteConnection, runId: String, sessionId: String, title: String, manifest: String) {
        connection.prepare("INSERT OR REPLACE INTO sessions VALUES (?, ?, ?, 'insert', 'ready', 1, ?)").use { statement ->
            statement.bindText(1, sessionId)
            statement.bindText(2, runId)
            statement.bindText(3, title)
            statement.bindText(4, manifest)
            statement.step()
        }
    }

    /** Frozen v2 schema: the current entity definitions must not silently change this fixture. */
    private fun createVersionTwoSchema(connection: SQLiteConnection) {
        listOf(
            "CREATE TABLE documents (path TEXT NOT NULL PRIMARY KEY, bytes BLOB NOT NULL, updated_at INTEGER NOT NULL)",
            "CREATE TABLE runs (runId TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, novelId TEXT NOT NULL, status TEXT NOT NULL, updatedAtMillis INTEGER NOT NULL, manifest TEXT NOT NULL)",
            "CREATE TABLE sessions (sessionId TEXT NOT NULL PRIMARY KEY, runId TEXT NOT NULL, title TEXT NOT NULL, mode TEXT NOT NULL, status TEXT NOT NULL, updatedAtMillis INTEGER NOT NULL, manifest TEXT NOT NULL)",
            "CREATE INDEX index_sessions_runId ON sessions (runId)",
            "CREATE INDEX index_sessions_updatedAtMillis ON sessions (updatedAtMillis)",
            "CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, runId TEXT NOT NULL, sessionId TEXT NOT NULL, turnId TEXT NOT NULL, seq INTEGER NOT NULL, speaker TEXT NOT NULL, role TEXT NOT NULL, message TEXT NOT NULL, timestamp TEXT NOT NULL)",
            "CREATE INDEX index_messages_runId ON messages (runId)",
            "CREATE INDEX index_messages_sessionId ON messages (sessionId)",
            "CREATE UNIQUE INDEX index_messages_runId_sessionId_seq ON messages (runId, sessionId, seq)",
            "CREATE TABLE cards (cardId TEXT NOT NULL PRIMARY KEY, kind TEXT NOT NULL, title TEXT NOT NULL, updatedAtMillis INTEGER NOT NULL, fieldsJson TEXT NOT NULL)",
            "CREATE INDEX index_cards_kind ON cards (kind)",
            "CREATE INDEX index_cards_updatedAtMillis ON cards (updatedAtMillis)",
            "CREATE TABLE personas (personaId TEXT NOT NULL PRIMARY KEY, runId TEXT NOT NULL, novelId TEXT NOT NULL, name TEXT NOT NULL, updatedAtMillis INTEGER NOT NULL, profile TEXT NOT NULL)",
            "CREATE INDEX index_personas_runId ON personas (runId)",
            "CREATE INDEX index_personas_novelId ON personas (novelId)",
            "CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)",
            "INSERT INTO room_master_table VALUES (42, '305fe8fbcc5f78d30f774a5dea44bcb6')",
        ).forEach(connection::execSQL)
    }

    private companion object {
        const val ROOT = "/virtual/zaomeng"
        const val SESSION = "shared-session"
    }
}
