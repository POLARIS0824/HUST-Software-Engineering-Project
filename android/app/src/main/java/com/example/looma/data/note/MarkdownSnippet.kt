package com.example.looma.data.note

internal fun markdownSnippetOf(
    source: String,
    title: String,
    maxChars: Int = CARD_MARKDOWN_CHARS,
): String {
    val body = source.stripFrontmatter().trimStart('\n')
    val withoutTitle = body.lineSequence()
        .dropWhile { it.isBlank() }
        .toList()
        .let { lines ->
            val first = lines.firstOrNull()
            if (first != null && first.startsWith("# ") && first.removePrefix("# ").trim() == title.trim()) {
                lines.drop(1)
            } else {
                lines
            }
        }
        .joinToString("\n")
        .trim()
        .withoutTags()
        .trim()
    if (withoutTitle.length <= maxChars) return withoutTitle
    val clipped = withoutTitle.take(maxChars)
    val lastLineBreak = clipped.lastIndexOf('\n')
    return if (lastLineBreak > maxChars / 2) clipped.substring(0, lastLineBreak).trimEnd() else clipped.trimEnd()
}

private fun String.withoutTags(): String = lines()
    .mapNotNull { line ->
        val cleaned = TAG_PATTERN.replace(line, "")
        if (cleaned.isBlank() && line.isNotBlank()) null else cleaned
    }
    .joinToString("\n")

private fun String.stripFrontmatter(): String {
    if (!startsWith("---")) return this
    val firstBreak = indexOf('\n')
    if (firstBreak < 0) return this
    var cursor = firstBreak + 1
    while (cursor < length) {
        val lineEnd = indexOf('\n', cursor).let { if (it < 0) length else it }
        if (substring(cursor, lineEnd).trim() == "---") {
            return substring(lineEnd + 1)
        }
        if (lineEnd >= length) break
        cursor = lineEnd + 1
    }
    return this
}

internal const val CARD_MARKDOWN_CHARS = 600
