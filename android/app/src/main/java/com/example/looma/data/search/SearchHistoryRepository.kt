package com.example.looma.data.search

import com.example.looma.data.index.SearchHistoryEntity
import com.example.looma.data.vault.VaultRepository
import java.util.Locale

internal fun normalizeSearchQuery(raw: String): String? =
    raw.trim()
        .replace(Regex("\\s+"), " ")
        .lowercase(Locale.ROOT)
        .takeIf { it.isNotEmpty() }
        ?.take(120)

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
