package com.example.looma.domain.markdown

import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.sequentialparsers.RangesListBuilder
import org.intellij.markdown.parser.sequentialparsers.SequentialParser
import org.intellij.markdown.parser.sequentialparsers.TokensCache

internal class PermissiveMathParser : SequentialParser {
    override fun parse(tokens: TokensCache, rangesToGlue: List<IntRange>): SequentialParser.ParsingResult {
        val result = SequentialParser.ParsingResultBuilder()
        val remaining = RangesListBuilder()
        val text = tokens.originalText

        for (range in rangesToGlue) {
            var index = range.first
            while (index <= range.last) {
                val opening = tokens.Iterator(index)
                if (opening.type != GFMTokenTypes.DOLLAR) {
                    remaining.put(index++)
                    continue
                }
                val closingIndex = findClosing(tokens, index, range.last, opening.length)
                if (closingIndex == null) {
                    remaining.put(index++)
                    continue
                }
                val block = opening.length > 1
                val content = text.subSequence(opening.end, tokens.Iterator(closingIndex).start).toString()
                if (!isMathContent(content, block)) {
                    remaining.put(index++)
                    continue
                }
                val type = if (block) GFMElementTypes.BLOCK_MATH else GFMElementTypes.INLINE_MATH
                result.withNode(SequentialParser.Node(index..closingIndex + 1, type))
                index = closingIndex + 1
            }
        }
        return result.withFurtherProcessing(remaining.get())
    }

    private fun findClosing(tokens: TokensCache, openingIndex: Int, lastIndex: Int, length: Int): Int? {
        for (index in openingIndex + 1..lastIndex) {
            val token = tokens.Iterator(index)
            if (token.type == GFMTokenTypes.DOLLAR && token.length == length) return index
        }
        return null
    }

    private fun isMathContent(content: String, block: Boolean): Boolean {
        if (content.isBlank()) return false
        if (block) return true
        if (content.any { it == '\n' || it == '\r' }) return false
        return !looksLikeCurrency(content)
    }
}
