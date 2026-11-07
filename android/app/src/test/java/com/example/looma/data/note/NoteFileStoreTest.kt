package com.example.looma.data.note

import com.example.looma.domain.model.FolderPath
import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.NotePath
import com.example.looma.domain.model.NoteRevision
import com.example.looma.domain.model.NoteWriteMode
import com.example.looma.domain.model.Vault
import com.example.looma.domain.model.VaultId
import java.io.File
import java.io.IOException
import java.util.Properties
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NoteFileStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var root: File
    private lateinit var vault: Vault
    private lateinit var store: NoteFileStore
    private lateinit var file: File
    private lateinit var id: NoteId

    @Before
    fun setUp() {
        root = temporaryFolder.newFolder("vault")
        vault = Vault(VaultId("pinned"), "Pinned", root.absolutePath)
        store = NoteFileStore(vault)
        file = File(root, "note.md").apply { writeText("alpha") }
        id = NoteId(vault.id, NotePath("note.md"))
    }

    @Test
    fun `stored revision hashes raw bytes and leaves bak untouched`() = runBlocking {
        val bytes = "\uFEFF# 笔记\r\n".toByteArray() + byteArrayOf(0xC3.toByte(), 0x28)
        file.writeBytes(bytes)
        val backup = File(root, "note.md.bak").apply { writeText("backup") }

        val note = store.readNote(id)

        assertEquals(bytes.toString(Charsets.UTF_8), note.content)
        assertEquals(NoteRevision.of(bytes), note.revision)
        assertNotEquals(NoteRevision.of(note.content.toByteArray()), note.revision)
        assertArrayEquals(bytes, file.readBytes())
        assertEquals("backup", backup.readText())
    }

    @Test
    fun `equal timestamps and lengths do not hide external changes`() = runBlocking {
        val opened = store.readNote(id)
        file.writeText("bravo")
        assertTrue(file.setLastModified(opened.modifiedAt.toEpochMilli()))

        val failure = expectFailure<NoteChangedExternallyException> {
            store.saveNote(id, "mine", NoteWriteMode.Checked(opened.revision))
        }

        assertEquals(NoteRevision.of("bravo".toByteArray()), failure.actualRevision)
        assertEquals("bravo", file.readText())
    }

    @Test
    fun `same content is only a noop after validating revision`() = runBlocking {
        val opened = store.readNote(id)
        store.writeDraft(id, "draft", opened.revision)
        val saved = store.saveNote(id, opened.content, NoteWriteMode.Checked(opened.revision))

        assertEquals(opened.revision, saved.revision)
        assertEquals(opened.modifiedAt, saved.modifiedAt)
        assertEquals("draft", store.readDraft(id)?.content)
        assertFalse(File(root, ".looma/conflicts").exists())
        expectFailure<NoteChangedExternallyException> {
            store.saveNote(id, opened.content, NoteWriteMode.Checked(NoteRevision.Unknown))
        }
        assertEquals(opened.content, file.readText())
    }

    @Test
    fun `checked save returns new raw revision and keeps draft for repository cleanup`() = runBlocking {
        val opened = store.readNote(id)
        store.writeDraft(id, "mine", opened.revision)

        val saved = store.saveNote(id, "mine", NoteWriteMode.Checked(opened.revision))

        assertEquals("mine", file.readText())
        assertEquals(NoteRevision.of(file.readBytes()), saved.revision)
        assertEquals(StoredDraft("mine", opened.revision), store.readDraft(id))
    }

    @Test
    fun `overwrite backs up exact BOM and undecodable bytes`() = runBlocking {
        val bytes = "\uFEFFexternal\r\n".toByteArray() + byteArrayOf(0xFF.toByte())
        file.writeBytes(bytes)
        val observed = store.readNote(id)

        val saved = store.saveNote(id, "mine", NoteWriteMode.Overwrite(observed.revision))

        val backup = File(root, ".looma/conflicts").listFiles()!!.single()
        assertTrue(CONFLICTED_COPY.matches(backup.name))
        assertTrue(backup.name.startsWith("note (conflicted copy "))
        assertArrayEquals(bytes, backup.readBytes())
        assertEquals("mine", file.readText())
        assertEquals(NoteRevision.of("mine".toByteArray()), saved.revision)
    }

    @Test
    fun `overwrite backups mirror the note folder and never replace each other`() = runBlocking {
        val nestedId = NoteId(vault.id, NotePath("项目/周报.md"))
        val nested = File(root, "项目/周报.md").apply {
            parentFile.mkdirs()
            writeText("first")
        }
        val first = store.saveNote(nestedId, "second", NoteWriteMode.Overwrite(store.readNote(nestedId).revision))
        store.saveNote(nestedId, "third", NoteWriteMode.Overwrite(first.revision))

        val backups = File(root, ".looma/conflicts/项目").listFiles()!!.sortedBy { it.name }
        assertEquals(2, backups.size)
        assertTrue(backups.all { it.name.startsWith("周报 (conflicted copy ") && CONFLICTED_COPY.matches(it.name) })
        assertEquals(setOf("first", "second"), backups.map { it.readText() }.toSet())
        assertEquals("third", nested.readText())
    }

    @Test
    fun `overwrite backups of a long name stay within the filename byte limit`() = runBlocking {
        val longName = "长".repeat(80)
        val longId = NoteId(vault.id, NotePath("$longName.md"))
        File(root, "$longName.md").writeText("first")
        val first = store.saveNote(longId, "second", NoteWriteMode.Overwrite(store.readNote(longId).revision))
        store.saveNote(longId, "third", NoteWriteMode.Overwrite(first.revision))

        val backups = File(root, ".looma/conflicts").listFiles()!!.toList()
        assertEquals(2, backups.size)
        assertTrue(backups.all { CONFLICTED_COPY.matches(it.name) && it.name.startsWith("长") })
        assertTrue(backups.all { it.name.toByteArray(Charsets.UTF_8).size <= 255 })
        assertEquals(setOf("first", "second"), backups.map { it.readText() }.toSet())
    }

    @Test
    fun `overwrite rejects the wrong observed hash before backing up`() = runBlocking {
        val observed = store.readNote(id)
        file.writeText("external")

        val failure = expectFailure<NoteChangedExternallyException> {
            store.saveNote(id, "mine", NoteWriteMode.Overwrite(observed.revision))
        }

        assertEquals(NoteRevision.of(file.readBytes()), failure.actualRevision)
        assertEquals("external", file.readText())
        assertFalse(File(root, ".looma/conflicts").exists())
    }

    @Test
    fun `backup failure prevents overwriting target`() = runBlocking {
        val bytes = "\uFEFFexternal".toByteArray()
        file.writeBytes(bytes)
        val observed = store.readNote(id)
        assertTrue(File(root, ".looma").mkdir())
        val obstruction = File(root, ".looma/conflicts").apply { writeText("not a directory") }

        expectFailure<NoteBackupFailedException> {
            store.saveNote(id, "mine", NoteWriteMode.Overwrite(observed.revision))
        }

        assertArrayEquals(bytes, file.readBytes())
        assertEquals("not a directory", obstruction.readText())
        assertFalse(root.walkTopDown().any { it.name.endsWith(".tmp") })
    }

    @Test
    fun `recovered draft keeps its older mtime and original baseline`() = runBlocking {
        val opened = store.readNote(id)
        val content = "\uFEFF  笔记\nkey=value: #!\\\r\t"
        store.writeDraft(id, content, opened.revision)
        val draft = draftFile(root, id.path)
        assertTrue(draft.setLastModified(1_000L))
        file.writeText("external")
        assertTrue(file.setLastModified(10_000L))
        val restarted = NoteFileStore(vault)

        assertEquals(StoredDraft(content, opened.revision), restarted.readDraft(id))
        val properties = Properties().apply { draft.reader(Charsets.UTF_8).use { load(it) } }
        assertEquals("1", properties.getProperty("version"))
        assertEquals(content, properties.getProperty("content"))
        assertEquals((opened.revision as NoteRevision.Known).sha256, properties.getProperty("baseRevision"))
        assertEquals(listOf(draft.name), draft.parentFile.list()!!.toList())
    }

    @Test
    fun `legacy draft has unknown baseline and remains until successful cleanup`() = runBlocking {
        val legacy = legacyDraftFile(root, id.path)
        assertTrue(legacy.parentFile.mkdirs())
        val bytes = "\uFEFFlegacy edits".toByteArray()
        legacy.writeBytes(bytes)
        assertTrue(legacy.setLastModified(1_000L))

        assertEquals(StoredDraft("\uFEFFlegacy edits", NoteRevision.Unknown), store.readDraft(id))
        expectFailure<NoteChangedExternallyException> {
            store.saveNote(id, "legacy edits", NoteWriteMode.Checked(NoteRevision.Unknown))
        }
        store.writeDraft(id, "continued editing", NoteRevision.Unknown)
        assertEquals(StoredDraft("continued editing", NoteRevision.Unknown), store.readDraft(id))
        assertArrayEquals(bytes, legacy.readBytes())
        store.deleteDraft(id)
        assertNull(store.readDraft(id))
        assertFalse(legacy.exists())
        assertFalse(draftFile(root, id.path).exists())
    }

    @Test
    fun `checkpoint equal to disk removes obsolete primary and legacy drafts`() = runBlocking {
        val opened = store.readNote(id)
        store.writeDraft(id, "obsolete", opened.revision)
        val legacy = legacyDraftFile(root, id.path).apply { writeText("older") }

        store.writeDraft(id, opened.content, opened.revision)

        assertNull(store.readDraft(id))
        assertFalse(draftFile(root, id.path).exists())
        assertFalse(legacy.exists())
    }

    @Test
    fun `maximum draft body allows properties serialization overhead`() = runBlocking {
        val content = "=".repeat(MAX_MARKDOWN_BYTES.toInt())
        store.writeDraft(id, content, NoteRevision.Unknown)

        assertTrue(draftFile(root, id.path).length() > MAX_MARKDOWN_BYTES)
        assertTrue(draftFile(root, id.path).length() <= MAX_DRAFT_BYTES)
        assertEquals(StoredDraft(content, NoteRevision.Unknown), store.readDraft(id))
    }

    @Test
    fun `oversized draft writes preserve the previous record`() = runBlocking {
        store.writeDraft(id, "kept", NoteRevision.Unknown)
        val before = draftFile(root, id.path).readBytes()

        expectFailure<NoteTooLargeException> {
            store.writeDraft(id, "x".repeat(MAX_MARKDOWN_BYTES.toInt() + 1), NoteRevision.Unknown)
        }

        assertArrayEquals(before, draftFile(root, id.path).readBytes())
    }

    @Test
    fun `oversized serialized drafts are rejected without deleting the record`() = runBlocking {
        store.writeDraft(id, "kept", NoteRevision.Unknown)
        val draft = draftFile(root, id.path)
        val bytes = ByteArray(MAX_DRAFT_BYTES.toInt() + 1)
        draft.writeBytes(bytes)

        expectFailure<NoteTooLargeException> { store.readDraft(id) }

        assertArrayEquals(bytes, draft.readBytes())
    }

    @Test
    fun `invalid baseline cannot be guessed from disk or hidden by legacy fallback`() = runBlocking {
        store.writeDraft(id, "kept", NoteRevision.Unknown)
        val draft = draftFile(root, id.path)
        draft.writeText("version=1\ncontent=edits\nbaseRevision=invalid\n")
        val legacy = legacyDraftFile(root, id.path).apply { writeText("older") }

        expectFailure<IOException> { store.readDraft(id) }

        assertTrue(draft.exists())
        assertEquals("older", legacy.readText())
    }

    @Test
    fun `draft checkpoint still works when an external file exceeds the note limit`() = runBlocking {
        file.writeBytes(ByteArray(MAX_MARKDOWN_BYTES.toInt() + 1))

        store.writeDraft(id, "local edits", NoteRevision.Unknown)

        assertEquals(StoredDraft("local edits", NoteRevision.Unknown), store.readDraft(id))
    }

    @Test
    fun `oversized sources fail before restore`() = runBlocking {
        val bytes = ByteArray(MAX_MARKDOWN_BYTES.toInt() + 1)
        file.writeBytes(bytes)

        val trashed = store.deleteNote(id)
        expectFailure<NoteTooLargeException> { store.restoreNote(trashed.trashId) }
        assertFalse(file.exists())
        val record = VaultFileOps(root).trashed().single()
        assertArrayEquals(bytes, record.file.readBytes())
        assertTrue(record.metaFile.isFile)
    }

    @Test
    fun `deleting then restoring a note keeps its unsaved draft`() = runBlocking {
        val opened = store.readNote(id)
        store.writeDraft(id, "unsaved", opened.revision)

        val trashed = store.deleteNote(id)
        assertNull(store.readDraft(id))
        store.restoreNote(trashed.trashId)

        assertEquals(StoredDraft("unsaved", opened.revision), store.readDraft(id))
    }

    @Test
    fun `restore returns the raw revision`() = runBlocking {
        val bytes = "\uFEFFraw\r\n".toByteArray()
        file.writeBytes(bytes)
        val revision = NoteRevision.of(bytes)

        val trashed = store.deleteNote(id)
        val restored = store.restoreNote(trashed.trashId)
        assertEquals(revision, restored.revision)
        assertArrayEquals(bytes, restored.file.readBytes())
    }

    @Test
    fun `store is pinned and refuses another vault id`() = runBlocking {
        val otherRoot = temporaryFolder.newFolder("other")
        val otherVault = Vault(VaultId("other"), "Other", otherRoot.absolutePath)
        File(otherRoot, "note.md").writeText("other")
        val otherStore = NoteFileStore(otherVault)

        assertEquals("alpha", store.readNote(id).content)
        assertEquals("other", otherStore.readNote(NoteId(otherVault.id, id.path)).content)
        val failure = expectFailure<VaultFileException> {
            store.readNote(NoteId(otherVault.id, id.path))
        }
        assertEquals(VaultFileFailure.NOT_FOUND, failure.failure)
        assertEquals(vault.id, store.createNote("created").id.vaultId)
    }

    private inline fun <reified T : Throwable> expectFailure(block: () -> Unit): T {
        try {
            block()
        } catch (failure: Throwable) {
            if (failure is T) return failure
            throw failure
        }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }

    private companion object {
        val CONFLICTED_COPY = Regex(""".+ \(conflicted copy \d{4}-\d{2}-\d{2} \d{6}\)( \d+)?\.md""")
    }
}
