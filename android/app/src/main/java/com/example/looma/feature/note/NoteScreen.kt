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
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.example.looma.feature.note.components.WikiLinkPanel
import com.example.looma.feature.note.components.wikiReferenceFor
import com.example.looma.ui.components.LoomaIcon
import com.example.looma.ui.components.LoomaLoadingIndicator
import com.example.looma.ui.markdown.LocalImageSourceResolver
import com.example.looma.ui.markdown.MarkdownBody
import com.example.looma.ui.theme.BODY_LINE_HEIGHT
import com.example.looma.ui.theme.LoomaTheme
import com.example.looma.ui.theme.LoomaTokens
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * 笔记页组合根。阅读和编辑是同一篇笔记的两种呈现，共用一份正文，
 * 所以切换模式不会重新读盘，也不会出现"编辑完阅读页还是旧内容"。
 */
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

    // 从别的笔记回来时双链可能已经变了：那边可能新增了反向链接，或让未解析的目标存在了
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.refreshWiki() }
    // 退到后台先存草稿再冲刷：用 ON_PAUSE 而不是 ON_STOP，进程可能停在两者之间
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { viewModel.onEnterBackground() }

    // 有未落盘内容、或要丢弃空白笔记时接管返回；其余情况交给 Navigation 3 做预测性返回
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
            // 图片引用依赖库根与笔记位置，解析规则在 data 层，UI 只拿到结果
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

/**
 * 阅读与编辑共用的工作区；模式、焦点和两份滚动位置都在这一层持有。
 *
 * 标题与正文是两个输入框，但只有一份文档：每次改动都拼回 Markdown 交给 ViewModel，
 * 所以"标题在界面上是独立一格"不会让文件里多出一份标题数据。
 */
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
    modifier: Modifier = Modifier,
) {
    // reloadToken 变化表示正文换了来源（首次加载、冲突后重载）。
    // 缓冲区跟着 token 重建，而不是在 LaunchedEffect 里补写：否则重建前那一帧会把旧文本
    // 再发给 ViewModel，刚重载完的笔记立刻又变成"有未保存修改"。
    val loaded = remember(state.reloadToken) { EditorDocument.split(state.content) }
    val buffers = key(state.reloadToken) {
        // 光标落在正文末尾：打开一篇笔记是为了接着写，而不是从头找位置
        rememberTextFieldState(initialText = loaded.title) to
            rememberTextFieldState(
                initialText = loaded.body,
                initialSelection = TextRange(loaded.body.length),
            )
    }
    val titleBuffer = buffers.first
    val bodyBuffer = buffers.second
    // 笔记一律先进入编辑态：阅读是切换过去的另一种呈现，不是默认落点
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

    // 解析得到就打开，解析不到就报出目标名：跳转与否只由这一处决定
    val onWikiLinkClick: (String) -> Unit = { target ->
        val id = state.wiki.resolve(target)
        if (id != null) {
            onOpenNote(id)
        } else {
            scope.launch {
                snackbarHostState.showSnackbar(resources.getString(R.string.note_link_missing, target))
            }
        }
    }

    // 输入 → ViewModel 单向流动；标题、正文、阅读态勾选复选框都走这一条通道
    LaunchedEffect(bodyBuffer, titleBuffer) {
        snapshotFlow {
            EditorDocument(titleBuffer.text.toString(), bodyBuffer.text.toString()).render()
        }
            .distinctUntilChanged()
            .collect(onContentChanged)
    }

    LaunchedEffect(mode) {
        if (mode == NoteMode.Edit) {
            // 新建的笔记先落在标题上，直接打字就写标题；已有笔记落在正文末尾
            if (isNew) {
                titleFocus.requestFocus()
            } else {
                bodyFocus.requestFocus()
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
        containerColor = LoomaTokens.colors.page,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            NoteTopBar(
                mode = mode,
                onNavigateUp = onRequestLeave,
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
                        // 只改 "[ ]" 这三个字符，不整段替换缓冲区：光标、撤销栈和滚动位置都保住
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
                            buffer = titleBuffer,
                            placeholder = fallbackTitle,
                            focusRequester = titleFocus,
                            onNext = { bodyFocus.requestFocus() },
                        )
                        NoteBodyEditor(
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

/** 标题只有一行；新建笔记的标题缓冲区本来就是空的，所以占位文案只是提示 */
@Composable
private fun NoteTitleField(
    buffer: TextFieldState,
    placeholder: String,
    focusRequester: FocusRequester,
    onNext: () -> Unit,
) {
    val text = buffer.text.toString()
    BasicTextField(
        state = buffer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 10.dp)
            .focusRequester(focusRequester),
        lineLimits = TextFieldLineLimits.SingleLine,
        textStyle = MaterialTheme.typography.headlineMedium.copy(
            color = LoomaTokens.colors.title,
            fontWeight = FontWeight.SemiBold,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        onKeyboardAction = KeyboardActionHandler { onNext() },
        decorator = TextFieldDecorator { inner ->
            Box {
                if (text.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
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
    buffer: TextFieldState,
    focusRequester: FocusRequester,
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
) {
    val placeholder = stringResource(R.string.editor_placeholder)
    BasicTextField(
        state = buffer,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .focusRequester(focusRequester),
        // 行高与 Markdown 阅读态同一档，编辑态和阅读态切换时文字不会上下跳
        textStyle = LoomaTokens.type.body.copy(
            color = LoomaTokens.colors.title,
            lineHeight = BODY_LINE_HEIGHT.sp,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        scrollState = scrollState,
        decorator = TextFieldDecorator { innerTextField ->
            Box {
                if (buffer.text.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = LoomaTokens.type.body,
                        color = LoomaTokens.colors.muted,
                    )
                }
                innerTextField()
            }
        },
    )
}

/** 恢复草稿只在打开时提示一次，动一下正文就消失 */
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
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            ),
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

/** 在光标处插入 `[[引用]]`；已有选区就整段替换 */
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
        containerColor = LoomaTokens.colors.page,
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
        containerColor = LoomaTokens.colors.page,
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
