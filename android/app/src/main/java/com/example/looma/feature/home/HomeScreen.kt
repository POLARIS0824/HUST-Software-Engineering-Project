package com.example.looma.feature.home

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.looma.R
import com.example.looma.data.vault.VaultStorageAccess
import com.example.looma.domain.model.FolderPath
import com.example.looma.domain.model.NoteId
import com.example.looma.feature.home.components.HomeBreadcrumb
import com.example.looma.feature.home.components.HomeDeleteFolderDialog
import com.example.looma.feature.home.components.HomeFilesGrid
import com.example.looma.feature.home.components.HomeFolderActionsDialog
import com.example.looma.feature.home.components.HomeMoveFolderDialog
import com.example.looma.feature.home.components.HomePropertiesDialog
import com.example.looma.feature.home.components.HomeSearchBar
import com.example.looma.feature.home.components.HomeSelectionBar
import com.example.looma.feature.home.components.HomeTextInputDialog
import com.example.looma.ui.components.LoomaIcon
import com.example.looma.ui.components.LoomaLoadingIndicator
import com.example.looma.ui.theme.LoomaTokens

/** 首页全部事件出口；屏幕本身无状态，Preview 与测试可以直接给空实现 */
@Immutable
data class HomeActions(
    val onQueryChange: (String) -> Unit = {},
    val onSearchSubmit: () -> Unit = {},
    val onSearchResultClick: (NoteId) -> Unit = {},
    val onToggleLayout: () -> Unit = {},
    val onSortSelect: (HomeSort) -> Unit = {},
    val onNoteClick: (NoteId) -> Unit = {},
    val onFolderClick: (FolderPath) -> Unit = {},
    val onNavigateUp: () -> Unit = {},
    val onCreateNoteClick: () -> Unit = {},
    val onCreateFolderClick: () -> Unit = {},
    val onFolderActions: (HomeFolderItem) -> Unit = {},
    val onRenameFolderClick: () -> Unit = {},
    val onMoveFolderClick: () -> Unit = {},
    val onDeleteFolderClick: () -> Unit = {},
    val onConfirmRenameFolder: (String) -> Unit = {},
    val onConfirmMoveFolder: (FolderPath) -> Unit = {},
    val onConfirmDeleteFolder: () -> Unit = {},
    val onToggleSelection: (HomeNoteItem) -> Unit = {},
    val onClearSelection: () -> Unit = {},
    val onRenameClick: () -> Unit = {},
    val onMoveClick: () -> Unit = {},
    val onInfoClick: () -> Unit = {},
    val onDeleteClick: () -> Unit = {},
    val onConfirmRename: (String) -> Unit = {},
    val onConfirmNewFolder: (String) -> Unit = {},
    val onDialogDismiss: () -> Unit = {},
    val onUndoDelete: (List<String>) -> Unit = {},
    val onMessageShown: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onRecentsScrolled: () -> Unit = {},
    val onMenuClick: () -> Unit = {},
    val onSettingsClick: () -> Unit = {},
    val onRetryClick: () -> Unit = {},
)

/**
 * 组合根：把 ViewModel 的状态接到无状态 UI 上。
 * 导航、生命周期、目录选择器和一次性事件都留在这里，ViewModel 不认识它们。
 */
