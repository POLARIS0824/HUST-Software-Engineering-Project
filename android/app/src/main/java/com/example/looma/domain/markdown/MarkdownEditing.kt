package com.example.looma.domain.markdown

/** 文本缓冲区的选区；偏移是 UTF-16 下标，start 可能大于 end。 */
data class TextSelection(val start: Int, val end: Int) {
    val from: Int get() = minOf(start, end)
    val to: Int get() = maxOf(start, end)
}

/** 一次编辑的结果；selection 始终有序。 */
data class MarkdownEdit(val text: String, val selection: TextSelection)

enum class MarkdownCommand {
    Bold, Italic, Strikethrough, Highlight, InlineCode, Link,
    Heading1, Heading2, Heading3,
    BulletList, NumberedList, Checklist, Quote,
    CodeBlock, Table, Divider,
}

enum class MarkdownInlineStyle { Bold, Italic, Strikethrough, Highlight, Code }

enum class MarkdownBlockStyle { Paragraph, Heading, BulletList, NumberedList, Checklist, Quote }

data class MarkdownFormatState(
    val inline: Set<MarkdownInlineStyle> = emptySet(),
    val headingLevel: Int? = null,
    val block: MarkdownBlockStyle = MarkdownBlockStyle.Paragraph,
    val inCodeBlock: Boolean = false,
)

/**
 * 源码模式的 Markdown 编辑引擎：只做"文本 + 选区 → 新文本 + 新选区"的纯变换。
 *
 * 不依赖 Android 和 Compose，所以工具条上每个按钮都能在单测里验证。
 * 行内样式按成对的标记扫描，块级命令只改行首前缀，正文内容原样保留。
 */
object MarkdownEditing {

    fun apply(command: MarkdownCommand, text: String, selection: TextSelection): MarkdownEdit {
        val from = selection.from.coerceIn(0, text.length)
        val to = selection.to.coerceIn(0, text.length)
        return when (command) {
            MarkdownCommand.Bold -> toggleInline(text, from, to, MarkdownInlineStyle.Bold)
            MarkdownCommand.Italic -> toggleInline(text, from, to, MarkdownInlineStyle.Italic)
            MarkdownCommand.Strikethrough -> toggleInline(text, from, to, MarkdownInlineStyle.Strikethrough)
            MarkdownCommand.Highlight -> toggleInline(text, from, to, MarkdownInlineStyle.Highlight)
            MarkdownCommand.InlineCode -> toggleInline(text, from, to, MarkdownInlineStyle.Code)
            MarkdownCommand.Link -> applyLink(text, from, to)
            MarkdownCommand.Heading1 -> applyHeading(text, from, to, 1)
            MarkdownCommand.Heading2 -> applyHeading(text, from, to, 2)
            MarkdownCommand.Heading3 -> applyHeading(text, from, to, 3)
            MarkdownCommand.BulletList -> applyList(text, from, to, ListKind.Bullet)
            MarkdownCommand.NumberedList -> applyList(text, from, to, ListKind.Numbered)
            MarkdownCommand.Checklist -> applyList(text, from, to, ListKind.Checklist)
            MarkdownCommand.Quote -> applyQuote(text, from, to)
            MarkdownCommand.CodeBlock -> applyCodeBlock(text, from, to)
            MarkdownCommand.Table -> insertTable(text, from, to)
            MarkdownCommand.Divider -> insertDivider(text, from, to)
        }
    }

