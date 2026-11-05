package com.example.looma.domain.markdown

object ToolbarOrder {

    /**
     * 序列化：将工具栏列表转为逗号分隔的字符串
     */
    fun encode(order: List<ToolbarItem>): String {
        return order.joinToString(",") { it.id }
    }

    fun restore(raw: String?): List<ToolbarItem> {
        if (raw.isNullOrBlank()) return ToolbarItem.DEFAULT_ORDER

        // 1. 拆分、去除空格、映射并过滤无效 ID，随后去重
        val existing = raw.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { ToolbarItem.fromId(it) }
            .distinct()

        // 2. 找出当前配置里缺少的默认项（例如未来版本新增的按钮）
        val missing = ToolbarItem.DEFAULT_ORDER.filter { it !in existing }

        // 3. 拼接补齐
        val merged = existing + missing

        // 4. 最终兜底防空
        return merged.ifEmpty { ToolbarItem.DEFAULT_ORDER }
    }
}