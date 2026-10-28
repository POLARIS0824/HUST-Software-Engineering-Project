# Editor comparison findings

## E01. CodeMirror text exists but is not painted

Resolved on the tested API 37 emulator. Actual backend command probes passed while source was initially invisible. Removing Compose alpha, changing native visibility, and using software layers did not independently fix it. A fixed viewport root for #editor restored drawing. Final default-hardware screenshot, read/edit return and UI input are verified. Exact Chromium internals and other device behavior remain unverified. See `evidence/cm-paint-investigation.md`, `screenshots/cm-source.png`, `screenshots/cm-dark.png` and `evidence/ui-journeys.json`.

## E02. Reader default headings overpower the paper layout

Resolved. The M3 renderer defaults used display roles for headings. Both candidates now share headline/title role mappings. Final screenshots `screenshots/cm-read.png` and `screenshots/native-read.png` were visually checked.

## E03. Native UI selection collapses when entering reading

Open for the real implementation. After toolbar Bold, the native selection [116,118] became [118,118] when focus/keyboard was cleared for reading. Text and undo history stayed correct. UI source snapshots `evidence/native-ui-redo.json`, `evidence/native-ui-read.json` and `evidence/native-ui-return.json` show the transition. Backend-only selection cases still pass, which does not prove UI selection retention. Preserve the intended selection through the input-session handoff before production acceptance.

## E04. Product features intentionally outside the experiment

WikiLinks render literally in the common reader. No link resolution, tag extraction, attachment loading, durable save or index integration was implemented. Search uses fixture metadata/source rather than updated session drafts. This prototype cannot be used as the product implementation without a separate Feature pass.
