package com.example.looma.domain.markdown

/**
 * 判断一篇笔记是否等于「空」。
 *
 * 新建笔记会带一个标题行，所以只看正文是否为空还不够：剥掉 frontmatter 和标题行之后
 * 没有任何非空行，才算用户什么都没写。标题行只在第一行出现，不会误吞正文里的 `#`。
 */
fun isEffectivelyEmpty(markdown: String): Boolean {
    val body = markdown.stripFrontmatterForEmptiness()
    val lines = body.lines()
    var index = 0
    while (index < lines.size && lines[index].isBlank()) index++
    if (index < lines.size && lines[index].trimStart().startsWith("#")) index++
    return lines.drop(index).all { it.isBlank() }
}

private fun String.stripFrontmatterForEmptiness(): String {
    if (!startsWith("---")) return this
    if (length > 3 && this[3] != '\n' && this[3] != '\r') return this
    var cursor = indexOf('\n')
    if (cursor < 0) return this
    cursor += 1
    while (cursor < length) {
        val lineEnd = indexOf('\n', cursor).let { if (it < 0) length else it }
        if (substring(cursor, lineEnd).trim() == "---") {
            return if (lineEnd < length) substring(lineEnd + 1) else ""
        }
        if (lineEnd >= length) break
        cursor = lineEnd + 1
    }
    return this
}
