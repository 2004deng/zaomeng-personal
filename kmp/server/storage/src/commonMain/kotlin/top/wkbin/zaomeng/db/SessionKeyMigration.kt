package top.wkbin.zaomeng.db

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import top.wkbin.zaomeng.ktor.services.PathSafety
import kotlin.time.Instant

/**
 * v2 的 sessionId 单列主键会让导入的会话覆盖其他书卷中的同名投影。
 *
 * 先保留所有旧实体行，再从权威 documents 回补被覆盖的会话。这里只重建 sessions：
 * documents 原始字节和 mtime、messages（含已归档的旧窗口）及其他实体均保持不变。
 * Room 在一个事务内执行迁移；无效文档不应阻止其他会话恢复，也不应覆盖仍可用的旧行。
 */
internal val MIGRATION_2_3: Migration = object : Migration(2, 3) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `sessions_v3` (`sessionId` TEXT NOT NULL, " +
                "`runId` TEXT NOT NULL, `title` TEXT NOT NULL, `mode` TEXT NOT NULL, " +
                "`status` TEXT NOT NULL, `updatedAtMillis` INTEGER NOT NULL, " +
                "`manifest` TEXT NOT NULL, PRIMARY KEY(`runId`, `sessionId`))",
        )
        connection.execSQL(
            "INSERT INTO `sessions_v3` (`sessionId`, `runId`, `title`, `mode`, `status`, `updatedAtMillis`, `manifest`) " +
                "SELECT `sessionId`, `runId`, `title`, `mode`, `status`, `updatedAtMillis`, `manifest` FROM `sessions`",
        )
        connection.execSQL("DROP TABLE `sessions`")
        connection.execSQL("ALTER TABLE `sessions_v3` RENAME TO `sessions`")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_sessions_runId` ON `sessions` (`runId`)")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_sessions_updatedAtMillis` ON `sessions` (`updatedAtMillis`)")
        restoreSessionDocuments(connection)
    }
}

private val sessionManifestPath = Regex(".*/runs/([^/]+)/dialogue/sessions/([^/]+)/session_manifest\\.json")

private fun restoreSessionDocuments(connection: SQLiteConnection) {
    val json = Json { ignoreUnknownKeys = true; isLenient = true }
    // 只把清单的 BLOB 带入内存，不读取小说、头像或完整归档。
    connection.prepare(
        "SELECT `path`, `bytes`, `updated_at` FROM `documents` " +
            "WHERE `path` LIKE '%/dialogue/sessions/%/session_manifest.json'",
    ).use { documents ->
        connection.prepare(
            "INSERT OR REPLACE INTO `sessions` " +
                "(`sessionId`, `runId`, `title`, `mode`, `status`, `updatedAtMillis`, `manifest`) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)",
        ).use { insert ->
            while (documents.step()) {
                val match = sessionManifestPath.matchEntire(documents.getText(0).replace('\\', '/')) ?: continue
                val runId = runCatching { PathSafety.validateStorageId(match.groupValues[1], "run_id") }.getOrNull() ?: continue
                val sessionId = runCatching { PathSafety.validateStorageId(match.groupValues[2], "session_id") }.getOrNull() ?: continue
                val text = documents.getBlob(1).decodeToString()
                val manifest = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: continue
                fun field(name: String): String = (manifest[name] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val updatedAt = runCatching { Instant.parse(field("updated_at")).toEpochMilliseconds() }.getOrNull()
                    ?: documents.getLong(2)
                insert.bindText(1, sessionId)
                insert.bindText(2, runId)
                insert.bindText(3, field("title"))
                insert.bindText(4, field("mode"))
                insert.bindText(5, field("status"))
                insert.bindLong(6, updatedAt)
                insert.bindText(7, text)
                insert.step()
                insert.reset()
            }
        }
    }
}
