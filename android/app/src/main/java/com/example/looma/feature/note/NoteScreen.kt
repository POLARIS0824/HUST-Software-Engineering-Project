@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.example.looma.feature.note

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.looma.R
import com.example.looma.data.note.resolveImageSource
import com.example.looma.domain.markdown.EditorDocument
import com.example.looma.domain.markdown.MarkdownEditing
import com.example.looma.domain.markdown.MarkdownIndex
import com.example.looma.domain.markdown.TextSelection
import com.example.looma.domain.markdown.withCheckboxChecked
import com.example.looma.domain.model.NoteId
import com.example.looma.domain.wiki.WikiContext
import com.example.looma.domain.wiki.WikiNote
import com.example.looma.feature.note.components.BacklinksSection
import com.example.looma.feature.note.components.NoteFormatBar
import com.example.looma.feature.note.components.NoteLeaveDialog
import com.example.looma.feature.note.components.NoteSaveBanner
import com.example.looma.feature.note.components.NoteTopBar
import com.example.looma.feature.note.components.NoteTextField
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.compose.animation.EnterExitState
import com.example.looma.feature.note.components.WikiLinkPanel
import com.example.looma.feature.note.components.wikiReferenceFor
import com.example.looma.ui.components.LoomaIcon
import com.example.looma.ui.components.LoomaLoadingIndicator
import com.example.looma.ui.markdown.LocalImageSourceResolver
import com.example.looma.ui.markdown.MarkdownBody
import com.example.looma.ui.theme.LoomaTheme
import com.example.looma.ui.theme.LoomaTokens
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Composable
fun NoteRoute(
    viewModel: NoteViewModel,
    fallbackTitle: String,
    onNavigateUp: () -> Unit,
    onOpenNote: (NoteId) -> Unit,
    modifier: Modifier = Modifier,
    isNew: Boolean = false,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val requestLeave = { viewModel.requestLeave(onLeave = onNavigateUp) }
    val editorActive = LocalNavAnimatedContentScope.current.transition.targetState == EnterExitState.Visible

    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.refreshWiki() }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { viewModel.onEnterBackground() }

    BackHandler(enabled = state.needsLeaveGuard || state.willDiscardEmpty) {
        viewModel.requestLeave(onLeave = onNavigateUp)
    }

    when {
        state.isLoading -> NoteLoadingScreen(
            onNavigateUp = onNavigateUp,
            modifier = modifier,
        )

        state.loadFailed -> NoteLoadErrorScreen(
            onNavigateUp = onNavigateUp,
            onRetry = viewModel::load,
            modifier = modifier,
        )

        else -> {
            val imageContext = state.imageContext
            val resolver = remember(imageContext) {
                if (imageContext == null) {
                    { _: String -> null }
                } else {
                    { link: String ->
                        resolveImageSource(imageContext.vaultRoot, imageContext.noteFile, link)
                    }
                }
            }
            CompositionLocalProvider(LocalImageSourceResolver provides resolver) {
                NoteWorkspace(
                    state = state,
                    fallbackTitle = fallbackTitle,
                    isNew = isNew,
                    onContentChanged = viewModel::onContentChanged,
                    onRequestLeave = requestLeave,
                    onRetrySave = viewModel::retrySave,
                    onOverwrite = viewModel::overwrite,
                    onReloadFromDisk = viewModel::reloadFromDisk,
                    onOpenNote = onOpenNote,
                    editorActive = editorActive,
                    modifier = modifier,
                )
            }
        }
    }

    if (state.leaveBlocked) {
        NoteLeaveDialog(
            state = state,
            onRetry = { viewModel.saveThenLeave(onSaved = onNavigateUp) },
            onOverwrite = { viewModel.saveThenLeave(force = true, onSaved = onNavigateUp) },
            onDiscard = {
                viewModel.onLeaveHandled()
                onNavigateUp()
            },
            onDismiss = viewModel::onLeaveHandled,
        )
    }
}

