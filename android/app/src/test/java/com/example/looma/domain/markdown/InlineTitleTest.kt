package com.example.looma.domain.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InlineTitleTest {

    @Test
    fun `the file name is shown when the note has no heading`() {
        assertEquals("周会记录", inlineTitleFor("周会记录", null, show = true)?.text)
    }

    @Test
    fun `the file name is hidden when it matches the first heading`() {
        assertNull(inlineTitleFor("周会记录", "周会记录", show = true))
    }

    @Test
    fun `dedup ignores surrounding spaces and case`() {
        assertNull(inlineTitleFor("  Weekly Notes ", "weekly notes", show = true))
    }

    @Test
    fun `a different heading keeps the file name visible`() {
        assertEquals("2026-11-07", inlineTitleFor("2026-11-07", "周会记录", show = true)?.text)
    }

    @Test
    fun `the setting hides the file name even without a heading`() {
        assertNull(inlineTitleFor("周会记录", null, show = false))
    }

    @Test
    fun `a blank file name shows nothing`() {
        assertNull(inlineTitleFor("   ", null, show = true))
    }
}