    fun formatState(text: String, selection: TextSelection): MarkdownFormatState {
        val from = selection.from.coerceIn(0, text.length)
        val to = selection.to.coerceIn(0, text.length)
        val lineStart = text.lastIndexOf('\n', from - 1) + 1
        val lineEnd = text.indexOf('\n', from).let { if (it < 0) text.length else it }
        val spans = spansInLine(text, lineStart, lineEnd)
        val active = spans
            .filter { from >= it.openStart && to <= it.closeEnd }
            .mapTo(mutableSetOf()) { it.style }

        val firstLine = linesInRange(text, from, to).firstOrNull { it.text.isNotBlank() }
        val parsed = firstLine?.let { parseLine(it.text) }
        val block = when {
            parsed == null -> MarkdownBlockStyle.Paragraph
            parsed.headingLevel != null -> MarkdownBlockStyle.Heading
            parsed.quote -> MarkdownBlockStyle.Quote
            parsed.list == ListKind.Bullet -> MarkdownBlockStyle.BulletList
            parsed.list == ListKind.Numbered -> MarkdownBlockStyle.NumberedList
            parsed.list == ListKind.Checklist -> MarkdownBlockStyle.Checklist
            else -> MarkdownBlockStyle.Paragraph
        }
        val inCodeBlock = isInsideFence(text, from)
        return MarkdownFormatState(
            inline = if (inCodeBlock) emptySet() else active,
            headingLevel = parsed?.headingLevel,
            block = block,
            inCodeBlock = inCodeBlock,
        )
    }

    // ---------------------------------------------------------------- inline

    private fun toggleInline(
        text: String,
        from: Int,
        to: Int,
        style: MarkdownInlineStyle,
    ): MarkdownEdit {
        val marker = style.marker
        val length = marker.length

        // 选区外侧紧贴成对标记：去掉它们
        if (isWrappedOutside(text, from, to, style)) {
            return removeRanges(
                text = text,
                selection = TextSelection(from, to),
                ranges = listOf(from - length until from, to until to + length),
            )
        }

        // 选区自身包含成对标记：去掉内侧标记，保留选中文字
        if (isWrappedInside(text, from, to, style)) {
            return removeRanges(
                text = text,
                selection = TextSelection(from + length, to - length),
                ranges = listOf(from until from + length, to - length until to),
            )
        }

        // 选区落在已有 span 里：按"关闭当前样式"处理
        val span = findSpan(text, from, to, style)
        if (span != null) {
            return removeRanges(
                text = text,
                selection = TextSelection(from, to),
                ranges = listOf(span.openStart until span.openEnd, span.closeStart until span.closeEnd),
            )
        }

        // 没有可关闭的样式：插入标记
        if (from == to) {
            val updated = text.substring(0, from) + marker + marker + text.substring(from)
            return MarkdownEdit(updated, TextSelection(from + length, from + length))
        }
        val updated = text.substring(0, from) + marker + text.substring(from, to) + marker + text.substring(to)
        return MarkdownEdit(updated, TextSelection(from + length, to + length))
    }

    private fun isWrappedOutside(text: String, from: Int, to: Int, style: MarkdownInlineStyle): Boolean {
        val marker = style.marker
        val length = marker.length
        if (from < length || to + length > text.length) return false
        if (!text.regionMatches(from - length, marker, 0, length)) return false
        if (!text.regionMatches(to, marker, 0, length)) return false
        val char = marker[0]
        val before = runEndingAt(text, from, char)
        val after = runStartingAt(text, to, char)
        return when (style) {
            // 单个星号/反引号只认长度为 1 的 run，避免把 ** 当成斜体
            MarkdownInlineStyle.Italic, MarkdownInlineStyle.Code -> before == 1 && after == 1
            MarkdownInlineStyle.Bold -> before >= 2 && after >= 2
            MarkdownInlineStyle.Strikethrough, MarkdownInlineStyle.Highlight -> before >= 2 && after >= 2
        }
    }

    private fun isWrappedInside(text: String, from: Int, to: Int, style: MarkdownInlineStyle): Boolean {
        val marker = style.marker
        val length = marker.length
        if (to - from < 2 * length) return false
        if (!text.regionMatches(from, marker, 0, length)) return false
        if (!text.regionMatches(to - length, marker, 0, length)) return false
        return when (style) {
            MarkdownInlineStyle.Italic, MarkdownInlineStyle.Code -> {
                val next = text.getOrNull(from + length)
                val previous = text.getOrNull(to - length - 1)
                next != marker[0] && previous != marker[0]
            }
            else -> true
        }
    }

