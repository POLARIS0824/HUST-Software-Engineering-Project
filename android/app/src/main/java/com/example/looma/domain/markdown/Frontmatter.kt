package com.example.looma.domain.markdown

internal fun frontmatterEnd(source: String): Int {
    if (!source.startsWith("---")) return 0
    if (source.length > 3 && source[3] != '\n' && source[3] != '\r') return 0
    var cursor = source.indexOf('\n').let { if (it < 0) return 0 else it + 1 }
    while (cursor < source.length) {
        val lineEnd = source.indexOf('\n', cursor).let { if (it < 0) source.length else it }
        val line = source.substring(cursor, lineEnd).trimEnd('\r')
        if (line.trim() == "---") {
            return if (lineEnd < source.length) lineEnd + 1 else lineEnd
        }
        if (lineEnd >= source.length) break
        cursor = lineEnd + 1
    }
    return 0
}
