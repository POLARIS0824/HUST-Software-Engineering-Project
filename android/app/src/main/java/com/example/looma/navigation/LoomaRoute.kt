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

    @Serializable
    data object Trash : LoomaRoute

    @Serializable
    data object Settings : LoomaRoute

    @Serializable
    data class Note(
        val vaultId: String,
        val relativePath: String,
        val isNew: Boolean = false,
    ) : LoomaRoute {
        companion object {
            fun from(id: NoteId, isNew: Boolean = false): Note = Note(
                vaultId = id.vaultId.value,
                relativePath = id.path.relativePath,
                isNew = isNew,
            )
        }
    }
}
