package edu.hust.justmd.prototype

import android.content.Context
import android.os.Build
import android.system.Os
import android.system.OsConstants
import androidx.room3.Dao
import androidx.room3.Database
import androidx.room3.Entity
import androidx.room3.Fts5
import androidx.room3.FtsOptions
import androidx.room3.Insert
import androidx.room3.PrimaryKey
import androidx.room3.Query
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.coroutines.cancellation.CancellationException
import org.json.JSONArray
import org.json.JSONObject

@Entity(tableName = "probe_fts")
@Fts5(tokenizer = FtsOptions.TOKENIZER_TRIGRAM, detail = FtsOptions.Detail.FULL)
data class NativeProbeDocument(@PrimaryKey val rowid: Long, val body: String)

@Dao
interface NativeProbeDao {
    @Insert suspend fun insert(documents: List<NativeProbeDocument>)
    @Query("SELECT rowid FROM probe_fts WHERE probe_fts MATCH :expression ORDER BY rowid")
    suspend fun match(expression: String): List<Long>
    @Query("SELECT rowid FROM probe_fts WHERE body LIKE :pattern ESCAPE '\\' ORDER BY rowid")
    suspend fun literalLike(pattern: String): List<Long>
    @Query("SELECT sqlite_version()") suspend fun version(): String
    @Query("SELECT sql FROM sqlite_master WHERE name = 'probe_fts'")
    suspend fun schema(): String
}

@Database(entities = [NativeProbeDocument::class], version = 1, exportSchema = false)
abstract class NativeProbeDatabase : RoomDatabase() {
    abstract fun documents(): NativeProbeDao
}

private sealed interface NativeOutcome {
    data class Success(val evidence: JSONObject) : NativeOutcome
    data class Failure(val stage: String, val error: Exception) : NativeOutcome
}

private fun NativeOutcome.json(): JSONObject = when (this) {
    is NativeOutcome.Success -> JSONObject().put("status", "observed").put("evidence", evidence)
    is NativeOutcome.Failure -> JSONObject().put("status", "exception").put("stage", stage)
        .put("type", error.javaClass.name).put("message", error.message ?: "")
}

private suspend fun observe(stage: String, action: suspend () -> JSONObject): NativeOutcome = try {
    NativeOutcome.Success(action())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    NativeOutcome.Failure(stage, error)
}

suspend fun runNativeProbes(context: Context): String {
    val search = observe("room_fts5") { probeSearch(context) }
    val storage = observe("external_existing_file_replace") { probeReplacement(context) }
    return JSONObject().put("android", JSONObject()
        .put("sdk", Build.VERSION.SDK_INT).put("release", Build.VERSION.RELEASE)
        .put("manufacturer", Build.MANUFACTURER).put("model", Build.MODEL)
        .put("fingerprint", Build.FINGERPRINT))
        .put("dependencies", JSONObject().put("room3", "3.0.3").put("sqliteBundled", "2.7.1"))
        .put("search", search.json()).put("storage", storage.json())
        .put("scope", "Disposable fixed fixtures. No timings, process-kill, power-loss, or production durability claim.")
        .toString(2)
}

