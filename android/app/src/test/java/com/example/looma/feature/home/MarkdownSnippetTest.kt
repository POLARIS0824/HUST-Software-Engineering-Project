package com.example.looma.feature.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownSnippetTest {

    @Test
    fun `frontmatter is dropped`() {
        val source = """
            ---
            tags: [java]
            ---
            # 标题

            正文
        """.trimIndent()

        assertEquals("正文", markdownSnippetOf(source, "标题"))
    }

    @Test
    fun `heading that repeats the title is dropped`() {
        val source = "# 周会记录\n\n- [ ] 第一项"

        assertEquals("- [ ] 第一项", markdownSnippetOf(source, "周会记录"))
    }

    @Test
    fun `heading with different text stays`() {
        val source = "# 另一件事\n\n正文"

        assertEquals("# 另一件事\n\n正文", markdownSnippetOf(source, "周会记录"))
    }

    @Test
    fun `long body is cut at a line boundary within the limit`() {
        val source = (1..40).joinToString("\n") { "第 $it 行正文内容" }

        val snippet = markdownSnippetOf(source, "长文", maxChars = 60)

        assertTrue(snippet.length <= 60)
        assertTrue(snippet.endsWith("行正文内容"))
    }

    @Test
    fun `blank body stays blank`() {
        assertEquals("", markdownSnippetOf("", "空"))
    }
}