@Composable
fun HomeRoute(
    viewModel: HomeViewModel,
    onOpenNote: (NoteId) -> Unit,
    onCreatedNote: (NoteId) -> Unit,
    onMenuClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val untitledTitle = stringResource(R.string.home_untitled_note)
    val context = LocalContext.current
    val storageAccess = remember(context) { VaultStorageAccess(context) }

    // 移动目标复用系统的目录选择器：选完只要换算成笔记库内的相对路径
    val moveTargetPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            val target = storageAccess.resolveFolderWithin(uri, state.vaultRootPath)
            if (target != null) viewModel.moveSelectedTo(target) else viewModel.onMoveTargetRejected()
        }
    }

    // 从编辑器或外部编辑器回到前台时重新扫描 Markdown；首次进入也走这里，因此 ViewModel 不再 init 加载
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    // 新建成功是一次性事件：Route 负责导航，ViewModel 只交出一个 NoteId
    LaunchedEffect(state.createdNote) {
        state.createdNote?.let { noteId ->
            onCreatedNote(noteId)
            viewModel.onCreatedNoteOpened()
        }
    }

    // 选择模式优先消费返回键；否则仅在文件夹内接管，根目录交给 NavDisplay。
    // 搜索展开时它自己的返回处理在 HomeScreen 内更晚注册，会先消费返回。
    BackHandler(enabled = state.isSelecting || state.isBrowsing) {
        if (state.isSelecting) viewModel.clearSelection() else viewModel.navigateUp()
    }

    HomeScreen(
        state = state,
        actions = HomeActions(
            onQueryChange = viewModel::setQuery,
            onSearchSubmit = viewModel::recordSearch,
            onSearchResultClick = { noteId ->
                viewModel.recordSearch()
                onOpenNote(noteId)
            },
            onToggleLayout = viewModel::toggleLayout,
            onSortSelect = viewModel::setSort,
            onNoteClick = onOpenNote,
            onFolderClick = viewModel::openFolder,
            onNavigateUp = viewModel::navigateUp,
            onCreateNoteClick = { viewModel.createNote(untitledTitle) },
            onCreateFolderClick = viewModel::startNewFolder,
            onFolderActions = viewModel::startFolderActions,
            onRenameFolderClick = viewModel::startFolderRename,
            onMoveFolderClick = viewModel::startFolderMove,
            onDeleteFolderClick = viewModel::startFolderDelete,
            onConfirmRenameFolder = viewModel::confirmRenameFolder,
            onConfirmMoveFolder = viewModel::confirmMoveFolder,
            onConfirmDeleteFolder = viewModel::confirmDeleteFolder,
            onToggleSelection = viewModel::toggleSelection,
            onClearSelection = viewModel::clearSelection,
            onRenameClick = viewModel::startRename,
            onMoveClick = { moveTargetPicker.launch(null) },
            onInfoClick = viewModel::startProperties,
            onDeleteClick = viewModel::deleteSelected,
            onConfirmRename = viewModel::confirmRename,
            onConfirmNewFolder = viewModel::confirmNewFolder,
            onDialogDismiss = viewModel::dismissDialog,
            onUndoDelete = viewModel::undoDelete,
            onMessageShown = viewModel::onMessageShown,
            onRefresh = viewModel::refreshFromUser,
            onRecentsScrolled = viewModel::onRecentsScrolled,
            onMenuClick = onMenuClick,
            onSettingsClick = onSettingsClick,
            onRetryClick = viewModel::refresh,
        ),
        modifier = modifier,
    )
}

