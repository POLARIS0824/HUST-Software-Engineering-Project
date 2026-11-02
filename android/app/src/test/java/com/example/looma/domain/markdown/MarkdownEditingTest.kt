package com.example.looma.domain.markdown

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownEditingTest {

    private fun edit(
        command: MarkdownCommand,
        text: String,
        start: Int,
        end: Int = start,
    ): MarkdownEdit = MarkdownEditing.apply(command, text, TextSelection(start, end))

    private fun state(text: String, start: Int, end: Int = start): MarkdownFormatState =
        MarkdownEditing.formatState(text, TextSelection(start, end))

    @Test
    fun boldWrapsCollapsedCaret() {
        val result = edit(MarkdownCommand.Bold, "abcdef", 3)
        assertEquals("abc****def", result.text)
        assertEquals(TextSelection(5, 5), result.selection)
    }

    @Test
    fun boldWrapsSelectionAndKeepsItSelected() {
        val result = edit(MarkdownCommand.Bold, "abcdef", 1, 4)
        assertEquals("a**bcd**ef", result.text)
        assertEquals(TextSelection(3, 6), result.selection)
    }

    @Test
    fun boldUnwrapsMarkersJustOutsideSelection() {
        val result = edit(MarkdownCommand.Bold, "**hello**", 2, 7)
        assertEquals("hello", result.text)
        assertEquals(TextSelection(0, 5), result.selection)
    }

    @Test
    fun boldUnwrapsSelectionIncludingMarkers() {
        val result = edit(MarkdownCommand.Bold, "**hello**", 0, 9)
        assertEquals("hello", result.text)
        assertEquals(TextSelection(0, 5), result.selection)
    }

    @Test
    fun boldTurnsOffWhenCaretSitsInsideSpan() {
        val result = edit(MarkdownCommand.Bold, "**hello**", 4)
        assertEquals("hello", result.text)
        assertEquals(TextSelection(2, 2), result.selection)
    }

    @Test
    fun italicUnwrapsSingleStarSpan() {
        val result = edit(MarkdownCommand.Italic, "*italic*", 1, 7)
        assertEquals("italic", result.text)
        assertEquals(TextSelection(0, 6), result.selection)
    }

    @Test
    fun italicDoesNotMistakeBoldMarkerForItsOwn() {
        val result = edit(MarkdownCommand.Italic, "**hello**", 2, 7)
        assertEquals("***hello***", result.text)
        assertEquals(TextSelection(3, 8), result.selection)
    }

    @Test
    fun italicRemovesOnlyOneStarFromTripleRun() {
        val result = edit(MarkdownCommand.Italic, "***hello***", 3, 8)
        assertEquals("**hello**", result.text)
        assertEquals(TextSelection(2, 7), result.selection)
    }

    @Test
    fun strikethroughTogglesOff() {
        val result = edit(MarkdownCommand.Strikethrough, "~~old~~", 2, 5)
        assertEquals("old", result.text)
    }

    @Test
    fun highlightTogglesOff() {
        val result = edit(MarkdownCommand.Highlight, "==marked==", 2, 8)
        assertEquals("marked", result.text)
    }

    @Test
    fun inlineCodeTogglesOff() {
        val result = edit(MarkdownCommand.InlineCode, "`code`", 1, 5)
        assertEquals("code", result.text)
    }

    @Test
    fun linkOnEmptySelectionInsertsBrackets() {
        val result = edit(MarkdownCommand.Link, "", 0)
        assertEquals("[]()", result.text)
        assertEquals(TextSelection(1, 1), result.selection)
    }

    @Test
    fun linkWrapsSelectionAndPlacesCaretInUrl() {
        val result = edit(MarkdownCommand.Link, "abc", 0, 3)
        assertEquals("[abc]()", result.text)
        assertEquals(TextSelection(6, 6), result.selection)
    }

    @Test
    fun linkUnwrapsExistingLink() {
        val result = edit(MarkdownCommand.Link, "[abc](https://x.y)", 0, 18)
        assertEquals("abc", result.text)
        assertEquals(TextSelection(0, 3), result.selection)
    }

    @Test
    fun headingAddsPrefix() {
        val result = edit(MarkdownCommand.Heading1, "Title", 0)
        assertEquals("# Title", result.text)
        assertEquals(TextSelection(2, 2), result.selection)
    }

    @Test
    fun headingTogglesOffSameLevel() {
        val result = edit(MarkdownCommand.Heading1, "# Title", 3)
        assertEquals("Title", result.text)
        assertEquals(TextSelection(1, 1), result.selection)
    }

    @Test
    fun headingReplacesDifferentLevel() {
        val result = edit(MarkdownCommand.Heading1, "## Title", 4)
        assertEquals("# Title", result.text)
        assertEquals(TextSelection(3, 3), result.selection)
    }

    @Test
    fun headingKeepsIndentation() {
        val result = edit(MarkdownCommand.Heading2, "  Title", 3)
        assertEquals("  ## Title", result.text)
    }

    @Test
    fun headingAppliesToEverySelectedLine() {
        val result = edit(MarkdownCommand.Heading1, "a\nb", 0, 3)
        assertEquals("# a\n# b", result.text)
        assertEquals(TextSelection(2, 7), result.selection)
    }

    @Test
    fun bulletListAppliesPerLine() {
        val result = edit(MarkdownCommand.BulletList, "a\nb", 0, 3)
        assertEquals("- a\n- b", result.text)
    }

    @Test
    fun bulletListTogglesOff() {
        val result = edit(MarkdownCommand.BulletList, "- a\n- b", 0, 7)
        assertEquals("a\nb", result.text)
    }

    @Test
    fun numberedListRenumbers() {
        val result = edit(MarkdownCommand.NumberedList, "5. a\nb", 0, 6)
        assertEquals("1. a\n2. b", result.text)
    }

    @Test
    fun checklistTogglesOff() {
        val result = edit(MarkdownCommand.Checklist, "- [ ] task", 0, 10)
        assertEquals("task", result.text)
    }

    @Test
    fun switchingBulletToQuote() {
        val result = edit(MarkdownCommand.Quote, "- item", 0, 6)
        assertEquals("> item", result.text)
    }

    @Test
    fun quoteTogglesOff() {
        val result = edit(MarkdownCommand.Quote, "> quoted", 0, 8)
        assertEquals("quoted", result.text)
    }

    @Test
    fun codeBlockOnEmptyCaret() {
        val result = edit(MarkdownCommand.CodeBlock, "", 0)
        assertEquals("```\n\n```", result.text)
        assertEquals(TextSelection(4, 4), result.selection)
    }

    @Test
    fun codeBlockWrapsSelection() {
        val result = edit(MarkdownCommand.CodeBlock, "abc", 0, 3)
        assertEquals("```\nabc\n```", result.text)
        assertEquals(TextSelection(4, 7), result.selection)
    }

    @Test
    fun tableInsertsGfmTable() {
        val result = edit(MarkdownCommand.Table, "", 0)
        assertEquals("|  |  |\n| --- | --- |\n|  |  |", result.text)
        assertEquals(TextSelection(2, 2), result.selection)
    }

    @Test
    fun dividerStartsOnOwnLine() {
        val result = edit(MarkdownCommand.Divider, "abc", 3)
        assertEquals("abc\n\n---\n", result.text)
    }

    @Test
    fun formatStateReportsBoldInsideSpan() {
        val result = state("**bold**", 4)
        assertEquals(setOf(MarkdownInlineStyle.Bold), result.inline)
    }

    @Test
    fun formatStateReportsBoldAndItalicForTripleRun() {
        val result = state("***both***", 3)
        assertEquals(setOf(MarkdownInlineStyle.Bold, MarkdownInlineStyle.Italic), result.inline)
    }

    @Test
    fun formatStateReportsHeadingAndBlock() {
        val result = state("## title", 2)
        assertEquals(2, result.headingLevel)
        assertEquals(MarkdownBlockStyle.Heading, result.block)
    }

    @Test
    fun formatStateReportsChecklist() {
        val result = state("- [x] done", 3)
        assertEquals(MarkdownBlockStyle.Checklist, result.block)
    }

    @Test
    fun formatStateReportsQuote() {
        val result = state("> quoted", 3)
        assertEquals(MarkdownBlockStyle.Quote, result.block)
    }

    @Test
    fun formatStateReportsFencedCode() {
        val result = state("```\ncode\n```", 5)
        assertEquals(true, result.inCodeBlock)
    }

    @Test
    fun reversedSelectionIsNormalized() {
        val result = edit(MarkdownCommand.Bold, "abc", 3, 0)
        assertEquals("**abc**", result.text)
        assertEquals(TextSelection(2, 5), result.selection)
    }

    @Test
    fun outOfRangeSelectionIsClamped() {
        val result = edit(MarkdownCommand.Bold, "abc", -10, 99)
        assertEquals("**abc**", result.text)
    }

    @Test
    fun multilineEditKeepsBlankLineInPlace() {
        val result = edit(MarkdownCommand.BulletList, "a\n\nb", 0, 4)
        assertEquals("- a\n\n- b", result.text)
    }

    @Test
    fun headingStartsOnEmptyLine() {
        val result = edit(MarkdownCommand.Heading1, "", 0)
        assertEquals("# ", result.text)
        assertEquals(TextSelection(2, 2), result.selection)
    }

    @Test
    fun listStartsOnEmptyLine() {
        val result = edit(MarkdownCommand.BulletList, "", 0)
        assertEquals("- ", result.text)
        assertEquals(TextSelection(2, 2), result.selection)
    }

    @Test
    fun quoteStartsOnEmptyLine() {
        val result = edit(MarkdownCommand.Quote, "", 0)
        assertEquals("> ", result.text)
    }
}