    private fun findSpan(
        text: String,
        from: Int,
        to: Int,
        style: MarkdownInlineStyle,
    ): Span? {
        val lineStart = text.lastIndexOf('\n', from - 1) + 1
        val lineEnd = text.indexOf('\n', from).let { if (it < 0) text.length else it }
        if (to > lineEnd) return null
        return spansInLine(text, lineStart, lineEnd)
            .filter { it.style == style && from >= it.openStart && to <= it.closeEnd }
            .minByOrNull { it.closeEnd - it.openStart }
    }

    private fun removeRanges(
        text: String,
        selection: TextSelection,
        ranges: List<IntRange>,
    ): MarkdownEdit {
        val sorted = ranges.filter { it.first <= it.last }.sortedByDescending { it.first }
        var updated = text
        for (range in sorted) {
            updated = updated.removeRange(range.first, range.last + 1)
        }
        return MarkdownEdit(
            text = updated,
            selection = TextSelection(mapRemoved(selection.from, ranges), mapRemoved(selection.to, ranges)),
        )
    }

    private fun mapRemoved(position: Int, ranges: List<IntRange>): Int {
        var mapped = position
        for (range in ranges.sortedBy { it.first }) {
            val length = range.last - range.first + 1
            if (position >= range.last + 1) {
                mapped -= length
            } else if (position > range.first) {
                mapped -= position - range.first
            }
        }
        return mapped.coerceAtLeast(0)
    }

    // ------------------------------------------------------------------ link

    private fun applyLink(text: String, from: Int, to: Int): MarkdownEdit {
        val label = text.substring(from, to)
        val existing = LINK.matchEntire(label)
        if (existing != null) {
            val inner = existing.groupValues[1]
            return MarkdownEdit(
                text = text.substring(0, from) + inner + text.substring(to),
                selection = TextSelection(from, from + inner.length),
            )
        }
        val inserted = if (from == to) "[]()" else "[$label]()"
        val caret = if (from == to) from + 1 else from + label.length + 3
        return MarkdownEdit(
            text = text.substring(0, from) + inserted + text.substring(to),
            selection = TextSelection(caret, caret),
        )
    }

    // --------------------------------------------------------------- heading

    private fun applyHeading(text: String, from: Int, to: Int, level: Int): MarkdownEdit {
        val lines = linesInRange(text, from, to)
        val parsed = lines.map { parseLine(it.text) }
        val nonBlank = parsed.filterIndexed { index, _ -> lines[index].text.isNotBlank() }
        val allTarget = nonBlank.isNotEmpty() && nonBlank.all { it.headingLevel == level }
        val rewrites = lines.mapIndexedNotNull { index, line ->
            val info = parsed[index]
            // 多行选择里跳过空行；但光标停在空行时仍要能插入前缀，否则没法开始一个新列表
            if (line.text.isBlank() && from != to) return@mapIndexedNotNull null
            val heading = if (allTarget) "" else "#".repeat(level) + " "
            Rewrite(
                slice = line,
                newLine = info.indent + heading + info.content,
                oldPrefixLength = line.text.length - info.content.length,
                newPrefixLength = info.indent.length + heading.length,
            )
        }
        return rewriteLines(text, from, to, rewrites)
    }

    // ------------------------------------------------------------- line style

    private enum class ListKind { Bullet, Numbered, Checklist }

