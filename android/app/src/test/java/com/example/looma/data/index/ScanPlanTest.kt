package com.example.looma.data.index

import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.NotePath
import com.example.looma.domain.model.VaultId
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class ScanPlanTest {

    private val vaultId = VaultId("vault")

    @Test
    fun `empty index reads every file on disk`() {
        val plan = planScan(
            indexed = emptyList(),
            onDisk = mapOf(path("a.md") to fingerprint(), path("b.md") to fingerprint()),
        )

        assertEquals(listOf(path("a.md"), path("b.md")), plan.toRead)
        assertEquals(emptyList<IndexedNote>(), plan.kept)
        assertEquals(emptyList<NotePath>(), plan.removed)
    }

    @Test
    fun `unchanged files are kept without reading`() {
        val plan = planScan(
            indexed = listOf(note("a.md"), note("b.md")),
            onDisk = mapOf(path("a.md") to fingerprint(), path("b.md") to fingerprint()),
        )

        assertEquals(emptyList<NotePath>(), plan.toRead)
        assertEquals(listOf("a.md", "b.md"), plan.kept.map { it.id.path.relativePath })
        assertEquals(emptyList<NotePath>(), plan.removed)
    }

    @Test
    fun `one changed file is the only one read`() {
        val plan = planScan(
            indexed = listOf(note("a.md"), note("b.md")),
            onDisk = mapOf(
                path("a.md") to fingerprint(),
                path("b.md") to fingerprint(modifiedAtMillis = 2_000),
            ),
        )

        assertEquals(listOf(path("b.md")), plan.toRead)
        assertEquals(listOf(path("a.md")), plan.kept.map { it.id.path })
    }

    @Test
    fun `index rows without a file are removed`() {
        val plan = planScan(
            indexed = listOf(note("a.md"), note("b.md")),
            onDisk = mapOf(path("a.md") to fingerprint()),
        )

        assertEquals(listOf(path("b.md")), plan.removed)
        assertEquals(listOf(path("a.md")), plan.kept.map { it.id.path })
    }

    @Test
    fun `new file and deleted file are handled in the same plan`() {
        val plan = planScan(
            indexed = listOf(note("gone.md")),
            onDisk = mapOf(path("fresh.md") to fingerprint()),
        )

        assertEquals(listOf(path("fresh.md")), plan.toRead)
        assertEquals(listOf(path("gone.md")), plan.removed)
        assertEquals(emptyList<IndexedNote>(), plan.kept)
    }

    @Test
    fun `same size with a different mtime counts as changed`() {
        val plan = planScan(
            indexed = listOf(note("a.md", sizeBytes = 10, modifiedAtMillis = 1_000)),
            onDisk = mapOf(path("a.md") to fingerprint(sizeBytes = 10, modifiedAtMillis = 1_500)),
        )

        assertEquals(listOf(path("a.md")), plan.toRead)
        assertEquals(emptyList<IndexedNote>(), plan.kept)
    }

    private fun path(value: String) = NotePath(value)

    private fun fingerprint(sizeBytes: Long = 10, modifiedAtMillis: Long = 1_000) =
        FileFingerprint(sizeBytes, modifiedAtMillis)

    private fun note(
        path: String,
        sizeBytes: Long = 10,
        modifiedAtMillis: Long = 1_000,
    ) = IndexedNote(
        id = NoteId(vaultId, NotePath(path)),
        title = path,
        excerpt = "",
        snippet = "",
        coverPath = null,
        modifiedAt = Instant.ofEpochMilli(modifiedAtMillis),
        fingerprint = fingerprint(sizeBytes, modifiedAtMillis),
        tags = emptyList(),
        links = emptyList(),
    )
}
