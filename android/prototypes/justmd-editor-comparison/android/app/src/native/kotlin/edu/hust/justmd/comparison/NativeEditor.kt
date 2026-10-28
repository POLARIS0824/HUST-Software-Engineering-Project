@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package edu.hust.justmd.comparison

import android.content.Context
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun createFlavorEditorPort(context: Context): EditorPort = NativeEditorPort()

private class NativeNote(val id: String, source: String) {
    val field = TextFieldState(initialText = normalizedSource(source))
    var observed: DraftSnapshot? = null

    fun snapshot(): DraftSnapshot {
        val text = field.text.toString()
        val previous = observed
        return DraftSnapshot(
            noteId = id,
            text = text,
            selection = Utf16Selection(field.selection.start, field.selection.end),
            revision = (previous?.revision ?: 0L) + if (previous != null && previous.text != text) 1 else 0,
            canUndo = field.undoState.canUndo,
            canRedo = field.undoState.canRedo,
        ).also { observed = it }
    }
}

private class NativeEditorPort : EditorPort {
    private val notes = mutableMapOf<String, NativeNote>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableActive = MutableStateFlow<DraftSnapshot?>(null)
    override val active = mutableActive.asStateFlow()
    override val ready = MutableStateFlow(true).asStateFlow()
    var selected by mutableStateOf<NativeNote?>(null)
        private set

    override suspend fun selectNote(note: NoteDocument): DraftSnapshot = withContext(Dispatchers.Main.immediate) {
        val session = notes.getOrPut(note.id) {
            NativeNote(note.id, note.markdown).also { created ->
                scope.launch {
                    snapshotFlow {
                        DraftSnapshot(created.id, created.field.text.toString(),
                            Utf16Selection(created.field.selection.start, created.field.selection.end),
                            0, created.field.undoState.canUndo, created.field.undoState.canRedo)
                    }.collect {
                        val snapshot = created.snapshot()
                        if (selected === created) mutableActive.value = snapshot
                    }
                }
            }
        }
        selected = session
        publish(session)
    }

    override suspend fun command(command: EditorCommand): DraftSnapshot = withContext(Dispatchers.Main.immediate) {
        val session = checkNotNull(selected) { "Select a note before editing" }
        val field = session.field
        when (command) {
            EditorCommand.Undo -> field.undoState.undo()
            EditorCommand.Redo -> field.undoState.redo()
            is EditorCommand.Select -> {
                requireSelection(field.text.toString(), Utf16Selection(command.anchor, command.head))
                field.edit { selection = TextRange(command.anchor, command.head) }
            }
            EditorCommand.Heading -> field.edit {
                val start = selection.min
                val lineStart = originalText.toString().lastIndexOf('\n', start - 1) + 1
                val before = selection
                replace(lineStart, lineStart, "## ")
                selection = TextRange(before.start + 3, before.end + 3)
            }
            EditorCommand.Bold, EditorCommand.Link -> field.edit {
                val start = selection.min
                val end = selection.max
                val inner = if (start == end) {
                    if (command == EditorCommand.Bold) "粗体" else "链接"
                } else originalText.subSequence(start, end).toString()
                val prefix = if (command == EditorCommand.Bold) "**" else "["
                val suffix = if (command == EditorCommand.Bold) "**" else "](https://example.com)"
                val reversed = selection.reversed
                replace(start, end, prefix + inner + suffix)
                val innerStart = start + prefix.length
                val innerEnd = innerStart + inner.length
                selection = if (reversed) TextRange(innerEnd, innerStart) else TextRange(innerStart, innerEnd)
            }
            is EditorCommand.Insert -> field.edit {
                val start = selection.min
                replace(start, selection.max, command.text)
                selection = TextRange(start + command.text.length)
            }
        }
        publish(session)
    }

    override suspend fun snapshotForRead(): DraftSnapshot = withContext(Dispatchers.Main.immediate) {
        publish(checkNotNull(selected) { "Select a note before reading" })
    }

    private fun publish(note: NativeNote): DraftSnapshot = note.snapshot().also { mutableActive.value = it }

    override fun close() {
        scope.cancel()
        notes.clear()
        selected = null
        mutableActive.value = null
    }
}

@Composable
fun FlavorEditor(port: EditorPort, colors: ColorScheme, modifier: Modifier = Modifier, visible: Boolean = true) {
    val session = (port as NativeEditorPort).selected ?: return
    CompositionLocalProvider(LocalTextSelectionColors provides TextSelectionColors(
        handleColor = colors.primary,
        backgroundColor = colors.secondaryContainer,
    )) {
        BasicTextField(
            state = session.field,
            modifier = modifier.padding(20.dp),
            textStyle = TextStyle(color = colors.onSurface, fontFamily = FontFamily.Monospace,
                fontSize = 16.sp, lineHeight = 25.sp),
            cursorBrush = SolidColor(colors.primary),
        )
    }
}
