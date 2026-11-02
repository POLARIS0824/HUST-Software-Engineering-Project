package com.example.looma.data.index

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(tableName = "indexed_note", primaryKeys = ["vaultId", "path"])
internal data class IndexedNoteEntity(
    val vaultId: String,
    val path: String,
    val title: String,
    val excerpt: String,
    val snippet: String,
    val coverPath: String?,
    val modifiedAt: Long,
    val sizeBytes: Long,
    val indexedAt: Long,
)

@Entity(
    tableName = "note_tag",
    primaryKeys = ["vaultId", "notePath", "tag"],
    foreignKeys = [
        ForeignKey(
            entity = IndexedNoteEntity::class,
            parentColumns = ["vaultId", "path"],
            childColumns = ["vaultId", "notePath"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("vaultId", "notePath")],
)
internal data class NoteTagEntity(
    val vaultId: String,
    val notePath: String,
    val tag: String,
    val ordinal: Int,
)

@Entity(
    tableName = "note_link",
    primaryKeys = ["vaultId", "notePath", "ordinal"],
    foreignKeys = [
        ForeignKey(
            entity = IndexedNoteEntity::class,
            parentColumns = ["vaultId", "path"],
            childColumns = ["vaultId", "notePath"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("vaultId", "notePath")],
)
internal data class NoteLinkEntity(
    val vaultId: String,
    val notePath: String,
    val ordinal: Int,
    val target: String,
    val embedded: Boolean,
)

/** 搜索记录按 vault 隔离：切换笔记库后不会看到另一个库的查询 */
@Entity(tableName = "search_history", primaryKeys = ["vaultId", "key"])
internal data class SearchHistoryEntity(
    val vaultId: String,
    val key: String,
    val query: String,
    val lastUsedAt: Long,
)
