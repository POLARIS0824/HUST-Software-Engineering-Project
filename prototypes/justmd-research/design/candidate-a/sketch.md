# Usage first

Open the native fixture list, search, open a note, follow a backlink, then switch styles.
The selected note and its session edits survive style switches because style only changes rendering.
Run probes separately, then capture actual emulator list/editor screens for each style.

```kotlin
val session = remember { ResearchSession(Fixtures.notes) }
ResearchApp(session.state, session::dispatch)
session.dispatch(Action.SelectStyle(Style.AppleNotes))
session.dispatch(Action.Open(NoteId("syntax-lab")))
val report = ProbeRunner(context).run(Fixtures.probeCases) // suspend; not implemented
```

# Problem

Determine whether three native shells and an offline Vditor editor deserve further work.
This is a disposable Android experiment with one dataset, independent technical evidence,
Room 3.0.3, bundled SQLite 2.7.1, and no production save or durability contract.

# Shape

```kotlin
@JvmInline value class NoteId(val value: String)
data class Note(val id: NoteId, val title: String, val folder: String,
                val tags: Set<String>, val markdown: String)
enum class Style { Obsidian, AppleNotes, Expressive }
sealed interface Destination { data object List : Destination
  data class Editor(val noteId: NoteId) : Destination }
data class ResearchState(val notes: Map<NoteId, Note>, val drafts: Map<NoteId, String>,
  val style: Style, val destination: Destination, val query: String)
sealed interface Action { /* SelectStyle, Search, Open, Back, ReplaceDraft */ }
class ResearchSession(notes: List<Note>) {
  val state: StateFlow<ResearchState> // not implemented
  fun dispatch(action: Action): Unit = TODO("not implemented")
}
@Composable fun ResearchApp(state: StateFlow<ResearchState>, dispatch: (Action) -> Unit)
@Composable fun LocalEditor(noteId: NoteId, markdown: String, onDraft: (String) -> Unit)
data class FidelityCase(val id: String, val original: String, val adjacentEdit: String)
sealed interface ProbeResult {
  data class Fidelity(val caseId: String, val input: String, val untouched: String,
    val edited: String, val bundleHash: String) : ProbeResult
  data class Search(val query: String, val literalIds: List<NoteId>,
    val trigramIds: List<NoteId>?, val reason: String?) : ProbeResult
  data class Replace(val replaced: Boolean, val readback: String?,
    val parentSync: String) : ProbeResult
  data class Failure(val probe: String, val detail: String) : ProbeResult
}
class ProbeRunner(context: Context) {
  suspend fun run(cases: List<FidelityCase>): List<ProbeResult> = TODO("not implemented")
}
```

`ResearchApp.kt` owns Compose list/editor chrome, derived search/backlinks, and three style palettes.
`ResearchSession.kt` owns all session mutations. Styles never create stores or databases.
`LocalEditor.kt` owns one retained WebView, pinned local assets, and a validated message adapter.
Load through WebViewAssetLoader; block network and unrelated navigation; keep bridge messages local.
`ProbeRunner.kt` owns isolated editor cases, the Room schema/driver, and disposable replacement files.
Its editor cases use the same adapter and bundle as the visible editor, never the live draft.
Probe output records raw strings and exact diffs, Android/WebView identity, dependency versions,
SQL and returned IDs, and errors. Unknown syntax is measured both untouched and after an adjacent edit.
Trigram MATCH is skipped with a reason below three code points; literal search escapes metacharacters.
Replacement probes replace an existing file, read back, and report directory-sync support separately.
The native UI says “Session draft” and “Probe passed/failed”; it never says “Saved”.

# Tradeoffs and alternatives

Accept one small state holder and four files in exchange for easy deletion and short call chains.
Derive backlinks and fixture search directly rather than persisting redundant indexes in the UI path.
The probe boundary hides WebView wire messages, SQL setup, and file operations behind one run call.
A database-backed whole-app repository hides persistence but makes visual editing imply an unearned save contract.
A WebView-only shell hides layout machinery but cannot answer native Compose interaction and screenshot questions.
A generic plugin/probe registry exposes lifecycle policy to every caller without helping this fixed experiment.

# Decision and reconciliation

Candidate proposal only. Synthesis belongs to the orchestrator; implementation deviations remain unaccepted.
First build one fixture list and editor, then run the real Android evidence path before multiplying styles.
Capture list/editor screenshots with identical note/query/scroll and device identity for all three styles.
No runtime results are claimed by this sketch, and no timings or power-loss conclusions are in scope.
