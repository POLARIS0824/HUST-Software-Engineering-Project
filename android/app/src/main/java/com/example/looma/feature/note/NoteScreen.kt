@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.example.looma.feature.note

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.looma.R
import com.example.looma.domain.markdown.MarkdownEditing
import com.example.looma.domain.markdown.MarkdownIndex
import com.example.looma.domain.markdown.TextSelection
import com.example.looma.domain.markdown.withCheckboxChecked
import com.example.looma.feature.note.components.NoteFormatBar
import com.example.looma.feature.note.components.NoteLeaveDialog
import com.example.looma.feature.note.components.NoteSaveBanner
import com.example.looma.feature.note.components.NoteTopBar
import com.example.looma.ui.markdown.MarkdownBody
import com.example.looma.ui.theme.LoomaTheme
import com.example.looma.ui.theme.LoomaTokens
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * 笔记页组合根。阅读和编辑是同一篇笔记的两种呈现，共用一份缓冲区，
 * 所以切换模式不会重新读盘，也不会出现"编辑完阅读页还是旧内容"。
 */
@Composable
fun NoteRoute(
    viewModel: NoteViewModel,
    fallbackTitle: String,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val requestLeave = {
        if (state.needsLeaveGuard) {
            viewModel.saveThenLeave(onSaved = onNavigateUp)
        } else {
            onNavigateUp()
        }
    }

    // 有未落盘内容时接管返回，先冲刷再走；干净状态交给 Navigation 3 做预测性返回
    BackHandler(enabled = state.needsLeaveGuard) {
        viewModel.saveThenLeave(onSaved = onNavigateUp)
    }

    val loadError = state.loadError
    when {
        state.isLoading -> NoteLoadingScreen(
            onNavigateUp = onNavigateUp,
            modifier = modifier,
        )

        loadError != null -> NoteLoadErrorScreen(
            message = loadError,
            onNavigateUp = onNavigateUp,
            onRetry = viewModel::load,
            modifier = modifier,
        )

        else -> NoteWorkspace(
            state = state,
            fallbackTitle = fallbackTitle,
            onContentChanged = viewModel::onContentChanged,
            onRequestLeave = requestLeave,
            onRetrySave = viewModel::retrySave,
            onOverwrite = viewModel::overwrite,
            modifier = modifier,
        )
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

/** 阅读与编辑共用的工作区；模式、焦点和两份滚动位置都在这一层持有 */
@Composable
private fun NoteWorkspace(
    state: NoteUiState,
    fallbackTitle: String,
    onContentChanged: (String) -> Unit,
    onRequestLeave: () -> Unit,
    onRetrySave: () -> Unit,
    onOverwrite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val buffer = rememberTextFieldState(
        initialText = state.content,
        initialSelection = TextRange(0),
    )
    var mode by rememberSaveable { mutableStateOf(NoteMode.Read) }
    val readScroll = rememberScrollState()
    val editScroll = rememberScrollState()
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    // 输入 → ViewModel 单向流动；阅读态里勾选复选框也走同一条通道
    LaunchedEffect(buffer) {
        snapshotFlow { buffer.text.toString() }
            .distinctUntilChanged()
            .collect(onContentChanged)
    }

    LaunchedEffect(mode) {
        if (mode == NoteMode.Edit) {
            focusRequester.requestFocus()
        } else {
            focusManager.clearFocus()
            keyboard?.hide()
        }
    }

    val text = buffer.text.toString()
    val selection = buffer.selection
    val formatState = remember(text, selection) {
        MarkdownEditing.formatState(
            text = text,
            selection = TextSelection(selection.start, selection.end),
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = LoomaTokens.colors.page,
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
            if (state.saveError != null) {
                NoteSaveBanner(
                    state = state,
                    onRetry = onRetrySave,
                    onOverwrite = onOverwrite,
                )
            }

            when (mode) {
                NoteMode.Read -> NoteReadBody(
                    text = text,
                    fallbackTitle = fallbackTitle,
                    scrollState = readScroll,
                    onCheckboxToggle = { position, checked ->
                        MarkdownIndex.index(buffer.text.toString(), fallbackTitle)
                            .withCheckboxChecked(position, checked)
                            ?.let { buffer.replaceAll(it) }
                    },
                    modifier = Modifier.weight(1f),
                )

                NoteMode.Edit -> {
                    NoteEditor(
                        buffer = buffer,
                        focusRequester = focusRequester,
                        scrollState = editScroll,
                        modifier = Modifier.weight(1f),
                    )
                    NoteFormatBar(
                        formatState = formatState,
                        canUndo = buffer.undoState.canUndo,
                        canRedo = buffer.undoState.canRedo,
                        onCommand = { command ->
                            applyMarkdownCommand(buffer, command)
                            focusRequester.requestFocus()
                        },
                        onUndo = {
                            buffer.undoState.undo()
                            focusRequester.requestFocus()
                        },
                        onRedo = {
                            buffer.undoState.redo()
                            focusRequester.requestFocus()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun NoteReadBody(
    text: String,
    fallbackTitle: String,
    scrollState: ScrollState,
    onCheckboxToggle: (position: Int, checked: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val document = remember(text, fallbackTitle) {
        MarkdownIndex.index(text, fallbackTitle)
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        MarkdownBody(document = document, onCheckboxToggle = onCheckboxToggle)
    }
}

@Composable
private fun NoteEditor(
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
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .focusRequester(focusRequester),
        textStyle = MaterialTheme.typography.bodyLarge.copy(
            color = LoomaTokens.colors.title,
            lineHeight = 26.sp,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        scrollState = scrollState,
        decorator = TextFieldDecorator { innerTextField ->
            Box {
                if (buffer.text.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp),
                        color = LoomaTokens.colors.muted,
                    )
                }
                innerTextField()
            }
        },
    )
}

/** 阅读态勾选复选框后整段替换缓冲区，光标回到开头，避免下一次进编辑页落在文末 */
private fun TextFieldState.replaceAll(text: String) {
    edit {
        replace(0, length, text)
        selection = TextRange(0)
    }
}

@Composable
private fun NoteLoadingScreen(    onNavigateUp: () -> Unit,
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
            CircularProgressIndicator()
        }
    }
}

@Composable
private fun NoteLoadErrorScreen(
    message: String,
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
            Text(message, color = MaterialTheme.colorScheme.error)
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
            onContentChanged = {},
            onRequestLeave = {},
            onRetrySave = {},
            onOverwrite = {},
        )
    }
}
