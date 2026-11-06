package com.example.looma.domain.markdown

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.CancellationToken
import org.intellij.markdown.parser.MarkdownParser

object MarkdownIndex {

    fun index(source: String, fallbackTitle: String): MarkdownDocument {
        val body = splitBody(source)
        val facts = Facts()
        walk(parse(body.text), body.text, body.start, facts)

        return MarkdownDocument(
            source = source,
            body = body.text,
            bodyStart = body.start,
            title = facts.title?.takeIf { it.isNotBlank() } ?: fallbackTitle,
            checkboxes = facts.checkboxes,
            links = WikiLinks.occurrences(body.text, body.start, facts.code),
        )
    }

    @Suppress("DEPRECATION")
    private fun parse(text: String): ASTNode =
        MarkdownParser(GFMFlavourDescriptor(), cancellationToken = CancellationToken.NonCancellable)
            .buildMarkdownTreeFromString(text)

    private fun walk(node: ASTNode, text: String, base: Int, facts: Facts) {
        when (node.type) {
            MarkdownElementTypes.CODE_FENCE,
            MarkdownElementTypes.CODE_BLOCK,
            MarkdownElementTypes.CODE_SPAN,
            -> {
                facts.code += node.startOffset until node.endOffset
                return
            }

            MarkdownElementTypes.ATX_1,
            MarkdownElementTypes.SETEXT_1,
            -> if (facts.title == null) facts.title = headingText(node, text)

            MarkdownElementTypes.LIST_ITEM -> checkboxIn(node, text, base)?.let(facts.checkboxes::add)
        }
        node.children.forEach { walk(it, text, base, facts) }
    }

    private fun headingText(node: ASTNode, text: String): String = node.children
        .firstOrNull { it.type == MarkdownTokenTypes.ATX_CONTENT || it.type == MarkdownTokenTypes.SETEXT_CONTENT }
        ?.getTextInNode(text)
        ?.toString()
        ?.trim()
        .orEmpty()

    private fun checkboxIn(item: ASTNode, text: String, base: Int): MarkdownCheckbox? {
        val marker = item.children.firstOrNull { child ->
            val slice = child.getTextInNode(text)
            slice.length <= 4 && MARKER.matches(slice)
        } ?: return null
        val start = marker.startOffset
        if (start + MARKER_LENGTH > text.length) return null
        return MarkdownCheckbox(
            range = (base + start) until (base + start + MARKER_LENGTH),
            checked = text.regionMatches(start, CHECKED, 0, MARKER_LENGTH, ignoreCase = true),
        )
    }

    private class Facts {
        var title: String? = null
        val checkboxes = mutableListOf<MarkdownCheckbox>()
        val code = mutableListOf<IntRange>()
    }

    private data class Body(val text: String, val start: Int)

    private fun splitBody(source: String): Body {
        val start = frontmatterEnd(source)
        return Body(source.substring(start), start)
    }

    private val MARKER = Regex("""^\[[ xX]]\s*$""")
}
