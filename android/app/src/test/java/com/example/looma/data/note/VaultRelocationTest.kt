package com.example.looma.data.note

import com.example.looma.domain.model.FolderPath
import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.NotePath
import com.example.looma.domain.model.StalledRelocation
import com.example.looma.domain.model.Vault
import com.example.looma.domain.model.VaultId
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Properties
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VaultRelocationTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var root: File
    private lateinit var vault: Vault
    private lateinit var relocation: VaultRelocation

    @Before
    fun setUp() {
        root = temporaryFolder.newFolder()
        vault = Vault(VaultId(root.name), "test", root.absolutePath)
        relocation = VaultRelocation(vault)
    }

    @Test
    fun `rename discovers fresh filesystem referrers and preserves raw markdown`() {
        note("old.md", "# Old\r\n")
        val original = "\uFEFF---\r\ntitle: untouched\r\n---\r\n[[ old#Heading | Alias ]]\r\n`[[old]]`\r\n```\r\n[[old]]\r\n```\r\n"
        note("fresh.md", original)
        val untouched = note("unchanged.md", "# untouched\r\n").apply { setLastModified(123456000L) }
        val stamp = untouched.lastModified()

        val result = relocation.renameNote(id("old.md"), "new")

        assertEquals(NotePath("new.md"), result.path)
        assertFalse(file("old.md").exists())
        assertEquals(original.replace("[[ old#", "[[ new#"), file("fresh.md").readText())
        assertEquals("# Old\r\n", file("new.md").readText())
        assertEquals(stamp, untouched.lastModified())
        assertEquals(1, result.linksUpdated)
        assertEquals(0, result.linksFailed)
        assertNoPendingJournal()
    }

    @Test
    fun `ambiguous basenames retain their resolved identity`() {
        note("a/topic.md", "A")
        note("b/topic.md", "B")
        note("a/ref.md", "[[topic]] [[topic#Heading|Alias]]")
        note("b/ref.md", "[[topic]]")

        val result = relocation.renameNote(id("a/topic.md"), "renamed")

        assertEquals("[[a/renamed]] [[a/renamed#Heading|Alias]]", file("a/ref.md").readText())
        assertEquals("[[topic]]", file("b/ref.md").readText())
        assertEquals(2, result.linksUpdated)
    }

    @Test
    fun `moving a note relinks outgoing references whose relative resolution changes`() {
        note("a/topic.md", "A")
        note("b/topic.md", "B")
        note("a/ref.md", "[[topic]] and [[a/topic#Heading|Alias]]")

        assertEquals(NotePath("b/ref.md"), relocation.moveNote(id("a/ref.md"), FolderPath("b")).path)

        assertEquals("[[a/topic]] and [[a/topic#Heading|Alias]]", file("b/ref.md").readText())
        assertEquals("A", file("a/topic.md").readText())
        assertEquals("B", file("b/topic.md").readText())
    }

    @Test
    fun `folder rename relinks descendants and external references without rewriting stable links`() {
        note("old/deep/topic.md", "[[old/sibling]]")
        note("old/sibling.md", "[[deep/topic]]")
        note("ref.md", "[[old/deep/topic#Heading|Alias]] [[topic]]")
        note("old/asset.bin", "binary")
        note("old/.private/skip.md", "[[old/deep/topic]]")

        assertEquals(FolderPath("new"), relocation.renameFolder(FolderPath("old"), "new").path)

        assertFalse(file("old").exists())
        assertEquals("[[new/sibling]]", file("new/deep/topic.md").readText())
        assertEquals("[[deep/topic]]", file("new/sibling.md").readText())
        assertEquals("[[new/deep/topic#Heading|Alias]] [[topic]]", file("ref.md").readText())
        assertEquals("binary", file("new/asset.bin").readText())
        assertEquals("[[old/deep/topic]]", file("new/.private/skip.md").readText())
    }

    @Test
    fun `folder move keeps descendants and updates their full wiki paths`() {
        note("old/deep/topic.md", "content")
        note("ref.md", "[[old/deep/topic]]")
        file("archive").mkdir()

        assertEquals(FolderPath("archive/deep"), relocation.moveFolder(FolderPath("old/deep"), FolderPath("archive")).path)

        assertEquals("[[archive/deep/topic]]", file("ref.md").readText())
        assertEquals("content", file("archive/deep/topic.md").readText())
    }

    @Test
    fun `no op preserves files and creates no journal`() {
        val note = note("folder/note.md", "[[note]]").apply { setLastModified(123456000L) }
        val stamp = note.lastModified()

        val results = listOf(
            relocation.renameNote(id("folder/note.md"), "note"),
            relocation.moveNote(id("folder/note.md"), FolderPath("folder")),
            relocation.renameFolder(FolderPath("folder"), "folder"),
            relocation.moveFolder(FolderPath("folder"), FolderPath.Root),
        )

        assertEquals(
            listOf(NotePath("folder/note.md"), NotePath("folder/note.md"), FolderPath("folder"), FolderPath("folder")),
            results.map { it.path },
        )
        assertEquals(stamp, note.lastModified())
        assertTrue(results.all { it.linksUpdated == 0 && it.changes == RelocationChanges() })
        assertFalse(file(".looma").exists())
    }

    @Test
    fun `oversized markdown elsewhere is left byte identical while the note moves`() {
        note("old.md", "original")
        val bytes = ByteArray(MAX_MARKDOWN_BYTES.toInt() + 1) { 65 }
        note("oversized.md", "").writeBytes(bytes)

        assertEquals(NotePath("new/deep/old.md"), relocation.moveNote(id("old.md"), FolderPath("new/deep")).path)

        assertFalse(file("old.md").exists())
        assertEquals("original", file("new/deep/old.md").readText())
        assertArrayEquals(bytes, file("oversized.md").readBytes())
        assertNoPendingJournal()
    }

    @Test
    fun `invalid utf8 elsewhere is left byte identical while the note is renamed`() {
        note("old.md", "original")
        val bytes = byteArrayOf(0xC3.toByte(), 0x28)
        note("ref.md", "").writeBytes(bytes)

        assertEquals(NotePath("new.md"), relocation.renameNote(id("old.md"), "new").path)

        assertFalse(file("old.md").exists())
        assertEquals("original", file("new.md").readText())
        assertArrayEquals(bytes, file("ref.md").readBytes())
        assertNoPendingJournal()
    }

    @Test
    fun `planning selects a unique note destination without losing the existing note`() {
        note("old.md", "original")
        note("folder/old.md", "existing")
        note("ref.md", "[[old]]")

        assertEquals(NotePath("folder/old (1).md"), relocation.moveNote(id("old.md"), FolderPath("folder")).path)

        assertEquals("existing", file("folder/old.md").readText())
        assertEquals("original", file("folder/old (1).md").readText())
        assertEquals("[[folder/old (1)]]", file("ref.md").readText())
    }

    @Test
    fun `invalid folder destination and collisions leave source unchanged`() {
        note("old/deep/note.md", "original")
        note("taken/note.md", "taken")

        assertThrows(IOException::class.java) { relocation.moveFolder(FolderPath("old"), FolderPath("old/deep")) }
        assertThrows(IOException::class.java) { relocation.renameFolder(FolderPath("old"), "taken") }
        assertThrows(IOException::class.java) { relocation.moveNote(id("old/deep/note.md"), FolderPath("../escape")) }
        assertThrows(IOException::class.java) { relocation.renameNote(NoteId(VaultId("other"), NotePath("old/deep/note.md")), "renamed") }

        assertEquals("original", file("old/deep/note.md").readText())
        assertFalse(file(".looma").exists())
    }

    @Test
    fun `recovery completes after namespace rename and can run twice`() {
        note("old.md", "[[old]]")
        note("ref.md", "[[old]]")
        failAfter("move")

        val error = assertThrows(RelocationStalledException::class.java) { relocation.renameNote(id("old.md"), "new") }
        assertEquals(StalledRelocation(journal().name, "old.md", "new.md"), error.stalled)
        assertFalse(file("old.md").exists())
        assertEquals("[[old]]", file("ref.md").readText())

        val recovered = VaultRelocation(vault)
        val first = recovered.recoverPending()
        val second = recovered.recoverPending()

        assertEquals("[[new]]", file("new.md").readText())
        assertEquals("[[new]]", file("ref.md").readText())
        assertNull(first.stalled)
        assertEquals(listOf(NotePath("old.md")), first.changes.removedNotes)
        assertEquals(setOf(NotePath("new.md"), NotePath("ref.md")), first.changes.changedNotes.toSet())
        assertEquals(RecoveryReport(RelocationChanges(), null), second)
        assertNoPendingJournal()
    }

    @Test
    fun `recovery recognizes an applied relink even before progress was saved`() {
        note("old.md", "original")
        note("a-ref.md", "[[old]]")
        note("b-ref.md", "[[old]]")
        failAfter("replace")

        assertThrows(IOException::class.java) { relocation.renameNote(id("old.md"), "new") }
        assertEquals("[[new]]", file("a-ref.md").readText())
        assertEquals("[[old]]", file("b-ref.md").readText())

        assertNull(VaultRelocation(vault).recoverPending().stalled)

        assertEquals("[[new]]", file("a-ref.md").readText())
        assertEquals("[[new]]", file("b-ref.md").readText())
        assertNoPendingJournal()
    }

    @Test
    fun `external changed referrer stalls recovery and blocks relocations until abandoned`() {
        note("old.md", "original")
        note("ref.md", "[[old]]")
        failAfter("move")
        assertThrows(IOException::class.java) { relocation.renameNote(id("old.md"), "new") }
        file("ref.md").writeText("external work")
        val journalId = journal().name

        val report = VaultRelocation(vault).recoverPending()

        assertEquals(StalledRelocation(journalId, "old.md", "new.md"), report.stalled)
        assertEquals(RelocationChanges(), report.changes)
        assertEquals("external work", file("ref.md").readText())
        assertEquals("original", file("new.md").readText())
        assertTrue(journal().resolve("intent.properties").isFile)
        val error = assertThrows(RelocationStalledException::class.java) {
            VaultRelocation(vault).renameNote(id("new.md"), "third")
        }
        assertEquals(journalId, error.stalled.journalId)
        assertFalse(file("third.md").exists())

        VaultRelocation(vault).abandon(journalId)

        assertTrue(file(".looma/abandoned-operations/$journalId/intent.properties").isFile)
        assertFalse(file(".looma/operations/$journalId").exists())
        assertEquals(NotePath("third.md"), VaultRelocation(vault).renameNote(id("new.md"), "third").path)
        assertEquals("original", file("third.md").readText())
        assertEquals("external work", file("ref.md").readText())
    }

    @Test
    fun `external changed moved note stalls replay even when it has no links`() {
        note("old.md", "original")
        failAfter("move")
        assertThrows(IOException::class.java) { relocation.renameNote(id("old.md"), "new") }
        file("new.md").writeText("external work")

        assertNotNull(VaultRelocation(vault).recoverPending().stalled)

        assertEquals("external work", file("new.md").readText())
        assertTrue(journal().resolve("intent.properties").isFile)
    }

    @Test
    fun `both primary and legacy drafts follow a note and retain the original baseline`() {
        note("old.md", "[[old]] canonical")
        val primary = primaryDraft("old.md", "[[old#Heading|Alias]] unsaved", "original-baseline")
        val legacy = note(draftPath("old.md", ".md"), "\uFEFF[[old]] legacy\r\n")

        relocation.renameNote(id("old.md"), "new")

        assertFalse(primary.exists())
        assertFalse(legacy.exists())
        val moved = load(file(draftPath("new.md", ".properties")))
        assertEquals("[[new#Heading|Alias]] unsaved", moved.getProperty("content"))
        assertEquals("original-baseline", moved.getProperty("baseRevision"))
        assertEquals("preserved", moved.getProperty("extra"))
        assertEquals("\uFEFF[[new]] legacy\r\n", file(draftPath("new.md", ".md")).readText())
        assertEquals("[[new]] canonical", file("new.md").readText())
    }

    @Test
    fun `folder drafts and unmoved draft referrers recover with their unsaved content`() {
        note("old/deep/note.md", "canonical")
        note("ref.md", "canonical ref")
        primaryDraft("old/deep/note.md", "own unsaved [[old/deep/note]]", "baseline")
        primaryDraft("ref.md", "ref unsaved [[old/deep/note]]", "ref-baseline")
        failAfter("move")
        assertThrows(IOException::class.java) { relocation.renameFolder(FolderPath("old"), "new") }

        assertNull(VaultRelocation(vault).recoverPending().stalled)

        val moved = load(file(draftPath("new/deep/note.md", ".properties")))
        val ref = load(file(draftPath("ref.md", ".properties")))
        assertEquals("own unsaved [[new/deep/note]]", moved.getProperty("content"))
        assertEquals("baseline", moved.getProperty("baseRevision"))
        assertEquals("ref unsaved [[new/deep/note]]", ref.getProperty("content"))
        assertEquals("ref-baseline", ref.getProperty("baseRevision"))
        assertEquals("canonical", file("new/deep/note.md").readText())
    }

    @Test
    fun `a colliding destination draft aborts before moving canonical files`() {
        note("old.md", "original")
        primaryDraft("new.md", "unfamiliar", "baseline")

        assertThrows(IOException::class.java) { relocation.renameNote(id("old.md"), "new") }

        assertEquals("original", file("old.md").readText())
        assertEquals("unfamiliar", load(file(draftPath("new.md", ".properties"))).getProperty("content"))
        assertFalse(file(".looma/operations").exists())
    }

    @Test
    fun `every durable application boundary can be recovered independently`() {
        val events = mutableListOf<String>()
        seedRecoverableMove()
        relocation.afterIo = { events += it }
        relocation.moveNote(id("old.md"), FolderPath("new/deep"))
        assertTrue(events.containsAll(listOf("intent", "mkdir", "move", "replace", "progress", "complete")))

        for (boundary in events.indices) {
            setUp()
            seedRecoverableMove()
            var current = 0
            relocation.afterIo = { if (current++ == boundary) throw IOException("injected boundary $boundary") }
            assertThrows(IOException::class.java) { relocation.moveNote(id("old.md"), FolderPath("new/deep")) }

            val recovered = VaultRelocation(vault)
            assertNull(recovered.recoverPending().stalled)
            assertNull(recovered.recoverPending().stalled)

            assertFalse(file("old.md").exists())
            assertEquals("[[old]] own", file("new/deep/old.md").readText())
            assertEquals("[[new/deep/old]] ref", file("ref.md").readText())
            assertEquals("[[old]] unsaved", load(file(draftPath("new/deep/old.md", ".properties"))).getProperty("content"))
            assertNoPendingJournal()
        }
    }

    @Test
    fun `journal cleanup never deletes an unfamiliar file`() {
        note("old.md", "original")
        relocation.afterIo = { boundary -> if (boundary == "complete") journal().resolve("user-file").writeText("keep") }

        relocation.renameNote(id("old.md"), "new")
        assertEquals(RecoveryReport(RelocationChanges(), null), VaultRelocation(vault).recoverPending())

        val retained = file(".looma/operations").listFiles()!!.single()
        assertEquals("keep", retained.resolve("user-file").readText())
        assertEquals("original", file("new.md").readText())
    }

    @Test
    fun `journal traversal is rejected and an external file stays untouched`() {
        note("old.md", "original")
        failAfter("intent")
        assertThrows(IOException::class.java) { relocation.renameNote(id("old.md"), "new") }
        val manifest = journal().resolve("intent.properties")
        val data = load(manifest)
        data.setProperty("move.0.destination", "../outside.md")
        manifest.writeBytes(store(data))
        val external = File(root.parentFile, "outside.md").apply { writeText("external") }

        val report = VaultRelocation(vault).recoverPending()

        assertEquals(StalledRelocation(journal().name, "", ""), report.stalled)
        assertEquals("external", external.readText())
        assertEquals("original", file("old.md").readText())
    }

    @Test
    fun `a symlink elsewhere in the vault does not block a rename`() {
        note("old.md", "original")
        note("target/inside.md", "[[old]]")
        try {
            Files.createSymbolicLink(file("linked").toPath(), file("target").toPath())
        } catch (error: Exception) {
            assumeNoException(error)
        }

        val result = relocation.renameNote(id("old.md"), "new")

        assertEquals(NotePath("new.md"), result.path)
        assertEquals("original", file("new.md").readText())
        assertEquals("[[new]]", file("target/inside.md").readText())
        assertTrue(Files.isSymbolicLink(file("linked").toPath()))
        assertNoPendingJournal()
    }

    @Test
    fun `unpublished journal directories are swept and report no pending work`() {
        note("old.md", "original")
        val intentless = file(".looma/operations/op-00000000-0000-0000-0000-000000000001").apply { mkdirs() }
        intentless.resolve("0.before").writeText("before")
        intentless.resolve("0.after").writeText("after")
        val staging = file(".looma/operations/staging-00000000-0000-0000-0000-000000000002").apply { mkdirs() }
        staging.resolve("0.before").writeText("before")
        staging.resolve("intent.properties").writeText("version=1")

        assertEquals(RecoveryReport(RelocationChanges(), null), relocation.recoverPending())

        assertFalse(intentless.exists())
        assertFalse(staging.exists())
        assertEquals("original", file("old.md").readText())
    }

    @Test
    fun `an unpublished directory with an unfamiliar file is kept and ignored`() {
        val staging = file(".looma/operations/staging-00000000-0000-0000-0000-000000000003").apply { mkdirs() }
        staging.resolve("0.before").writeText("before")
        staging.resolve("user-file").writeText("keep")
        note("old.md", "original")

        assertEquals(RecoveryReport(RelocationChanges(), null), relocation.recoverPending())
        assertEquals(NotePath("new.md"), relocation.renameNote(id("old.md"), "new").path)

        assertEquals("keep", staging.resolve("user-file").readText())
        assertFalse(staging.resolve("0.before").exists())
    }

    @Test
    fun `an unpreservable link is left as is and counted while other links are rewritten`() {
        note("a/topic.md", "A")
        note("f#g/topic.md", "F")
        note("a/ref.md", "[[topic]]")
        note("c.md", "[[a/ref]]")

        val result = relocation.moveNote(id("a/ref.md"), FolderPath("f#g"))

        assertEquals(NotePath("f#g/ref.md"), result.path)
        assertEquals("[[a/topic]]", file("f#g/ref.md").readText())
        assertEquals("[[a/ref]]", file("c.md").readText())
        assertEquals(1, result.linksUpdated)
        assertEquals(1, result.linksFailed)
    }

    @Test
    fun `folder relocation reports moved markdown members and rewritten referrers`() {
        note("old/deep/topic.md", "[[old/sibling]]")
        note("old/sibling.md", "[[deep/topic]]")
        note("old/asset.bin", "binary")
        note("old/.private/skip.md", "hidden")
        note("ref.md", "[[old/deep/topic]]")
        note("unrelated.md", "[[nothing]]")

        val changes = relocation.renameFolder(FolderPath("old"), "new").changes

        assertEquals(setOf(NotePath("old/deep/topic.md"), NotePath("old/sibling.md")), changes.removedNotes.toSet())
        assertEquals(
            setOf(NotePath("new/deep/topic.md"), NotePath("new/sibling.md"), NotePath("ref.md")),
            changes.changedNotes.toSet(),
        )
        assertEquals(changes.changedNotes.distinct(), changes.changedNotes)
    }

    private fun seedRecoverableMove() {
        note("old.md", "[[old]] own")
        note("ref.md", "[[old]] ref")
        primaryDraft("old.md", "[[old]] unsaved", "baseline")
        note(draftPath("old.md", ".md"), "legacy")
        note("path-ref.md", "[[old.md]]")
        note("b/old.md", "ambiguous")
    }

    private fun file(path: String) = File(root, path)
    private fun id(path: String) = NoteId(vault.id, NotePath(path))
    private fun note(path: String, content: String): File = file(path).apply { parentFile.mkdirs(); writeText(content) }
    private fun draftPath(path: String, suffix: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(path.toByteArray(Charsets.UTF_8))
        return ".looma/drafts/" + digest.joinToString("") { "%02x".format(it) }.take(32) + suffix
    }
    private fun primaryDraft(path: String, content: String, baseline: String): File {
        val properties = Properties().apply {
            setProperty("content", content)
            setProperty("baseRevision", baseline)
            setProperty("extra", "preserved")
        }
        return note(draftPath(path, ".properties"), "").apply { writeBytes(store(properties)) }
    }
    private fun store(data: Properties): ByteArray = ByteArrayOutputStream().use { data.store(it, null); it.toByteArray() }
    private fun load(file: File): Properties = Properties().apply { file.inputStream().use { load(it) } }
    private fun failAfter(boundary: String) {
        relocation.afterIo = { if (it == boundary) throw IOException("injected after $boundary") }
    }
    private fun journal(): File = file(".looma/operations").listFiles()!!.single { it.resolve("intent.properties").isFile }
    private fun assertNoPendingJournal() {
        assertTrue(file(".looma/operations").listFiles().orEmpty().none { it.resolve("intent.properties").exists() })
    }
}
