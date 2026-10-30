# Candidate B: two flavor APKs, one editing contract

## Usage first

Install `cmDebug` and `nativeDebug` side by side. Open the same fixture note in each. The B note list, paper editor body, Material Expressive controls, Dynamic Color, and native Read pane are shared; the source editor is the sole flavor choice.

```kotlin
// Common shell, on note tap. selectNote finishes the previous note before activation.
scope.launch { editor.selectNote(fixtureNotes[id]); mode = Edit }
// Common toolbar. The active engine applies the command at its current selection.
scope.launch { editor.command(EditorCommand.Bold) }
// Common Edit -> Read. Display only the acknowledged source returned here.
scope.launch { readSource = editor.snapshotForRead().text; mode = Read }
```

The shell never edits `text`, offsets, or undo history. Returning from Read reveals the still-mounted editor. A note switch awaits the previous engine's final snapshot. The read renderer receives source and cannot write it back.

## Shape

```kotlin
@JvmInline value class NoteId(val value: String)
data class NoteDocument(val id: NoteId, val markdown: String) // LF fixture source
data class Utf16Selection(val anchor: Int, val head: Int) // direction retained
data class DraftSnapshot(
    val noteId: NoteId, val text: String, val selection: Utf16Selection,
    val revision: Long
)
enum class EditorCommand { Bold, Heading, Link, Undo, Redo }
interface EditorPort {
    val active: StateFlow<DraftSnapshot?> // observation only; engine owns mutations
    suspend fun selectNote(note: NoteDocument): DraftSnapshot
    suspend fun command(command: EditorCommand): DraftSnapshot
    suspend fun snapshotForRead(): DraftSnapshot
}
@Composable fun FlavorEditor(port: EditorPort, colors: EditorColors, modifier: Modifier)
fun createFlavorEditorPort(): EditorPort // one definition in each flavor source set
@Composable fun NativeRead(markdown: String, modifier: Modifier)
```

`app/src/main` owns `MainActivity`, B layout, `EditorPort`, fixture data, Dynamic Color scheme, native Read renderer, and verification journey. `app/src/cm` owns only `CmEditorPort`, its WebView composable, pinned CodeMirror bundle and local asset page. `app/src/native` owns only `NativeEditorPort` and state-based `BasicTextField`. Distinct `applicationIdSuffix` values allow side-by-side installation. No repository, persistence, DI, editor IR, or production note model is needed.

One port instance lives for the activity session. Its map has one mutable editor state per `NoteId`; that engine state owns text, UTF-16 selection, and undo. `active` is a derived, read-only snapshot for chrome and tests, not a second writer. `selectNote` seeds a state once from `NoteDocument`; reopening uses its existing state. Revision increases only on source changes. An idempotent repeated selection of the active note never replaces state.

For `native`, each note keeps its `TextFieldState`; `BasicTextField` edits it, toolbar formatting uses one `state.edit` transaction, and `undoState` handles Undo/Redo. The port reads `state.text` and directional selection for snapshots. The renderer version must be resolved against the project's Kotlin/Compose toolchain; the researched M3 renderer has a suitable native `Markdown(content)` surface, but compilation is not yet proven.

For `cm`, each note keeps one CodeMirror `EditorState` in a mounted WebView. `EditorView.setState` activates it; the old state is captured first. Toolbar commands execute in JS against the current selection, with one history event. The appassets bridge accepts only the pinned local origin, validates note ID, activation generation, monotone sequence, bounded text, and UTF-16 endpoints. `selectNote`, `command`, and `snapshotForRead` await matching acknowledgements. A stale command is rejected and resynchronized; late snapshots cannot overwrite the active note. Read mode blurs/suspends input, waits for composition to finish and a final snapshot, then covers the mounted editor with native Read. Missing bridge capability fails visibly. Theme changes reconfigure CSS variables without rebuilding `EditorState`.

The public interface hides bridge transport and `TextFieldState` details. The common shell has three actions and one observation point; it cannot accidentally mutate source from the reader. Boundary checks belong inside the CM adapter. Native per-note state and the single mounted CM view avoid reconstructing undo history from snapshots.

## Comparison contract

Use Android Dynamic Color on API 31+ and a declared fallback below 31. Feed one resolved Material scheme into both the Compose shell and CM CSS roles. Keep B's geometry and typography; use expressive native controls and motion in both flavors. Both use the same native Read component, so observed differences mainly concern editing.

On the existing emulator, run identical fixture journeys in each APK: type and paste source; select text including an emoji surrogate pair; Bold, Undo, Redo; enter Read and return; navigate away and back; change system theme; rapidly switch notes; inspect exact source, selection direction, note ID, and history at each step. Inspect the native APK for WebView/JS assets. Record APK size and behavior separately; a successful build alone proves neither. Chinese IME composition, process death, durable saves, and 5,000-note performance remain outside this run.

## Tradeoffs and alternatives

- We accept two launches and duplicated installation in exchange for a physically inspectable native artifact and independent startup/memory observations.
- We accept engine-private undo histories in exchange for preserving each editor's real editing semantics. The comparison never switches engines within a session.
- A runtime switch in one APK would simplify side-by-side interaction, but exposes engine switching and history transfer to the shell and cannot establish a WebView-free native package.
- A shared canonical mutable draft plus engine-local text would create two writers. The port instead exposes only acknowledged snapshots.

The first implementation step is the common B shell and contract with a trivial fixture reader, followed by one flavor at a time. No implementation or Android behavior is claimed by this sketch.
