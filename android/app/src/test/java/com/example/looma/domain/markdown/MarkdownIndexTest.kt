package com.example.looma.domain.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownIndexTest {

    private val source = """
        ---
        title: 周会
        ---

        # 周会记录

        正文里有一个 #标签 和 [[另一篇笔记|别名]]。

        - [ ] 未完成
        - [x] 已完成
    """.trimIndent()

    @Test
    fun `front matter stays out of the rendered body`() {
        val document = MarkdownIndex.index(source, "兜底标题")

        assertFalse(document.body.contains("title:"))
        assertTrue(document.body.trimStart().startsWith("# 周会记录"))
        assertEquals("周会记录", document.title)
    }

    @Test
    fun `checkbox ranges point at the markers in the original text`() {
        val document = MarkdownIndex.index(source, "兜底标题")

        assertEquals(2, document.checkboxes.size)
        val first = document.checkboxes[0]
        val second = document.checkboxes[1]
        assertEquals("[ ]", source.substring(first.range.first, first.range.last + 1))
        assertEquals("[x]", source.substring(second.range.first, second.range.last + 1))
        assertFalse(first.checked)
        assertTrue(second.checked)
    }

    @Test
    fun `checking one box rewrites only that marker`() {
        val document = MarkdownIndex.index(source, "兜底标题")

        val updated = document.withCheckboxChecked(document.checkboxes[0].range.first, true)

        assertEquals(source.replaceFirst("- [ ] 未完成", "- [x] 未完成"), updated)
        assertEquals(source, document.source)
    }

    @Test
    fun `a position that is not a checkbox is refused`() {
        val document = MarkdownIndex.index(source, "兜底标题")

        assertNull(document.withCheckboxChecked(0, true))
        assertNull(document.withCheckboxChecked(source.length - 1, true))
    }

    @Test
    fun `checkbox-looking lines inside a code fence are not clickable`() {
        val fenced = """
            # 标题

            ```
            - [ ] 代码里的示例
            ```

            - [ ] 真正的任务
        """.trimIndent()

        val document = MarkdownIndex.index(fenced, "兜底")

        assertEquals(1, document.checkboxes.size)
        assertEquals("[ ]", fenced.substring(document.checkboxes.single().range.first, document.checkboxes.single().range.first + 3))
    }
}
