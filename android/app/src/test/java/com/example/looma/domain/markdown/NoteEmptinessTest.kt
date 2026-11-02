package com.example.looma.domain.markdown

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「空笔记」判定：新建模板、只剩标题、带 frontmatter 都要算空；写过字就不算 */
class NoteEmptinessTest {

    @Test
    fun `empty note template counts as empty`() {
        assertTrue(isEffectivelyEmpty("# Untitled note\n\n"))
    }

    @Test
    fun `whitespace only counts as empty`() {
        assertTrue(isEffectivelyEmpty("   \n\n\t\n"))
    }

    @Test
    fun `frontmatter alone counts as empty`() {
        assertTrue(
            isEffectivelyEmpty(
                """
                ---
                tags: [work]
                ---
                # Meeting

                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `any body text is not empty`() {
        assertFalse(isEffectivelyEmpty("# Meeting\n\n- [ ] send notes\n"))
        assertFalse(isEffectivelyEmpty("# Meeting\n\nhello"))
    }

    @Test
    fun `title is only stripped once`() {
        assertFalse(isEffectivelyEmpty("# One\n\n# Two\n"))
    }
}
