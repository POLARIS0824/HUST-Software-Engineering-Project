package com.example.looma.navigation

import androidx.navigation3.runtime.NavKey
import com.example.looma.domain.model.NoteId
import kotlinx.serialization.Serializable

@Serializable
sealed interface LoomaRoute : NavKey {
    /** 首次启动（或笔记库不可用）时的门禁页，只有这里可以没有笔记库 */
    @Serializable
    data object VaultSetup : LoomaRoute

    @Serializable
    data object Home : LoomaRoute

    @Serializable
    data object Trash : LoomaRoute

    @Serializable
    data object Settings : LoomaRoute

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
