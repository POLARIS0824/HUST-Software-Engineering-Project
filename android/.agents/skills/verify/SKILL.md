---
name: verify
description: Drive the real Looma Android app on an isolated emulator, verify user journeys, and retain UI and Markdown-file evidence. Use after Android changes or when checking vault setup, editing, search, trash, or settings. Android only.
---

# Verify Looma Android

Read `android/AGENTS.md` and [the feature index](features/README.md). Run commands from `android/`. This skill owns only this directory; do not modify product code to make verification pass. The app is `com.example.looma`, launcher `.MainActivity`, built by Gradle with JDK 25, SDK platform 37 and minSdk 26. It is local-first: no service, credentials, network account or migration command is required. Room creates its index after a vault is connected; Markdown files remain the content source.

## Launch

Requirements: Python 3.10+, the repository's Gradle/JDK toolchain, Android SDK platform-tools and emulator, hardware virtualization, and an installed **English-language** AVD at API 30+ with a local primary storage provider. API 26–29 uses a different permission route and needs a separate run. The creation proof used the existing `looma36` AVD (API 36, x86_64). On this machine the SDK is `D:/Code/Android/Sdk`; discover it from `ANDROID_HOME`, `ANDROID_SDK_ROOT` or `local.properties`, rather than copying that path to another host. List available AVDs with the SDK's `emulator -list-avds`; if `looma36` is absent, select an equivalent installed AVD. Do not install SDKs or create AVDs implicitly.

Use Nushell:

```nu
cd android # only when starting at the repository root
^./gradlew.bat assembleDebug --console=plain
if $env.LAST_EXIT_CODE != 0 { error make {msg: 'Android build failed'} }
$env.LOOM_VERIFY_RUN = $"app/build/verify/verify-(date now | format date '%Y%m%d-%H%M%S')"
def --wrapped v [...args: string] {
    ^python .agents/skills/verify/scripts/control.py --run $env.LOOM_VERIFY_RUN ...$args
    if $env.LAST_EXIT_CODE != 0 { error make {msg: 'Verification command failed; run doctor before more driving'} }
}
v launch --avd looma36 --port 5580
```

On Linux/macOS use `./gradlew` for the build; the helper locates the SDK's platform-specific executables. Those hosts were not exercised during creation.

`launch` starts one headless emulator with `-read-only -no-snapshot`, records its PID/serial and a guest ownership marker, removes any old Looma installation **only inside that disposable overlay**, installs the built APK, creates an empty `/sdcard/Documents/<run-name>` vault directory, and opens the real launcher. It waits for boot, the visible `Choose a Looma vault` screen, and doctor success. No app preferences, DB rows, vault paths, permission grants or note contents are injected. Connect the empty directory using the UI recipe in [vault.md](features/vault.md). Ready for feature drives means the connected Home has `New note` and the empty-vault message; a booted emulator alone is insufficient.

Keep this one instance alive and drive it serially. Do not share it with another agent or target a physical device. The helper refuses occupied emulator ports and reused run directories. Separate runs may use distinct even ports, run directories and read-only overlays; there must be only one UI driver per serial. Never point a recipe at a personal vault. A failed launch attempts cleanup automatically; inspect `session.json` and `emulator.log` if cleanup reports failure.

## Doctor

```nu
v doctor
```

This observer checks guest ownership, completed boot, a running Looma process, the installed APK's SHA-256 against the APK launched by this run, the resumed app activity and a nonempty UI hierarchy. It records `doctor.json` and `doctor.xml`. It does not grant permissions or change app data. UI observation writes a temporary hierarchy file in the guest and artifacts on the host.

During an expected system permission/folder-picker transition use `v doctor --system`; it additionally accepts Android Settings, DocumentsUI and PermissionController. This option is not permission to dismiss an unexpected system screen blindly. Doctor cannot establish that a particular feature is usable: also inspect `v ui` and assert the expected screen.

Run doctor before the first drive, after relaunch, and after any failure/surprising transition. If doctor passes but the UI is wedged, capture evidence, reset to a known screen with Back or `v relaunch`, then doctor again. If the app cannot resume safely, `v cleanup` and start a new run. Never keep clicking after a failed assertion. Fix harness drift under this directory, clean failed-iteration residue, and re-drive the affected journey before claiming the fix works.

## Drive

The standard-library ADB helper resolves **current** accessibility text, description, resource ID or widget class from UIAutomator before tapping. It does not cache coordinates. Compose lacks test tags here; title/body fields are the first/second `android.widget.EditText` on the editor screen. Always inspect the hierarchy before using positional field indices or ambiguous text.

```nu
v ui
v tap --desc 'New note'
v tap --class android.widget.EditText --index 0
v text 'Verification note'
v tap --class android.widget.EditText --index 1
v text 'Persistence marker #verify'
v tap --desc Read
v assert --text 'Verification note'
v capture note-read
v tap --desc Back
v files note-saved
```

