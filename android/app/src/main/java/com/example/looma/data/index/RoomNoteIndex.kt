package com.example.looma.data.index

import androidx.room.withTransaction
import com.example.looma.data.note.MarkdownLink
import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.NotePath
import com.example.looma.domain.model.VaultId
import java.time.Instant

/**
 * Room 版的 [NoteIndex]。
 *
 * `@Upsert` 只覆盖主表，标签与链接在重扫时必须先删后插，否则已从正文删掉的标签会残留。
 */
internal class RoomNoteIndex(private val database: NoteIndexDatabase) : NoteIndex {

    private val dao = database.noteIndexDao()

    override suspend fun load(vaultId: VaultId): List<IndexedNote> {
        val key = vaultId.value
        val tagsByPath = dao.tags(key).groupBy { it.notePath }
        val linksByPath = dao.links(key).groupBy { it.notePath }
        return dao.notes(key).map { note ->
            IndexedNote(
                id = NoteId(vaultId, NotePath(note.path)),
                title = note.title,
                excerpt = note.excerpt,
                snippet = note.snippet,
                coverPath = note.coverPath,
                modifiedAt = Instant.ofEpochMilli(note.modifiedAt),
                fingerprint = FileFingerprint(note.sizeBytes, note.modifiedAt),
                tags = tagsByPath[note.path].orEmpty().map { it.tag },
                links = linksByPath[note.path].orEmpty()
                    .map { MarkdownLink(target = it.target, embedded = it.embedded) },
            )
        }
    }

    override suspend fun apply(delta: IndexDelta) {
        if (delta.upserts.isEmpty() && delta.removed.isEmpty()) return
        database.withTransaction {
            val vaultId = delta.vaultId.value
            val upsertPaths = delta.upserts.map { it.id.path.relativePath }
            // 写穿与重扫都在一次事务里重建子表，读到的一半旧一半新不会出现
            if (upsertPaths.isNotEmpty()) {
                dao.deleteTags(vaultId, upsertPaths)
                dao.deleteLinks(vaultId, upsertPaths)
                val indexedAt = System.currentTimeMillis()
                dao.upsertNotes(delta.upserts.map { it.toEntity(vaultId, indexedAt) })
                val tags = delta.upserts.flatMap { note -> note.tagEntities(vaultId) }
                if (tags.isNotEmpty()) dao.insertTags(tags)
                val links = delta.upserts.flatMap { note -> note.linkEntities(vaultId) }
                if (links.isNotEmpty()) dao.insertLinks(links)
            }
            if (delta.removed.isNotEmpty()) {
                dao.deleteNotes(vaultId, delta.removed.map { it.relativePath })
            }
        }
    }

    private fun IndexedNote.toEntity(vaultId: String, indexedAt: Long) = IndexedNoteEntity(
        vaultId = vaultId,
        path = id.path.relativePath,
        title = title,
        excerpt = excerpt,
        snippet = snippet,
        coverPath = coverPath,
        modifiedAt = modifiedAt.toEpochMilli(),
        sizeBytes = fingerprint.sizeBytes,
        indexedAt = indexedAt,
    )

    private fun IndexedNote.tagEntities(vaultId: String): List<NoteTagEntity> {
        val path = id.path.relativePath
        return tags.mapIndexed { index, tag -> NoteTagEntity(vaultId, path, tag, index) }
    }

    private fun IndexedNote.linkEntities(vaultId: String): List<NoteLinkEntity> {
        val path = id.path.relativePath
        return links.mapIndexed { index, link ->
            NoteLinkEntity(vaultId, path, index, link.target, link.embedded)
        }
    }
}
