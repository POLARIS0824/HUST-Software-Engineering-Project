package com.example.looma.feature.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.looma.R
import com.example.looma.domain.model.FolderPath
import com.example.looma.domain.model.NoteId
import com.example.looma.feature.home.components.HomeBreadcrumb
import com.example.looma.feature.home.components.HomeFilesGrid
import com.example.looma.feature.home.components.HomeSearchBar
import com.example.looma.ui.components.LoomaIcon
import com.example.looma.ui.theme.LoomaTokens

/**
 * 组合根：把 ViewModel 的状态接到无状态 UI 上。
 * 导航、生命周期、一次性事件都留在这里，ViewModel 不认识导航。
 */
@Composable
fun HomeRoute(
    viewModel: HomeViewModel,
    onOpenNote: (NoteId) -> Unit,
    onMenuClick: () -> Unit,
    onAssistantClick: () -> Unit,
    onAvatarClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // 从编辑器或外部编辑器回到前台时重新扫描 Markdown；首次进入也走这里，因此 ViewModel 不再 init 加载
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    // 新建成功是一次性事件：Route 负责导航，ViewModel 只交出一个 NoteId
    LaunchedEffect(state.createdNote) {
        state.createdNote?.let { noteId ->
            onOpenNote(noteId)
            viewModel.onCreatedNoteOpened()
        }
    }

    // 只在文件夹内接管返回键；根目录交给 NavDisplay，行为和以前一致。
    // 搜索展开时它自己的返回处理在 HomeScreen 内更晚注册，会先消费返回。
    BackHandler(enabled = state.isBrowsing) { viewModel.navigateUp() }

    HomeScreen(
        state = state,
        onQueryChange = viewModel::setQuery,
        onToggleLayout = viewModel::toggleLayout,
        onSortSelect = viewModel::setSort,
        onNoteClick = onOpenNote,
        onFolderClick = viewModel::openFolder,
        onNavigateUp = viewModel::navigateUp,
        onCreateNoteClick = viewModel::createNote,
        onMenuClick = onMenuClick,
        onAssistantClick = onAssistantClick,
        onAvatarClick = onAvatarClick,
        onRetryClick = viewModel::refresh,
        modifier = modifier,
    )
}

/** 无状态：同一份 [HomeUiState] 渲染出同一屏，可单独 Preview / 测试 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: HomeUiState,
    onQueryChange: (String) -> Unit,
    onToggleLayout: () -> Unit,
    onSortSelect: (HomeSort) -> Unit,
    onNoteClick: (NoteId) -> Unit,
    onFolderClick: (FolderPath) -> Unit,
    onNavigateUp: () -> Unit,
    onCreateNoteClick: () -> Unit,
    onMenuClick: () -> Unit,
    onAssistantClick: () -> Unit,
    onAvatarClick: () -> Unit,
    onRetryClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = SearchBarDefaults.enterAlwaysSearchBarScrollBehavior()
    var sortSheetVisible by rememberSaveable { mutableStateOf(false) }

    Box(modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
            containerColor = LoomaTokens.colors.page,
            topBar = {
                HomeSearchBar(
                    state = state,
                    scrollBehavior = scrollBehavior,
                    onQueryChange = onQueryChange,
                    onToggleLayout = onToggleLayout,
                    onSortClick = { sortSheetVisible = true },
                    onNoteClick = onNoteClick,
                    onMenuClick = onMenuClick,
                    onAvatarClick = onAvatarClick,
                )
            },
            floatingActionButton = {
                HomeActionButtons(
                    isCreating = state.isCreating,
                    onAssistantClick = onAssistantClick,
                    onCreateNoteClick = onCreateNoteClick,
                )
            },
        ) { innerPadding ->
            HomeContent(
                state = state,
                contentPadding = innerPadding,
                onNoteClick = onNoteClick,
                onFolderClick = onFolderClick,
                onNavigateUp = onNavigateUp,
                onRetryClick = onRetryClick,
            )
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
                    onSortSelect(sort)
                    sortSheetVisible = false
                },
            )
        }
    }
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
    onNoteClick: (NoteId) -> Unit,
    onFolderClick: (FolderPath) -> Unit,
    onNavigateUp: () -> Unit,
    onRetryClick: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        state.error?.let { error ->
            HomeErrorBanner(error = error, onRetryClick = onRetryClick)
        }
        when {
            // 只有“什么都没有”时才占满屏幕，避免每次回到前台都闪一下进度条
            state.isLoading && state.notes.isEmpty() -> HomeCentered {
                CircularProgressIndicator()
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
                        HomeBreadcrumb(crumbs = state.files.crumbs, onBack = onNavigateUp)
                    }
                    HomeFilesGrid(
                        files = state.files,
                        isGridMode = state.isGridMode,
                        onFolderClick = onFolderClick,
                        onNoteClick = onNoteClick,
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

@Composable
private fun HomeActionButtons(
    isCreating: Boolean,
    onAssistantClick: () -> Unit,
    onCreateNoteClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SmallFloatingActionButton(
            onClick = onAssistantClick,
            modifier = Modifier.size(58.dp),
            shape = RoundedCornerShape(20.dp),
            containerColor = LoomaTokens.colors.accent,
        ) {
            LoomaIcon(
                iconRes = R.drawable.ic_sparkle,
                contentDescription = stringResource(R.string.home_assistant_description),
                modifier = Modifier.size(28.dp),
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
