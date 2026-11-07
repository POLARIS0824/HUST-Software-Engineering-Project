# Create, edit and read Markdown notes

Users create or open a note, edit its heading and body, format Markdown, switch to reading, follow links and return without losing content. Files on disk are the durable source of truth.

## Sub-features

- `note-create-save`: New note opens an editor; entered title/body persist after leaving and relaunch.
- `note-open`: Recent cards, Notes cards and search results each open the selected note in edit mode.
- `note-read-edit`: Read/Edit switches preserve content; read-mode checklists update Markdown.
- `note-format`: formatting actions, Undo/Redo and configurable toolbar order affect the body.
- `note-links`: Link a note inserts a wiki link; read mode resolves targets and shows backlinks; missing targets produce a message.
- `note-images`: existing local Markdown image references render in Read mode when the referenced image exists inside the vault.
- `note-recovery`: save failures/conflicts expose Retry save, Reload from disk, Overwrite and guarded leave; recovered drafts are shown.

## How to get to it (user POV)

- Home → New note (in the current folder).
- Tap a card in Recent, Notes/folder contents, or a search result.
- A resolved wiki link or a backlink opens its target; Back returns to the prior note.

## Driving it with control.py

Preconditions: connected empty Home, English UI, doctor passes. Create fixtures through New note; the baseline includes no wiki targets or image files.

- **Create.** `v tap --desc 'New note'`; `v ui`. On the editor the first `android.widget.EditText` is the title and the second is body. `v tap --class android.widget.EditText --index 0`; `v text 'Verification note'`; `v tap --class android.widget.EditText --index 1`; `v text 'Persistence marker #verify'`; `v capture note-typed`.
- **Read/save.** `v tap --desc Read`; `v assert --text 'Verification note'`; `v capture note-read`; `v tap --desc Back`; `v files note-saved`. Inspect the copied `.md`: it must contain `# Verification note` and `Persistence marker #verify`. The filename may remain an automatically assigned Untitled note filename; the heading is not a file rename.
- **Persistence/open entries.** `v relaunch`; `v ui`; tap `Verification note` with an explicit `--index` if both Recent and Notes show it. Require two EditText nodes with the saved values. Capture `note-reopened`. Return and repeat for the other card entry and the search-result entry; record each separately. `v tap --desc Read` shows the same heading/body; `v tap --desc Edit` returns to editing.
- **Formatting/undo.** Focus body field, tap `v tap --desc Bold`, type `v text formatted`, then `v tap --desc Undo` and `v tap --desc Redo` when enabled. Use `v ui` and horizontal toolbar scrolling as needed for hidden actions. Capture source and Read states, leave and `v files note-format`. Require the intended Markdown delimiters and rendered emphasis, not only a changed button state. For checklist input type `- [ ] Task`, toggle its actual checkbox in Read using the current hierarchy and verify `[x]` in the saved file.
- **Links.** First create a second note titled `Target note` through the same user path. In the first note focus body; `v tap --desc 'Link a note'`; `v tap --text 'Target note'`; capture insertion. Read and tap the visible target; verify its title, then its backlinks panel and return route. If the target is missing from the picker, report the missing precondition; do not replace the link journey with direct navigation. For a missing target, type a literal `[[Missing target]]` into body, Read, tap it and capture `No note named "Missing target"`.
- **Image boundary.** Supply a disposable image fixture in the run-owned vault, recording its origin and bytes; type `![Fixture](fixture.png)` into the body through `v text`, then `v tap --desc Read`. Capture and visually inspect the actual image; leave and `v files note-image` to prove the persisted reference and image bytes. Report the missing prerequisite if no image fixture was supplied. This is a rendering check, not an attachment-import journey: the current toolbar filters out Insert image/Insert file, and NoteScreen does not wire import handlers.
- **Recovery boundary.** Only in this disposable vault, use a separately documented external-file fixture change to simulate another editor, then type more in the UI. Verify the conflict banner and each chosen recovery action through its visible label and saved/backup bytes. A conflict run requires a deterministically changed file modification time. Never claim crash recovery merely from a successful normal relaunch; record the precise interruption used.

## Gotchas

- Existing notes initially open in Edit mode without automatically showing the keyboard; a new note focuses title. The title/body buffers are separate; the saved document combines them into one Markdown file.
- Saving is throttled (not a typing-stops-only debounce) and flushed on leave/background. Poll observable state and inspect disk; arbitrary sleeps are not proof.
- New empty notes are discarded by default unless Keep empty notes is on; do not interpret their absence as failed nonempty saving.
- Back may first dismiss the keyboard. Prefer the editor's Back description when verifying leave behavior.
- Toolbar actions may be offscreen or reordered. Resolve current descriptions; do not bake in x/y positions. ADB ASCII input does not verify IME composition.
- Links/backlinks require two real saved notes and refreshed indexing. Images require a referenced image fixture. Insert image/Insert file are not currently exposed user routes. Recovery paths require deliberately induced failures; normal saving does not cover them.