private suspend fun probeSearch(context: Context): JSONObject {
    val documents = listOf(
        NativeProbeDocument(1, "今天学习数据库和中文搜索"),
        NativeProbeDocument(2, "数据库索引 SQLite FTS5 mixed中文 AlphaBeta"),
        NativeProbeDocument(3, "literal 100% done and file_name"),
        NativeProbeDocument(4, "literal 100X done and fileXname"),
        NativeProbeDocument(5, "escape back\\slash and a\"b token")
    )
    val queries = listOf("数据库", "中文", "中", "SQLite", "alphaBETA", "mixed中", "100%", "file_", "%", "_", "back\\", "a\"b")
    val database = Room.inMemoryDatabaseBuilder(context, NativeProbeDatabase::class.java)
        .setDriver(BundledSQLiteDriver()).build()
    try {
        val dao = database.documents()
        dao.insert(documents)
        val evidence = JSONObject().put("sqliteVersion", dao.version()).put("schema", dao.schema())
            .put("matchSql", "SELECT rowid FROM probe_fts WHERE probe_fts MATCH ? ORDER BY rowid")
            .put("likeSql", "SELECT rowid FROM probe_fts WHERE body LIKE ? ESCAPE '\\' ORDER BY rowid")
            .put("fixtures", JSONArray(documents.map { JSONObject().put("id", it.rowid).put("body", it.body) }))
        val cases = JSONArray()
        evidence.put("cases", cases)
        for (query in queries) {
            val pattern = "%" + query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
            val expression = "\"" + query.replace("\"", "\"\"") + "\""
            val expected = documents.filter { it.body.contains(query, ignoreCase = true) }.map { it.rowid }
            val like = observe("literal_like") {
                val ids = dao.literalLike(pattern)
                JSONObject().put("returnedIds", JSONArray(ids)).put("equalsFixtureScan", ids == expected)
            }
            val match = observe("raw_trigram_match") {
                val ids = dao.match(expression)
                JSONObject().put("returnedIds", JSONArray(ids)).put("equalsFixtureScan", ids == expected)
            }
            cases.put(JSONObject().put("query", query).put("codePointCount", query.codePointCount(0, query.length))
                .put("matchParameter", expression).put("likeParameter", pattern)
                .put("expectedLiteralScanIds", JSONArray(expected)).put("like", like.json()).put("rawMatch", match.json())
                .put("applicationRoute", if (query.codePointCount(0, query.length) < 3) "literal LIKE; raw MATCH observed only" else "trigram MATCH eligible")
                .put("scanRule", "Kotlin contains(ignoreCase=true); fixtures restrict case comparison to ASCII")
                .put("optimizationClaim", "None. LIKE ESCAPE is a literal-semantics probe, not trigram optimization evidence."))
        }
        return evidence
    } finally {
        database.close()
    }
}

private suspend fun probeReplacement(context: Context): JSONObject {
    val root = context.getExternalFilesDir(null)
        ?: throw IllegalStateException("App-specific external files directory is unavailable")
    val directory = File(root, "disposable-native-probe-${UUID.randomUUID()}")
    check(directory.mkdir()) { "Could not create disposable probe directory" }
    val target = File(directory, "existing.md")
    val temporary = File(directory, "replacement.tmp")
    val old = "old existing fixture\n"
    val replacement = "replacement 中文 fixture\n"
    FileOutputStream(target).use { it.write(old.toByteArray(Charsets.UTF_8)); it.fd.sync() }
    val before = target.readText(Charsets.UTF_8)
    check(before == old) { "Existing fixture readback differs" }
    val beforeStat = Os.stat(target.absolutePath)
    FileOutputStream(temporary).use { it.write(replacement.toByteArray(Charsets.UTF_8)); it.fd.sync() }
    Os.rename(temporary.absolutePath, target.absolutePath)
    val after = target.readText(Charsets.UTF_8)
    val afterStat = Os.stat(target.absolutePath)
    val parentSync = observe("parent_directory_fsync") {
        check(directory.isDirectory)
        val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
        try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
        JSONObject().put("openFlags", "O_RDONLY on verified directory").put("fsyncReturned", true)
    }
    val nioMove = observe("nio_atomic_replace_existing") {
        val nioTemporary = File(directory, "nio.tmp")
        val nioBody = "NIO replacement 中文 fixture\n"
        FileOutputStream(nioTemporary).use { it.write(nioBody.toByteArray(Charsets.UTF_8)); it.fd.sync() }
        Files.move(nioTemporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        JSONObject().put("readback", target.readText()).put("replacementReadbackMatches", target.readText() == nioBody)
    }
    return JSONObject().put("directory", directory.absolutePath).put("target", target.absolutePath)
        .put("before", before).put("after", after).put("expectedAfter", replacement)
        .put("existingFileVerified", true).put("replacementReadbackMatches", after == replacement)
        .put("temporaryExistsAfterRename", temporary.exists())
        .put("inodeBefore", beforeStat.st_ino).put("inodeAfter", afterStat.st_ino)
        .put("parentSync", parentSync.json()).put("nioAtomicReplace", nioMove.json()).put("filesRetainedForInspection", true)
        .put("claim", "Observed same-directory rename over existing file and readback; no concurrent-reader or crash durability verification")
}
