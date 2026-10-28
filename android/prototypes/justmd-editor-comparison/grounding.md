# JustMD editor comparison, second round

User selected visual direction B from prototypes/justmd-research, requires Android Dynamic Color, prioritizes Material Expressive UI, and wants a technical prototype comparing CodeMirror View plus Kotlin Compose against a pure Kotlin Compose route. The native route may give up instant rendering and use separate source editing and reading. Study MarkReader, Markleaf and Kori implementations rather than assuming their technology from README labels.

Preserve the first-round prototype and evidence unchanged. New artifacts live here. Reuse its toolchain and visual assets where useful. Its native Room probe is already sufficient for the narrow previous conclusion, so this round focuses editor architecture, behavior, native rendering, dynamic theming and engineering cost.

Existing first-round Android shell uses one ResearchSession, six fixture notes, source drafts in memory, selected note ID, style, query and destination. A keyed LocalEditor owns the WebView and validated appassets origin message bridge. CodeMirror is pinned in the old package-lock. Old Vditor evidence remains historical, not a candidate for this round.

Data shape for this comparison starts with one NoteDocument(id, markdown), an editor implementation identity, an Edit/Read mode, and a per-note DraftSnapshot(text, UTF-16 selection, revision). Both candidates must receive identical source and perform comparable edit/read/undo/navigation operations. Native rendering must not silently transform saved source. No permanent-save claim.

Two Android flavors with shared Compose chrome would let the native APK physically exclude WebView/JS dependencies. A runtime switcher is an alternative. Compare the actual minimum shapes before choosing. Both run on the existing emulator. UI selection can be compared in a local comparison page if separate APKs are selected.

Success rubric, score each 0 to 2: same B layout and Dynamic Color roles for both candidates; source state and note identity have one owner; native route can prove no WebView/JS renderer is used; actual Android edit/read/undo round trips can be observed; small disposable implementation with no production framework layers.

Research angles must pin repository revisions and cite source paths. MarkReader appears at usamaiqb/mark-reader, Markleaf at jeiel85/markleaf-android, Kori at YangDai2003/Kori. Primary sources must determine whether each uses Compose, Android Views, WebView, or a mixture. Do not copy project code unnecessarily.

Scope limits remain explicit: real Chinese IME candidate behavior needs actual input, full 5,000-note performance needs a separate measurement run, process-kill state recovery and durable saves are not implied by in-memory editing.
