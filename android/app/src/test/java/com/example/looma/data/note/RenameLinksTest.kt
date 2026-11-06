package com.example.looma.data.note

import com.example.looma.domain.markdown.MarkdownIndex
import com.example.looma.domain.markdown.WikiLinks
import com.example.looma.domain.model.NotePath
import com.example.looma.domain.wiki.retargetAfterRename
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class RenameLinksTest {

    private val old = NotePath("Inbox/Old.md")
    private val renameTo = NotePath("Inbox/New.md")

    private fun relink(source: String): String? = WikiLinks.retarget(
        source = source,
        spans = MarkdownIndex.index(source, "Old").links,
        retarget = retargetAfterRename(old, renameTo),
    )

    @Test
    fun `rename rewrites each reference once and keeps everything else verbatim`() {
        val body = """
            ---
            tags: [demo]
            ---

            # Old

            [[Old]] 与 [[Inbox/Old|路径写法]]，还有 [[Other]] 和 [相对链接](Other.md)。
        """.trimIndent()

        val rewritten = relink(body)!!

        assertEquals(
            """
                ---
                tags: [demo]
                ---

                # Old

                [[New]] 与 [[Inbox/New|路径写法]]，还有 [[Other]] 和 [相对链接](Other.md)。
            """.trimIndent(),
            rewritten,
        )
        assertFalse(rewritten.contains("[[Old"))
    }

    @Test
    fun `a second run reports no change`() {
        val once = relink("[[Old]]")!!

        assertEquals("[[New]]", once)
        assertNull(relink(once))
    }

    @Test
    fun `a fenced reference is not rewritten`() {
        val body = "```md\n[[Old]]\n```\n\n正文里的 [[Old]]"

        assertEquals("```md\n[[Old]]\n```\n\n正文里的 [[New]]", relink(body))
    }

    @Test
    fun `a body that only mentions the old name in code needs no write`() {
        assertNull(relink("```\n[[Old]]\n```\n"))
    }

    @Test
    fun `alias and heading survive the rename`() {
        assertEquals("[[New|别名]]", relink("[[Old|别名]]"))
        assertEquals("![[New#章节]]", relink("![[Old#章节]]"))
    }
}
