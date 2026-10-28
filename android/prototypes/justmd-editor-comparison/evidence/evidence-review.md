# Independent evidence review

Reviewed by **gpt-6-sol**. Scope: the second-round prototype after the user's 2026-10-28 12:55:52Z choice of layout B, real Dynamic Color, Material Expressive components, and a CodeMirror View + Compose versus pure Kotlin Compose comparison with live rendering set aside. I checked this run's named Codex transcript only, the decision trail, report, issue list, probe and UI result summaries, APK inspection, palette records, build log, paint investigation, and final source screenshots. I did not change either app or the emulator.

## Attention

1. **Native selection handoff is an actual gap.** Trail row `2026-10-28T22:01:29.1403340+08:00` says core UI checks passed and notes the exception. `ui-journeys.json` shows native selection `[116,118]` before reading and `[118,118]` at reading. `issues.md` E03 and the report correctly leave this open. Its `themeRetainsDraftAndSelection` predicate applies to the already collapsed selection; it must not be read as preservation of the original range. Fix and rerun an input-session handoff check before claiming selection retention.

2. **The CodeMirror paint fix has narrow runtime coverage.** Trail row `2026-10-28T22:01:29.1015642+08:00` maps to `cm-paint-investigation.md` and the inspected final hardware screenshot `cm-paint/cm-final-hardware.png`; the visible result is real on the API 37 emulator. The `position: fixed` workaround is still an integration risk across WebView versions, viewport resizing and keyboard states. The report correctly says Chromium internals and other devices are unproven. Treat this as a prototype result, not a portable rendering fix.

3. **The recommendation rests on the accepted, narrower editor scope.** Trail row `2026-10-28T22:01:29.1420005+08:00` is a defensible preference for native editing plus a separate reader, not evidence of full product suitability. Both eight-case backend suites have 11 true predicates per case, and both UI journeys have ten true checks, but real input was ADB ASCII `UI42`. Chinese IME composition, durable saving, process death, large-file performance, accessibility, and full Markdown rendering were not validated. `research-report.md` and `issues.md` E04 state these limits and preserve Instant Rendering as a separate product requirement. Carry these acceptance gates into any Feature work.

4. **Checklist status is stale.** `.audit/justmd-editor-comparison.todo.md` still has every playbook and coverage item unchecked even though the transcript and artifacts show implementation, tests, screenshots and report creation. The TSV trail is more useful than this checklist for reconstructing the work; a casual reader could misread the unchecked boxes as unfinished verification. This is an audit presentation issue, not evidence that the recorded checks failed.

## Claims checked without a flag

- The transcript shows the user's B/Dynamic Color/Expressive/two-stack choice, the CodeMirror blank-screen discovery, the viewport fix, the real UI pass, and disclosure of the native selection collapse. The decision rows from `2026-10-28T20:59:42+08:00` onward map to those events or local artifacts. I found no invented decision or unlogged pivot that changed the outcome. The earlier visual row explicitly said its screenshot was pending and later rows record the follow-up.
- The final `cm-source.png`, `native-source.png`, and `cm-paint/cm-final-hardware.png` visibly show the two source editors and the fixed CodeMirror drawing. The build log ends in `BUILD SUCCESSFUL` for both flavor tasks. The APK inspection lists the native APK without editor JS or AndroidX WebKit descriptors and the CM APK with both. This establishes the stated debug-artifact boundary, not all possible runtime implementation details.
- Palette records show both flavors at `#326A4D` in green light mode and `#A4D1B4` in green dark mode, with `dynamic: true`; the original and restored settings are `null` theme customization and `no` night mode. The restored blue `primary` differs slightly from the original (`#4E5E8B` versus `#4C5E8B`), as the report acknowledges.

## Review limit

This was an evidence and trail review, not a fresh device test or source-code audit. I parsed the named transcript selectively to avoid unrelated chats and large payloads, and inspected representative final screenshots rather than every captured frame.

## Parent resolution after review

The parent checked the review against current files. Flag 4 refers to an earlier checklist snapshot. The checklist now marks the completed prototype work and independent review, while issues E03/E04 and production acceptance remain open. Flags 1-3 remain explicit limitations of this prototype decision; no broader acceptance is claimed.
