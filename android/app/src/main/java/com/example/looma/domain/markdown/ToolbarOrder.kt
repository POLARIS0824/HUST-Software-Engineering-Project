package com.example.looma.domain.markdown

object ToolbarOrder {

    fun encode(order: List<ToolbarItem>): String {
        return order.joinToString(",") { it.id }
    }

    fun restore(raw: String?): List<ToolbarItem> {
        if (raw.isNullOrBlank()) return ToolbarItem.DEFAULT_ORDER

        val existing = raw.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { ToolbarItem.fromId(it) }
            .distinct()

        val missing = ToolbarItem.DEFAULT_ORDER.filter { it !in existing }

        val merged = existing + missing

        return merged.ifEmpty { ToolbarItem.DEFAULT_ORDER }
    }
}