@Composable
private fun NoteWorkspace(
    state: NoteUiState,
    fallbackTitle: String,
    isNew: Boolean,
    onContentChanged: (String) -> Unit,
    onRequestLeave: () -> Unit,
    onRetrySave: () -> Unit,
    onOverwrite: () -> Unit,
    onReloadFromDisk: () -> Unit,
    onOpenNote: (NoteId) -> Unit,
    editorActive: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val loaded = remember(state.reloadToken) { EditorDocument.split(state.content) }
    val buffers = key(state.reloadToken) {
        rememberTextFieldState(initialText = loaded.title) to
            rememberTextFieldState(
                initialText = loaded.body,
                initialSelection = TextRange(loaded.body.length),
            )
    }
    val titleBuffer = buffers.first
    val bodyBuffer = buffers.second
    var mode by rememberSaveable { mutableStateOf(NoteMode.Edit) }
    var linkPanelOpen by rememberSaveable { mutableStateOf(false) }
    val readScroll = rememberScrollState()
    val editScroll = rememberScrollState()
    val titleFocus = remember { FocusRequester() }
    val bodyFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    val leaveWorkspace = {
        focusManager.clearFocus(force = true)
        keyboard?.hide()
        onRequestLeave()
    }

    val onWikiLinkClick: (String) -> Unit = { target ->
        val id = state.wiki.resolve(target)
        if (id != null) {
            focusManager.clearFocus(force = true)
            keyboard?.hide()
            onOpenNote(id)
        } else {
            scope.launch {
                snackbarHostState.showSnackbar(resources.getString(R.string.note_link_missing, target))
            }
        }
    }

    LaunchedEffect(bodyBuffer, titleBuffer) {
        snapshotFlow {
            EditorDocument(titleBuffer.text.toString(), bodyBuffer.text.toString()).render()
        }
            .distinctUntilChanged()
            .collect(onContentChanged)
    }

    LaunchedEffect(mode) {
        if (mode == NoteMode.Edit) {
            if (isNew) {
                titleFocus.requestFocus()
            }
        } else {
            linkPanelOpen = false
            focusManager.clearFocus()
            keyboard?.hide()
        }
    }

    val bodyText = bodyBuffer.text.toString()
    val selection = bodyBuffer.selection
    val formatState = remember(bodyText, selection) {
        MarkdownEditing.formatState(
            text = bodyText,
            selection = TextSelection(selection.start, selection.end),
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = LoomaTokens.colors.noteCanvas,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            NoteTopBar(
                mode = mode,
                onNavigateUp = leaveWorkspace,
                onToggleMode = {
                    mode = if (mode == NoteMode.Read) NoteMode.Edit else NoteMode.Read
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding(),
        ) {
            if (state.restoredDraft) {
                DraftRestoredNotice()
            }
            if (state.saveFailed) {
                NoteSaveBanner(
                    state = state,
                    onRetry = onRetrySave,
                    onReload = onReloadFromDisk,
                    onOverwrite = onOverwrite,
                )
            }

            when (mode) {
                NoteMode.Read -> NoteReadBody(
                    title = titleBuffer.text.toString(),
                    bodyText = bodyText,
                    fallbackTitle = fallbackTitle,
                    scrollState = readScroll,
                    wiki = state.wiki,
                    onWikiLinkClick = onWikiLinkClick,
                    onOpenNote = onOpenNote,
                    onCheckboxToggle = { position, checked ->
                        val document = MarkdownIndex.index(bodyBuffer.text.toString(), fallbackTitle)
                        if (document.withCheckboxChecked(position, checked) != null) {
                            bodyBuffer.edit {
                                replace(
                                    position,
                                    position + MARKER_LENGTH,
                                    if (checked) CHECKED_MARKER else UNCHECKED_MARKER,
                                )
                            }
                        }
                    },
                    modifier = Modifier.weight(1f),
                )

                NoteMode.Edit -> {
                    Column(modifier = Modifier.weight(1f)) {
                        NoteTitleField(
                            active = editorActive,
                            buffer = titleBuffer,
                            placeholder = fallbackTitle,
                            focusRequester = titleFocus,
                            onNext = { bodyFocus.requestFocus() },
                        )
                        NoteBodyEditor(
                            active = editorActive,
                            buffer = bodyBuffer,
                            focusRequester = bodyFocus,
                            scrollState = editScroll,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (linkPanelOpen) {
                        WikiLinkPanel(
                            candidates = state.wiki.linkables,
                            onPick = { note ->
                                insertWikiLink(
                                    buffer = bodyBuffer,
                                    reference = wikiReferenceFor(note, state.wiki.linkables),
                                )
                                linkPanelOpen = false
                                bodyFocus.requestFocus()
                            },
                            onDismiss = {
                                linkPanelOpen = false
                                bodyFocus.requestFocus()
                            },
                        )
                    }
                    NoteFormatBar(
                        formatState = formatState,
                        toolbarOrder = state.toolbarOrder,
                        canUndo = bodyBuffer.undoState.canUndo,
                        canRedo = bodyBuffer.undoState.canRedo,
                        onCommand = { command ->
                            applyMarkdownCommand(bodyBuffer, command)
                            bodyFocus.requestFocus()
                        },
                        onUndo = {
                            bodyBuffer.undoState.undo()
                            bodyFocus.requestFocus()
                        },
                        onRedo = {
                            bodyBuffer.undoState.redo()
                            bodyFocus.requestFocus()
                        },
                        onWikiLink = {
                            linkPanelOpen = !linkPanelOpen
                            if (linkPanelOpen) {
                                focusManager.clearFocus()
                            } else {
                                bodyFocus.requestFocus()
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun NoteTitleField(
    active: Boolean,
    buffer: TextFieldState,
    placeholder: String,
    focusRequester: FocusRequester,
    onNext: () -> Unit,
) {
    val text = buffer.text.toString()
    NoteTextField(
        state = buffer,
        active = active,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp)
            .focusRequester(focusRequester),
        lineLimits = TextFieldLineLimits.SingleLine,
        textStyle = LoomaTokens.type.noteTitle.copy(
            color = LoomaTokens.colors.title,
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        onKeyboardAction = KeyboardActionHandler { onNext() },
        decorator = TextFieldDecorator { inner ->
            Box {
                if (text.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = LoomaTokens.type.noteTitle,
                        color = LoomaTokens.colors.muted,
                        maxLines = 1,
                    )
                }
                inner()
            }
        },
    )
}

@Composable
private fun NoteBodyEditor(
    active: Boolean,
    buffer: TextFieldState,
    focusRequester: FocusRequester,
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
) {
    val placeholder = stringResource(R.string.editor_placeholder)
    NoteTextField(
        state = buffer,
        active = active,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .focusRequester(focusRequester),
        textStyle = LoomaTokens.type.noteBody.copy(
            color = LoomaTokens.colors.title,
        ),
        scrollState = scrollState,
        decorator = TextFieldDecorator { innerTextField ->
            Box {
                if (buffer.text.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = LoomaTokens.type.noteBody,
                        color = LoomaTokens.colors.muted,
                    )
                }
                innerTextField()
            }
        },
    )
}

@Composable
private fun DraftRestoredNotice(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.editor_draft_restored),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun NoteReadBody(
    title: String,
    bodyText: String,
    fallbackTitle: String,
    scrollState: ScrollState,
    wiki: WikiContext,
    onWikiLinkClick: (String) -> Unit,
    onOpenNote: (NoteId) -> Unit,
    onCheckboxToggle: (position: Int, checked: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val document = remember(bodyText, fallbackTitle) {
        MarkdownIndex.index(bodyText, fallbackTitle)
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title.ifBlank { fallbackTitle },
            style = LoomaTokens.type.noteTitle,
            color = LoomaTokens.colors.title,
        )
        MarkdownBody(
            document = document,
            onCheckboxToggle = onCheckboxToggle,
            wiki = wiki,
            onWikiLinkClick = onWikiLinkClick,
        )
        BacklinksSection(backlinks = wiki.backlinks, onOpen = onOpenNote)
    }
}

private fun insertWikiLink(buffer: TextFieldState, reference: String) {
    val snippet = "[[$reference]]"
    val selection = buffer.selection
    val start = minOf(selection.start, selection.end)
    val end = maxOf(selection.start, selection.end)
    buffer.edit {
        replace(start, end, snippet)
        this.selection = TextRange(start + snippet.length)
    }
}

private const val MARKER_LENGTH = 3
private const val CHECKED_MARKER = "[x]"
private const val UNCHECKED_MARKER = "[ ]"

@Composable
private fun NoteLoadingScreen(
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = LoomaTokens.colors.noteCanvas,
        topBar = {
            NoteTopBar(
                mode = NoteMode.Read,
                onNavigateUp = onNavigateUp,
                onToggleMode = null,
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentAlignment = Alignment.Center,
        ) {
            LoomaLoadingIndicator()
        }
    }
}

@Composable
private fun NoteLoadErrorScreen(
    onNavigateUp: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = LoomaTokens.colors.noteCanvas,
        topBar = {
            NoteTopBar(
                mode = NoteMode.Read,
                onNavigateUp = onNavigateUp,
                onToggleMode = null,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.editor_load_failed),
                style = LoomaTokens.type.supporting,
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = onRetry) { Text(stringResource(R.string.editor_reload)) }
        }
    }
}

@Preview(showBackground = true, heightDp = 780)
@Composable
private fun NoteWorkspacePreview() {
    LoomaTheme(dynamicColor = false) {
        NoteWorkspace(
            state = NoteUiState(
                content = "# 周会记录\n\n- [ ] 补充**设计稿**\n- [x] 对齐接口\n\n> 记得同步给 @所有人",
                savedContent = "# 周会记录\n\n- [ ] 补充**设计稿**\n- [x] 对齐接口\n\n> 记得同步给 @所有人",
                isLoading = false,
                saveState = NoteSaveState.Saved,
            ),
            fallbackTitle = "周会记录",
            isNew = false,
            onContentChanged = {},
            onRequestLeave = {},
            onRetrySave = {},
            onOverwrite = {},
            onReloadFromDisk = {},
            onOpenNote = {},
        )
    }
}
