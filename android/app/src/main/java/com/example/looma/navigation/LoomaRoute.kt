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
        /** 刚从首页新建：进来先落在标题上，什么都没写就离开时按设置丢弃 */
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
