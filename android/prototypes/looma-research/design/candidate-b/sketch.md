# Candidate B: one experiment session

## Usage (caller's view)

Launch the disposable Android app. Search the fixture notes, open one, inspect its backlinks, and switch among Obsidian, Apple Notes, and M3 Expressive. The selected note and query stay put while only presentation changes. Capture one real Android screenshot per style with the same note open.

```kotlin
val session = remember { ExperimentSession(FixtureNotes.all) }
ResearchScreen(session.snapshot(), onAction = session::dispatch)
session.dispatch(Action.SelectStyle(Style.AppleNotes)) // same notes and selection
session.dispatch(Action.RunEditorProbe(noteId))        // yields typed fidelity evidence
session.dispatch(Action.RunStorageProbes)              // yields SQLite and file evidence
```

## Problem

This is a throwaway feasibility probe, not a note app. It must separate attractive screenshots from evidence that offline Vditor, bundled SQLite, and app-owned file replacement work on Android. The generated Compose app is empty; no production data or compatibility boundary exists.

## Shape

```kotlin
@JvmInline value class NoteId(val value: String)
data class Note(val id: NoteId, val title: String, val folder: String,
                val tags: Set<String>, val markdown: String)
enum class Style { Obsidian, AppleNotes, M3Expressive }
enum class Destination { List, Note, Evidence }
data class UiState(val notes: List<Note>, val query: String, val selected: NoteId?,
                   val style: Style, val destination: Destination,
                   val evidence: List<ProbeEvidence>)
sealed interface Action {
  data class Search(val query: String) : Action
  data class Open(val id: NoteId) : Action
  data class SelectStyle(val style: Style) : Action
  data class GoTo(val destination: Destination) : Action
  data class RunEditorProbe(val id: NoteId) : Action
  data object RunStorageProbes : Action
}
sealed interface ProbeEvidence { val observedAt: Instant
  data class Editor(val input: String, val firstRoundTrip: String,
                    val adjacentEditRoundTrip: String, val bundleHash: String,
                    override val observedAt: Instant) : ProbeEvidence
  data class Sqlite(val version: String, val query: String, val matches: List<NoteId>,
                    val error: String?, override val observedAt: Instant) : ProbeEvidence
  data class FileReplace(val before: String, val after: String?,
                         val replaceCompleted: Boolean, val parentSync: SyncOutcome,
                         override val observedAt: Instant) : ProbeEvidence
}
enum class SyncOutcome { Completed, Unsupported, Failed }
class ExperimentSession(fixtures: List<Note>) {
  fun snapshot(): UiState = TODO("not implemented")
  fun dispatch(action: Action) = TODO("not implemented")
}
@Composable fun ResearchScreen(state: UiState, onAction: (Action) -> Unit) = TODO("not implemented")
```

`ExperimentSession` owns selection, query, style, and append-only evidence. It receives one immutable `FixtureNotes.all`; search and backlinks derive from those notes. `ResearchScreen` renders a compact style-specific shell from the same `UiState`; styles have no repository or copy of note content. Evidence is displayed on its own destination and never converted to a save indicator.

`EditorProbe` owns the pinned Vditor bundle, `WebViewAssetLoader`, JS bridge, and the known/unknown-syntax corpus. Its one `suspend fun run(note: Note): ProbeEvidence.Editor` loads offline IR, sets Markdown, reads it back, inserts an adjacent edit, and reads it back again. It records raw outputs and bundle hash. The bridge accepts only fixed probe commands and is scoped to the local asset origin.

`StorageProbe` owns a disposable Room 3.0.3 database with bundled SQLite 2.7.1 and an app-owned temporary directory. Its one `suspend fun run(fixtures: List<Note>): List<ProbeEvidence>` inserts the fixture notes, runs FTS5 trigram `MATCH` and literal short-query checks, then replaces an existing file and attempts parent directory sync. It records SQL errors and sync outcome without inferring speed or power-loss durability. Room/KSP/SQLiteDriver wiring stays here.

Boundary validation rejects duplicate or blank note IDs when constructing the session and verifies the pinned asset hash before an editor probe. Probe failures become typed evidence; they do not erase prior results. Internal UI projection is pure and derived from fixtures plus session state. The public surface is `snapshot` and `dispatch`; dependency and protocol details remain behind the probes.

## Tradeoffs accepted

- We accept an in-memory session and lost results on process death in exchange for a smaller disposable experiment. Captured artifacts live outside the app.
- We accept a tiny bridge and WebView inside the same APK in exchange for testing actual Android integration and offline loading.
- We accept sequential probe runs in exchange for simple evidence ordering; there is no benchmark claim.

## Alternatives considered

- Three independently themed apps expose fixture synchronization and comparison work to callers, so they lose despite simpler individual screens.
- A repository/ViewModel/use-case hierarchy exposes extra pass-through interfaces for an experiment whose only mutable owner is the session.
- A browser-only mock hides Android WebView, Room, and SQLite behavior, so it cannot answer the requested feasibility questions.

## Next implementation step

Build the fixture-backed Compose shell and capture the three same-state Android screenshots before adding technical probes.