    private fun applyList(text: String, from: Int, to: Int, kind: ListKind): MarkdownEdit {
        val lines = linesInRange(text, from, to)
        val parsed = lines.map { parseLine(it.text) }
        val nonBlank = parsed.filterIndexed { index, _ -> lines[index].text.isNotBlank() }
        val allKind = nonBlank.isNotEmpty() && nonBlank.all { it.blockStyle() == kind.blockStyle() }
        var number = 1
        val rewrites = lines.mapIndexedNotNull { index, line ->
            val info = parsed[index]
            if (line.text.isBlank() && from != to) return@mapIndexedNotNull null
            val marker = if (allKind) {
                ""
            } else {
                when (kind) {
                    ListKind.Bullet -> "- "
                    ListKind.Numbered -> "${number++}. "
                    ListKind.Checklist -> "- [ ] "
                }
            }
            Rewrite(
                slice = line,
                newLine = info.indent + marker + info.content,
                oldPrefixLength = line.text.length - info.content.length,
                newPrefixLength = info.indent.length + marker.length,
            )
        }
        return rewriteLines(text, from, to, rewrites)
    }

    private fun applyQuote(text: String, from: Int, to: Int): MarkdownEdit {
        val lines = linesInRange(text, from, to)
        val parsed = lines.map { parseLine(it.text) }
        val nonBlank = parsed.filterIndexed { index, _ -> lines[index].text.isNotBlank() }
        val allQuoted = nonBlank.isNotEmpty() && nonBlank.all { it.blockStyle() == MarkdownBlockStyle.Quote }
        val rewrites = lines.mapIndexedNotNull { index, line ->
            val info = parsed[index]
            if (line.text.isBlank() && from != to) return@mapIndexedNotNull null
            val marker = if (allQuoted) "" else "> "
            Rewrite(
                slice = line,
                newLine = info.indent + marker + info.content,
                oldPrefixLength = line.text.length - info.content.length,
                newPrefixLength = info.indent.length + marker.length,
            )
        }
        return rewriteLines(text, from, to, rewrites)
    }

    // ----------------------------------------------------------------- blocks

    private fun applyCodeBlock(text: String, from: Int, to: Int): MarkdownEdit {
        val leading = if (from > 0 && text[from - 1] != '\n') "\n" else ""
        if (from == to) {
            val trailing = if (to < text.length && text[to] != '\n') "\n" else ""
            val block = "$leading```\n\n```$trailing"
            val caret = from + leading.length + 4
            return MarkdownEdit(
                text = text.substring(0, from) + block + text.substring(to),
                selection = TextSelection(caret, caret),
            )
        }
        val trailing = if (to < text.length && text[to] != '\n') "\n" else ""
        val block = "$leading```\n" + text.substring(from, to) + "\n```$trailing"
        return MarkdownEdit(
            text = text.substring(0, from) + block + text.substring(to),
            selection = TextSelection(from + leading.length + 4, to + leading.length + 4),
        )
    }

    private fun insertTable(text: String, from: Int, to: Int): MarkdownEdit {
        val leading = if (from > 0 && text[from - 1] != '\n') "\n" else ""
        val trailing = if (to < text.length && text[to] != '\n') "\n" else ""
        val table = "|  |  |\n| --- | --- |\n|  |  |$trailing"
        val caret = from + leading.length + 2
        return MarkdownEdit(
            text = text.substring(0, from) + leading + table + text.substring(to),
            selection = TextSelection(caret, caret),
        )
    }

    private fun insertDivider(text: String, from: Int, to: Int): MarkdownEdit {
        val leading = if (from > 0 && text[from - 1] != '\n') "\n\n" else ""
        val trailing = if (to < text.length && text[to] != '\n') "\n" else ""
        val block = "${leading}---\n$trailing"
        val caret = from + block.length
        return MarkdownEdit(
            text = text.substring(0, from) + block + text.substring(to),
            selection = TextSelection(caret, caret),
        )
    }

    // ------------------------------------------------------------- line model

    private data class LineSlice(val start: Int, val end: Int, val text: String)

