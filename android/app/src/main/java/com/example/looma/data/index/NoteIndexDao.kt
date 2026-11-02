package com.example.looma.data.index

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert

/**
 * 索引表的读写。
 *
 * `paths` 一律由调用方保证非空：SQLite 不接受空的 `IN ()`，
 * 所以空批次在 [RoomNoteIndex] 里就被跳过，而不是在这里拼一个恒假条件。
 */
@Dao
internal interface NoteIndexDao {

    @Query("SELECT * FROM indexed_note WHERE vaultId = :vaultId")
    suspend fun notes(vaultId: String): List<IndexedNoteEntity>

    @Query("SELECT * FROM note_tag WHERE vaultId = :vaultId ORDER BY notePath, ordinal")
    suspend fun tags(vaultId: String): List<NoteTagEntity>

    @Query("SELECT * FROM note_link WHERE vaultId = :vaultId ORDER BY notePath, ordinal")
    suspend fun links(vaultId: String): List<NoteLinkEntity>

    @Upsert
    suspend fun upsertNotes(notes: List<IndexedNoteEntity>)

    @Query("DELETE FROM note_tag WHERE vaultId = :vaultId AND notePath IN (:paths)")
    suspend fun deleteTags(vaultId: String, paths: List<String>)

    @Query("DELETE FROM note_link WHERE vaultId = :vaultId AND notePath IN (:paths)")
    suspend fun deleteLinks(vaultId: String, paths: List<String>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTags(tags: List<NoteTagEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLinks(links: List<NoteLinkEntity>)

    /** 子表靠外键级联删除，调用方不需要自己清理标签与链接 */
    @Query("DELETE FROM indexed_note WHERE vaultId = :vaultId AND path IN (:paths)")
    suspend fun deleteNotes(vaultId: String, paths: List<String>)
}
