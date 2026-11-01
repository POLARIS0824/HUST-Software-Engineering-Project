@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package com.example.looma.feature.home.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideIn
import androidx.compose.animation.slideOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.AppBarWithSearch
import androidx.compose.material3.ExpandedFullScreenContainedSearchBar
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SearchBarScrollBehavior
import androidx.compose.material3.SearchBarState
import androidx.compose.material3.SearchBarValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberContainedSearchBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.looma.R
import com.example.looma.domain.model.NoteId
import com.example.looma.feature.home.HomeNoteItem
import com.example.looma.feature.home.HomeSort
import com.example.looma.feature.home.HomeUiState
import com.example.looma.ui.components.LoomaIcon
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * AppBarWithSearch + 全屏搜索结果，交互结构对齐 Material 3 官方
 * `FullScreenSearchBarScaffoldSample()`。
 *
 * 输入框状态、展开状态、动画、键盘都由组件自己持有，
 * 对外只有 [onQueryChange]（输入 → ViewModel）和 [onNoteClick]；
 * 过滤结果由 [HomeUiState.searchResults] 提供，组件不认识仓库。
 */
@Composable
internal fun HomeSearchBar(
    state: HomeUiState,
    scrollBehavior: SearchBarScrollBehavior,
    onQueryChange: (String) -> Unit,
    onToggleLayout: () -> Unit,
    onToggleSort: () -> Unit,
    onNoteClick: (NoteId) -> Unit,
    onMenuClick: () -> Unit,
    onAvatarClick: () -> Unit,
) {
    val textFieldState = rememberTextFieldState(initialText = state.query)
    val searchBarState = rememberContainedSearchBarState()
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val appBarColors = SearchBarDefaults.appBarWithSearchColors(
        searchBarColors = SearchBarDefaults.containedColors(state = searchBarState),
    )

    val collapse: () -> Unit = {
        keyboard?.hide()
        scope.launch { searchBarState.animateToCollapsed() }
    }

    // 单向数据流：输入框只向上报告最新文本，ViewModel 不反向写回，避免状态互相覆盖
    LaunchedEffect(textFieldState) {
        snapshotFlow { textFieldState.text.toString() }
            .distinctUntilChanged()
            .collect(onQueryChange)
    }

    // query 只在搜索展开期间有意义：收起（回到箭头、返回键、选中结果）后清空输入，
    // 首页恢复全量笔记，避免“输入框有词、列表却是全量”的不一致
    LaunchedEffect(searchBarState, textFieldState) {
        snapshotFlow { searchBarState.currentValue }
            .filter { it == SearchBarValue.Collapsed }
            .collect { textFieldState.setTextAndPlaceCursorAtEnd("") }
    }

    val inputField: @Composable () -> Unit = {
        val expanded = searchBarState.currentValue == SearchBarValue.Expanded
        SearchBarDefaults.InputField(
            textFieldState = textFieldState,
            searchBarState = searchBarState,
            colors = appBarColors.searchBarColors.inputFieldColors,
            onSearch = {
                keyboard?.hide()
                scope.launch { searchBarState.animateToCollapsed() }
            },
            placeholder = {
                HomeSearchPlaceholder(
                    text = if (expanded) "搜索 ${state.vaultName}" else "Looma",
                    emphasized = !expanded,
                )
            },
            leadingIcon = {
                if (expanded) {
                    IconButton(onClick = collapse) {
                        LoomaIcon(R.drawable.ic_arrow_back, contentDescription = "关闭搜索")
                    }
                } else {
                    LoomaIcon(R.drawable.ic_search, contentDescription = null)
                }
            },
            trailingIcon = {
                if (expanded) {
                    if (textFieldState.text.isNotEmpty()) {
                        IconButton(
                            onClick = { textFieldState.setTextAndPlaceCursorAtEnd("") },
                        ) { LoomaIcon(R.drawable.ic_close, contentDescription = "清除搜索内容") }
                    }
                } else {
                    HomeListControls(
                        isGridMode = state.isGridMode,
                        sort = state.sort,
                        onToggleLayout = onToggleLayout,
                        onToggleSort = onToggleSort,
                    )
                }
            },
        )
    }

    AppBarWithSearch(
        state = searchBarState,
        scrollBehavior = scrollBehavior,
        colors = appBarColors,
        inputField = inputField,
        navigationIcon = {
            HideWhenSearchExpanded(searchBarState, fromStart = true) {
                IconButton(onClick = onMenuClick) {
                    LoomaIcon(R.drawable.ic_menu, contentDescription = "打开侧边菜单")
                }
            }
        },
        actions = {
            HideWhenSearchExpanded(searchBarState, fromStart = false) {
                HomeAvatarButton(onClick = onAvatarClick)
            }
        },
    )

    ExpandedFullScreenContainedSearchBar(
        state = searchBarState,
        inputField = inputField,
        colors = appBarColors.searchBarColors,
    ) {
        HomeSearchResults(
            query = state.query,
            notes = state.searchResults,
            onNoteClick = { noteId ->
                collapse()
                onNoteClick(noteId)
            },
        )
    }
}