    private data class ParsedLine(
        val indent: String,
        val headingLevel: Int?,
        val quote: Boolean,
        val list: ListKind?,
        val content: String,
    ) {
        fun blockStyle(): MarkdownBlockStyle = when {
            headingLevel != null -> MarkdownBlockStyle.Heading
            quote -> MarkdownBlockStyle.Quote
            list == ListKind.Bullet -> MarkdownBlockStyle.BulletList
            list == ListKind.Numbered -> MarkdownBlockStyle.NumberedList
            list == ListKind.Checklist -> MarkdownBlockStyle.Checklist
            else -> MarkdownBlockStyle.Paragraph
        }
    }

    private data class Rewrite(
        val slice: LineSlice,
        val newLine: String,
        val oldPrefixLength: Int,
        val newPrefixLength: Int,
    )

    private fun parseLine(line: String): ParsedLine {
        val indent = line.takeWhile { it == ' ' || it == '\t' }
        var rest = line.substring(indent.length)
        var quote = false
        while (rest.startsWith(">")) {
            quote = true
            rest = rest.substring(1)
            if (rest.startsWith(" ")) rest = rest.substring(1)
        }
        val heading = HEADING.find(rest)
        val headingLevel = heading?.groupValues?.get(1)?.length
        if (heading != null) rest = rest.substring(heading.range.last + 1)
        val listMatch = LIST.find(rest)
        val list = listMatch?.let { match ->
            val token = match.value
            when {
                token.contains('[') -> ListKind.Checklist
                token.first().isDigit() -> ListKind.Numbered
                else -> ListKind.Bullet
            }
        }
        if (listMatch != null) rest = rest.substring(listMatch.range.last + 1)
        return ParsedLine(
            indent = indent,
            headingLevel = headingLevel,
            quote = quote,
            list = list,
            content = rest,
        )
    }

    private fun linesInRange(text: String, from: Int, to: Int): List<LineSlice> {
        val firstStart = text.lastIndexOf('\n', from - 1) + 1
        val lastPosition = if (to > from) (to - 1).coerceAtLeast(from) else to
        val lastStart = text.lastIndexOf('\n', lastPosition - 1) + 1
        val lines = mutableListOf<LineSlice>()
        var start = firstStart
        while (start <= lastStart) {
            val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
            lines += LineSlice(start, end, text.substring(start, end))
            if (end >= text.length) break
            start = end + 1
        }
        return lines
    }

    private fun rewriteLines(
        text: String,
        from: Int,
        to: Int,
        rewrites: List<Rewrite>,
    ): MarkdownEdit {
        if (rewrites.isEmpty()) return MarkdownEdit(text, TextSelection(from, to))
        val builder = StringBuilder()
        var cursor = 0
        var newFrom = from
        var newTo = to
        var fromMapped = false
        var toMapped = false

        for (rewrite in rewrites) {
            val slice = rewrite.slice
            builder.append(text, cursor, slice.start)
            val lineStartInNew = builder.length
            builder.append(rewrite.newLine)
            cursor = slice.end

            fun mapLocal(position: Int): Int {
                val local = position - slice.start
                return if (local >= rewrite.oldPrefixLength) {
                    local + (rewrite.newPrefixLength - rewrite.oldPrefixLength)
                } else {
                    rewrite.newPrefixLength
                }
            }

            if (!fromMapped && from in slice.start..slice.end) {
                newFrom = lineStartInNew + mapLocal(from)
                fromMapped = true
            }
            if (!toMapped && to in slice.start..slice.end) {
                newTo = lineStartInNew + mapLocal(to)
                toMapped = true
            }
        }
        builder.append(text, cursor, text.length)

        fun deltaBefore(position: Int): Int = rewrites
            .filter { it.slice.end < position }
            .sumOf { it.newLine.length - it.slice.text.length }

        if (!fromMapped) newFrom = from + deltaBefore(from)
        if (!toMapped) newTo = to + deltaBefore(to)
        return MarkdownEdit(builder.toString(), TextSelection(newFrom, newTo))
    }

    // ------------------------------------------------------------------ spans

