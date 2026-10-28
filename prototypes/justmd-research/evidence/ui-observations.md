# Android UI observations

These are direct driver observations, not a product acceptance suite. `android layout` provided target coordinates. Each captured PNG was opened and visually inspected before continuing.

| Observed interaction | Evidence | Result |
| --- | --- | --- |
| Open TCP note from the A library card, then system Back | a-editor-layout.json, a-library-layout.json | Correct note and library shown |
| Open the top style menu and choose B | style-menu-layout.json, b-library-layout.json, b-library.png | Same six notes, warm paper presentation |
| Open TCP note from B, then switch to C in the editor menu | b-editor-layout.json, style-menu-editor-layout.json, c-editor-layout.json | Same note body and native destination retained |
| Return to C library and search body-only keyword `cwnd` | search-positive-layout.json | One result, TCP 拥塞控制 |
| Clear search and enter `no_such_note` | search-empty-layout.json, c-search-empty.png | Zero results and explanatory empty state |
| Open the incoming-link sheet and choose TCP 三次握手 | backlinks-layout.json, backlink-open-after-fix.json | Correct handshake body shown after fixing native view identity |
| Create a new session draft, type `DraftProbe42`, return to library, reopen it | new-draft-layout.json, draft-input-layout.json, draft-reopen-layout.json | New note created and text retained within the process |

Final same-state comparison screenshots are `a-library.png`, `b-library.png`, `c-library.png`, `a-editor.png`, `b-editor.png`, and `c-editor.png` under `../screenshots/`. They are original Android captures at 1080 × 2424. Device status indicators can change between captures.

The native UI scans fixtures. It does not query Room. Editing is an in-memory session draft. No production persistence, inline WikiLink navigation, real Chinese candidate input, TalkBack, 200% text, landscape, or large-library performance is claimed.