/** 无状态：同一份 [HomeUiState] 渲染出同一屏，可单独 Preview / 测试 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HomeScreen(
    state: HomeUiState,
    actions: HomeActions,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = SearchBarDefaults.enterAlwaysSearchBarScrollBehavior()
    var sortSheetVisible by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val pullToRefreshState = rememberPullToRefreshState()
    val undoLabel = stringResource(R.string.home_undo)
    val messageText = state.message?.let { homeMessageText(it) }
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        val text = messageText ?: return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = text,
            actionLabel = if (message is HomeMessage.Deleted) undoLabel else null,
            duration = if (message is HomeMessage.Deleted) {
                SnackbarDuration.Long
            } else {
                SnackbarDuration.Short
            },
        )
        if (result == SnackbarResult.ActionPerformed && message is HomeMessage.Deleted) {
            actions.onUndoDelete(message.trashIds)
        }
        actions.onMessageShown()
    }

    Box(modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = LoomaTokens.colors.page,
            topBar = {
                if (state.isSelecting) {
                    HomeSelectionBar(
                        state = state,
                        onCloseClick = actions.onClearSelection,
                        onRenameClick = actions.onRenameClick,
                        onMoveClick = actions.onMoveClick,
                        onInfoClick = actions.onInfoClick,
                        onDeleteClick = actions.onDeleteClick,
                    )
                } else {
                    HomeSearchBar(
                        state = state,
                        scrollBehavior = scrollBehavior,
                        onQueryChange = actions.onQueryChange,
                        onToggleLayout = actions.onToggleLayout,
                        onSortClick = { sortSheetVisible = true },
                        onSearchSubmit = actions.onSearchSubmit,
                        onSearchResultClick = actions.onSearchResultClick,
                        onMenuClick = actions.onMenuClick,
                        onAvatarClick = actions.onSettingsClick,
                    )
                }
            },
            floatingActionButton = {
                if (!state.isSelecting) {
                    HomeActionButtons(
                        isCreating = state.isCreating,
                        onCreateFolderClick = actions.onCreateFolderClick,
                        onCreateNoteClick = actions.onCreateNoteClick,
                    )
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { innerPadding ->
            PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = actions.onRefresh,
                state = pullToRefreshState,
                // 下拉指示器同样用 M3 Expressive 的 LoadingIndicator，和整页加载保持一致
                indicator = {
                    PullToRefreshDefaults.LoadingIndicator(
                        state = pullToRefreshState,
                        isRefreshing = state.isRefreshing,
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                },
                modifier = Modifier.fillMaxSize().padding(innerPadding),
            ) {
                HomeContent(
                    state = state,
                    contentPadding = PaddingValues(),
                    actions = actions,
                )
            }
        }

        // 顶栏收起后列表会滑到状态栏下方，用页面底色遮住状态栏区域，避免文字压在状态栏图标上
        Box(
            Modifier
                .fillMaxWidth()
                .windowInsetsTopHeight(WindowInsets.statusBars)
                .background(LoomaTokens.colors.page),
        )
    }

    if (sortSheetVisible) {
        ModalBottomSheet(
            onDismissRequest = { sortSheetVisible = false },
            containerColor = LoomaTokens.colors.sheet,
        ) {
            HomeSortSheet(
                current = state.sort,
                onSelect = { sort ->
                    actions.onSortSelect(sort)
                    sortSheetVisible = false
                },
            )
        }
    }

    HomeFileDialogs(state = state, actions = actions)
}

@Composable
private fun HomeFileDialogs(state: HomeUiState, actions: HomeActions) {
    when (val dialog = state.dialog) {
        null -> Unit

        is HomeFileDialog.Rename -> HomeTextInputDialog(
            title = stringResource(R.string.home_rename_title),
            hint = stringResource(R.string.home_rename_hint),
            initialValue = dialog.initialName,
            confirmLabel = stringResource(R.string.dialog_save),
            error = state.dialogError,
            busy = state.isActionRunning,
            onConfirm = actions.onConfirmRename,
            onDismiss = actions.onDialogDismiss,
        )

        is HomeFileDialog.Properties -> HomePropertiesDialog(
            properties = dialog.properties,
            onDismiss = actions.onDialogDismiss,
        )

        HomeFileDialog.NewFolder -> HomeTextInputDialog(
            title = stringResource(R.string.home_new_folder_title),
            hint = stringResource(R.string.home_new_folder_hint),
            initialValue = "",
            confirmLabel = stringResource(R.string.dialog_create),
            error = state.dialogError,
            busy = state.isActionRunning,
            onConfirm = actions.onConfirmNewFolder,
            onDismiss = actions.onDialogDismiss,
        )

        is HomeFileDialog.FolderActions -> HomeFolderActionsDialog(
            folderName = dialog.folder.name,
            onRename = actions.onRenameFolderClick,
            onMove = actions.onMoveFolderClick,
            onDelete = actions.onDeleteFolderClick,
            onDismiss = actions.onDialogDismiss,
        )

        is HomeFileDialog.RenameFolder -> HomeTextInputDialog(
            title = stringResource(R.string.home_folder_rename_title),
            hint = stringResource(R.string.home_folder_rename_hint),
            initialValue = dialog.initialName,
            confirmLabel = stringResource(R.string.dialog_save),
            error = state.dialogError,
            busy = state.isActionRunning,
            onConfirm = actions.onConfirmRenameFolder,
            onDismiss = actions.onDialogDismiss,
        )

        is HomeFileDialog.MoveFolder -> HomeMoveFolderDialog(
            folderName = dialog.folderName,
            targets = dialog.targets,
            error = state.dialogError,
            busy = state.isActionRunning,
            onSelect = actions.onConfirmMoveFolder,
            onDismiss = actions.onDialogDismiss,
        )

        is HomeFileDialog.DeleteFolder -> HomeDeleteFolderDialog(
            folderName = dialog.folderName,
            noteCount = dialog.noteCount,
            error = state.dialogError,
            busy = state.isActionRunning,
            onConfirm = actions.onConfirmDeleteFolder,
            onDismiss = actions.onDialogDismiss,
        )
    }
}

@Composable
private fun homeMessageText(message: HomeMessage): String = when (message) {
    is HomeMessage.Deleted -> stringResource(R.string.home_snackbar_deleted, message.count)
    is HomeMessage.Restored -> stringResource(R.string.home_snackbar_restored, message.count)
    is HomeMessage.Moved -> stringResource(R.string.home_snackbar_moved, message.count)
    is HomeMessage.Renamed -> when {
        message.linksFailed > 0 -> stringResource(R.string.home_snackbar_renamed_failed, message.linksFailed)
        message.linksUpdated > 0 -> stringResource(R.string.home_snackbar_renamed_links, message.linksUpdated)
        else -> stringResource(R.string.home_snackbar_renamed)
    }
    HomeMessage.FolderCreated -> stringResource(R.string.home_snackbar_folder_created)
    HomeMessage.FolderRenamed -> stringResource(R.string.home_folder_snackbar_renamed)
    HomeMessage.FolderMoved -> stringResource(R.string.home_folder_snackbar_moved)
    is HomeMessage.FolderDeleted -> if (message.notes > 0) {
        pluralStringResource(R.plurals.home_folder_snackbar_deleted, message.notes, message.notes)
    } else {
        stringResource(R.string.home_folder_snackbar_deleted_empty)
    }
    HomeMessage.ActionFailed -> stringResource(R.string.home_error_action)
    HomeMessage.InvalidMoveTarget -> stringResource(R.string.home_move_invalid_target)
}

@Composable
private fun HomeSortSheet(current: HomeSort, onSelect: (HomeSort) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        Text(
            text = stringResource(R.string.home_sort_sheet_title),
            style = LoomaTokens.type.sheetTitle,
            color = LoomaTokens.colors.title,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
        )
        HomeSortOption(
            label = stringResource(R.string.home_sort_last_modified),
            selected = current == HomeSort.LAST_MODIFIED,
            onClick = { onSelect(HomeSort.LAST_MODIFIED) },
        )
        HomeSortOption(
            label = stringResource(R.string.home_sort_title),
            selected = current == HomeSort.TITLE,
            onClick = { onSelect(HomeSort.TITLE) },
        )
    }
}

@Composable
private fun HomeSortOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(28.dp),
        color = if (selected) LoomaTokens.colors.chip else Color.Transparent,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selected) {
                LoomaIcon(
                    iconRes = R.drawable.ic_check,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = LoomaTokens.colors.accent,
                )
            } else {
                Spacer(Modifier.width(22.dp))
            }
            Spacer(Modifier.width(16.dp))
            Text(
                text = label,
                style = LoomaTokens.type.sheetOption,
                color = LoomaTokens.colors.title,
            )
        }
    }
}

@Composable
private fun HomeContent(
    state: HomeUiState,
    contentPadding: PaddingValues,
    actions: HomeActions,
) {
    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        state.error?.let { error ->
            HomeErrorBanner(error = error, onRetryClick = actions.onRetryClick)
        }
        when {
            // 只有“什么都没有”时才占满屏幕，避免每次回到前台都闪一下进度条
            state.isLoading && state.notes.isEmpty() -> HomeCentered {
                LoomaLoadingIndicator()
            }

            state.files.isAtRoot && state.files.isEmpty -> HomeCentered {
                Text(
                    text = stringResource(R.string.home_empty_vault),
                    style = LoomaTokens.type.emptyState,
                    color = LoomaTokens.colors.muted,
                )
            }

            else -> key(state.files.path) {
                Column(Modifier.fillMaxSize()) {
                    if (!state.files.isAtRoot) {
                        HomeBreadcrumb(crumbs = state.files.crumbs, onBack = actions.onNavigateUp)
                    }
                    HomeFilesGrid(
                        files = state.files,
                        isGridMode = state.isGridMode,
                        selection = state.selection,
                        scrollRecentsToStart = state.scrollRecentsToStart,
                        onRecentsScrolled = actions.onRecentsScrolled,
                        onFolderClick = actions.onFolderClick,
                        onFolderActions = actions.onFolderActions,
                        onNoteClick = { note ->
                            if (state.isSelecting) {
                                actions.onToggleSelection(note)
                            } else {
                                actions.onNoteClick(note.id)
                            }
                        },
                        onNoteLongClick = actions.onToggleSelection,
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeErrorBanner(error: HomeError, onRetryClick: () -> Unit) {
    val message = when (error) {
        is HomeError.UnreadableNotes -> stringResource(R.string.home_error_unreadable, error.count)
        HomeError.LoadFailed -> stringResource(R.string.home_error_load)
        HomeError.CreateFailed -> stringResource(R.string.home_error_create)
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = message,
            style = LoomaTokens.type.cardBody,
            color = LoomaTokens.colors.error,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRetryClick) {
            Text(stringResource(R.string.home_retry))
        }
    }
}

@Composable
private fun HomeCentered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

/** 右下角两枚动作：新建文件夹（小）与新建笔记（大），与 Keep 的层级一致 */
@Composable
private fun HomeActionButtons(
    isCreating: Boolean,
    onCreateFolderClick: () -> Unit,
    onCreateNoteClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SmallFloatingActionButton(
            onClick = onCreateFolderClick,
            modifier = Modifier.size(58.dp),
            shape = RoundedCornerShape(20.dp),
            containerColor = LoomaTokens.colors.accentContainer,
        ) {
            LoomaIcon(
                iconRes = R.drawable.ic_folder_plus,
                contentDescription = stringResource(R.string.home_new_folder_description),
                modifier = Modifier.size(26.dp),
            )
        }

        LargeFloatingActionButton(
            onClick = onCreateNoteClick,
            modifier = Modifier.size(88.dp),
            shape = RoundedCornerShape(28.dp),
            containerColor = LoomaTokens.colors.accentContainer,
        ) {
            if (isCreating) {
                CircularProgressIndicator(Modifier.size(28.dp))
            } else {
                LoomaIcon(
                    iconRes = R.drawable.ic_add,
                    contentDescription = stringResource(R.string.home_create_note_description),
                    modifier = Modifier.size(38.dp),
                )
            }
        }
    }
}
