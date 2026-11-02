package com.example.looma.data.index

import com.example.looma.domain.model.NotePath

internal data class ScanPlan(
    val toRead: List<NotePath>,
    val kept: List<IndexedNote>,
    val removed: List<NotePath>,
)

/**
 * 把“索引里有什么”和“磁盘上有什么”合成这一轮要做的事。
 *
 * 指纹变化是唯一的失效依据：只看 mtime 会漏掉同毫秒内的写入，
 * 所以 [planScan] 用大小 + mtime 的组合比较。
 */
internal fun planScan(
    indexed: List<IndexedNote>,
    onDisk: Map<NotePath, FileFingerprint>,
): ScanPlan {
    val indexedByPath = indexed.associateBy { it.id.path }
    val toRead = onDisk.entries
        .filter { (path, fingerprint) -> indexedByPath[path]?.fingerprint != fingerprint }
        .map { it.key }
    val kept = indexed.filter { note -> onDisk[note.id.path] == note.fingerprint }
    val removed = indexed
        .filter { it.id.path !in onDisk }
        .map { it.id.path }
    return ScanPlan(toRead = toRead, kept = kept, removed = removed)
}
