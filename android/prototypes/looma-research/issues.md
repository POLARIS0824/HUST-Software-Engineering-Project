# Prototype findings to carry into implementation

## P01. Vditor 3.9.9 removes reference link titles

Open. Blocks an unqualified strict source-fidelity claim for the current candidate. Requirements BR-06 and FR-EDIT-02 are affected. `evidence/vditor-probes.json`, case `reference`, contains the input, untouched output, adjacent edit, and undo output. Reproduce through the Android probe. Decide whether to replace the editor, patch the pinned version, or explicitly accept normalization before production implementation.

## P02. Vditor reads localStorage with note cache disabled

Open. Conflicts with the documented DOM-storage-disabled setup. `evidence/vditor-domstorage-disabled.log` records the startup exception. `assets-lock.json` identifies the exact fixed bundle, whose constructor accesses `localStorage.getItem` before the cache-enabled condition. The runnable UI explicitly enables DOM storage and disables note cache. This is an experimental configuration change, not silent compliance with the design document.

## P03. Full editor semantics remain unimplemented

Open. WikiLink IR, UTF-16 source mapping, composition-safe snapshots, 100-word-grouped undo, TalkBack, and sustained long-document behavior need implementation and actual device validation. The raw WikiLink text and an incoming-link sheet do not establish those capabilities. The fixed Vditor undo stack has 50 entries. The CodeMirror comparison proves only the small raw-source operations in its method field.

## P04. Vditor dynamic icons conflict with strict script CSP

Open. Local XHR is allowed by same-origin connect policy, but Vditor injects inline icon script that CSP rejects. This does not block the native-toolbar visual prototype. Preserve the CSP and adapt or prebundle the icon path if Vditor remains a candidate.

## Resolved integration defects

- Vditor local CDN paths now retain `dist/`, fixing local language and engine resource lookup.
- Editor height comes from actual native measurement, fixing the zero CSS viewport at initialization. Evidence is the visible final editor screenshot and `evidence/editor-layout.json`.
- Dark system-bar icon appearance is applied on window focus as well as style change. Final A screenshots show readable system icons.
- The fidelity harness previously called a nonexistent public `editor.undo()`. The failed run is retained as `evidence/vditor-harness-undo-error.json`. Final results use the explicitly identified pinned internal undo entry and preserve raw readbacks.

## P05. Backlink navigation reused a destroyed AndroidView

Resolved in the prototype. Selecting a reference changed native metadata but left a blank WebView. Evidence is `evidence/backlink-open-before-fix.json`. The editor composition is now keyed by note ID so note changes recreate the native view, while style changes retain the same note editor. Repeating the same sheet selection produces the handshake body containing SYN and no old cwnd text in `evidence/backlink-open-after-fix.json`.
