package com.example.looma.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.looma.R
import com.example.looma.domain.model.NoteId
import com.example.looma.feature.home.components.HomeNoteGrid
import com.example.looma.feature.home.components.HomeSearchBar
import com.example.looma.ui.components.LoomaIcon

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

    HomeScreen(
        state = state,
        onQueryChange = viewModel::setQuery,
        onToggleLayout = viewModel::toggleLayout,
        onToggleSort = viewModel::toggleSort,
        onNoteClick = onOpenNote,
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
    onToggleSort: () -> Unit,
    onNoteClick: (NoteId) -> Unit,
    onCreateNoteClick: () -> Unit,
    onMenuClick: () -> Unit,
    onAssistantClick: () -> Unit,
    onAvatarClick: () -> Unit,
    onRetryClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollBehavior = SearchBarDefaults.enterAlwaysSearchBarScrollBehavior()

    Scaffold(
        modifier = modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            HomeSearchBar(
                state = state,
                scrollBehavior = scrollBehavior,
                onQueryChange = onQueryChange,
                onToggleLayout = onToggleLayout,
                onToggleSort = onToggleSort,
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
            onRetryClick = onRetryClick,
        )
    }
}

@Composable
private fun HomeContent(
    state: HomeUiState,
    contentPadding: PaddingValues,
    onNoteClick: (NoteId) -> Unit,
    onRetryClick: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        state.errorMessage?.let { message ->
            HomeErrorBanner(message = message, onRetryClick = onRetryClick)
        }
        when {
            // 只有“什么都没有”时才占满屏幕，避免每次回到前台都闪一下进度条
            state.isLoading && state.notes.isEmpty() -> HomeCentered {
                CircularProgressIndicator()
            }

            state.notes.isEmpty() -> HomeCentered {
                Text(
                    text = "还没有笔记，点击 + 新建",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> HomeNoteGrid(
                notes = state.notes,
                isGridMode = state.isGridMode,
                onNoteClick = onNoteClick,
            )
        }
    }
}

@Composable
private fun HomeErrorBanner(message: String, onRetryClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRetryClick) {
            Text("重试")
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
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ) {
            LoomaIcon(
                iconRes = R.drawable.ic_sparkle,
                contentDescription = "辅助功能",
                modifier = Modifier.size(28.dp),
            )
        }

        LargeFloatingActionButton(
            onClick = onCreateNoteClick,
            modifier = Modifier.size(88.dp),
            shape = RoundedCornerShape(28.dp),
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            if (isCreating) {
                CircularProgressIndicator(Modifier.size(28.dp))
            } else {
                LoomaIcon(
                    iconRes = R.drawable.ic_add,
                    contentDescription = if (isCreating) "正在创建笔记" else "新建笔记",
                    modifier = Modifier.size(38.dp),
                )
            }
        }
    }
}
