package com.example.looma.data.search

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.example.looma.data.index.SearchHistoryEntity

@Dao
internal interface SearchHistoryDao {

    @Query("SELECT query FROM search_history WHERE vaultId = :vaultId ORDER BY lastUsedAt DESC LIMIT :limit")
    suspend fun recent(vaultId: String, limit: Int): List<String>

    @Upsert
    suspend fun upsert(entry: SearchHistoryEntity)

    /**
     * 只保留最近的 [keep] 条。
     *
     * 直接写 `LIMIT :keep`：prune 与 recent 用同一套排序，
     * 两处各算一遍容易在时间戳相同时出现不一致。
     */
    @Query(
        """
        DELETE FROM search_history
        WHERE vaultId = :vaultId AND key NOT IN (
            SELECT key FROM search_history WHERE vaultId = :vaultId ORDER BY lastUsedAt DESC LIMIT :keep
        )
        """,
    )
    suspend fun prune(vaultId: String, keep: Int)

    @Query("DELETE FROM search_history WHERE vaultId = :vaultId")
    suspend fun clear(vaultId: String)
}