@Composable
private fun HomeSearchPlaceholder(text: String, emphasized: Boolean) {
    Text(
        text = text,
        // 占位文字不需要被读屏重复朗读
        modifier = Modifier.clearAndSetSemantics {},
        style = if (emphasized) {
            MaterialTheme.typography.titleLarge
        } else {
            MaterialTheme.typography.bodyLarge
        },
        fontWeight = if (emphasized) FontWeight.SemiBold else FontWeight.Normal,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
    )
}

@Composable
private fun HomeListControls(
    isGridMode: Boolean,
    sort: HomeSort,
    onToggleLayout: () -> Unit,
    onToggleSort: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onToggleLayout) {
            LoomaIcon(
                iconRes = if (isGridMode) R.drawable.ic_view_list else R.drawable.ic_grid,
                contentDescription = if (isGridMode) "切换为列表" else "切换为网格",
            )
        }
        IconButton(onClick = onToggleSort) {
            LoomaIcon(
                iconRes = R.drawable.ic_sort,
                contentDescription = if (sort == HomeSort.LAST_MODIFIED) {
                    "按标题排序"
                } else {
                    "按修改时间排序"
                },
            )
        }
    }
}

@Composable
private fun HomeAvatarButton(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .size(44.dp)
            .semantics { contentDescription = "笔记库设置" },
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text("L", style = MaterialTheme.typography.titleMedium)
        }
    }
}

/**
 * 官方 SampleNavigationIcon / SampleActions 的动画：
 * 搜索展开时导航图标向左、操作按钮向右滑出，收起时反向滑入。
 */
@Composable
private fun HideWhenSearchExpanded(
    searchBarState: SearchBarState,
    fromStart: Boolean,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = searchBarState.targetValue == SearchBarValue.Collapsed,
        enter = slideIn(
            animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
            initialOffset = { size -> IntOffset(if (fromStart) -size.width else size.width, 0) },
        ),
        exit = slideOut(
            animationSpec = tween(durationMillis = 150),
            targetOffset = { size -> IntOffset(if (fromStart) -size.width else size.width, 0) },
        ),
    ) { content() }
}

/** 独立的搜索结果列表，可单独 Preview；关键字与结果都来自同一个状态快照 */
@Composable
internal fun HomeSearchResults(
    query: String,
    notes: List<HomeNoteItem>,
    onNoteClick: (NoteId) -> Unit,
) {
    when {
        query.isBlank() -> HomeSearchHint("搜索标题、正文与标签")
        notes.isEmpty() -> HomeSearchHint("没有匹配的笔记")
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            items(notes, key = { it.listKey }) { note ->
                ListItem(
                    headlineContent = {
                        Text(note.summary.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    supportingContent = {
                        Text(
                            text = note.summary.excerpt.ifEmpty { note.id.path.relativePath },
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    leadingContent = { LoomaIcon(R.drawable.ic_search, contentDescription = null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClickLabel = "打开笔记") { onNoteClick(note.id) },
                )
            }
        }
    }
}

@Composable
private fun HomeSearchHint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
