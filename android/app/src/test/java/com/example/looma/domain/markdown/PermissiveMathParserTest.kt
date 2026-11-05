package com.example.looma.domain.markdown

import org.intellij.markdown.IElementType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.parser.CancellationToken
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissiveMathParserTest {
    @Suppress("DEPRECATION")
    private fun parse(source: String): ASTNode =
        MarkdownParser(WikiImageFlavour(), cancellationToken = CancellationToken.NonCancellable)
            .buildMarkdownTreeFromString(source)

    private fun ASTNode.descendants(): List<ASTNode> = listOf(this) + children.flatMap { it.descendants() }

    private fun mathNodes(source: String): List<ASTNode> = parse(source).descendants()
        .filter { it.type == GFMElementTypes.INLINE_MATH || it.type == GFMElementTypes.BLOCK_MATH }

    private fun types(source: String): List<IElementType> = mathNodes(source).map { it.type }

    private fun assertInlineMath(source: String) {
        assertEquals("expected one inline math in <$source>", listOf(GFMElementTypes.INLINE_MATH), types(source))
    }

    @Test
    fun `standard parser still handles tight inline math`() {
        assertInlineMath("\$x\$")
    }

    @Test
    fun `spaces inside and next to the delimiters are allowed`() {
        assertInlineMath("\$ x \$")
        assertInlineMath("\$x \$")
        assertInlineMath("\$ x\$")
    }

    @Test
    fun `letters cjk and digits may touch the delimiters`() {
        assertInlineMath("中文\$x\$中文")
        assertInlineMath("word\$x\$word")
        assertInlineMath("2\$x\$2")
    }

    @Test
    fun `punctuation around the delimiters is allowed`() {
        assertInlineMath("**\$x\$**")
        assertInlineMath("[\$x\$](https://a)")
    }

    @Test
    fun `math inside link text keeps the surrounding link`() {
        val nodes = parse("[\$x\$](https://a)").descendants()
        assertEquals(1, nodes.count { it.type == GFMElementTypes.INLINE_MATH })
        assertEquals(1, nodes.count { it.type == MarkdownElementTypes.INLINE_LINK })
    }

    @Test
    fun `each dollar pair is its own node`() {
        assertEquals(
            listOf(GFMElementTypes.INLINE_MATH, GFMElementTypes.INLINE_MATH),
            types("\$x\$ and \$ y \$"),
        )
    }

    @Test
    fun `currency is text but arithmetic is math`() {
        assertEquals(emptyList<IElementType>(), types("\$100 and \$200"))
        assertInlineMath("\$2x\$")
        assertInlineMath("\$100 + 200\$")
    }

    @Test
    fun `code spans and fenced code stay literal`() {
        assertEquals(emptyList<IElementType>(), types("`\$ x \$`"))
        assertEquals(emptyList<IElementType>(), types("```md\n\$\$ x \$\$\n```"))
    }

    @Test
    fun `escaped dollars are literal`() {
        assertEquals(emptyList<IElementType>(), types("""\$ x \$"""))
    }

    @Test
    fun `unmatched and multiline inline dollars are literal`() {
        assertEquals(emptyList<IElementType>(), types("\$x"))
        assertEquals(emptyList<IElementType>(), types("\$\nx\n\$"))
    }

    @Test
    fun `blank content is not math`() {
        assertEquals(emptyList<IElementType>(), types("\$ \$"))
        assertEquals(emptyList<IElementType>(), types("\$\$\$\$"))
    }

    @Test
    fun `block math may contain spaces and newlines`() {
        val source = "\$\$ x \$\$"
        val spaced = parse(source)
        val spacedNodes = spaced.descendants().filter { it.type == GFMElementTypes.BLOCK_MATH }
        assertEquals(1, spacedNodes.size)
        assertEquals(source, spacedNodes.single().getTextInNode(source).toString())
        assertEquals(listOf(GFMElementTypes.BLOCK_MATH), types("\$\$\nx\n\$\$"))
    }

    @Test
    fun `inline and block math do not pair across paragraph boundaries`() {
        assertTrue(types("\$x\n\n\$").isEmpty())
    }
}
