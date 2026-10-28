# Candidate A. One APK with a runtime editor switch

Open a fixture in B's paper layout, select Native or CodeMirror, edit source, then tap Read.
The same native reader, toolbar and note list stay in place when the editor changes.
The switch is an explicit comparison action. It transfers text and selection and resets that note's undo history.

```kotlin
val comparison = remember { ComparisonSession(fixtures) }
ComparisonScreen(comparison.ui, comparison::dispatch)
// Native Bold, then Undo use the same entry point as CodeMirror.
comparison.dispatch(Action.Format(Format.Bold))
// Read and implementation switches await the current editor's completed checkpoint.
comparison.dispatch(Action.Read)
comparison.dispatch(Action.UseEditor(EditorKind.CodeMirror))
```

```kotlin
enum class EditorKind { Native, CodeMirror }
data class Selection16(val anchor: Int, val head: Int)
data class DraftSnapshot(val text: String, val selection: Selection16, val revision: Long)
sealed interface Body {
  data class Editing(val noteId: NoteId, val kind: EditorKind) : Body
  data class Reading(val noteId: NoteId, val snapshot: DraftSnapshot) : Body
}
sealed interface Action { /* Open(id), Read, Edit, UseEditor(kind), Format(op), Undo, Redo */ }
class ComparisonSession(fixtures: List<NoteDocument>) {
  val ui: State<ComparisonUi> get() = error("not implemented")
  fun dispatch(action: Action): Unit = error("not implemented")
}
interface EditorController {
  suspend fun activate(note: NoteId, initial: DraftSnapshot): Unit
  suspend fun checkpoint(): DraftSnapshot
  suspend fun command(command: EditorCommand): Unit
  fun updateTheme(roles: EditorColors)
}
@Composable fun ComparisonScreen(ui: State<ComparisonUi>, onAction: (Action) -> Unit)
@Composable fun EditorBody(kind: EditorKind, controller: EditorController)
@Composable fun NativeRead(snapshot: DraftSnapshot)
```

`ComparisonSession.kt` owns note identity, source slots, mode and transitions. `ComparisonScreen.kt` owns B chrome and Dynamic Color.
`NativeEditor.kt` owns BasicTextField integration. `CodeMirrorEditor.kt` and its local JS bundle own WebView integration.
`NativeRead.kt` hosts one M3 Markdown renderer. No repository, database, production ViewModel or generic editor framework is added.

Each note has exactly one editor-owned live document slot and its undo stack. Native slots contain stable per-note TextFieldState.
CodeMirror slots are per-note EditorState objects inside one retained WebView. Never recreate either state during recomposition.
Session snapshots are acknowledged read-only observations, not a second writable document. Toolbar actions run against the live owner.
Opening another note checkpoints the old owner and activates that note's retained slot. Read overlays the mounted CM host and disables its input.
The native field may leave composition while its TextFieldState remains in the slot. Returning to Edit reuses that state and history.
An engine switch checkpoints, retires the selected note's old slot, and creates its new slot from text and directional UTF-16 selection.
Other notes retain their slots. A visible “Undo reset after editor switch” label prevents a false history-equivalence claim.

The session serializes toolbar actions and transitions. Pending actions disable transition controls; failures keep the current editor visible.
Checkpoint finishes or safely defers active IME composition, blurs input, and waits for final source acknowledgement before Read or navigation.
CM bridge details stay private. Validate appassets main-frame origin, session/note/generation, ordered sequence, size and selection bounds.
Ready precedes activation. Commands carry ID, generation and expected revision; stale commands are rejected with current state.
Missing web-message support or failed acknowledgement shows an unavailable/error state. It never silently advances to a stale reader.
Native formatting uses one TextFieldState.edit transaction and undoState; CM uses an isolated transaction and public undo/redo commands.
Read consumes only the completed snapshot. Rendering never writes back into source; remote resource loading and raw HTML execution are disabled.

Use Android dynamic light/dark schemes on API 31+, an explicit fallback below, and MaterialExpressiveTheme around the common B layout.
Pass the same resolved surface, foreground, primary, outline, selection and caret roles into CM CSS compartments without replacing its state.
Reuse the pinned CM lock and bundle only CM assets. Try native renderer 0.38.1 against Kotlin 2.2.21; resolve compatibility before upgrading.
The research supports separate native source/read rendering; it does not establish runtime IME correctness or renderer compilation.

Later acceptance drives actual Android typing, native Bold, Undo/Redo, Read/Edit, rapid note changes and reopen with exact source/selection checks.
Repeat with emoji ranges, trailing LF/tabs/unknown syntax, composition during navigation and theme changes; declare fixtures LF-only.
This single APK is cheap to install and compare side by side in time. It cannot prove a JS-free native APK or fair size/startup/memory isolation.
Native route inspection can prove its selected code path uses Compose editing and native rendering, while the APK still contains CM/WebView code.
No full IR, durable saves, process-death recovery, large-vault performance or real Chinese IME claim is included. No build or runtime result is claimed.
