package com.example.looma.feature.home

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.IntOffset
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
    /** 点击与长按都只是事件：打开还是选中由 ViewModel 的选择规则决定 */
    val onNoteClick: (HomeNoteItem) -> Unit = {},
    val onNoteLongClick: (HomeNoteItem) -> Unit = {},
    val onFolderClick: (HomeFolderItem) -> Unit = {},
    val onFolderLongClick: (HomeFolderItem) -> Unit = {},
    val onBackgroundClick: () -> Unit = {},
    val onNavigateUp: () -> Unit = {},
    val onCreateNoteClick: () -> Unit = {},
    val onCreateFolderClick: () -> Unit = {},
    val onRenameFolderClick: () -> Unit = {},
    /** 笔记与文件夹共用：都启动系统目录选择器 */
    val onMoveClick: () -> Unit = {},
    val onDeleteFolderClick: () -> Unit = {},
    val onConfirmRenameFolder: (String) -> Unit = {},
    val onConfirmDeleteFolder: () -> Unit = {},
    val onClearSelection: () -> Unit = {},
    val onRenameClick: () -> Unit = {},
    val onInfoClick: () -> Unit = {},
    val onDeleteClick: () -> Unit = {},
    val onConfirmRename: (String) -> Unit = {},
    val onConfirmNewFolder: (String) -> Unit = {},
    val onDialogDismiss: () -> Unit = {},
    val onUndo: (HomeUndo) -> Unit = {},
    val onMessageShown: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onRecentRevealed: (RecentReveal) -> Unit = {},
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

    // 移动笔记和移动文件夹都走同一个系统目录选择器：选完换算成笔记库内的相对路径，
    // 移动的是谁由当前选择决定，ViewModel 只有一个入口
    val moveTargetPicker = rememberVaultFolderPicker(
        vaultRoot = state.vaultRootPath,
        onPicked = viewModel::moveSelectionTo,
        onRejected = { viewModel.moveSelectionTo(null) },
    )

    // 从编辑器或外部编辑器回到前台时重新扫描 Markdown；首次进入也走这里，因此 ViewModel 不再 init 加载
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    // 新建成功是一次性事件：Route 负责导航，ViewModel 只交出一个 NoteId
    LaunchedEffect(state.createdNote) {
        state.createdNote?.let { noteId ->
            onCreatedNote(noteId)
            viewModel.onCreatedNoteOpened()
        }
    }

    // 点开笔记同理：选择态下的点击只改选择，不会走到这里
    LaunchedEffect(state.noteToOpen) {
        state.noteToOpen?.let { noteId ->
            onOpenNote(noteId)
            viewModel.onNoteOpened()
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
            onNoteClick = { viewModel.onSelectionEvent(HomeSelectionEvent.NoteTapped(it)) },
            onNoteLongClick = { viewModel.onSelectionEvent(HomeSelectionEvent.NoteLongPressed(it)) },
            onFolderClick = { viewModel.onSelectionEvent(HomeSelectionEvent.FolderTapped(it)) },
            onFolderLongClick = { viewModel.onSelectionEvent(HomeSelectionEvent.FolderLongPressed(it)) },
            onBackgroundClick = { viewModel.onSelectionEvent(HomeSelectionEvent.BackgroundTapped) },
            onNavigateUp = viewModel::navigateUp,
            onCreateNoteClick = { viewModel.createNote(untitledTitle) },
            onCreateFolderClick = viewModel::startNewFolder,
            onRenameFolderClick = viewModel::startFolderRename,
            onMoveClick = { moveTargetPicker.launch(null) },
            onDeleteFolderClick = viewModel::startFolderDelete,
            onConfirmRenameFolder = viewModel::confirmRenameFolder,
            onConfirmDeleteFolder = viewModel::confirmDeleteFolder,
            onClearSelection = viewModel::clearSelection,
            onRenameClick = viewModel::startRename,
            onInfoClick = viewModel::startProperties,
            onDeleteClick = viewModel::deleteSelected,
            onConfirmRename = viewModel::confirmRename,
            onConfirmNewFolder = viewModel::confirmNewFolder,
            onDialogDismiss = viewModel::dismissDialog,
            onUndo = viewModel::undo,
            onMessageShown = viewModel::onMessageShown,
            onRefresh = viewModel::refreshFromUser,
            onRecentRevealed = viewModel::onRecentRevealed,
            onMenuClick = onMenuClick,
            onSettingsClick = onSettingsClick,
            onRetryClick = viewModel::refresh,
        ),
        modifier = modifier,
    )
}

