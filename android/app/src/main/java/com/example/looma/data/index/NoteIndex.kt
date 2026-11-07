package com.example.looma.data.index

import com.example.looma.data.note.MarkdownLink
import com.example.looma.data.note.MarkdownMetadata
import com.example.looma.data.note.markdownSnippetOf
import com.example.looma.data.note.resolveCover
import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.NotePath
import com.example.looma.domain.model.VaultId
import java.io.File
import java.time.Instant

internal data class FileFingerprint(
    val sizeBytes: Long,
    val modifiedAtMillis: Long,
)

internal data class IndexedNote(
    val id: NoteId,
    val title: String,
    val excerpt: String,
    val snippet: String,
    val coverPath: String?,
    val modifiedAt: Instant,
    val fingerprint: FileFingerprint,
    val tags: List<String>,
    val links: List<MarkdownLink>,
)

internal data class IndexDelta(
    val vaultId: VaultId,
    val upserts: List<IndexedNote> = emptyList(),
    val removed: List<NotePath> = emptyList(),
)

internal interface NoteIndex {
    suspend fun load(vaultId: VaultId): List<IndexedNote>

    suspend fun apply(delta: IndexDelta)
}

internal fun indexedNote(
    vaultId: VaultId,
    root: File,
    file: File,
    text: String,
    metadata: MarkdownMetadata,
): IndexedNote {
    val modifiedAtMillis = file.lastModified()
    return IndexedNote(
        id = NoteId(vaultId, NotePath(file.relativeTo(root).invariantSeparatorsPath)),
        title = metadata.title,
        excerpt = metadata.excerpt,
        snippet = markdownSnippetOf(text, metadata.title),
        coverPath = resolveCover(root, file, metadata.firstImageReference),
        modifiedAt = Instant.ofEpochMilli(modifiedAtMillis),
        fingerprint = FileFingerprint(file.length(), modifiedAtMillis),
        tags = metadata.tags.toList(),
        links = metadata.links,
    )
}
