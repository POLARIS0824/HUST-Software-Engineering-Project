package com.example.looma.feature.note.components

import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.NotePath
import com.example.looma.domain.model.VaultId
import com.example.looma.domain.wiki.WikiNote
import org.junit.Assert.assertEquals
import org.junit.Test

class WikiLinkPanelTest {

    private fun note(path: String, title: String) =
        WikiNote(NoteId(VaultId("v"), NotePath(path)), title)

    @Test
    fun `prefix matches come before substring matches`() {
        val candidates = listOf(
            note("a/Review.md", "Weekly review"),
            note("b/Review.md", "Review"),
            note("c/Notes.md", "Notes"),
        )

        val result = filterCandidates(candidates, "rev")

        assertEquals(listOf("Review", "Weekly review"), result.map { it.title })
    }

    @Test
    fun `matching ignores case`() {
        val candidates = listOf(note("a/Alpha.md", "Alpha"))

        assertEquals(1, filterCandidates(candidates, "ALP").size)
    }

    @Test
    fun `an empty query shows the head of the list`() {
        val candidates = (1..10).map { note("n/Note$it.md", "Note$it") }

        assertEquals(6, filterCandidates(candidates, "  ").size)
    }

    @Test
    fun `a query with no match returns nothing`() {
        assertEquals(emptyList<WikiNote>(), filterCandidates(listOf(note("a/A.md", "A")), "zzz"))
    }

    @Test
    fun `the reference uses the file name, not the heading`() {
        val target = note("笔记/周会记录.md", "周会记录")

        assertEquals("周会记录", wikiReferenceFor(target, listOf(target)))
    }

    @Test
    fun `a heading that differs from the file name becomes an alias`() {
        val target = note("笔记/Meeting.md", "周会记录")

        assertEquals("Meeting|周会记录", wikiReferenceFor(target, listOf(target)))
    }

    @Test
    fun `a duplicated file name is written with its folder`() {
        val target = note("b/Review.md", "Review")
        val sameName = note("a/Review.md", "Review")

        assertEquals("b/Review|Review", wikiReferenceFor(target, listOf(target, sameName)))
    }
}
