package com.example.looma.domain.markdown

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolbarOrderTest {
    @Test
    fun `custom order survives serialization`() {
        val order = ToolbarItem.DEFAULT_ORDER.reversed()
        assertEquals(order, ToolbarOrder.restore(ToolbarOrder.encode(order)))
    }

    @Test
    fun `restoring old or invalid data preserves known order and adds missing items once`() {
        val restored = ToolbarOrder.restore("bold,unknown,undo,bold")
        assertEquals(listOf(ToolbarItem.Bold, ToolbarItem.Undo), restored.take(2))
        assertEquals(ToolbarItem.DEFAULT_ORDER.toSet(), restored.toSet())
        assertEquals(ToolbarItem.DEFAULT_ORDER.size, restored.size)
        assertEquals(ToolbarItem.DEFAULT_ORDER, ToolbarOrder.restore(null))
    }
}
