package com.example.looma.data.note

/** 批量回收站操作的结果：部分失败也要如实报数 */
data class TrashBulkOutcome(val succeeded: Int, val failed: Int) {
    val isFullSuccess: Boolean get() = failed == 0
    val total: Int get() = succeeded + failed
}
