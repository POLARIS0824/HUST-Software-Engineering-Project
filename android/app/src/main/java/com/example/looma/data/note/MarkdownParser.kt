package com.example.looma.data.note

/** Markdown 正文中的任务项，表示内容语义，而不是 UI 状态 */
data class MarkdownChecklistItem(
    val text: String,
    val checked: Boolean,
)

/** 纯文本解析结果，不包含文件访问或页面展示状态 */
data class MarkdownMetadata(
    val title: String,
    val excerpt: String,
    val tags: Set<String>,
    val checklist: List<MarkdownChecklistItem>,
    val firstWikiLink: String?,
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
            .map { it.trim().replace(LIST_MARKER, "") }
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

        val firstWikiLink = WIKI_LINK.find(text)?.groupValues?.get(1)
            ?.substringBefore('|')?.substringBefore('#')?.take(40)
        val firstImageReference = MARKDOWN_IMAGE.find(text)?.groupValues?.get(1)
            ?: WIKI_IMAGE.find(text)?.groupValues?.get(1)

        return MarkdownMetadata(
            title = extractTitle(body, fallbackTitle),
            excerpt = excerpt,
            tags = TAG.findAll(text).map { it.groupValues[1] }.toSet(),
            checklist = checklist,
            firstWikiLink = firstWikiLink,
            firstImageReference = firstImageReference,
        )
    }

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
        val MARKDOWN_IMAGE = Regex("""!\[[^]]*]\(([^)]+)\)""")
        val WIKI_IMAGE = Regex("""!\[\[([^]]+)]]""")
        val WIKI_LINK = Regex("""(?<!!)\[\[([^]]+)]]""")
    }
}
