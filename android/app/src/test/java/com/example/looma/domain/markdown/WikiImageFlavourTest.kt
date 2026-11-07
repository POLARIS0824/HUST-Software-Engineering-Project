package com.example.looma.domain.markdown

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.parser.CancellationToken
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WikiImageFlavourTest {
    @Suppress("DEPRECATION")
    private fun parse(source: String): ASTNode =
        MarkdownParser(WikiImageFlavour(), cancellationToken = CancellationToken.NonCancellable)
            .buildMarkdownTreeFromString(source)

    private fun ASTNode.descendants(): List<ASTNode> = listOf(this) + children.flatMap { it.descendants() }

    @Test
    fun `wiki embeds use the standard image nodes with original source positions`() {
        val text = "![[folder/my pic.png|300]]\n\n![[https://img.test/a%20b.png?q=x%2Fy#part]]"
        val images = parse(text).descendants().filter { it.type == MarkdownElementTypes.IMAGE }
        assertEquals(2, images.size)
        assertEquals(0, images[0].startOffset)
        assertEquals(text.indexOf("![[https"), images[1].startOffset)
        val destinations = images.map { image ->
            image.descendants().first { it.type == MarkdownElementTypes.LINK_DESTINATION }.getTextInNode(text).toString()
        }
        assertEquals("folder/my pic.png|300", destinations[0])
        assertEquals("https://img.test/a%20b.png?q=x%2Fy#part", destinations[1])
    }

    @Test
    fun `checkbox after multiple embeds still points to original marker`() {
        val text = "- [ ] before\n\n![[one.png]]\n\n![[folder/two.png|90]]\n\n- [x] after"
        val boxes = parse(text).descendants().filter { it.type.name == "CHECK_BOX" }
        assertEquals(listOf(text.indexOf("[ ]"), text.indexOf("[x]")), boxes.map { it.startOffset })
        val changed = text.replaceRange(boxes.last().startOffset + 1, boxes.last().startOffset + 2, " ")
        assertEquals(text.substringBeforeLast("[x]") + "[ ] after", changed)
    }

    @Test
    fun `code escaped embeds and ordinary wiki links stay literal`() {
        val text = "`![[inline.png]]`\n\n```md\n![[fenced.png]]\n```\n\n    ![[indented.png]]\n\n\\![[escaped.png]]\n\n[[note|label]]"
        assertTrue(parse(text).descendants().none { it.type == MarkdownElementTypes.IMAGE })
    }

    @Test
    fun `standard images and wiki images coexist without changing standard destinations`() {
        val text = "![alt](https://img.test/a%20b.png)\n\n![[local.png]]"
        val destinations = parse(text).descendants().filter { it.type == MarkdownElementTypes.LINK_DESTINATION }
            .map { it.getTextInNode(text).toString() }
        assertEquals(listOf("https://img.test/a%20b.png", "local.png"), destinations)
    }

    @Test
    fun `unclosed and multiline embeds remain text`() {
        val text = "![[missing.png\n\n![[line\nbreak.png]]\n\n![[|200]]"
        assertTrue(parse(text).descendants().none { it.type == MarkdownElementTypes.IMAGE })
    }
}
