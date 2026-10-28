# Native project source audit

Status PASS for source classification. Runtime behavior remains unverified. No project was built or run. Public GitHub source and released Google Maven sources were read on 2026-10-28.

## Pinned projects

| Project | Revision | Editor | Markdown reading |
|---|---|---|---|
| usamaiqb/mark-reader | `195a0fcf82137233efe1244c18a36c3e1dc789d7` | Compose value-based BasicTextField | Mixed. Editor preview uses Markwon spans and Android TextViews. General Markdown reader uses WebView. |
| jeiel85/markleaf-android | `43172492228612e794fba7669d67eb3ea43db5a8` | Compose value-based BasicTextField and TextFieldValue, custom undo | CommonMark Java AST adapted to preview rows and rendered with Compose. |
| YangDai2003/Kori | `bca6471cbcfaa6550793369f8ba291c3a7d94eb0` | Compose state-based BasicTextField and TextFieldState | AndroidView hosting WebView. Kotlin parser generates HTML and JavaScript updates the document. |

### MarkReader

[EditorScreen.kt](https://github.com/usamaiqb/mark-reader/blob/195a0fcf82137233efe1244c18a36c3e1dc789d7/app/src/main/java/com/markreader/ui/screens/EditorScreen.kt#L276) calls BasicTextField with `uiState.textFieldValue` and `viewModel::onTextChanged`. Its preview calls RenderedTextView at line 326.

[MarkwonRenderer.kt](https://github.com/usamaiqb/mark-reader/blob/195a0fcf82137233efe1244c18a36c3e1dc789d7/app/src/main/java/com/markreader/format/markdown/MarkwonRenderer.kt#L119) parses Markdown with Markwon and produces Spanned. [ReaderContentView.kt](https://github.com/usamaiqb/mark-reader/blob/195a0fcf82137233efe1244c18a36c3e1dc789d7/app/src/main/java/com/markreader/ui/reader/ReaderContentView.kt#L166) creates native Views through AndroidView. This is native Android rendering, but not pure Compose rendering.

[WebViewReader.kt](https://github.com/usamaiqb/mark-reader/blob/195a0fcf82137233efe1244c18a36c3e1dc789d7/app/src/main/java/com/markreader/ui/reader/WebViewReader.kt#L54) explicitly hosts the shared Markdown web renderer. Do not describe the whole app as WebView-free because the editor preview has a native path.

[Theme.kt](https://github.com/usamaiqb/mark-reader/blob/195a0fcf82137233efe1244c18a36c3e1dc789d7/app/src/main/java/com/markreader/ui/theme/Theme.kt#L74) defaults dynamicColor to true and selects dynamic light/dark schemes on Android 12+. The source editor takes foreground and cursor colors from MaterialTheme.

### Markleaf

[EditorScreen.kt](https://github.com/jeiel85/markleaf-android/blob/43172492228612e794fba7669d67eb3ea43db5a8/app/src/main/java/com/markleaf/notes/feature/editor/EditorScreen.kt#L1770) calls `BasicTextField(value = editorState, onValueChange = ...)`, applies auto-continuation, and uses MarkdownSyntaxVisualTransformation. Its history is custom, rather than TextFieldState.undoState.

[CommonMarkPreviewAdapter.kt](https://github.com/jeiel85/markleaf-android/blob/43172492228612e794fba7669d67eb3ea43db5a8/app/src/main/java/com/markleaf/notes/core/markdown/CommonMarkPreviewAdapter.kt#L101) owns CommonMark parsers and emits PreviewLine/PreviewInlineSegment structures. [SimpleMarkdownPreview.kt](https://github.com/jeiel85/markleaf-android/blob/43172492228612e794fba7669d67eb3ea43db5a8/app/src/main/java/com/markleaf/notes/core/markdown/SimpleMarkdownPreview.kt#L221) delegates parsing to that adapter. The old name does not mean the main block parser is still regex based.

[MarkdownPreviewList.kt](https://github.com/jeiel85/markleaf-android/blob/43172492228612e794fba7669d67eb3ea43db5a8/app/src/main/java/com/markleaf/notes/core/markdown/preview/MarkdownPreviewList.kt) uses LazyColumn, Material Text, AnnotatedString, selection containers, native table layouts, and Coil AsyncImage. The inspected source editing and Markdown preview path has no WebView or AndroidView import. This is evidence about that path, not an exhaustive whole-repository absence proof.

[app/build.gradle.kts](https://github.com/jeiel85/markleaf-android/blob/43172492228612e794fba7669d67eb3ea43db5a8/app/build.gradle.kts#L491) pins CommonMark 0.30.0 plus extensions. [Theme.kt](https://github.com/jeiel85/markleaf-android/blob/43172492228612e794fba7669d67eb3ea43db5a8/app/src/main/java/com/markleaf/notes/ui/theme/Theme.kt#L237) supports dynamic schemes but its function defaults dynamicColor to false. Do not copy that default into JustMD.

### Kori

[Editor.kt](https://github.com/YangDai2003/Kori/blob/bca6471cbcfaa6550793369f8ba291c3a7d94eb0/composeApp/src/commonMain/kotlin/org/yangdai/kori/presentation/component/note/Editor.kt#L252) proves genuine Compose source editing with state-based BasicTextField, TextFieldState, and OutputTransformation.

[MarkdownViewer.kt](https://github.com/YangDai2003/Kori/blob/bca6471cbcfaa6550793369f8ba291c3a7d94eb0/composeApp/src/commonMain/kotlin/org/yangdai/kori/presentation/component/note/markdown/MarkdownViewer.kt#L60) parses with the in-tree kmark GFM parser and generates HTML. [MarkdownViewer.android.kt](https://github.com/YangDai2003/Kori/blob/bca6471cbcfaa6550793369f8ba291c3a7d94eb0/composeApp/src/androidMain/kotlin/org/yangdai/kori/presentation/component/note/markdown/MarkdownViewer.android.kt#L80) creates AndroidView(WebView), loads an appassets-origin HTML document, and calls evaluateJavascript to update content. Kotlin Multiplatform branding does not establish a native Markdown preview.

[Theme.kt](https://github.com/YangDai2003/Kori/blob/bca6471cbcfaa6550793369f8ba291c3a7d94eb0/composeApp/src/commonMain/kotlin/org/yangdai/kori/presentation/theme/Theme.kt) uses MaterialExpressiveTheme. [Theme.android.kt](https://github.com/YangDai2003/Kori/blob/bca6471cbcfaa6550793369f8ba291c3a7d94eb0/composeApp/src/androidMain/kotlin/org/yangdai/kori/presentation/theme/Theme.android.kt) selects Android dynamic schemes. This is a useful editor and theme reference, not a WebView-free end-to-end reference.

## Small implementation recommendation

Use one per-note TextFieldState, a source BasicTextField, and a separate read mode. Derive preview input from state.text.toString() when entering read mode. Keep the original source authoritative. Reuse the same theme and discrete native read surface for both editor candidates so the comparison isolates editing behavior.

[Android's text-input guide](https://developer.android.com/develop/ui/compose/text/user-input) confirms state-based input, editable buffers and independently hoistable TextFieldState. For this paper surface, BasicTextField avoids a form-field container. Do not implement instant rendering in the first native experiment.

Released [Foundation 1.10.0 source JAR](https://dl.google.com/dl/android/maven2/androidx/compose/foundation/foundation/1.10.0/foundation-1.10.0-sources.jar) and [1.10.2 source JAR](https://dl.google.com/dl/android/maven2/androidx/compose/foundation/foundation/1.10.2/foundation-1.10.2-sources.jar), `commonMain/androidx/compose/foundation/text/input/TextFieldState.kt`, lines 213-241, were directly inspected. `commitEdit` records programmatic text edits with `NeverMerge`. Toolbar `state.edit { replace(selection.min, selection.max, insertion); selection = ... }` therefore contributes an independent undo step on these versions. Use `state.undoState.undo()` and `.redo()` with the required experimental opt-in. There is no need to regress to TextFieldValue solely to support toolbar undo. Preserve a TextFieldState per note rather than replacing a shared state's contents, which would also create undo entries. This is source evidence, not observed Android undo behavior.

For the native preview, mikepenz's M3 renderer avoids copying Markleaf's substantial adapter. Checked [v0.39.2 M3 Markdown source](https://github.com/mikepenz/multiplatform-markdown-renderer/blob/9eea267d560a03a481251ba77ba9aa9f654be397/multiplatform-markdown-renderer-m3/src/commonMain/kotlin/com/mikepenz/markdown/m3/Markdown.kt) provides `com.mikepenz.markdown.m3.Markdown(content: String)` and defaults colors and typography from its M3 adapters. Its parser is JetBrains Markdown, not WebView HTML.

Version caution. Published [0.39.2 Android M3 POM](https://repo.maven.apache.org/maven2/com/mikepenz/multiplatform-markdown-renderer-m3-android/0.39.2/multiplatform-markdown-renderer-m3-android-0.39.2.pom) requires Kotlin stdlib 2.3.0. Published core Gradle metadata for [0.38.1](https://repo.maven.apache.org/maven2/com/mikepenz/multiplatform-markdown-renderer-android/0.38.1/multiplatform-markdown-renderer-android-0.38.1.module) requires Kotlin 2.2.21 and [0.37.0](https://repo.maven.apache.org/maven2/com/mikepenz/multiplatform-markdown-renderer-android/0.37.0/multiplatform-markdown-renderer-android-0.37.0.module) requires 2.2.20. Prefer checking 0.38.1 against a Kotlin 2.2.21 shell before upgrading the compiler merely for the latest renderer. Exact resolved Compose compatibility and compilation remain for the implementation agent to verify. Do not claim a verified minimum renderer version from this source-only scan.

Runtime acceptance must actually type or insert text, invoke toolbar formatting, undo, redo, toggle read/edit, and navigate away/back while checking source text, UTF-16 selection and note identity. Rendering source directly in a test hook cannot prove IME editing. Chinese composition, rich HTML parity, very large documents, durable saves and process death remain separate checks.
