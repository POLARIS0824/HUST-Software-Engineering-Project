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
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.looma.R
import com.example.looma.domain.model.NoteId
import com.example.looma.feature.home.HomeNoteItem
import com.example.looma.feature.home.HomeUiState
import com.example.looma.ui.components.LoomaIcon
import com.example.looma.ui.theme.LoomaTokens
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
    onSortClick: () -> Unit,
    onNoteClick: (NoteId) -> Unit,
    onMenuClick: () -> Unit,
    onAvatarClick: () -> Unit,
) {
    val textFieldState = rememberTextFieldState(initialText = state.query)
    val searchBarState = rememberContainedSearchBarState()
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    // 搜索条和页面同底色，输入框用卡片色，靠明度差分层；不画描边。
    val pageColor = LoomaTokens.colors.page
    val fieldColor = LoomaTokens.colors.searchField
    val baseSearchColors = SearchBarDefaults.containedColors(state = searchBarState)
    val fieldColors = baseSearchColors.inputFieldColors.copy(
        focusedContainerColor = fieldColor,
        unfocusedContainerColor = fieldColor,
    )
    val appBarColors = SearchBarDefaults.appBarWithSearchColors(
        searchBarColors = baseSearchColors.copy(
            containerColor = fieldColor,
            dividerColor = Color.Transparent,
            inputFieldColors = fieldColors,
        ),
        scrolledSearchBarContainerColor = fieldColor,
        appBarContainerColor = pageColor,
        scrolledAppBarContainerColor = pageColor,
        appBarNavigationIconColor = LoomaTokens.colors.title,
        appBarActionIconColor = LoomaTokens.colors.title,
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
            textStyle = LoomaTokens.type.searchText,
            onSearch = {
                keyboard?.hide()
                scope.launch { searchBarState.animateToCollapsed() }
            },
            placeholder = {
                HomeSearchPlaceholder(
                    text = stringResource(R.string.home_search_placeholder, state.vaultName),
                )
            },
            leadingIcon = {
                if (expanded) {
                    IconButton(onClick = collapse) {
                        LoomaIcon(
                            iconRes = R.drawable.ic_arrow_back,
                            contentDescription = stringResource(R.string.home_close_search_description),
                        )
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
                        ) {
                            LoomaIcon(
                                iconRes = R.drawable.ic_close,
                                contentDescription = stringResource(R.string.home_clear_search_description),
                            )
                        }
                    }
                } else {
                    HomeListControls(
                        isGridMode = state.isGridMode,
                        onToggleLayout = onToggleLayout,
                        onSortClick = onSortClick,
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
                    LoomaIcon(
                        iconRes = R.drawable.ic_menu,
                        contentDescription = stringResource(R.string.home_menu_description),
                    )
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
private fun HomeSearchPlaceholder(text: String) {
    Text(
        text = text,
        // 占位文字不需要被读屏重复朗读
        modifier = Modifier.clearAndSetSemantics {},
        style = LoomaTokens.type.searchText,
        color = LoomaTokens.colors.muted,
        maxLines = 1,
    )
}

@Composable
private fun HomeListControls(
    isGridMode: Boolean,
    onToggleLayout: () -> Unit,
    onSortClick: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onToggleLayout) {
            LoomaIcon(
                iconRes = if (isGridMode) R.drawable.ic_view_list else R.drawable.ic_grid,
                contentDescription = stringResource(
                    if (isGridMode) R.string.home_switch_to_list_description else R.string.home_switch_to_grid_description,
                ),
            )
        }
        IconButton(onClick = onSortClick) {
            LoomaIcon(
                iconRes = R.drawable.ic_sort,
                contentDescription = stringResource(R.string.home_sort_description),
            )
        }
    }
}

@Composable
private fun HomeAvatarButton(onClick: () -> Unit) {
    val avatarDescription = stringResource(R.string.home_avatar_description)
    Surface(
        onClick = onClick,
        modifier = Modifier
            .padding(end = 4.dp)
            .size(40.dp)
            .semantics { contentDescription = avatarDescription },
        shape = CircleShape,
        color = LoomaTokens.colors.accentContainer,
        border = BorderStroke(2.dp, LoomaTokens.colors.avatarRing),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.home_avatar_letter),
                style = LoomaTokens.type.avatar,
            )
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
        query.isBlank() -> HomeSearchHint(stringResource(R.string.home_search_hint))
        notes.isEmpty() -> HomeSearchHint(stringResource(R.string.home_search_no_match))
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            items(notes, key = { it.listKey }) { note ->
                ListItem(
                    headlineContent = {
                        Text(
                            text = note.summary.title,
                            style = LoomaTokens.type.cardTitle,
                            color = LoomaTokens.colors.title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    supportingContent = {
                        Text(
                            text = note.summary.excerpt.ifEmpty { note.id.path.relativePath },
                            style = LoomaTokens.type.cardBody,
                            color = LoomaTokens.colors.body,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    leadingContent = { LoomaIcon(R.drawable.ic_search, contentDescription = null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClickLabel = stringResource(R.string.home_open_note_label)) {
                            onNoteClick(note.id)
                        },
                )
            }
        }
    }
}

@Composable
private fun HomeSearchHint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = LoomaTokens.type.emptyState, color = LoomaTokens.colors.muted)
    }
}
