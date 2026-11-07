# Browse files and search notes

Users browse folders and recent notes, change list/grid or sorting, search titles/content/tags, and manage note or folder files with selection actions.

## Sub-features

- `browse-layout-sort`: list/grid, Sort by and pull-to-refresh preserve the visible note set.
- `browse-folders`: create/open/navigate up, rename/move/delete a folder.
- `browse-selection`: long-press selects notes/folders; clear, batch-delete/move, single-note rename/info and snackbar Undo act on the selected files.
- `search-match`: title, body and hashtag queries show matching notes and open the result.
- `search-empty-clear`: absent queries, Clear search and Close search show distinct expected states.
- `search-history`: submitted/open-result queries become recent searches.

## How to get to it (user POV)

- Connected Home shows Recent, Folders and Notes; tap a folder and use breadcrumb/up to return.
- Tap the Search <vault-name> field; search runs inside the expanded surface.
- Tap Show as list/Show as grid or Sort in the collapsed search bar.
- Long-press a note/folder; New folder is a separate floating action.

## Driving it with control.py

Preconditions: doctor passes on connected Home. Create `Verification note` with body `Persistence marker #verify` and a second distinct note via [notes.md](notes.md). No fixtures are supplied by launch.

- **Layout/sort.** `v tap --desc 'Show as list'`; inspect both fixture titles, `v capture browse-list`; `v tap --desc 'Show as grid'`; `v tap --desc Sort`; `v tap --text Title`; inspect the actual file-list order rather than Recent, which has its own order. Repeat Last modified; capture. `v swipe down` performs a refresh gesture only from the top of Home; require the returned list and no load error.
- **Folder.** `v tap --desc 'New folder'`; `v tap --class android.widget.EditText`; `v text Projects`; `v tap --text Create`; `v tap --text Projects`; assert `This folder is empty`. Create a note there and verify the copied path under Projects via `v files browse-folder`. Use `v tap --desc 'Go up one folder'`. `v tap --text Projects --long`; `v ui`; the selection top bar offers `v tap --desc Rename`, `v tap --desc 'Move to…'` and `v tap --desc Delete`. Rename opens the Rename folder dialog; edit its field with select-all and confirm Save. Delete opens confirmation; inspect it before confirming and prove descendant notes move to trash. Move uses the system folder picker and accepts targets within the connected vault only.
- **Selection/info/rename.** `v tap --text 'Verification note' --index 0 --long` after inspecting duplicates. `v tap --desc 'File info'` opens Location/Size/Modified/Words/Characters; capture and close with OK. Reselect; `v tap --desc Rename`; the dialog's field is labelled File name. `v tap --class android.widget.EditText`; `v select-all`; `v text Renamed`; `v tap --text Save`. Require Renamed feedback and `v files browse-renamed` with the renamed `.md`; its heading may still be Verification note. Reopen to prove body integrity. For batch operations select two files before Move to…/Delete and verify both paths; check snackbar Undo before it expires. `v tap --desc 'Clear selection'` must restore normal Home controls.
- **Title query.** On Home `v ui`; `v tap --class android.widget.EditText`; `v text Verification`; await `v assert --text 'Verification note'`; `v capture search-title`. Do not submit Enter to inspect expanded results: submitting collapses the search surface.
- **Body/tag queries.** `v tap --desc 'Clear search'`; `v text Persistence`; inspect the result and matching excerpt; capture. Clear and type `#verify`, inspect matching note and capture. Tap a result to open the editor; Back returns Home. Search history is recorded on result selection or submit, not every keystroke; reopen the search field and inspect Recent searches.
- **No match/clear/close.** Clear the query and `v text nonexistent-verify-token`; require `v assert --text 'No matching notes'`. `v tap --desc 'Clear search'` removes the query; `v tap --desc 'Close search'` returns the full Home list and clears filtering. Capture `search-closed`; `v files search-unchanged` and compare note bytes against the pre-search snapshot.

## Gotchas

- Home, Recent and search may repeat the same title. Ambiguous text selectors must be resolved from `v ui`, not guessed. File-list headings derive from note content; rename is a filename operation.
- Controls described here are accessibility descriptions, while menu/dialog actions may be text. Inspect exact attributes if an assertion fails; doctor before retrying.
- To replace a populated field, focus it, `v select-all` and type; app state setters do not count. The helper intentionally does not pretend `text` replaces existing text.
- Search submission collapses and clears the query; inspect matching results before Enter. Search scans the vault, not just the visible folder.
- Folder deletion requires confirmation and moves descendant notes to `.looma/trash`; empty folders are removed. A picker cancellation must not move files; selecting outside the vault must not move files there.
