package com.example.looma.data.index

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.looma.data.note.MarkdownLink
import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.NotePath
import com.example.looma.domain.model.VaultId
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NoteIndexDaoTest {

    private lateinit var database: NoteIndexDatabase
    private lateinit var index: RoomNoteIndex

    private val vaultId = VaultId("vault")

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, NoteIndexDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        index = RoomNoteIndex(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun upsertingSamePathReplacesTagsAndLinks() = runBlocking {
        index.apply(
            IndexDelta(
                vaultId = vaultId,
                upserts = listOf(
                    note("a.md", tags = listOf("first", "second"), links = listOf(MarkdownLink("Old", false))),
                    note("b.md", tags = listOf("keep"), links = listOf(MarkdownLink("B", true))),
                ),
            ),
        )
        index.apply(
            IndexDelta(
                vaultId = vaultId,
                upserts = listOf(
                    note("a.md", tags = listOf("second"), links = listOf(MarkdownLink("New", true))),
                ),
            ),
        )

        val loaded = index.load(vaultId).associateBy { it.id.path.relativePath }

        assertEquals(listOf("second"), loaded.getValue("a.md").tags)
        assertEquals(listOf(MarkdownLink("New", true)), loaded.getValue("a.md").links)
        assertEquals(listOf("keep"), loaded.getValue("b.md").tags)
        assertEquals(listOf(MarkdownLink("B", true)), loaded.getValue("b.md").links)
        assertEquals(2, loaded.size)
    }

    @Test
    fun deletingNoteCascadesToTagsAndLinks() = runBlocking {
        index.apply(
            IndexDelta(
                vaultId = vaultId,
                upserts = listOf(
                    note("gone.md", tags = listOf("t"), links = listOf(MarkdownLink("T", false))),
                    note("stays.md", tags = listOf("s"), links = listOf(MarkdownLink("S", false))),
                ),
            ),
        )

        index.apply(IndexDelta(vaultId = vaultId, removed = listOf(NotePath("gone.md"))))

        val dao = database.noteIndexDao()
        assertEquals(listOf("stays.md"), dao.notes(vaultId.value).map { it.path })
        assertTrue(dao.tags(vaultId.value).none { it.notePath == "gone.md" })
        assertTrue(dao.links(vaultId.value).none { it.notePath == "gone.md" })
        assertEquals(listOf("stays.md"), dao.tags(vaultId.value).map { it.notePath })
    }

    @Test
    fun removingMoreNotesThanOneSqlStatementHoldsClearsTheIndex() = runBlocking {
        val notes = List(1200) { note("n$it.md", tags = listOf("t"), links = listOf(MarkdownLink("L", false))) }
        index.apply(IndexDelta(vaultId = vaultId, upserts = notes))
        index.apply(IndexDelta(vaultId = vaultId, upserts = notes))

        index.apply(IndexDelta(vaultId = vaultId, removed = notes.map { it.id.path }))

        assertTrue(index.load(vaultId).isEmpty())
        assertTrue(database.noteIndexDao().tags(vaultId.value).isEmpty())
    }

    @Test
    fun searchHistoryKeepsRecentEntriesPerVault() = runBlocking {
        val dao = database.searchHistoryDao()
        repeat(5) { index ->
            dao.upsert(SearchHistoryEntity("vault", "q$index", "q$index", index.toLong()))
        }
        dao.upsert(SearchHistoryEntity("other", "keep", "keep", 0))

        dao.prune("vault", 3)

        assertEquals(listOf("q4", "q3", "q2"), dao.recent("vault", 10))
        assertEquals(listOf("keep"), dao.recent("other", 10))
    }

    private fun note(
        path: String,
        tags: List<String> = emptyList(),
        links: List<MarkdownLink> = emptyList(),
    ) = IndexedNote(
        id = NoteId(vaultId, NotePath(path)),
        title = path,
        excerpt = "",
        snippet = "",
        coverPath = null,
        modifiedAt = Instant.ofEpochMilli(1_000),
        fingerprint = FileFingerprint(sizeBytes = 10, modifiedAtMillis = 1_000),
        tags = tags,
        links = links,
    )
}
