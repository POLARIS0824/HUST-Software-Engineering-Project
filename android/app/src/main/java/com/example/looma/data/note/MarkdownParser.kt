package com.example.looma.data.note

/** Markdown 正文中的任务项，表示内容语义，而不是 UI 状态 */
data class MarkdownChecklistItem(
    val text: String,
    val checked: Boolean,
)

/** 正文里的一条 `[[目标]]` 或 `![[目标]]`；顺序即出现顺序，重复的也保留 */
data class MarkdownLink(
    val target: String,
    val embedded: Boolean,
)

/** 纯文本解析结果，不包含文件访问或页面展示状态 */
data class MarkdownMetadata(
    val title: String,
    val excerpt: String,
    val tags: Set<String>,
    val checklist: List<MarkdownChecklistItem>,
    val links: List<MarkdownLink>,
    val firstImageReference: String?,
)

/** 轻量级元信息提取，不负责 Markdown 渲染 */
internal class MarkdownParser {
    fun parse(text: String, fallbackTitle: String): MarkdownMetadata {
        val body = bodyLines(text)
        val excerpt = body.asSequence()
            .filterNot { it.matches(HEADING) || it.matches(CHECKLIST) }
            .filterNot {
                it.trim().startsWith("```") ||
                        it.trim().startsWith("![[") ||
                        it.trim().startsWith("![")
            }
            .map { stripInlineMarkdown(it.trim().replace(LIST_MARKER, "")) }
            .filter { it.isNotBlank() }
            .take(15)
            .joinToString("\n")
            .take(360)

        val checklist = body.mapNotNull { line ->
            val match = CHECKLIST.matchEntire(line) ?: return@mapNotNull null
            MarkdownChecklistItem(
                text = match.groupValues[2],
                checked = match.groupValues[1].equals("x", ignoreCase = true),
            )
        }

        val links = WIKI_REFERENCE.findAll(text)
            .mapNotNull { match ->
                // 别名与标题锚点不属于目标笔记，索引只需要可匹配的 path
                match.groupValues[2]
                    .substringBefore('|')
                    .substringBefore('#')
                    .trim()
                    .takeIf { it.isNotEmpty() }
                    ?.let { MarkdownLink(target = it, embedded = match.groupValues[1].isNotEmpty()) }
            }
            .toList()
        val firstImageReference = MARKDOWN_IMAGE.find(text)?.groupValues?.get(1)
            ?: WIKI_IMAGE.find(text)?.groupValues?.get(1)

        return MarkdownMetadata(
            title = extractTitle(body, fallbackTitle),
            excerpt = excerpt,
            tags = TAG.findAll(text).map { it.groupValues[1] }.toSet(),
            checklist = checklist,
            links = links,
            firstImageReference = firstImageReference,
        )
    }

    /** 摘要只做纯文本，渲染交给 Markdown 渲染器，这里不去猜样式 */
    private fun stripInlineMarkdown(line: String): String = line
        .replace(WIKI_IMAGE, "")
        .replace(WIKI_LINK, "$1")
        .replace(MARKDOWN_IMAGE, "")
        .replace(MARKDOWN_LINK, "$1")
        .replace(EMPHASIS, "")
        .replace(CODE_SPAN, "$1")

    private fun bodyLines(text: String): List<String> {
        val lines = text.lines()
        if (lines.firstOrNull()?.trim() != "---") return lines
        val end = lines.drop(1).indexOfFirst { it.trim() == "---" }
        return if (end >= 0) lines.drop(end + 2) else lines
    }

    private fun extractTitle(lines: List<String>, fallback: String): String =
        lines.firstOrNull { it.startsWith("# ") }
            ?.removePrefix("# ")?.trim()?.takeIf { it.isNotBlank() }
            ?: fallback

    private companion object {
        val LIST_MARKER = Regex("""^[-*]\s+""")
        val HEADING = Regex("""^#{1,6}\s+.+""")
        val CHECKLIST = Regex("""^\s*[-*+]\s+\[([ xX])\]\s+(.+)$""")
        val TAG = Regex("""(?<![\p{L}\p{N}])#([\p{L}\p{N}_/-]+)""")
        val WIKI_REFERENCE = Regex("""(!?)\[\[([^]]+)]]""")
        val MARKDOWN_IMAGE = Regex("""!\[[^]]*]\(([^)]+)\)""")
        val WIKI_IMAGE = Regex("""!\[\[([^]]+)]]""")
        val WIKI_LINK = Regex("""(?<!!)\[\[([^]]+)]]""")
        val MARKDOWN_LINK = Regex("""\[([^]]*)]\(([^)]*)\)""")
        val EMPHASIS = Regex("""\*\*|\*|__|~~""")
        val CODE_SPAN = Regex("""`([^`]*)`""")
    }
}
