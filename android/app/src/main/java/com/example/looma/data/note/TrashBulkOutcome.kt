package com.example.looma.data.note

data class TrashBulkOutcome(val succeeded: Int, val failed: Int) {
    val isFullSuccess: Boolean get() = failed == 0
    val total: Int get() = succeeded + failed
}