    private data class Span(
        val style: MarkdownInlineStyle,
        val openStart: Int,
        val openEnd: Int,
        val closeStart: Int,
        val closeEnd: Int,
    )

    private fun spansInLine(text: String, lineStart: Int, lineEnd: Int): List<Span> {
        val spans = mutableListOf<Span>()
        val open = mutableMapOf<MarkdownInlineStyle, IntRange>()
        var index = lineStart
        while (index < lineEnd) {
            val char = text[index]
            if (char == '\\') {
                index += 2
                continue
            }
            val runEnd = when (char) {
                '*', '~', '=', '`' -> {
                    var end = index
                    while (end < lineEnd && text[end] == char) end++
                    end
                }
                else -> index
            }
            if (runEnd == index) {
                index++
                continue
            }
            val runLength = runEnd - index
            when (char) {
                '*' -> when {
                    runLength == 1 -> toggleSpan(spans, open, MarkdownInlineStyle.Italic, index, index + 1)
                    runLength == 2 -> toggleSpan(spans, open, MarkdownInlineStyle.Bold, index, index + 2)
                    else -> {
                        toggleSpan(spans, open, MarkdownInlineStyle.Bold, index, index + 2)
                        toggleSpan(spans, open, MarkdownInlineStyle.Italic, runEnd - 1, runEnd)
                    }
                }
                '~' -> if (runLength >= 2) {
                    toggleSpan(spans, open, MarkdownInlineStyle.Strikethrough, index, index + 2)
                }
                '=' -> if (runLength >= 2) {
                    toggleSpan(spans, open, MarkdownInlineStyle.Highlight, index, index + 2)
                }
                '`' -> toggleSpan(spans, open, MarkdownInlineStyle.Code, index, index + 1)
            }
            index = runEnd
        }
        return spans
    }

    private fun toggleSpan(
        spans: MutableList<Span>,
        open: MutableMap<MarkdownInlineStyle, IntRange>,
        style: MarkdownInlineStyle,
        start: Int,
        end: Int,
    ) {
        val previous = open.remove(style)
        if (previous == null) {
            open[style] = start until end
        } else {
            spans += Span(style, previous.first, previous.last + 1, start, end)
        }
    }

    private fun runEndingAt(text: String, position: Int, char: Char): Int {
        var count = 0
        var index = position - 1
        while (index >= 0 && text[index] == char) {
            count++
            index--
        }
        return count
    }

    private fun runStartingAt(text: String, position: Int, char: Char): Int {
        var count = 0
        var index = position
        while (index < text.length && text[index] == char) {
            count++
            index++
        }
        return count
    }

    private fun isInsideFence(text: String, position: Int): Boolean {
        var fences = 0
        var lineStart = 0
        while (lineStart < text.length && lineStart <= position) {
            val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
            if (text.substring(lineStart, lineEnd).trimStart().startsWith("```")) fences++
            if (lineEnd >= text.length) break
            lineStart = lineEnd + 1
        }
        return fences % 2 == 1
    }

    private val MarkdownInlineStyle.marker: String
        get() = when (this) {
            MarkdownInlineStyle.Bold -> "**"
            MarkdownInlineStyle.Italic -> "*"
            MarkdownInlineStyle.Strikethrough -> "~~"
            MarkdownInlineStyle.Highlight -> "=="
            MarkdownInlineStyle.Code -> "`"
        }

    private fun ListKind.blockStyle(): MarkdownBlockStyle = when (this) {
        ListKind.Bullet -> MarkdownBlockStyle.BulletList
        ListKind.Numbered -> MarkdownBlockStyle.NumberedList
        ListKind.Checklist -> MarkdownBlockStyle.Checklist
    }

    private val HEADING = Regex("^(#{1,6})\\s+")
    private val LIST = Regex("^([-*+]\\s+\\[[ xX]]\\s+|[-*+]\\s+|\\d+[.)]\\s+)")
    private val LINK = Regex("^\\[([^]]*)]\\([^)]*\\)$")
}
