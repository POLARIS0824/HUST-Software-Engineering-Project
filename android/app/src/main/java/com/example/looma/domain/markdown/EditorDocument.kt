package com.example.looma.domain.markdown

/**
 * 编辑页把一篇笔记拆成"标题"和"正文"两半，落盘时再拼回一份 Markdown。
 *
 * 文件里标题就是正文里的第一个一级标题（与 [MarkdownIndex] 取标题的规则相同），
 * 这里只负责把那一行搬进标题栏，其余字节原样留在 [body]。
 * 拆分与拼接是互逆的：`split(x).render() == x`，frontmatter 和 CRLF 都不动。
 *
 * 标题为空表示这篇笔记还没有一级标题，拼接时不会凭空写出一行 `# `。
 */
data class EditorDocument(
    val title: String,
    val body: String,
) {
    fun render(): String {
        val heading = title.trim()
        if (heading.isEmpty()) return body
        val eol = if (body.contains("\r\n")) "\r\n" else "\n"
        // 有 frontmatter 时标题必须落在它后面，否则整篇的元数据就被顶掉了
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
                    // 标题行在正文里不一定是第一行，搬走后其余内容顺序不变
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
