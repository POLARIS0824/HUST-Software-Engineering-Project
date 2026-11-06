package com.example.looma.domain.markdown

data class MarkdownCheckbox(
    val range: IntRange,
    val checked: Boolean,
)

data class MarkdownDocument(
    val source: String,
    val body: String,
    val bodyStart: Int,
    val title: String,
    val checkboxes: List<MarkdownCheckbox>,
    val links: List<MarkdownLinkSpan> = emptyList(),
)

fun MarkdownDocument.withCheckboxChecked(position: Int, checked: Boolean): String? {
    val checkbox = checkboxes.firstOrNull { it.range.first == position } ?: return null
    val start = checkbox.range.first
    val end = start + MARKER_LENGTH
    if (end > source.length) return null
    val marker = source.substring(start, end)
    if (!marker.equals(UNCHECKED) && !marker.equals(CHECKED, ignoreCase = true)) return null
    return source.replaceRange(start, end, if (checked) CHECKED else UNCHECKED)
}

internal const val MARKER_LENGTH = 3
internal const val UNCHECKED = "[ ]"
internal const val CHECKED = "[x]"
