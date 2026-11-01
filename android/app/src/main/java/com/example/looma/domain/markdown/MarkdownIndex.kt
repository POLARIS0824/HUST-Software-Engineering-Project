package com.example.looma.domain.markdown

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.ast.getTextInNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.CancellationToken
import org.intellij.markdown.parser.MarkdownParser

/**
 * 把 Markdown 原文解析成渲染和写回共用的一份结果。
 *
 * 只依赖 org.intellij:markdown，不碰 Android 和 Compose，能在纯单测里跑。
 * 渲染器画 [MarkdownDocument.body]，写回改 [MarkdownDocument.source] 上 [MarkdownCheckbox.range] 指的三个字符，
 * 两边来自同一次解析，不会各算一份位置。
 */
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
        )
    }

    @Suppress("DEPRECATION")
    private fun parse(text: String): ASTNode =
        MarkdownParser(GFMFlavourDescriptor(), cancellationToken = CancellationToken.NonCancellable)
            .buildMarkdownTreeFromString(text)

    private fun walk(node: ASTNode, text: String, base: Int, facts: Facts) {
        when (node.type) {
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

    /**
     * 复选框标记没有公开的 token 常量，所以按结构找：列表项里那个 "[ ]" / "[x]" 子节点。
     * 它和渲染器拿到的节点是同一棵树上的同一个节点，偏移因此一致。
     */
    private fun checkboxIn(item: ASTNode, text: String, base: Int): MarkdownCheckbox? {
        val marker = item.children.firstOrNull { child ->
            val slice = child.getTextInNode(text)
            slice.length <= 4 && MARKER.matches(slice)
        } ?: return null
        val start = marker.startOffset
        if (start + MARKER_LENGTH > text.length) return null
        return MarkdownCheckbox(
            range = (base + start) until (base + start + MARKER_LENGTH),
            checked = text.regionMatches(start, CHECKED, 0, MARKER_LENGTH),
        )
    }

    private class Facts {
        var title: String? = null
        val checkboxes = mutableListOf<MarkdownCheckbox>()
    }

    private data class Body(val text: String, val start: Int)

    /** 只剥掉文件开头那段合法 frontmatter，剥不掉就整段当正文，宁可多显示也不丢内容 */
    private fun splitBody(source: String): Body {
        if (!source.startsWith("---")) return Body(source, 0)
        if (source.length > 3 && source[3] != '\n' && source[3] != '\r') return Body(source, 0)
        var cursor = source.indexOf('\n').let { if (it < 0) return Body(source, 0) else it + 1 }
        while (cursor < source.length) {
            val lineEnd = source.indexOf('\n', cursor).let { if (it < 0) source.length else it }
            val line = source.substring(cursor, lineEnd).trimEnd('\r')
            if (line.trim() == "---") {
                val start = if (lineEnd < source.length) lineEnd + 1 else lineEnd
                return Body(source.substring(start), start)
            }
            if (lineEnd >= source.length) break
            cursor = lineEnd + 1
        }
        return Body(source, 0)
    }

    private val MARKER = Regex("""^\[[ xX]]\s*$""")
}
