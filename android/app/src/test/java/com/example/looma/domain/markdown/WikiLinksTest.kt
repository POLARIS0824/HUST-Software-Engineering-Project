package com.example.looma.domain.markdown

import com.example.looma.domain.model.NotePath
import com.example.looma.domain.wiki.retargetAfterRename
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WikiLinksTest {

    @Test
    fun `plain reference yields one span over the whole brackets`() {
        val text = "见 [[NoteA]] 一节"
        val span = WikiLinks.occurrences(text).single()

        assertEquals("[[NoteA]]", text.substring(span.range.first, span.range.last + 1))
        assertEquals("NoteA", text.substring(span.targetRange.first, span.targetRange.last + 1))
        assertEquals("NoteA", span.target)
        assertNull(span.alias)
        assertNull(span.heading)
        assertEquals(false, span.embedded)
    }

    @Test
    fun `heading and alias are stripped from the target but kept on the span`() {
        val span = WikiLinks.occurrences("[[NoteA#二级标题|显示名]]").single()

        assertEquals("NoteA", span.target)
        assertEquals("二级标题", span.heading)
        assertEquals("显示名", span.alias)
    }

    @Test
    fun `an embedded reference is marked`() {
        val span = WikiLinks.occurrences("![[插图.png]]").single()

        assertTrue(span.embedded)
        assertEquals("插图.png", span.target)
        assertEquals("![[插图.png]]", "![[插图.png]]".substring(span.range.first, span.range.last + 1))
    }

    @Test
    fun `surrounding spaces are kept while the target range covers only the name`() {
        val text = "[[  NoteA  ]]"
        val span = WikiLinks.occurrences(text).single()

        assertEquals("NoteA", text.substring(span.targetRange.first, span.targetRange.last + 1))
        assertEquals("[[  NoteB  ]]", WikiLinks.retarget(text, listOf(span)) { "NoteB" })
    }

    @Test
    fun `empty and multiline targets are not links`() {
        assertTrue(WikiLinks.occurrences("[[]]").isEmpty())
        assertTrue(WikiLinks.occurrences("[[ ]]").isEmpty())
        assertTrue(WikiLinks.occurrences("[[Not\nLinked]]").isEmpty())
        assertTrue(WikiLinks.occurrences("[[#只看标题]]").isEmpty())
    }

    @Test
    fun `references inside excluded code ranges are skipped`() {
        val text = "`[[InCode]]` and [[Real]]"
        val code = text.indexOf("`[[InCode]]`") until (text.indexOf("`[[InCode]]`") + 12)

        assertEquals(listOf("Real"), WikiLinks.occurrences(text, excluded = listOf(code)).map { it.target })
    }

    @Test
    fun `rename rewrites only the target and keeps alias heading and embed marker`() {
        val text = "![[Old#章节|别名]] 和 [[Old]]"
        val spans = WikiLinks.occurrences(text)
        val retarget = retargetAfterRename(NotePath("Old.md"), NotePath("New.md"))

        assertEquals("![[New#章节|别名]] 和 [[New]]", WikiLinks.retarget(text, spans, retarget))
    }

    @Test
    fun `rename keeps the path form for path style links`() {
        val text = "[[Inbox/Old]] 和 [[Old]]"
        val spans = WikiLinks.occurrences(text)
        val retarget = retargetAfterRename(NotePath("Inbox/Old.md"), NotePath("Archive/New.md"))

        assertEquals("[[Archive/New]] 和 [[New]]", WikiLinks.retarget(text, spans, retarget))
    }

    @Test
    fun `rename is idempotent and reports no change the second time`() {
        val text = "[[Old]] and [[Other]]"
        val retarget = retargetAfterRename(NotePath("Old.md"), NotePath("New.md"))

        val renamed = WikiLinks.retarget(text, WikiLinks.occurrences(text), retarget)
        assertEquals("[[New]] and [[Other]]", renamed)
        assertNull(WikiLinks.retarget(renamed!!, WikiLinks.occurrences(renamed), retarget))
    }

    @Test
    fun `unrelated targets are left alone`() {
        val text = "[[Oldest]] and [[Something/Else]]"
        val retarget = retargetAfterRename(NotePath("Old.md"), NotePath("New.md"))

        assertNull(WikiLinks.retarget(text, WikiLinks.occurrences(text), retarget))
    }

    @Test
    fun `moving to another folder leaves a bare name reference untouched`() {
        val text = "[[Note]] and [[Old/Note]]"
        val retarget = retargetAfterRename(NotePath("Old/Note.md"), NotePath("New/Note.md"))

        assertEquals("[[Note]] and [[New/Note]]", WikiLinks.retarget(text, WikiLinks.occurrences(text), retarget))
    }
}
