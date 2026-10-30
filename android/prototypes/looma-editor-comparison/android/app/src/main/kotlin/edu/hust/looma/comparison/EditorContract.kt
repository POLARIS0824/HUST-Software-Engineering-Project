package edu.hust.looma.comparison

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.StateFlow

data class NoteDocument(val id: String, val markdown: String)
data class Utf16Selection(val anchor: Int, val head: Int)
data class DraftSnapshot(val noteId: String, val text: String, val selection: Utf16Selection,
    val revision: Long, val canUndo: Boolean, val canRedo: Boolean)

sealed interface EditorCommand {
    data object Bold : EditorCommand
    data object Heading : EditorCommand
    data object Link : EditorCommand
    data object Undo : EditorCommand
    data object Redo : EditorCommand
    data class Select(val anchor: Int, val head: Int) : EditorCommand
    data class Insert(val text: String) : EditorCommand
}

interface EditorPort {
    val active: StateFlow<DraftSnapshot?>
    val ready: StateFlow<Boolean>
    suspend fun selectNote(note: NoteDocument): DraftSnapshot
    suspend fun command(command: EditorCommand): DraftSnapshot
    suspend fun snapshotForRead(): DraftSnapshot
    fun close()
}

fun requireSelection(text: String, selection: Utf16Selection) {
    for (offset in listOf(selection.anchor, selection.head)) {
        require(offset in 0..text.length) { "Selection outside source" }
        require(offset == 0 || offset == text.length ||
            !(text[offset - 1].isHighSurrogate() && text[offset].isLowSurrogate())) { "Selection splits surrogate pair" }
    }
}

fun normalizedSource(source: String) = source.replace("\r\n", "\n").replace('\r', '\n')

data class ResearchNote(val id: String, val title: String, val folder: String, val excerpt: String,
    val markdown: String, val updated: String, val pinned: Boolean = false) {
    fun document() = NoteDocument(id, normalizedSource(markdown))
}
