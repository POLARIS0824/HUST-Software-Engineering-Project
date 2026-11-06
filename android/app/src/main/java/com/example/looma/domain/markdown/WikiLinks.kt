package com.example.looma.domain.markdown

data class MarkdownLinkSpan(
    val range: IntRange,
    val targetRange: IntRange,
    val target: String,
    val heading: String?,
    val alias: String?,
    val embedded: Boolean,
)

internal object WikiLinks {

    fun occurrences(
        text: String,
        base: Int = 0,
        excluded: List<IntRange> = emptyList(),
    ): List<MarkdownLinkSpan> = WIKI_REFERENCE.findAll(text)
        .mapNotNull { match -> spanOf(match, base, excluded) }
        .toList()

    fun retarget(
        source: String,
        spans: List<MarkdownLinkSpan>,
        retarget: (String) -> String?,
    ): String? {
        var result = source
        var changed = false
        for (span in spans.sortedByDescending { it.targetRange.first }) {
            val replacement = retarget(span.target) ?: continue
            if (replacement == result.substring(span.targetRange.first, span.targetRange.last + 1)) continue
            result = result.replaceRange(span.targetRange, replacement)
            changed = true
        }
        return result.takeIf { changed }
    }

    private fun spanOf(
        match: MatchResult,
        base: Int,
        excluded: List<IntRange>,
    ): MarkdownLinkSpan? {
        if (excluded.any { it.overlaps(match.range) }) return null

        val inner = match.groupValues[2]
        val innerStart = match.range.first + match.groupValues[1].length + WIKI_OPEN.length
        val targetPart = inner.substringBefore(ALIAS_MARKER).substringBefore(HEADING_MARKER)

        val leading = targetPart.indexOfFirst { !it.isWhitespace() }
        val target = targetPart.trim()
        if (target.isEmpty()) return null

        val targetStart = innerStart + leading + base
        return MarkdownLinkSpan(
            range = (match.range.first + base) until (match.range.last + 1 + base),
            targetRange = targetStart until (targetStart + target.length),
            target = target,
            heading = inner.substringBefore(ALIAS_MARKER).substringAfter(HEADING_MARKER, "")
                .trim().takeIf { it.isNotEmpty() },
            alias = inner.substringAfter(ALIAS_MARKER, "").trim().takeIf { it.isNotEmpty() },
            embedded = match.groupValues[1].isNotEmpty(),
        )
    }

    private fun IntRange.overlaps(other: IntRange): Boolean =
        first <= other.last && other.first <= last

    private const val WIKI_OPEN = "[["
    private const val ALIAS_MARKER = '|'
    private const val HEADING_MARKER = '#'
    private val WIKI_REFERENCE = Regex("""(!?)\[\[([^\]\n]+)]]""")
}
