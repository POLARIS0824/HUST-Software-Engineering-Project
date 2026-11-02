package com.example.looma.domain.markdown

/**
 * 正文里的一处 `[[目标]]`，坐标是文件原文坐标。
 *
 * [range] 覆盖整个引用（含前导 `!`）；[targetRange] 只覆盖目标名本身，
 * 改名时替换这一段，`#heading`、`|alias` 和首尾空白都原样保留。
 */
data class MarkdownLinkSpan(
    val range: IntRange,
    val targetRange: IntRange,
    val target: String,
    val heading: String?,
    val alias: String?,
    val embedded: Boolean,
)

/**
 * 双链语法的唯一实现。
 *
 * 索引、渲染、改名改写都从这里拿 span，避免三处各写一份匹配规则后对不上。
 * 只认文本规则；代码块和行内代码里的 `[[..]]` 由调用方用 AST 区间排除，
 * 因为那属于 Markdown 结构，不属于双链语法。
 */
internal object WikiLinks {

    /**
     * [text] 里的全部引用，[base] 是它在文件原文里的起点；
     * [excluded] 用 [text] 自己的坐标描述代码区间，相交的引用直接跳过。
     */
    fun occurrences(
        text: String,
        base: Int = 0,
        excluded: List<IntRange> = emptyList(),
    ): List<MarkdownLinkSpan> = WIKI_REFERENCE.findAll(text)
        .mapNotNull { match -> spanOf(match, base, excluded) }
        .toList()

    /**
     * 整篇改写：[retarget] 返回新目标名的引用才替换，其余原样。
     * 没有任何引用真的变了就返回 null，调用方据此跳过写盘；重复调用结果不变。
     */
    fun retarget(
        source: String,
        spans: List<MarkdownLinkSpan>,
        retarget: (String) -> String?,
    ): String? {
        var result = source
        var changed = false
        // 从后往前替换，前面的坐标就不会被后面的替换挪动
        for (span in spans.sortedByDescending { it.targetRange.first }) {
            val replacement = retarget(span.target) ?: continue
            // 移动目录时 `[[Note]]` 的写法本来就不用变，别为此重写一遍文件、刷新 mtime
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

    /** 区间相交即算落在代码里，宁可少建一条链接，也不链进代码示例 */
    private fun IntRange.overlaps(other: IntRange): Boolean =
        first <= other.last && other.first <= last

    private const val WIKI_OPEN = "[["
    private const val ALIAS_MARKER = '|'
    private const val HEADING_MARKER = '#'
    private val WIKI_REFERENCE = Regex("""(!?)\[\[([^\]\n]+)]]""")
}
