@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package com.example.looma.feature.home.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
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
import androidx.compose.material3.ExpandedFullScreenSearchBar
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SearchBarScrollBehavior
import androidx.compose.material3.SearchBarValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
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
 * AppBarWithSearch + 全屏搜索结果。
 *
 * 收起时是一条胶囊，夹在抽屉按钮和头像中间；这两者留在胶囊外面，
 * 展开时只淡出、不滑动，胶囊本身连续长成搜索结果面板。
 * 胶囊两边的宽度不随展开变化，所以展开过程中它不会被挤压。
 *
 * 展开时同一块表面从胶囊尺寸连续长到全屏，底色随搜索条的 progress
 * 从输入框色渐变到页面色，分隔线由展开条自己画出；
 * 结果跟着表面淡入，但比表面晚半拍落定，收起时按同一个函数反向播放。
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
    val searchBarState = rememberSearchBarState()
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val pageColor = LoomaTokens.colors.page
    val fieldColor = LoomaTokens.colors.searchField
    // 展开表面与输入框同色起步，随 progress 走到页面色：一帧一色，没有第二份动画状态
    val sheetColor = lerp(fieldColor, pageColor, searchBarState.progress.coerceIn(0f, 1f))
    val fieldColors = SearchBarDefaults.inputFieldColors(
        focusedContainerColor = fieldColor,
        unfocusedContainerColor = fieldColor,
        disabledContainerColor = fieldColor,
    )
    val searchBarColors = SearchBarDefaults.colors(
        containerColor = sheetColor,
        dividerColor = LoomaTokens.colors.divider,
        inputFieldColors = fieldColors,
    )
    val appBarColors = SearchBarDefaults.appBarWithSearchColors(
        searchBarColors = searchBarColors,
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

    // query 只在搜索展开期间有意义：收起（箭头、返回键、选中结果）后清空输入，
    // 首页恢复全量笔记，避免“输入框有词、列表却是全量”的不一致。
    // “已收起”由搜索条自己判定，别在这里另写一份 progress 阈值
    LaunchedEffect(searchBarState, textFieldState) {
        snapshotFlow { searchBarState.currentValue }
            .filter { it == SearchBarValue.Collapsed }
            .collect { textFieldState.setTextAndPlaceCursorAtEnd("") }
    }

    val inputField: @Composable () -> Unit = {
        // 用 currentValue 而不是 targetValue：表面缩回胶囊大小之后才换回胶囊的工具，
        // 否则整段收起过程都会在全屏表面上显示菜单和布局控件
        val expanded = searchBarState.currentValue == SearchBarValue.Expanded
        SearchBarDefaults.InputField(
            textFieldState = textFieldState,
            searchBarState = searchBarState,
            // 撑满顶栏剩余宽度：收起时胶囊占满抽屉按钮和头像之间的这一格，
            // 展开时长出去的那一圈只有左右各 8dp
            modifier = Modifier.fillMaxWidth(),
            colors = fieldColors,
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
                // 收起时这一格是空的，靠占位文字说明这是搜索框；展开时返回箭头从左边长出来
                AnimatedVisibility(
                    visible = expanded,
                    enter = searchIconEnter(Alignment.Start),
                    exit = searchIconExit(Alignment.Start),
                ) {
                    IconButton(onClick = collapse) {
                        LoomaIcon(
                            iconRes = R.drawable.ic_arrow_back,
                            contentDescription = stringResource(R.string.home_close_search_description),
                        )
                    }
                }
            },
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AnimatedVisibility(
                        visible = !expanded,
                        enter = searchIconEnter(Alignment.End),
                        exit = searchIconExit(Alignment.End),
                    ) {
                        HomeListControls(
                            isGridMode = state.isGridMode,
                            onToggleLayout = onToggleLayout,
                            onSortClick = onSortClick,
                        )
                    }
                    AnimatedVisibility(
                        visible = expanded && textFieldState.text.isNotEmpty(),
                        enter = searchIconEnter(Alignment.End),
                        exit = searchIconExit(Alignment.End),
                    ) {
                        IconButton(
                            onClick = { textFieldState.setTextAndPlaceCursorAtEnd("") },
                        ) {
                            LoomaIcon(
                                iconRes = R.drawable.ic_close,
                                contentDescription = stringResource(R.string.home_clear_search_description),
                            )
                        }
                    }
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
            // 抽屉入口留在胶囊外面，和 Keep 一样；只淡出不改宽度，否则权重胶囊会在展开中途跳一下
            Box(Modifier.graphicsLayer { alpha = 1f - searchBarState.progress }) {
                IconButton(onClick = onMenuClick) {
                    LoomaIcon(
                        iconRes = R.drawable.ic_menu,
                        contentDescription = stringResource(R.string.home_menu_description),
                    )
                }
            }
        },
        actions = {
            Box(Modifier.graphicsLayer { alpha = 1f - searchBarState.progress }) {
                HomeAvatarButton(onClick = onAvatarClick)
            }
        },
    )

    ExpandedFullScreenSearchBar(
        state = searchBarState,
        inputField = {
            // 内边距随 progress 从 0 长到 8dp：输入框起步就是胶囊本身，
            // 落定时左右各留 8dp
            Box(Modifier.padding(horizontal = 8.dp * searchBarState.progress)) {
                inputField()
            }
        },
        collapsedShape = CircleShape,
        colors = searchBarColors,
    ) {
        HomeSearchPanel(progress = searchBarState.progress) {
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
                iconRes = if (isGridMode) R.drawable.ic_view_agenda else R.drawable.ic_grid,
                contentDescription = stringResource(
                    if (isGridMode) R.string.home_switch_to_list_description else R.string.home_switch_to_grid_description,
                ),
            )
        }
        IconButton(onClick = onSortClick) {
            LoomaIcon(
                iconRes = R.drawable.ic_swap_vert,
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
 * 结果的淡入由展开条自己的内容层负责（它跟着 progress 变透明），这里只补一点上浮，
 * 让结果比表面晚落定。纯 progress 函数，不持有动画状态，收起时自动反向。
 */
@Composable
private fun HomeSearchPanel(progress: Float, content: @Composable () -> Unit) {
    val lift = ((progress - 0.5f) / 0.5f).coerceIn(0f, 1f)
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { translationY = (1f - lift) * 12.dp.toPx() },
    ) {
        content()
    }
}

// 图标进出共用一套动效：淡入淡出之外还伸缩宽度，文字区的宽度跟着连续变化，不会在动画中途跳一下
private fun searchIconEnter(align: Alignment.Horizontal): EnterTransition =
    fadeIn(tween(120)) + expandHorizontally(expandFrom = align, animationSpec = tween(180))

private fun searchIconExit(align: Alignment.Horizontal): ExitTransition =
    fadeOut(tween(90)) + shrinkHorizontally(shrinkTowards = align, animationSpec = tween(160))

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
    // 靠上而不是居中：展开时结果区的高度一直在长，居中会把提示文字一路往下带；
    // 靠上之后它只跟着结果区一起淡入上浮，不会自己往下漂
    Box(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 24.dp)) {
        Text(text, style = LoomaTokens.type.emptyState, color = LoomaTokens.colors.muted)
    }
}
