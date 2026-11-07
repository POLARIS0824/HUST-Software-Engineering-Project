package com.example.looma.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class NoteTextStatsTest {

    @Test
    fun `latin text counts whitespace separated tokens`() {
        assertEquals(3, NoteTextStats.words("write better notes"))
    }

    @Test
    fun `cjk text counts each character as a word`() {
        assertEquals(4, NoteTextStats.words("你好世界"))
    }

    @Test
    fun `mixed text counts both kinds`() {
        assertEquals(3, NoteTextStats.words("hello 世界"))
        assertEquals(5, NoteTextStats.words("和 Java 的集合"))
    }

    @Test
    fun `characters ignore whitespace`() {
        assertEquals(4, NoteTextStats.characters("a b\nc d"))
        assertEquals(4, NoteTextStats.characters("你好 世界"))
    }
}
