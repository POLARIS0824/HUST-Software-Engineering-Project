package com.example.looma.domain.model

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class NoteRevisionTest {
    @Test
    fun `revision is the SHA256 of the original bytes`() {
        assertEquals(
            NoteRevision.Known("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"),
            NoteRevision.of("abc".toByteArray()),
        )
        assertEquals(
            NoteRevision.Known("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"),
            NoteRevision.of(byteArrayOf()),
        )
        assertNotEquals(NoteRevision.of("abc".toByteArray()), NoteRevision.of("\uFEFFabc".toByteArray()))
        assertNotEquals(NoteRevision.of("a\n".toByteArray()), NoteRevision.of("a\r\n".toByteArray()))
    }

    @Test
    fun `note constructor derives a revision unless storage supplies the raw revision`() {
        val id = NoteId(VaultId("vault"), NotePath("note.md"))
        val raw = byteArrayOf(0xFF.toByte())
        val content = raw.toString(Charsets.UTF_8)
        val compatible = Note(id, "Note", content, Instant.EPOCH)
        val stored = Note(id, "Note", content, Instant.EPOCH, revision = NoteRevision.of(raw))

        assertEquals(NoteRevision.of(content.toByteArray(Charsets.UTF_8)), compatible.revision)
        assertEquals(NoteRevision.of(raw), stored.revision)
        assertNotEquals(compatible.revision, stored.revision)
    }
}
