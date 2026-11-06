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
