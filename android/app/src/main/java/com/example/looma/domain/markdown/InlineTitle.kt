package com.example.looma.domain.markdown

data class InlineTitle(val text: String)

fun inlineTitleFor(fileName: String, heading: String?, show: Boolean): InlineTitle? {
    if (!show) return null
    val name = fileName.trim()
    if (name.isEmpty()) return null
    if (heading != null && name.equals(heading.trim(), ignoreCase = true)) return null
    return InlineTitle(name)
}
