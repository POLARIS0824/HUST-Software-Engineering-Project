package com.example.looma.data.search

import com.example.looma.data.index.SearchHistoryEntity
import com.example.looma.data.vault.VaultRepository
import java.util.Locale

/**
 * 搜索历史的归一化键。
 *
 * 大小写与连续空白都不该产生第二条记录，所以键统一折叠；
 * 空串不记（用户只是清空输入框），长度上限避免异常输入撑爆主键。
 */
internal fun normalizeSearchQuery(raw: String): String? =
    raw.trim()
        .replace(Regex("\\s+"), " ")
        .lowercase(Locale.ROOT)
        .takeIf { it.isNotEmpty() }
        ?.take(120)

/** 最近搜索；按 vault 隔离，切换笔记库不会串味 */
class SearchHistoryRepository internal constructor(
    private val vaultRepository: VaultRepository,
    private val dao: SearchHistoryDao,
) {

    suspend fun recent(limit: Int = DEFAULT_LIMIT): List<String> =
        dao.recent(vaultRepository.requireCurrentVault().id.value, limit)

    suspend fun record(query: String) {
        val key = normalizeSearchQuery(query) ?: return
        val vaultId = vaultRepository.requireCurrentVault().id.value
        dao.upsert(
            SearchHistoryEntity(
                vaultId = vaultId,
                key = key,
                // 展示仍用用户输入的大小写，键才需要归一化
                query = query.trim().take(MAX_QUERY_LENGTH),
                lastUsedAt = System.currentTimeMillis(),
            ),
        )
        dao.prune(vaultId, MAX_ENTRIES)
    }

    suspend fun clear() {
        dao.clear(vaultRepository.requireCurrentVault().id.value)
    }

    private companion object {
        const val DEFAULT_LIMIT = 12
        const val MAX_ENTRIES = 20
        const val MAX_QUERY_LENGTH = 120
    }
}
