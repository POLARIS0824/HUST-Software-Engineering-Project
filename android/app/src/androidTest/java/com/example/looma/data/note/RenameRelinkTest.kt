package com.example.looma.data.note

import android.os.Environment
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.looma.data.index.NoteIndexDatabase
import com.example.looma.data.index.RoomNoteIndex
import com.example.looma.data.vault.VaultPreferences
import com.example.looma.data.vault.VaultRepository
import com.example.looma.domain.model.FolderPath
import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.NotePath
import com.example.looma.domain.model.Vault
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RenameRelinkTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val vaultId = "looma-relink-test"

    private lateinit var root: File
    private lateinit var original: Vault
    private lateinit var vaults: VaultRepository
    private lateinit var database: NoteIndexDatabase
    private lateinit var repository: NoteRepository

    @Before
    fun setUp() {
        runBlocking {
            vaults = VaultRepository(context)
            original = vaults.getCurrentVault()!!
            root = File(Environment.getExternalStorageDirectory(), "looma-relink-${UUID.randomUUID()}")
            assertTrue(root.mkdirs())
            write("Target.md", "# Target\n\n目标笔记。\n")
            write("From.md", "# From\n\n见 [[Target]] 与 [[Target|别名]]。\n")
            write("Other.md", "# Other\n\n[[Unrelated]]\n")
            write("Self.md", "# Self\n\n[[Self]]\n")

            VaultPreferences(context).saveVault(vaultId, "relink", root.path)
            database = Room.inMemoryDatabaseBuilder(context, NoteIndexDatabase::class.java).build()
            repository = NoteRepository(vaults, RoomNoteIndex(database))
            repository.listNotes()
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            database.close()
            root.deleteRecursively()
            VaultPreferences(context).saveVault(original.id.value, original.name, original.rootPath)
        }
    }

    @Test
    fun renamingRewritesEveryReferrerAndTheIndexFollows() = runBlocking {
        val outcome = repository.renameNote(id("Target.md"), "Renamed")

        assertEquals(1, outcome.linksUpdated)
        assertEquals(0, outcome.linksFailed)
        assertEquals("Renamed", outcome.note.id.path.relativePath.substringAfterLast('/').removeSuffix(".md"))

        assertEquals("# From\n\n见 [[Renamed]] 与 [[Renamed|别名]]。\n", read("From.md"))
        assertEquals("# Other\n\n[[Unrelated]]\n", read("Other.md"))
        assertEquals("# Target\n\n目标笔记。\n", read("Renamed.md"))

        val backlinks = repository.wikiContext(id("Renamed.md")).backlinks
        assertEquals(listOf("From.md"), backlinks.map { it.id.path.relativePath })
    }

    @Test
    fun aNoteThatLinksToItselfIsRewrittenToo() = runBlocking {
        repository.renameNote(id("Self.md"), "Myself")

        assertEquals("# Self\n\n[[Myself]]\n", read("Myself.md"))
    }

    @Test
    fun aReferrerThatVanishedIsCountedAndTheRenameStillStands() = runBlocking {
        File(root, "From.md").delete()

        val outcome = repository.renameNote(id("Target.md"), "Renamed")

        assertEquals(0, outcome.linksUpdated)
        assertEquals(1, outcome.linksFailed)
        assertTrue(File(root, "Renamed.md").isFile)
    }

    @Test
    fun movingToAnotherFolderKeepsBareNamesAndRewritesPathReferences() = runBlocking {
        File(root, "Inbox").mkdirs()
        write("Inbox/Target.md", "# Target\n\n目标笔记。\n")
        File(root, "From.md").writeText("# From\n\n见 [[Target]] 与 [[Inbox/Target]]。\n")
        File(root, "Target.md").delete()
        repository.listNotes()

        val outcome = repository.moveNote(id("Inbox/Target.md"), FolderPath("Archive"))

        assertEquals(1, outcome.linksUpdated)
        assertEquals(
            "# From\n\n见 [[Target]] 与 [[Archive/Target]]。\n",
            read("From.md"),
        )
    }

    private fun id(path: String): NoteId {
        val vault = runBlocking { vaults.getCurrentVault() }!!
        return NoteId(vault.id, NotePath(path))
    }

    private fun write(name: String, text: String) {
        File(root, name).apply { parentFile?.mkdirs() }.writeText(text)
    }

    private fun read(name: String): String = File(root, name).readText()
}
