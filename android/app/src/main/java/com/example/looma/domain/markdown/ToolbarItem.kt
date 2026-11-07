package com.example.looma.domain.markdown

enum class ToolbarItem(val id: String) {
    Undo("undo"),
    Redo("redo"),
    WikiLink("wikilink"),
    InsertImage("image"),
    InsertFile("file"),
    Bold("bold"),
    Italic("italic"),
    Strikethrough("strikethrough"),
    Highlight("highlight"),
    Link("link"),
    InlineCode("inline_code"),
    Heading1("h1"),
    Heading2("h2"),
    Heading3("h3"),
    BulletList("bullet_list"),
    NumberedList("numbered_list"),
    Checklist("checklist"),
    Quote("quote"),
    CodeBlock("code_block"),
    Table("table"),
    Divider("divider");

    companion object {
        val DEFAULT_ORDER = entries.toList()
        fun fromId(id: String): ToolbarItem? = entries.firstOrNull { it.id == id }
    }
}
