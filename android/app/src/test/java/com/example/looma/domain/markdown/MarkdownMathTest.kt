package com.example.looma.domain.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownMathTest {
    @Test
    fun `inline formula keeps source offsets and strips delimiters`() {
        val body = "前文 \$E=mc^2\$ 后文"
        val span = collectMathSpans(body).single()
        assertEquals("E=mc^2", span.latex)
        assertFalse(span.display)
        assertFalse(span.block)
        assertEquals("\$E=mc^2\$", body.substring(span.range))
    }

    @Test
    fun `standalone block formula is a block span`() {
        val body = "\$\$\n\\int_0^1 x\\,dx\n\$\$\n\n后文"
        val span = collectMathSpans(body).single()
        assertTrue(span.display)
        assertTrue(span.block)
        assertEquals("\n\\int_0^1 x\\,dx\n", span.latex)
    }

    @Test
    fun `block delimiter mixed with text stays inline`() {
        val span = collectMathSpans("inline \$\$x^2\$\$ text").single()
        assertTrue(span.display)
        assertFalse(span.block)
        assertEquals("x^2", span.latex)
    }

    @Test
    fun `currency escapes and code are not formulas`() {
        val body = "Pay \$100 and \$50\n\n`\$x\$`\n\n\\\$5\n\n```\n\$\$y\$\$\n```"
        assertTrue(collectMathSpans(body).isEmpty())
    }

    @Test
    fun `multiple formulas keep document order and block flags`() {
        val body = "\$a\$ and \$b\$\n\n\$\$c\$\$"
        val spans = collectMathSpans(body)
        assertEquals(listOf("a", "b", "c"), spans.map { it.latex })
        assertEquals(listOf(false, false, true), spans.map { it.block })
    }

    @Test
    fun `wiki images and formulas coexist with original offsets`() {
        val body = "![[pic.png|300]] 和 \$x^2\$"
        val span = collectMathSpans(body).single()
        assertEquals(body.indexOf("\$x"), span.range.first)
        assertEquals("x^2", span.latex)
    }

    @Test
    fun `formula after checkbox leaves the checkbox marker untouched`() {
        val body = "- [ ] task \$x\$\n- [x] done"
        val span = collectMathSpans(body).single()
        assertEquals("x", span.latex)
        assertTrue(body.indexOf("[ ]") < span.range.first)
        assertEquals("\$x\$", body.substring(span.range))
    }

    @Test
    fun `adjacent text spaces and formatting no longer hide inline math`() {
        val bodies = listOf(
            "\$ x \$",
            "\$x \$",
            "\$ x\$",
            "word\$x\$word",
            "中文\$x\$中文",
            "2\$x\$2",
            "**\$x\$**",
            "[\$x\$](https://a)",
        )
        bodies.forEach { body ->
            val spans = collectMathSpans(body)
            assertEquals("expected one span in <$body>", 1, spans.size)
            assertEquals("x", spans.single().latex.trim())
        }
    }

    @Test
    fun `currency and prose stay text while numeric formulas remain math`() {
        assertTrue(collectMathSpans("\$100 and \$200").isEmpty())
        assertTrue(collectMathSpans("price is \$5, tax is \$2.").isEmpty())
        assertEquals(1, collectMathSpans("\$2\$").size)
        assertEquals(1, collectMathSpans("\$100 + 200\$").size)
    }

    @Test
    fun `blank line inside display math no longer splits the block`() {
        val body = "\$\$\n\\begin{aligned}\na &= b \\\\\n\nc &= d\n\\end{aligned}\n\$\$"
        val render = preserveDisplayMathParagraphs(body)
        assertEquals(body.length, render.length)
        assertTrue('\u200B' in render)
        val span = collectMathSpans(render).single()
        assertTrue(span.block)
        assertFalse('\u200B' in span.latex)
        assertTrue("a &= b" in span.latex)
        assertTrue("c &= d" in span.latex)
    }

    @Test
    fun `display math preservation keeps checkbox offsets`() {
        val body = "- [ ] task\n\n\$\$\na\n\nb\n\$\$"
        val render = preserveDisplayMathParagraphs(body)
        assertEquals(body.length, render.length)
        assertEquals(body.indexOf("[ ]"), render.indexOf("[ ]"))
    }

    @Test
    fun `display math preservation leaves normal blank lines and fences alone`() {
        val prose = "first\n\nsecond"
        assertEquals(prose, preserveDisplayMathParagraphs(prose))
        val fenced = "\$\$\na\n\nb\n\$\$\n\n```md\n\$\$\na\n\nb\n\$\$\n```"
        val render = preserveDisplayMathParagraphs(fenced)
        assertEquals(1, render.count { it == '\u200B' })
        assertTrue(collectMathSpans(render).single().block)
    }
}