/**
 * 把系统目录选择器的结果换算成笔记库内的相对路径。
 *
 * 笔记移动与文件夹移动各要一个 launcher，所以换算规则只写这一处；
 * 选到库外或取消都走 [onRejected]，用户取消不该被当成错误弹窗。
 */
@Composable
private fun rememberVaultFolderPicker(
    vaultRoot: String?,
    onPicked: (FolderPath) -> Unit,
    onRejected: () -> Unit,
): ManagedActivityResultLauncher<Uri?, Uri?> {
    val context = LocalContext.current
    val storageAccess = remember(context) { VaultStorageAccess(context) }
    return rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val target = storageAccess.resolveFolderWithin(uri, vaultRoot)
        if (target != null) onPicked(target) else onRejected()
    }
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
    // 顶栏切换的动效曲线取自主题，和搜索条用的同一套 expressive 参数
    val topBarMotion = MaterialTheme.motionScheme
    val undoLabel = stringResource(R.string.action_undo)
    val messageText = state.message?.let { homeNoticeText(it.notice) }
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        val text = messageText ?: return@LaunchedEffect
        val undo = message.undo
        val result = snackbarHostState.showSnackbar(
            message = text,
            actionLabel = if (undo != null) undoLabel else null,
            duration = if (undo != null) SnackbarDuration.Long else SnackbarDuration.Short,
        )
        if (result == SnackbarResult.ActionPerformed && undo != null) {
            actions.onUndo(undo)
        }
        actions.onMessageShown()
    }

    Box(modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = LoomaTokens.colors.page,
            topBar = {
                // 选择态与搜索态换顶栏时用主题里的 expressive 动效：淡入加一点上移，
                // 和搜索条自己的 morph 是同一套弹簧参数，不会一个生硬一个顺滑
                AnimatedContent(
                    targetState = state.isSelecting,
                    transitionSpec = {
                        val spatial = topBarMotion.defaultSpatialSpec<IntOffset>()
                        val effects = topBarMotion.defaultEffectsSpec<Float>()
                        (fadeIn(effects) + slideInVertically(spatial) { -it / 4 })
                            .togetherWith(fadeOut(effects) + slideOutVertically(spatial) { it / 4 })
                    },
                    label = "home-top-bar",
                ) { selecting ->
                    val folder = state.selectedFolder
                    if (selecting && folder != null) {
                        HomeSelectionBar(
                            title = folder.name,
                            showRename = true,
                            showInfo = false,
                            onCloseClick = actions.onClearSelection,
                            onRenameClick = actions.onRenameFolderClick,
                            onMoveClick = actions.onMoveClick,
                            onInfoClick = {},
                            onDeleteClick = actions.onDeleteFolderClick,
                        )
                    } else if (selecting) {
                        HomeSelectionBar(
                            title = stringResource(R.string.home_selection_count, state.selectedNotes.size),
                            showRename = state.selectedNotes.size == 1,
                            showInfo = state.selectedNotes.size == 1,
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
                    actions = actions.copy(onRecentRevealed = { request ->
                        scrollBehavior.scrollState.scrollOffset = 0f
                        scrollBehavior.scrollState.contentOffset = 0f
                        actions.onRecentRevealed(request)
                    }),
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
private fun homeNoticeText(notice: HomeNotice): String = when (notice) {
    is HomeNotice.Deleted -> stringResource(R.string.home_snackbar_deleted, notice.count)
    is HomeNotice.Restored -> stringResource(R.string.home_snackbar_restored, notice.count)
    is HomeNotice.Moved -> stringResource(R.string.home_snackbar_moved, notice.count)
    is HomeNotice.Renamed -> when {
        notice.linksFailed > 0 -> stringResource(R.string.home_snackbar_renamed_failed, notice.linksFailed)
        notice.linksUpdated > 0 -> stringResource(R.string.home_snackbar_renamed_links, notice.linksUpdated)
        else -> stringResource(R.string.home_snackbar_renamed)
    }
    HomeNotice.FolderCreated -> stringResource(R.string.home_snackbar_folder_created)
    HomeNotice.FolderRenamed -> stringResource(R.string.home_folder_snackbar_renamed)
    HomeNotice.FolderMoved -> stringResource(R.string.home_folder_snackbar_moved)
    is HomeNotice.FolderDeleted -> if (notice.notes > 0) {
        pluralStringResource(R.plurals.home_folder_snackbar_deleted, notice.notes, notice.notes)
    } else {
        stringResource(R.string.home_folder_snackbar_deleted_empty)
    }
    HomeNotice.NameTaken -> stringResource(R.string.home_error_duplicate_name)
    HomeNotice.ActionFailed -> stringResource(R.string.home_error_action)
    HomeNotice.InvalidMoveTarget -> stringResource(R.string.home_move_invalid_target)
    HomeNotice.Undone -> stringResource(R.string.action_undone)
    HomeNotice.UndoFailed -> stringResource(R.string.action_undo_failed)
}

@Composable
private fun HomeSortSheet(current: HomeSort, onSelect: (HomeSort) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
        Text(
            text = stringResource(R.string.home_sort_sheet_title),
            style = LoomaTokens.type.pageTitle,
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
        HomeSortOption(
            label = stringResource(R.string.home_sort_title_descending),
            selected = current == HomeSort.TITLE_DESCENDING,
            onClick = { onSelect(HomeSort.TITLE_DESCENDING) },
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
                style = LoomaTokens.type.body,
                color = LoomaTokens.colors.title,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun HomeContent(
    state: HomeUiState,
    contentPadding: PaddingValues,
    actions: HomeActions,
) {
    val motion = MaterialTheme.motionScheme
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
                    style = LoomaTokens.type.supporting,
                    color = LoomaTokens.colors.muted,
                )
            }

            else -> AnimatedContent(
                targetState = state,
                contentKey = { it.files.path },
                transitionSpec = {
                    val direction = when (folderMotion(initialState.files.path, targetState.files.path)) {
                        FolderMotion.Forward -> 1
                        FolderMotion.Backward -> -1
                        FolderMotion.Replace -> 0
                    }
                    val spatial = motion.defaultSpatialSpec<IntOffset>()
                    val effects = motion.defaultEffectsSpec<Float>()
                    (fadeIn(effects) + slideInHorizontally(spatial) { direction * it / 8 })
                        .togetherWith(fadeOut(effects) + slideOutHorizontally(spatial) { -direction * it / 8 })
                        .using(null)
                },
                label = "home-folder",
            ) { snapshot ->
                Column(Modifier.fillMaxSize()) {
                    if (!snapshot.files.isAtRoot) {
                        HomeBreadcrumb(crumbs = snapshot.files.crumbs, onBack = actions.onNavigateUp)
                    }
                    // 选择态下点空白处取消选择；不在选择态时不挂 clickable，
                    // 手势树与原来完全一致，滚动和下拉刷新都不受影响
                    Box(
                        Modifier
                            .fillMaxSize()
                            .then(
                                if (snapshot.isSelecting) {
                                    Modifier.clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = actions.onBackgroundClick,
                                    )
                                } else {
                                    Modifier
                                },
                            ),
                    ) {
                        HomeFilesGrid(
                            files = snapshot.files,
                            isGridMode = snapshot.isGridMode,
                            selection = snapshot.selection,
                            recentReveal = snapshot.recentReveal,
                            onRecentRevealed = actions.onRecentRevealed,
                            onFolderClick = actions.onFolderClick,
                            onFolderLongClick = actions.onFolderLongClick,
                            onNoteClick = actions.onNoteClick,
                            onNoteLongClick = actions.onNoteLongClick,
                        )
                    }
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
