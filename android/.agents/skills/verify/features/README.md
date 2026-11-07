# Looma Android verification map

This is the maintained Android user-journey index. The map is seeded from the current checkout, including existing uncommitted changes. It does not claim every entry was live-tested during skill creation. Consult each run's `coverage.md` for actual coverage.

## Baseline preconditions

- Follow [Launch and Doctor](../SKILL.md); use one owned, English-language API 30+ emulator serial and an empty, unique primary-storage directory.
- `launch` creates the empty directory, not notes. First run [vault connection](vault.md) through Android Settings and the system folder picker.
- Other recipes start on connected Home, with keyboard/search/selection closed. Use real UI note creation for the fixtures they name. Do not drive multiple flows concurrently.
- Run the `v` Nushell wrapper defined in the skill. `v ui` reveals exact accessible names; no guessed tap coordinates.

## Driving conventions

- Commands are relative to `android/`. `v doctor` precedes driving and follows unexpected results; use `--system` only for expected Android Settings/DocumentsUI.
- Select exact text/description. Duplicate titles may occur in Recent and Notes; inspect `v ui` and specify `--index` deliberately.
- `v capture <unique-name>` records UI and logs; `v files <unique-name>` copies actual vault bytes. Open images and inspect bytes.
- Create prerequisites through the UI. Missing data, permissions or system providers must be reported with the attempted route. Do not weaken the journey.
- Destructive operations apply only to this run's disposable vault. Retain evidence through cleanup.

## Features

| Feature | User surfaces |
| --- | --- |
| [Vault connection](vault.md) | First launch, all-files access, primary folder picker, confirmation, reconnect/change vault |
| [Notes](notes.md) | New note, title/body editing, autosave, read mode, Markdown actions, wiki links/backlinks, local image rendering |
| [Browse, files and search](browse-search.md) | Root/folders, Recent/Notes entries, layout/sort, search/history, rename/move/info/batch actions |
| [Trash](trash.md) | Delete/undo, individual restore/delete, restore all/empty trash |
| [Settings](settings.md) | Avatar/drawer entries, vault/access, themes, language, empty notes, toolbar order |

## Proof and skip reporting

Record sub-feature ID, entry point, actual action/result and artifact names in run-local `coverage.md`. A skipped entry is unverified even if another route reaches the same screen. `verified-unreachable` requires the attempted route and a concrete OS/provider/input prerequisite. The initial one-feature proof does not establish complete release acceptance. Use `/maintain-verification-skill` to check the whole map as the product evolves.
