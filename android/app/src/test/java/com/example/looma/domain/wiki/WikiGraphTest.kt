package com.example.looma.domain.wiki

import com.example.looma.domain.model.NotePath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WikiGraphTest {

    private fun graph(vararg notes: String, links: Map<com.example.looma.domain.model.NoteId, List<String>> = emptyMap()) =
        WikiGraph(notes.map { wikiNote(it) }, links)

    @Test
    fun `resolves a bare name`() {
        val graph = graph("Inbox/NoteA.md")
        val id = graph.resolve("NoteA", NotePath("Inbox/Other.md"))
        assertEquals("Inbox/NoteA.md", id?.path?.relativePath)
    }

    @Test
    fun `resolves case insensitively and ignores the extension`() {
        val graph = graph("Inbox/NoteA.md")
        assertEquals("Inbox/NoteA.md", graph.resolve("notea.md", NotePath("x.md"))?.path?.relativePath)
        assertEquals("Inbox/NoteA.md", graph.resolve("  NOTEA ", NotePath("x.md"))?.path?.relativePath)
    }

    @Test
    fun `prefers the note in the same folder when names collide`() {
        val graph = graph("A/Note.md", "B/Note.md")
        assertEquals("A/Note.md", graph.resolve("Note", NotePath("A/source.md"))?.path?.relativePath)
        assertEquals("B/Note.md", graph.resolve("Note", NotePath("B/source.md"))?.path?.relativePath)
    }

    @Test
    fun `full path targets win over name matching`() {
        val graph = graph("A/Note.md", "B/Note.md")
        assertEquals("B/Note.md", graph.resolve("B/Note", NotePath("A/source.md"))?.path?.relativePath)
    }

    @Test
    fun `partial paths resolve by suffix`() {
        val graph = graph("Root/Sub/Note.md")
        assertEquals(
            "Root/Sub/Note.md",
            graph.resolve("Sub/Note", NotePath("elsewhere.md"))?.path?.relativePath,
        )
    }

    @Test
    fun `unknown target resolves to null instead of guessing`() {
        assertNull(graph("A.md").resolve("Missing", NotePath("A.md")))
        assertNull(graph("A.md").resolve("", NotePath("A.md")))
    }

    @Test
    fun `backlinks name the sources and skip self references`() {
        val graph = WikiGraph(
            notes = listOf(wikiNote("Target.md", "目标"), wikiNote("One.md", "一号"), wikiNote("Two.md")),
            linksByNote = linksByPath(
                "Target.md" to listOf("Target"),
                "One.md" to listOf("Target", "Target"),
                "Two.md" to listOf("Unrelated"),
            ),
        )
        val target = wikiNote("Target.md", "目标").id
        assertEquals(listOf("一号"), graph.backlinksOf(target).map { it.title })
    }

    @Test
    fun `referrers include the note itself for rename`() {
        val graph = WikiGraph(
            notes = listOf(wikiNote("Old.md"), wikiNote("A.md"), wikiNote("B.md")),
            linksByNote = linksByPath(
                "Old.md" to listOf("Old"),
                "A.md" to listOf("Old"),
                "B.md" to listOf("Other"),
            ),
        )
        val old = wikiNote("Old.md").id
        assertEquals(
            listOf("A.md", "Old.md"),
            graph.referrersOf(old).map { it.path.relativePath },
        )
    }

    @Test
    fun `context carries resolved targets and backlinks`() {
        val note = wikiNote("Note.md", "笔记")
        val graph = WikiGraph(
            notes = listOf(note, wikiNote("Target.md", "目标"), wikiNote("From.md", "来源")),
            linksByNote = linksByPath(
                "Note.md" to listOf("Target", "Missing"),
                "From.md" to listOf("Note"),
            ),
        )
        val context = graph.context(note.id)
        assertEquals("Target.md", context.resolve("Target")?.path?.relativePath)
        assertNull(context.resolve("Missing"))
        assertEquals(listOf("来源"), context.backlinks.map { it.title })
    }

    @Test
    fun `empty context is the default for a note without links`() {
        assertTrue(WikiContext.Empty.resolve("anything") == null)
        assertTrue(WikiContext.Empty.backlinks.isEmpty())
    }
}
