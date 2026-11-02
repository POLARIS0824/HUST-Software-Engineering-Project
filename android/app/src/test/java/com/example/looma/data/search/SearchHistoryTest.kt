package com.example.looma.data.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchHistoryTest {

    @Test
    fun `query is trimmed and lowercased`() {
        assertEquals("kotlin", normalizeSearchQuery("  Kotlin \n"))
    }

    @Test
    fun `consecutive whitespace collapses into a single space`() {
        assertEquals("room dao", normalizeSearchQuery("Room \t\n  DAO"))
    }

    @Test
    fun `long query is truncated to 120 characters`() {
        val normalized = normalizeSearchQuery("a".repeat(200))

        assertEquals(120, normalized?.length)
    }

    @Test
    fun `blank query is not recorded`() {
        assertNull(normalizeSearchQuery("   "))
        assertNull(normalizeSearchQuery(""))
    }
}
