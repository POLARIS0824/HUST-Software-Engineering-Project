package com.example.looma.domain.markdown

import org.junit.Assert.assertEquals
import org.junit.Test

class EditorDocumentTest {

    @Test
    fun `title and body split at the leading heading`() {
        val document = EditorDocument.split("# 周会记录\n\n- [ ] 补充设计稿")

        assertEquals("周会记录", document.title)
        assertEquals("\n- [ ] 补充设计稿", document.body)
    }

    @Test
    fun `render puts the heading back byte for byte`() {
        val source = "# 周会记录\n\n- [ ] 补充设计稿\n"

        assertEquals(source, EditorDocument.split(source).render())
    }

    @Test
    fun `a note without a heading keeps its whole body`() {
        val document = EditorDocument.split("只有正文，没有标题\n")

        assertEquals("", document.title)
        assertEquals("只有正文，没有标题\n", document.body)
        assertEquals("只有正文，没有标题\n", document.render())
    }

    @Test
    fun `clearing the title drops the heading instead of writing an empty one`() {
        val document = EditorDocument.split("# 旧标题\n正文").copy(title = "")

        assertEquals("正文", document.render())
    }

    @Test
    fun `frontmatter stays untouched and the heading after it is the title`() {
        val source = "---\ntags: [demo]\n---\n# 标题\n\n正文"

        val document = EditorDocument.split(source)

        assertEquals("标题", document.title)
        assertEquals("---\ntags: [demo]\n---\n\n正文", document.body)
        assertEquals(source, document.render())
    }

    @Test
    fun `crlf survives the round trip`() {
        val source = "# 标题\r\n\r\n正文\r\n"

        val document = EditorDocument.split(source)

        assertEquals("标题", document.title)
        assertEquals(source, document.render())
    }

    @Test
    fun `a level two heading is not a title`() {
        val source = "## 小节\n\n正文"

        assertEquals("", EditorDocument.split(source).title)
    }

    @Test
    fun `a heading later in the body is still the title`() {
        val source = "正文\n\n# 后来的标题\n"

        val document = EditorDocument.split(source)

        assertEquals("后来的标题", document.title)
        assertEquals("正文\n\n", document.body)
        assertEquals("# 后来的标题\n正文\n\n", document.render())
    }

    @Test
    fun `empty source stays empty`() {
        assertEquals("", EditorDocument.split("").render())
    }
}
