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
    data class Editor(
        val vaultId: String,
        val relativePath: String,
    ) : LoomaRoute {
        companion object {
            fun from(id: NoteId): Editor = Editor(
                vaultId = id.vaultId.value,
                relativePath = id.path.relativePath,
            )
        }
    }
}