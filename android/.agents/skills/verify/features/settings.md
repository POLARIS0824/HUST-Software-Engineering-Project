# Configure Looma

Settings shows the connected vault and file access, controls appearance and language, and lets users keep empty notes and reorder the editor's formatting actions.

## Sub-features

- `settings-entry`: avatar and drawer each open Settings; Back returns to Home.
- `settings-vault`: current folder/path, Change vault and file-access state reflect the active vault.
- `settings-theme`: Dynamic color and Color theme presets change appearance and persist.
- `settings-language`: Follow system, English and 简体中文 change localized UI.
- `settings-empty`: Keep empty notes changes whether an untouched new note survives leaving.
- `settings-toolbar`: move formatting actions up/down, Save, reset and Cancel preserve the intended toolbar order.

## How to get to it (user POV)

- Home avatar labelled Settings.
- Home → Open navigation → Settings.
- Scroll Settings for Notes, File access and About. Editor toolbar order opens a sheet.

## Driving it with control.py

Preconditions: connected Home, English UI, doctor passes. No settings need to be prewritten. On smaller displays scroll with `v swipe up`/`down` and inspect `v ui` after each gesture.

- **Both entries.** `v tap --desc Settings`; require `Settings`, Notes vault, the real folder/path and Color theme; `v capture settings-avatar`; `v tap --desc Back`. Then `v tap --desc 'Open navigation'`; `v tap --text Settings`; capture `settings-drawer`. Prove both routes separately.
- **Appearance.** `v ui`; tap the actual `android.widget.Switch` corresponding to Dynamic color (inspect its bounds/row association and index). Turning it off allows a preset to be visually assessed. `v tap --text 'Color theme'`; `v tap --text Teal`; capture the changed theme and selected value. `v relaunch`; reopen Settings and prove the same preset/switch value. Repeat Dynamic color enabled on API 31+; do not claim color change just because a setting label changed.
- **Language.** `v ui`; Language may appear as a section heading and a row; explicitly choose the row's matching text index. `v tap --text Language --index 1`; `v tap --text 简体中文`; `v capture settings-chinese`; inspect the localized hierarchy. Restore English through the visible language row/dialog, using actual Chinese resource labels. Relaunch and prove the selected language persists. The helper's ASCII typing limit does not prevent selecting existing Chinese text.
- **Empty-note behavior.** Scroll to Keep empty notes. Inspect `v ui`; tap its Switch by the correct current index, record checked state and return Home. `v tap --desc 'New note'`; type nothing; `v tap --desc Back`; `v files settings-keep-empty`. With the switch on a new empty `.md` must survive; with it off a fresh untouched note must be discarded. Compare separate before/after vault copies; previously saved notes must not disappear.
- **Toolbar order.** Open Settings; scroll to `Editor toolbar order`; `v tap --text 'Editor toolbar order'`; `v ui`. Use descriptions such as `Move Italic up`/`Move Bold down` for rows currently visible, then `v tap --text Save`. Reopen a note and compare actual action order, capture and relaunch to prove persistence. Reopen the sheet, `v tap --text 'Reset to default'`, Save, and verify default order. Cancel a separate unsaved change to prove it does not persist. Some formatting actions are unavailable and do not appear in the reorder list.
- **Storage/about.** Scroll and require `All files access granted`, Looma and `Version 0.1.0` (derive current version from build if it changes). Settings' actual vault path must match `session.json.vault` modulo `/sdcard` versus `/storage/emulated/0`. Change vault follows [vault.md](vault.md), not a preference write.

## Gotchas

- Dynamic color is unavailable below API 31; the UI should explain that state. Wallpaper colors can mask the selected preset until Dynamic color is off.
- Text in a ListItem may be a label rather than the Switch's hit target; inspect class/checked state and use the correct Switch node.
- Language changes recreate UI and invalidate English selectors. Doctor and inspect again before further actions. Restore English for the other recipes.
- Empty-note retention is evaluated on leave for newly created notes; testing an existing empty file does not cover it.
- Reordering a sheet is not proof of persistence until Save and an editor/relaunch show the new order. Cancel/reset are separate branches.
