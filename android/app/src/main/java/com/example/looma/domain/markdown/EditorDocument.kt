package com.example.looma.domain.markdown

data class EditorDocument(
    val title: String,
    val body: String,
) {
    fun render(): String {
        val heading = title.trim()
        if (heading.isEmpty()) return body
        val eol = if (body.contains("\r\n")) "\r\n" else "\n"
        val start = frontmatterEnd(body)
        return body.substring(0, start) + "# $heading$eol" + body.substring(start)
    }

    companion object {
        fun split(source: String): EditorDocument {
            var lineStart = frontmatterEnd(source)
            while (lineStart <= source.length) {
                val lineEnd = source.indexOf('\n', lineStart).let {
                    if (it < 0) source.length else it
                }
                val line = source.substring(lineStart, lineEnd).removeSuffix("\r")
                TITLE.matchEntire(line)?.let { match ->
                    val afterLine = if (lineEnd < source.length) lineEnd + 1 else lineEnd
                    return EditorDocument(
                        title = match.groupValues[1].trim(),
                        body = source.removeRange(lineStart, afterLine),
                    )
                }
                if (lineEnd >= source.length) break
                lineStart = lineEnd + 1
            }
            return EditorDocument(title = "", body = source)
        }

        private val TITLE = Regex("""^#[ \t]+(.+)$""")
    }
}
