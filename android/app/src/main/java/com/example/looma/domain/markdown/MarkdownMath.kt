package com.example.looma.domain.markdown

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.MarkdownFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.parser.CancellationToken
import org.intellij.markdown.parser.MarkdownParser

/**
 * 正文里的一个数学公式。
 *
 * [range] 指原文坐标（含定界符），其它位置数据都以原文为准，所以渲染层不必改写正文。
 * [display] 来自 GFM 的 `$$...$$`，[block] 表示这个公式独占一段，应该按块级排版。
 */
data class MathSpan(
    val range: IntRange,
    val latex: String,
    val display: Boolean,
    val block: Boolean,
)

/** 收集 GFM 数学节点；代码块、行内代码、货币符号和转义美元符都不会被当作公式 */
fun collectMathSpans(
    body: String,
    flavour: MarkdownFlavourDescriptor = WikiImageFlavour(),
): List<MathSpan> {
    if ('$' !in body) return emptyList()
    val tree = MarkdownParser(
        flavour = flavour,
        cancellationToken = CancellationToken.NonCancellable,
    ).buildMarkdownTreeFromString(body as CharSequence)
    val spans = mutableListOf<MathSpan>()
    fun visit(node: ASTNode) {
        when (node.type) {
            GFMElementTypes.INLINE_MATH -> {
                val span = mathSpan(body, node, display = false, block = false)
                if (!looksLikeCurrency(span.latex)) spans += span
            }

            GFMElementTypes.BLOCK_MATH -> spans += mathSpan(body, node, display = true, block = isStandalone(node))
            else -> node.children.forEach(::visit)
        }
    }
    visit(tree)
    return spans
}

private fun mathSpan(body: String, node: ASTNode, display: Boolean, block: Boolean): MathSpan {
    val delimiter = if (display) "$$" else "$"
    val source = body.substring(node.startOffset, node.endOffset)
    val latex = if (
        source.length > delimiter.length * 2 &&
        source.startsWith(delimiter) &&
        source.endsWith(delimiter)
    ) {
        source.substring(delimiter.length, source.length - delimiter.length)
    } else {
        source
    }.replace(PRESERVED_BREAK.toString(), "")
    return MathSpan(node.startOffset until node.endOffset, latex, display, block)
}

/** 段落里只有这一个公式时按块级排版；和其它文字混排时仍按行内内容处理 */
private fun isStandalone(node: ASTNode): Boolean {
    val parent = node.parent ?: return false
    if (parent.type != MarkdownElementTypes.PARAGRAPH) return false
    return parent.children.all { child ->
        child === node || child.type == MarkdownTokenTypes.WHITE_SPACE || child.type == MarkdownTokenTypes.EOL
    }
}

/**
 * 空白行会把段落拆开，`$$` 块因此在中间断开。
 * 把公式内空白行的第一个字符换成零宽空格，段落不再断开；只做等长替换，复选框坐标不变。
 */
fun preserveDisplayMathParagraphs(
    body: String,
    flavour: MarkdownFlavourDescriptor = WikiImageFlavour(),
): String {
    if ("$$" !in body) return body
    val code = runCatching { codeRanges(body, flavour) }.getOrElse { return body }
    val inCode = BooleanArray(body.length)
    code.forEach { range -> for (index in range) inCode[index] = true }
    val ranges = displayMathRanges(body, inCode)
    if (ranges.isEmpty()) return body
    val chars = body.toCharArray()
    var changed = false
    ranges.forEach { range ->
        var lineStart = range.first
        for (index in range) {
            if (chars[index] != '\n') continue
            val blank = (lineStart until index).all { chars[it] == ' ' || chars[it] == '\t' }
            if (blank) {
                if (index > lineStart) {
                    chars[lineStart] = PRESERVED_BREAK
                } else {
                    chars[index] = PRESERVED_BREAK
                }
                changed = true
            }
            if (chars[index] == '\n') lineStart = index + 1
        }
    }
    return if (changed) String(chars) else body
}

private fun codeRanges(body: String, flavour: MarkdownFlavourDescriptor): List<IntRange> {
    val tree = MarkdownParser(
        flavour = flavour,
        cancellationToken = CancellationToken.NonCancellable,
    ).buildMarkdownTreeFromString(body as CharSequence)
    val ranges = mutableListOf<IntRange>()
    fun visit(node: ASTNode) {
        when (node.type) {
            MarkdownElementTypes.CODE_SPAN,
            MarkdownElementTypes.CODE_FENCE,
            MarkdownElementTypes.CODE_BLOCK,
            -> {
                ranges += node.startOffset until node.endOffset
            }

            else -> node.children.forEach(::visit)
        }
    }
    visit(tree)
    return ranges
}

private fun displayMathRanges(body: String, inCode: BooleanArray): List<IntRange> {
    val ranges = mutableListOf<IntRange>()
    var index = 0
    while (index < body.length - 1) {
        if (inCode[index]) {
            index++
            continue
        }
        if (body[index] == '$' && body[index + 1] == '$' && !isEscaped(body, index)) {
            val close = findDisplayMathClose(body, inCode, index + 2)
            if (close < 0) break
            ranges += index until close + 2
            index = close + 2
            continue
        }
        index++
    }
    return ranges
}

private fun findDisplayMathClose(body: String, inCode: BooleanArray, from: Int): Int {
    var index = from
    while (index < body.length - 1) {
        if (!inCode[index] && body[index] == '$' && body[index + 1] == '$' && !isEscaped(body, index)) {
            return index
        }
        index++
    }
    return -1
}

private fun isEscaped(text: String, index: Int): Boolean {
    var backslashes = 0
    var cursor = index - 1
    while (cursor >= 0 && text[cursor] == '\\') {
        backslashes++
        cursor--
    }
    return backslashes % 2 == 1
}

/**
 * 以数字开头、含空格又没有数学运算符的行内内容按价格或普通文字处理。
 * `$2x$`、`$2$` 和 `$100 + 200$` 仍是公式，`$100 and $200` 与 `$5, tax is $2` 保持原文。
 */
internal fun looksLikeCurrency(content: String): Boolean {
    val trimmed = content.trim()
    if (trimmed.isEmpty() || !trimmed[0].isDigit()) return false
    if (trimmed.any { it in MATH_OPERATORS }) return false
    return trimmed.any { it.isWhitespace() }
}

private const val MATH_OPERATORS = "\\^_{}=+-*/()[]<>|"
private const val PRESERVED_BREAK = '\u200B'
