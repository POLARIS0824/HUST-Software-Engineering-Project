package com.example.looma.feature.home

import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.NotePath
import com.example.looma.domain.model.NoteSummary
import com.example.looma.domain.model.VaultId
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeSearchTest {

    private val notes = listOf(
        note("周会记录", "讨论了下周的发布计划"),
        note("读书笔记", "kotlin coroutines"),
        note("随记", "购物清单", tags = setOf("生活")),
    )

    @Test
    fun `a blank query keeps every note`() {
        assertEquals(notes, notes.matching("  "))
    }

    @Test
    fun `the title matches`() {
        assertEquals(listOf("周会记录"), notes.matching("周会").map { it.summary.title })
    }

    @Test
    fun `the full text matches, not just the snippet`() {
        assertEquals(listOf("读书笔记"), notes.matching("coroutines").map { it.summary.title })
    }

    @Test
    fun `tags match`() {
        assertEquals(listOf("随记"), notes.matching("生活").map { it.summary.title })
    }

    @Test
    fun `matching ignores case`() {
        assertEquals(listOf("读书笔记"), notes.matching("KOTLIN").map { it.summary.title })
    }

    private fun note(
        title: String,
        text: String,
        tags: Set<String> = emptySet(),
    ): HomeNoteItem = HomeNoteItem(
        summary = NoteSummary(
            id = NoteId(VaultId("vault"), NotePath("$title.md")),
            title = title,
            excerpt = "",
            modifiedAt = Instant.EPOCH,
            tags = tags,
        ),
        markdownSnippet = text.take(20),
        searchableText = text,
    )
}
