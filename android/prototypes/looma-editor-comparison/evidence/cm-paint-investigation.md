# CodeMirror paint investigation (2026-10-28)

Observed: source/backend state and accessibility tree were populated while the editor body screenshot was blank.

1. SOFTWARE WebView layer: still blank (`cm-paint/cm-software.png`). The software layer remained during intermediate diagnostics and was explicitly removed before final hardware verification.
2. Always VISIBLE native WebView: still blank (`cm-paint/cm-visible.png`). Restored explicit visible/invisible state for list/read lifecycle.
3. Fixed-position plain HTML sentinel rendered correctly while CM remained blank (`cm-paint/cm-sentinel.png`). This ruled out total WebView painting failure and native Compose occlusion.
4. DOM diagnostics: editor/scroller/content width 411 CSS px, left 0, first line top24; spans had normal nonzero widths and visible dark foreground. Native WebView location (0,568), visible rect (0,568)-(1080,2071), attached and visible.
5. Scroller z-index override did not fix it (`cm-paint/cm-zindex.png`). Content/scroller debug backgrounds also failed to paint (`cm-paint/cm-colors.png`). Removed all experimental overrides.
6. Pinning #editor to WebView viewport with position:fixed and inset:0 immediately restored the full subtree (`cm-paint/cm-fixed.png`, experimental colors/sentinel).
7. Clean CSS viewport fix, original CM editable behavior and dynamic colors restored: source renders (`cm-paint/cm-clean-fixed.png`) and still renders after reading/edit return (`cm-paint/cm-return-fixed.png`). Every captured screenshot was inspected immediately.

Final change: #editor uses a fixed viewport box. Scoped #editor .cm-content/.cm-scroller selectors also preserve intended padding/line-height over CM's injected base theme. This isolates the problem to normal-flow editor subtree clipping/painting in this WebView layout; the exact Chromium internals were not proven. No software layer or z-index/user-modify overrides remain. Temporary sentinel, bitmap, console and DOM diagnostics were removed. Final :app:assembleCmDebug passed. Parent owns final installation/probes/journeys.

Final hardware verification: cm-paint/cm-final-hardware.png renders the clean CM source with default WebView hardware compositing. The final APK was installed and launched after build. No software override remains in source.