Use `v assert --text 'exact text'`, `--desc`, `--id` or `--class`; `--absent` asserts disappearance. `v tap` accepts the same selectors, optional `--index 0` for a consciously selected duplicate and `--long` for selection. Both tap and assert wait for matching nodes, with bounded timeouts. The helper fails ambiguous taps. `v key KEYCODE_BACK`, `v key KEYCODE_ENTER`, and `v swipe up`/`down` cover keyboard and scrolling; swipes derive positions from the current device size. To replace a populated focused field use `v select-all` (real Ctrl+A input), then `v text 'replacement'`. `v relaunch` force-stops only this run's Looma installation and restarts it for persistence checks. It does not reinstall or clear data.

`v text` sends single-line ASCII without `%` through real Android input. Line breaks use `v key KEYCODE_ENTER`. It does not prove Chinese IME composition, clipboard, rich input or hardware-keyboard behavior. Report those checks as unverified unless driven with an appropriate real input tool/device. Exact names below assume English; after an intentional locale change inspect the new hierarchy and use the actual localized names.

Read the matching feature recipe. Do not treat this short example as coverage of the entire map. Launch creates **no sample notes**: recipes requiring notes, backlinks, trash or attachments must create the stated fixtures through user paths. A missing prerequisite is a failed/unreached journey, not a pass through an easier entry point.

## Evidence

All run artifacts are under `android/<LOOM_VERIFY_RUN>/`, normally `android/app/build/verify/verify-<timestamp>/`, ignored by the existing build-directory rule. Do not run Gradle `clean` or delete that build tree while evidence is needed; copy the whole run to a durable review location first if necessary.

- `actions.jsonl`: timestamped commands, stdout/stderr, exit codes, UI snapshots and selectors/bounds resolved for taps. This proves the actions as well as their outcomes.
- `session.json`, `emulator.log`, `doctor.json`/`.xml`: device ownership, emulator startup, expected APK hash and observed foreground.
- `v capture <name>`: `<name>.png`, `<name>.xml`, `<name>-logcat.txt`. Open the PNG and inspect it; existence alone is not visual verification.
- `v files <name>`: copies the actual vault to `<name>-vault/`, including `.looma` side effects. Each name must be new. Inspect note bytes, paths and trash metadata alongside UI results. This is a read-only second view, never the mutation path under test.

Proof requires real user input, the resulting UI, and durable effects where applicable. A screenshot of typed text is insufficient for saving: leave, reopen/relaunch and inspect the saved Markdown. Do not use `run-as` preference setters, direct index insertion, permission appops, prewritten fixture notes or test-only entry points as evidence for the corresponding user journey. No mocks are required. For external file/image journeys, use a disposable file as a boundary fixture and then select it in the system UI; retain its provenance and the resulting Markdown reference/file. API 36 emulator success does not establish physical-device permissions, API 26–29, OEM pickers, TalkBack, predictive-back gestures, IME composition, performance or other display sizes.

Record feature IDs and entry points covered, failures and unmet prerequisites in a run-local `coverage.md`. For creation, one mapped feature is sufficient; explicitly label the remaining map entries as source-seeded, not live-proven. A maintenance run must cover every feature file from source and live. Capture partial evidence before troubleshooting; a broken UI is a product gap unless source and intended behavior establish doc drift.

## Cleanup

After the last drive (including harness re-proofs), even on failure:

```nu
v cleanup
ls $env.LOOM_VERIFY_RUN
open --raw ($env.LOOM_VERIFY_RUN | path join 'session.json')
```

Cleanup verifies the guest ownership marker and sends `emu kill` only to the serial this run started, waits for its console port to close, and marks the session `cleaned`. Read-only emulator disk overlays, the installed app and scratch vault disappear with that instance; existing AVD storage is not overwritten. Startup failures stop only the recorded process tree. Never kill by process name, stop a pre-existing emulator, use `adb kill-server`, or wipe an AVD. A disconnected/already-exited device may need PID/port inspection before cleanup can establish ownership; report unresolved residue rather than killing another instance.

Cleanup preserves every host artifact. Confirm the named PNG, XML, action log and pulled Markdown still exist and open after teardown. Cleanup is safe to repeat on a cleaned session. Do not reuse that run name for a new emulator. No app or emulator started by a drive should outlive its usefulness; capture and clean residue after failed iterations before retrying.

## Helpers

[scripts/control.py](scripts/control.py) is an executable Python entry point (invoke via `python`, including on Windows); it has no pip dependencies. Commands above use only its documented subcommands:

```nu
^python .agents/skills/verify/scripts/control.py --help
^python .agents/skills/verify/scripts/control.py --run $env.LOOM_VERIFY_RUN launch --help
```

If host sandbox rules prevent Gradle cache writes, ADB or emulator startup, request the corresponding execution escalation. Do not mislabel a host denial as an app defect. Keep all helper corrections inside this skill and prove them live. Use `/maintain-verification-skill` when the Android app changes to reconcile the feature map and harness.
