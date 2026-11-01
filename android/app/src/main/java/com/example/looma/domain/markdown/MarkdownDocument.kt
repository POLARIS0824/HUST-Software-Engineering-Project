package com.example.looma.domain.markdown

/** 正文里的一个复选框。range 指向原文中的 "[ ]" 或 "[x]"，长度固定三个字符 */
data class MarkdownCheckbox(
    val range: IntRange,
    val checked: Boolean,
)

/**
 * 一次解析的结果，渲染和写回共用。
 *
 * [source] 是文件原文，[body] 是去掉 frontmatter 的正文，[bodyStart] 是正文在原文里的起点。
 * [checkboxes] 的位置都是原文坐标，写回文件时不用再换算。
 */
data class MarkdownDocument(
    val source: String,
    val body: String,
    val bodyStart: Int,
    val title: String,
    val checkboxes: List<MarkdownCheckbox>,
)

/**
 * 把某个复选框写成指定状态，返回新的原文。
 * 位置对不上，或那里并不是一个复选框标记时返回 null，调用方据此放弃写回。
 */
fun MarkdownDocument.withCheckboxChecked(position: Int, checked: Boolean): String? {
    val checkbox = checkboxes.firstOrNull { it.range.first == position } ?: return null
    val start = checkbox.range.first
    val end = start + MARKER_LENGTH
    if (end > source.length) return null
    val marker = source.substring(start, end)
    if (marker != UNCHECKED && marker != CHECKED) return null
    return source.replaceRange(start, end, if (checked) CHECKED else UNCHECKED)
}

internal const val MARKER_LENGTH = 3
internal const val UNCHECKED = "[ ]"
internal const val CHECKED = "[x]"
