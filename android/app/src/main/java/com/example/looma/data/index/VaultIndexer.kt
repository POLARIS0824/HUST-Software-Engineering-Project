package com.example.looma.data.index

import com.example.looma.data.note.MarkdownParser
import com.example.looma.data.note.VaultFileOps
import com.example.looma.data.note.readMarkdown
import com.example.looma.domain.model.FolderPath
import com.example.looma.domain.model.NotePath
import com.example.looma.domain.model.Vault
import com.example.looma.domain.model.VaultFolder
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

internal data class ScanReport(
    val filesRead: Int,
    val filesKept: Int,
    val filesRemoved: Int,
)

internal data class IndexScan(
    val notes: List<IndexedNote>,
    val folders: List<VaultFolder>,
    val unreadableCount: Int,
    val report: ScanReport,
)

/**
 * 全库扫描：磁盘上有什么、哪些内容变了，只读变了的正文。
 *
 * 索引在这里既当缓存也当增量依据：指纹没变的笔记直接复用索引行，
 * 所以打开首页不需要把整个 Vault 读一遍。
 */
internal class VaultIndexer(
    private val root: File,
    private val vault: Vault,
    private val index: NoteIndex,
) {
    private val parser = MarkdownParser()

    suspend fun scan(): IndexScan = withContext(Dispatchers.IO) {
        // VaultFileOps 构造会 canonical 化并 stat 根目录，放到 IO 线程里再建
        val ops = VaultFileOps(root)
        val rootDir = ops.rootDir
        val folders = mutableListOf<VaultFolder>()
        val files = LinkedHashMap<NotePath, File>()
        var complete = true

        // 不用 walkTopDown：需要区分“目录确实为空”和“目录读不了”，后者不能当成文件被删了
        val pending = ArrayDeque<Pair<File, Int>>()
        pending += rootDir to 0
        while (pending.isNotEmpty()) {
            coroutineContext.ensureActive()
            val (directory, depth) = pending.removeFirst()
            val children = directory.listFiles()
            if (children == null) {
                complete = false
                continue
            }
            for (child in children) {
                coroutineContext.ensureActive()
                if (child.isDirectory) {
                    val inside = runCatching { ops.isInside(child) }.getOrDefault(false)
                    if (!inside || child.name.startsWith('.')) continue
                    val path = FolderPath(child.relativeTo(rootDir).invariantSeparatorsPath)
                    folders += VaultFolder(vaultId = vault.id, path = path, name = child.name)
                    if (depth + 1 < MAX_DEPTH) {
                        pending += child to depth + 1
                    } else {
                        // 截断处以下的文件没被枚举到，同样不能据此判定删除
                        complete = false
                    }
                    continue
                }
                if (!child.isFile || !child.extension.equals("md", ignoreCase = true)) continue
                if (!runCatching { ops.isInside(child) }.getOrDefault(false)) continue
                files[NotePath(child.relativeTo(rootDir).invariantSeparatorsPath)] = child
            }
        }

        val onDisk = files.mapValues { (_, file) -> FileFingerprint(file.length(), file.lastModified()) }
        val indexed = index.load(vault.id)
        // 扫描不完整时宁留脏行不删数据：没走到的目录不代表笔记已经不在了
        val plan = planScan(indexed, onDisk).let { if (complete) it else it.copy(removed = emptyList()) }
        val indexedByPath = indexed.associateBy { it.id.path }

        val fresh = mutableListOf<IndexedNote>()
        // 读不了的笔记若已有索引行就继续用旧行，用户至少还能看到上次的标题与摘要
        val retained = mutableListOf<IndexedNote>()
        var unreadable = 0
        // 读失败的也算“读过一次”：它本来就在待读集合里，不是本轮新增的未变文件
        var readCount = 0
        for (path in plan.toRead) {
            coroutineContext.ensureActive()
            val file = files[path] ?: continue
            readCount++
            try {
                val text = readMarkdown(file)
                val metadata = parser.parse(text, file.nameWithoutExtension)
                fresh += indexedNote(vault.id, rootDir, file, text, metadata)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                unreadable++
                indexedByPath[path]?.let(retained::add)
            }
        }

        val removed = plan.removed
        if (fresh.isNotEmpty() || removed.isNotEmpty()) {
            index.apply(IndexDelta(vaultId = vault.id, upserts = fresh, removed = removed))
        }

        IndexScan(
            notes = plan.kept + fresh + retained,
            folders = folders.sortedBy { it.path.relativePath.lowercase() },
            unreadableCount = unreadable,
            report = ScanReport(
                filesRead = readCount,
                filesKept = plan.kept.size,
                filesRemoved = removed.size,
            ),
        )
    }

    private companion object {
        const val MAX_DEPTH = 40
    }
}
