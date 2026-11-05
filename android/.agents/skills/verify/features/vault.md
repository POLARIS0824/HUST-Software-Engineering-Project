# Connect a local vault

Users choose a folder containing their Markdown files, grant storage access, confirm the pending selection, and return to the same vault on later launches. Changing vault is available from Settings.

## Sub-features

- `vault-first-launch`: a fresh installation shows the setup gate, not a fabricated sample vault.
- `vault-permission`: Choose folder opens Android all-files access settings when needed.
- `vault-picker`: a local primary-storage subfolder can be selected in the system picker.
- `vault-confirm`: a selection remains pending until Connect; Home then becomes usable.
- `vault-persist`: relaunch retains the confirmed vault.
- `vault-change`: Settings → Change vault opens setup; selection can be cancelled/reselected before confirming.

## How to get to it (user POV)

- Launch a fresh installation: `Choose a Looma vault` is the only root.
- From Home choose the Settings avatar, or Open navigation → Settings, then Change vault.
- If access is unavailable, Settings offers Grant access and returns through the same permission/picker path.

## Driving it with control.py

Preconditions: the skill's fresh session is healthy; its empty folder is named by `session.json.owner` and exists under Documents. The emulator UI is English. The API 30+ permission screen and a local DocumentsUI provider are installed.

- **Initial gate.** `v doctor`; `v assert --text 'Choose a Looma vault'`; `v capture vault-gate`. Connect is not usable before selecting a folder.
- **Permission route.** `v tap --text 'Choose folder'`; `v doctor --system`; `v ui`. On API 36 the screen offers `Allow access to manage all files`; tap that label with `v tap --text 'Allow access to manage all files'`, capture `vault-permission`, then `v key KEYCODE_BACK`. Returning opens the folder picker. Use the actual hierarchy if the OS wording differs; never use appops as proof.
- **Picker route.** `v doctor --system`; `v ui`. DocumentsUI may remember an old folder even after reinstalling Looma. On the creation device it opened `Looma/notes`; `v key KEYCODE_BACK` twice navigated up to primary storage (`Android SDK built for x86_64`). Inspect after each Back; stop at primary storage rather than cancelling the picker. Then `v tap --text Documents`; `let owner = (open ($env.LOOM_VERIFY_RUN | path join 'session.json')).owner`; `v tap --text $owner`; `v tap --text 'USE THIS FOLDER'`; accept the access dialog with `v tap --text ALLOW`. On a provider showing `Show roots`, open that description and choose primary internal storage first. Root labels and button case are OS-dependent: inspect `v ui` at each system screen. Never select a remembered personal folder.
- **Pending selection.** `v doctor`; `v assert --text Pending`; `v capture vault-pending`; `v tap --text Connect`. Await `v assert --desc 'New note'` and `v assert --text 'No notes yet. Tap + to create one'`. Capture `vault-home` and `v files vault-empty`. Empty Home, usable creation controls and the empty copied directory prove initialization finished.
- **Relaunch.** `v relaunch`; `v assert --desc 'New note'`; `v assert --text 'Choose a Looma vault' --absent`; `v capture vault-relaunched`. It must not ask to connect again.
- **Change entry.** From Home, `v tap --desc Settings`; `v tap --text 'Change vault'`; `v assert --text 'Choose a Looma vault'`. Capture the current folder and use Back to cancel. Repeat through `v tap --desc 'Open navigation'`; `v tap --text Settings` for the drawer entry. To prove actual switching, create a second empty run-owned folder using the system picker's New folder action, select it and Connect; prove the Home folder name changes and the first vault's files remain intact.

## Gotchas

- The picker accepts local **primary** shared storage only; storage root, cloud providers and secondary volumes are unsupported. Download root may be restricted by Android; use Documents/<run-owner>.
- A healthy process while a picker is open is not connected Home. Check the foreground with doctor and the visible state separately.
- No sample notes are seeded. A missing note after first connection is expected; missing New note or a persistent Connecting state is a failure.
- Cancelling the picker must not switch the vault. Returning from permission Settings only proceeds if access was actually granted.
- API 26–29 uses storage runtime permissions instead of all-files access; that route is not covered by the API 36 creation proof.
