# Delete and restore notes

Deleted notes leave normal browsing and appear in Trash with their original location. Users can restore them, undo a restore, delete permanently, restore all or empty the trash after confirmation.

## Sub-features

- `trash-delete-undo`: Home selection Delete moves the file to trash; snackbar Undo restores it.
- `trash-open-empty`: the drawer opens Trash, including the empty state.
- `trash-restore`: individual Restore returns the note to its original path; its Undo moves it back.
- `trash-permanent`: Delete permanently requires confirmation and removes note and metadata.
- `trash-bulk`: Restore all and confirmed Empty trash act on all eligible items.

## How to get to it (user POV)

- Home: long-press note(s), choose Delete; snackbar offers Undo.
- Home → Open navigation → Trash.
- Trash rows expose Restore and Delete permanently; its top bar offers Restore all and Empty trash when populated.

## Driving it with control.py

Preconditions: connected Home, doctor passes. Create one nonempty fixture titled `Trash fixture` through New note and record its original `.md` path with `v files trash-before`. Create a second fixture before batch checks.

- **Move to trash.** `v ui`; long-press the intended `Trash fixture` card using `v tap --text 'Trash fixture' --index 0 --long`; `v tap --desc Delete`; capture the moved-to-trash snackbar. `v files trash-moved` must show the original path absent and a matching `.looma/trash/<uuid>.md` plus `<uuid>.properties` containing original path and deletion time. Tap `v tap --text Undo` promptly for the undo branch; verify original bytes/path return. Delete it again for the remaining route.
- **Drawer/row.** `v tap --desc 'Open navigation'`; `v tap --text Trash`; `v assert --text 'Trash fixture'`; `v capture trash-row`. Check the displayed original path, not only the title.
- **Restore.** `v tap --desc Restore`; capture `trash-restored`; `v files trash-restored`. Require the original file bytes, removal of that trash record and `Trash is empty` when it was the sole item. The restore snackbar's Undo must put it back into Trash and remove the original again; capture both views. Use actual row indices if multiple Restore buttons exist.
- **Permanent delete/cancel.** With the fixture back in Trash, `v tap --desc 'Delete permanently'`; `v assert --text 'Delete permanently?'`; first `v tap --text Cancel` and prove the record survives. Repeat and `v tap --text Delete`; `v files trash-permanent` must contain neither its original file nor the UUID's Markdown/properties pair. Capture the empty UI.
- **Bulk.** Create and delete two fixtures through Home. In Trash `v tap --desc 'Restore all'`; require both original paths and intact bytes via `v files trash-all-restored`. Delete both again; `v tap --desc 'Empty trash'`; verify the displayed count and cancel once. Reopen and `v tap --text Empty`; assert `Trash is empty`, capture and copy files to prove both records and originals are absent.

## Gotchas

- This app's trash directory is `.looma/trash`, not an Obsidian `.trash` folder. Confirm actual files; old documentation may describe a different path.
- Permanent deletion and emptying are irreversible within the guest; use only this run's disposable vault. Cleanup must retain the pulled before/after proof.
- Snackbar Undo expires; missing it is an unexercised undo branch, not a failure of restore.
- Original-path collisions can prevent restoration without overwriting a current note. A collision check requires two genuinely conflicting files and must inspect both byte sets.
- Long-press selection, drawer navigation and Trash Back are separate user routes; opening Trash directly through an internal route does not cover them.
