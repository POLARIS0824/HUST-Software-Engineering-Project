# Disposable Looma research

The user wants stack feasibility and UI screenshots to choose between Obsidian-like, Apple Notes-like, and M3 Expressive directions. There is no production code or Git repository. android-cli generated an empty Compose application in android/.

Keep all experiment code in this directory. No shipping, no PR. The prototype will use real Compose controls and a minimal local WebView editor because Android integration itself is a research question. This intentionally replaces the Prototype playbook's usual web-only visual shell.

The data shape starts with Note(id, title, folder, tags, markdown), a Style enum, a destination enum, and typed probe results. One shared fixture dataset must feed all styles. UI switching must not create independent stores.

Measure these decisions with actual artifacts where possible.

- Native Compose list and editor shell with three styles, searchable fixtures, note opening, backlinks, and a style selector.
- Offline Vditor IR inside WebViewAssetLoader. Observe Markdown round trips on known and unknown syntax, including after an adjacent edit. Do not claim production save behavior.
- Compile Room 3.0.3 + bundled SQLite 2.7.1 and execute FTS5 trigram queries on Android. Compare literal short-query behavior. No performance claims from desktop measurements.
- Probe an existing-file atomic replacement and parent directory synchronization in disposable app-owned storage. No power-loss claim.

Read-only how exploration found the dependencies are published, Room 3 needs KSP + SQLiteDriver, trigram cannot MATCH under three code points, and LIKE ESCAPE cannot use trigram optimization. The docs already describe those limits. Vditor upstream currently serializes DOM and has a fixed 50-entry undo stack. These observations must be checked against a pinned bundle before applying them to a version.

Primary sources are developer.android.com/jetpack/androidx/releases/room3, developer.android.com/jetpack/androidx/releases/sqlite, sqlite.org/fts5.html, github.com/Vanessa219/vditor, developer.android.com/develop/ui/views/layout/webapps/load-local-content, and developer.android.com/privacy-and-security/risks/insecure-webview-native-bridges.

## Architecture comparison rubric

Score 0 to 2 each. The candidate preserves one fixture dataset across styles. It keeps visual and technical probe results distinguishable. It can produce actual Android screenshots and local editor behavior evidence. It minimizes code and framework layers. It does not pretend to solve production durability or editor fidelity.
