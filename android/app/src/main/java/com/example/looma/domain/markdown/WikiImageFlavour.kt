package com.example.looma.domain.markdown

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.sequentialparsers.RangesListBuilder
import org.intellij.markdown.parser.sequentialparsers.SequentialParser
import org.intellij.markdown.parser.sequentialparsers.SequentialParserManager
import org.intellij.markdown.parser.sequentialparsers.TokensCache
import org.intellij.markdown.parser.sequentialparsers.impl.BacktickParser

internal class WikiImageFlavour : GFMFlavourDescriptor() {
    private val standardParsers = super.sequentialParserManager.getParserSequence()

    override val sequentialParserManager = object : SequentialParserManager() {
        override fun getParserSequence(): List<SequentialParser> =
            listOf(BacktickParser(), WikiImageParser()) + standardParsers.filterNot { it is BacktickParser }
    }
}

private class WikiImageParser : SequentialParser {
    override fun parse(tokens: TokensCache, rangesToGlue: List<IntRange>): SequentialParser.ParsingResult {
        val result = SequentialParser.ParsingResultBuilder()
        val remaining = RangesListBuilder()
        val text = tokens.originalText
        for (range in rangesToGlue) {
            var index = range.first
            while (index <= range.last) {
                val start = tokens.Iterator(index).start
                val opening = start + 3 <= text.length && text.subSequence(start, start + 3).toString() == "![["
                var escape = start - 1
                while (escape >= 0 && text[escape] == '\\') escape--
                if (!opening || (start - escape - 1) % 2 != 0) {
                    remaining.put(index++)
                    continue
                }
                val close = text.indexOf("]]", start + 3)
                val end = close + 2
                var after = index
                while (after <= range.last && tokens.Iterator(after).end < end) after++
                val complete = close >= 0 && after <= range.last && tokens.Iterator(after).end == end
                val targetEnd = if (close >= 0) text.indexOf('|', start + 3).takeIf { it in (start + 3) until close } ?: close else -1
                var firstTarget = index
                while (firstTarget <= range.last && tokens.Iterator(firstTarget).start < start + 3) firstTarget++
                var afterTarget = firstTarget
                while (afterTarget <= range.last && tokens.Iterator(afterTarget).start < targetEnd) afterTarget++
                val valid = complete && firstTarget < afterTarget &&
                    text.subSequence(start, close).none { it == '\n' || it == '\r' } &&
                    text.subSequence(start + 3, targetEnd).isNotBlank()
                if (!valid) {
                    remaining.put(index++)
                    continue
                }
                result.withNode(SequentialParser.Node(index..after + 1, MarkdownElementTypes.IMAGE))
                result.withNode(SequentialParser.Node(firstTarget..afterTarget, MarkdownElementTypes.LINK_DESTINATION))
                index = after + 1
            }
        }
        return result.withFurtherProcessing(remaining.get())
    }
}
