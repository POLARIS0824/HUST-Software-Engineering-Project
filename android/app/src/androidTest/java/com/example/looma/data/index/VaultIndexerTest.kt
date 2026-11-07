package com.example.looma.data.index

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.looma.domain.model.Vault
import com.example.looma.domain.model.VaultId
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VaultIndexerTest {

    private lateinit var database: NoteIndexDatabase
    private lateinit var vaultDir: File
    private lateinit var indexer: VaultIndexer

    private val vaultId = VaultId("vault")

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, NoteIndexDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        vaultDir = File(context.cacheDir, "indexer-test-${System.nanoTime()}").apply { mkdirs() }
        val vault = Vault(id = vaultId, name = "vault", rootPath = vaultDir.path)
        indexer = VaultIndexer(vaultDir, vault, RoomNoteIndex(database))
    }

    @After
    fun tearDown() {
        database.close()
        vaultDir.deleteRecursively()
    }

    @Test
    fun scanIsIncrementalAndFollowsFileChanges() = runBlocking {
        val tagged = File(vaultDir, "tagged.md").apply {
            writeText("# 标题\n\n#project\n\n链接 [[Target]]\n")
        }
        File(vaultDir, "plain.md").writeText("# 另一篇\n\n正文\n")

        val first = indexer.scan()
        assertEquals(2, first.report.filesRead)
        assertEquals(2, first.notes.size)
        val dao = database.noteIndexDao()
        val taggedTags = dao.tags(vaultId.value).filter { it.notePath == "tagged.md" }
        val taggedLinks = dao.links(vaultId.value).filter { it.notePath == "tagged.md" }
        assertEquals(listOf("project"), taggedTags.map { it.tag })
        assertEquals(listOf("Target"), taggedLinks.map { it.target })
        assertEquals(listOf(false), taggedLinks.map { it.embedded })

        val second = indexer.scan()
        assertEquals(0, second.report.filesRead)
        assertEquals(0, second.report.filesRemoved)
        assertEquals(2, second.notes.size)

        val indexedAtBefore = dao.notes(vaultId.value).associate { it.path to it.indexedAt }

        Thread.sleep(20)
        tagged.setLastModified(tagged.lastModified() + 1_000)
        val third = indexer.scan()
        assertEquals(1, third.report.filesRead)
        assertEquals(2, third.notes.size)

        val indexedAtAfter = dao.notes(vaultId.value).associate { it.path to it.indexedAt }
        assertTrue(indexedAtAfter.getValue("tagged.md") > indexedAtBefore.getValue("tagged.md"))
        assertEquals(indexedAtBefore.getValue("plain.md"), indexedAtAfter.getValue("plain.md"))

        File(vaultDir, "plain.md").delete()
        val fourth = indexer.scan()
        assertEquals(1, fourth.report.filesRemoved)
        assertEquals(1, fourth.notes.size)
        assertEquals(listOf("tagged.md"), fourth.notes.map { it.id.path.relativePath })
    }

    @Test
    fun sameSizeAndMtimeIsTreatedAsUnchanged() = runBlocking {
        val file = File(vaultDir, "note.md")
        file.writeText("# 原标题\n\n旧正文\n")
        indexer.scan()

        val mtime = file.lastModified()
        file.writeText("# 新标题\n\n新正文\n")
        file.setLastModified(mtime)

        val scan = indexer.scan()
        assertEquals(0, scan.report.filesRead)
        assertEquals("原标题", database.noteIndexDao().notes(vaultId.value).single().title)
    }
}
