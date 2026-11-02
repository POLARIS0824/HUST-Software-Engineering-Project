package com.example.looma.navigation

import androidx.navigation3.runtime.NavKey
import com.example.looma.domain.model.NoteId
import kotlinx.serialization.Serializable

@Serializable
sealed interface LoomaRoute : NavKey {
    @Serializable
    data object VaultSetup : LoomaRoute

    @Serializable
    data object Home : LoomaRoute

    /** 一篇笔记只有一个目的地，阅读和编辑是它的两种模式 */
    @Serializable
    data class Note(
        val vaultId: String,
        val relativePath: String,
    ) : LoomaRoute {
        companion object {
            fun from(id: NoteId): Note = Note(
                vaultId = id.vaultId.value,
                relativePath = id.path.relativePath,
            )
        }
    }
}
