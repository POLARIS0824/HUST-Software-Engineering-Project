package com.example.looma.feature.home

import android.content.res.Configuration
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items as staggeredItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.AppBarWithSearch
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExpandedFullScreenContainedSearchBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SearchBarValue
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberContainedSearchBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.NotePath
import com.example.looma.domain.model.NoteSummary
import com.example.looma.domain.model.VaultId
import com.example.looma.ui.theme.LoomaTheme
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

/** MainActivity / Navigation 3 层提供导航回调；Home 的文件操作全部通过 ViewModel */
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

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    HomeScreen(
        state = state,
        onQueryChange = viewModel::setQuery,
        onToggleLayout = viewModel::toggleLayout,
        onToggleSort = viewModel::toggleSort,
        onNoteClick = onOpenNote,
        onCreateNoteClick = { viewModel.createNote(onOpenNote) },
        onMenuClick = onMenuClick,
        onAssistantClick = onAssistantClick,
        onAvatarClick = onAvatarClick,
        onRetryClick = viewModel::refresh,
        modifier = modifier,
    )
}

/**
 * Google Keep 风格的 Home 页面，使用 Material 3 官方全屏搜索过渡
 * 展开：AppBarWithSearch -> ExpandedFullScreenContainedSearchBar
 */
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
    val searchBarState = rememberContainedSearchBarState()
    val textFieldState = rememberTextFieldState(initialText = state.query)
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val searchColors = SearchBarDefaults.appBarWithSearchColors(
        searchBarColors = SearchBarDefaults.containedColors(state = searchBarState),
    )
    val expanded = searchBarState.currentValue == SearchBarValue.Expanded

    // TextFieldState 只负责输入光标和编辑；query 仍由 ViewModel 统一管理
    LaunchedEffect(textFieldState) {
        snapshotFlow { textFieldState.text.toString() }
            .distinctUntilChanged()
            .collect { onQueryChange(it) }
    }

    // 官方全屏搜索 Dialog 被返回键关闭后，Home 恢复完整的笔记列表
    LaunchedEffect(searchBarState.currentValue) {
        if (searchBarState.currentValue == SearchBarValue.Collapsed) {
            if (textFieldState.text.isNotEmpty()) {
                textFieldState.setTextAndPlaceCursorAtEnd("")
                onQueryChange("")
            }
        }
    }

    val inputField: @Composable () -> Unit = {
        SearchBarDefaults.InputField(
            textFieldState = textFieldState,
            searchBarState = searchBarState,
            colors = searchColors.searchBarColors.inputFieldColors,
            onSearch = { keyboard?.hide() },
            placeholder = {
                Text(
                    text = if (expanded) "搜索 ${state.vaultName}" else "Looma",
                    style = if (expanded) MaterialTheme.typography.bodyLarge
                    else MaterialTheme.typography.titleLarge,
                    fontWeight = if (expanded) FontWeight.Normal else FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            },
            leadingIcon = {
                if (expanded) {
                    IconButton(
                        onClick = {
                            keyboard?.hide()
                            scope.launch { searchBarState.animateToCollapsed() }
                        },
                        modifier = Modifier.semantics { contentDescription = "关闭搜索" },
                    ) { HomeIcon(Glyph.Back) }
                } else {
                    HomeIcon(Glyph.Search)
                }
            },
            trailingIcon = {
                if (expanded) {
                    if (textFieldState.text.isNotEmpty()) {
                        IconButton(
                            onClick = { textFieldState.setTextAndPlaceCursorAtEnd("") },
                            modifier = Modifier.semantics { contentDescription = "清除搜索内容" },
                        ) { HomeIcon(Glyph.Close) }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = onToggleLayout,
                            modifier = Modifier.semantics { contentDescription = "切换列表或网格" },
                        ) { HomeIcon(if (state.isGridMode) Glyph.List else Glyph.Grid) }
                        IconButton(
                            onClick = onToggleSort,
                            modifier = Modifier.semantics {
                                contentDescription = if (state.sort == HomeSort.LAST_MODIFIED) {
                                    "按标题排序"
                                } else "按修改时间排序"
                            },
                        ) { HomeIcon(Glyph.Sort) }
                    }
                }
            },
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppBarWithSearch(
                state = searchBarState,
                inputField = inputField,
                colors = searchColors,
                navigationIcon = {
                    IconButton(
                        onClick = onMenuClick,
                        modifier = Modifier.semantics { contentDescription = "打开侧边菜单" },
                    ) { HomeIcon(Glyph.Menu) }
                },
                actions = {
                    Surface(
                        onClick = onAvatarClick,
                        modifier = Modifier.size(44.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        border = androidx.compose.foundation.BorderStroke(
                            2.dp, MaterialTheme.colorScheme.primary,
                        ),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("L", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                },
            )
            ExpandedFullScreenContainedSearchBar(
                state = searchBarState,
                inputField = inputField,
                colors = searchColors.searchBarColors,
            ) {
                HomeSearchResults(
                    query = state.query,
                    items = state.visibleNotes,
                    onNoteClick = { id ->
                        keyboard?.hide()
                        scope.launch { searchBarState.animateToCollapsed() }
                        onNoteClick(id)
                    },
                )
            }
        },
        floatingActionButton = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                SmallFloatingActionButton(
                    onClick = onAssistantClick,
                    modifier = Modifier.size(58.dp).semantics {
                        contentDescription = "辅助功能"
                    },
                    shape = RoundedCornerShape(20.dp),
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ) { HomeIcon(Glyph.Sparkle, Modifier.size(28.dp)) }

                LargeFloatingActionButton(
                    onClick = onCreateNoteClick,
                    modifier = Modifier.size(88.dp).semantics {
                        contentDescription = if (state.isCreating) "正在创建笔记" else "新建笔记"
                    },
                    shape = RoundedCornerShape(28.dp),
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    if (state.isCreating) CircularProgressIndicator(Modifier.size(28.dp))
                    else HomeIcon(Glyph.Plus, Modifier.size(38.dp))
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            state.errorMessage?.let { message ->
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "重试",
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable(onClick = onRetryClick).padding(8.dp),
                    )
                }
            }
            when {
                state.isLoading && state.notes.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                state.notes.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("还没有笔记，点击 + 新建", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                else -> {
                    // 首页始终展示全量笔记；筛选只发生在独立全屏搜索页内
                    val sortedNotes = when (state.sort) {
                        HomeSort.LAST_MODIFIED -> state.notes.sortedByDescending { it.summary.modifiedAt }
                        HomeSort.TITLE -> state.notes.sortedBy { it.summary.title.lowercase() }
                    }
                    LazyVerticalStaggeredGrid(
                        columns = StaggeredGridCells.Fixed(if (state.isGridMode) 2 else 1),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 12.dp, end = 12.dp, top = 8.dp, bottom = 172.dp,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalItemSpacing = 10.dp,
                    ) {
                        staggeredItems(
                            items = sortedNotes,
                            key = { "${it.id.vaultId.value}/${it.id.path.relativePath}" },
                        ) { note -> HomeNoteCard(note) { onNoteClick(note.id) } }
                    }
                }
            }
        }
    }
}

/** 全屏搜索页内容，亦可独立在 Compose Preview 里预览 */
@Composable
private fun HomeSearchResults(
    query: String,
    items: List<HomeNoteItem>,
    onNoteClick: (NoteId) -> Unit,
) {
    if (query.isBlank()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("搜索标题、正文与标签", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("没有匹配的笔记", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        lazyItems(
            items = items,
            key = { "${it.id.vaultId.value}/${it.id.path.relativePath}" },
        ) { note ->
            ListItem(
                headlineContent = {
                    Text(note.summary.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                supportingContent = {
                    Text(
                        note.summary.excerpt.ifEmpty { note.id.path.relativePath },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                leadingContent = { HomeIcon(Glyph.Search) },
                modifier = Modifier.fillMaxWidth().clickable { onNoteClick(note.id) },
            )
        }
    }
}

@Composable
private fun HomeNoteCard(note: HomeNoteItem, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp, MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Column {
            note.coverPath?.let { NoteCover(it) }
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    note.summary.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (note.summary.excerpt.isNotBlank()) Text(
                    note.summary.excerpt,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 9,
                    overflow = TextOverflow.Ellipsis,
                )
                note.checklist.forEach { item ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            if (item.checked) "☑" else "☐",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            item.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                note.linkLabel?.let { HomeLabel("▣ $it") }
                if (note.summary.tags.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        note.summary.tags.take(2).forEach { tag ->
                            Box(Modifier.weight(1f, fill = false)) {
                                HomeLabel("#$tag")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeLabel(label: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            label,
            modifier = Modifier.widthIn(max = 150.dp)
                .padding(horizontal = 10.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 不额外引入 Coil，简单本地图片走后台解码 + 降采样 */
@Composable
private fun NoteCover(path: String) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = path) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
                var sample = 1
                while (bounds.outWidth / sample > 800 || bounds.outHeight / sample > 800) {
                    sample *= 2
                }
                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
                    inSampleSize = sample
                })?.asImageBitmap()
            }.getOrNull()
        }
    }
    bitmap?.let { image ->
        Image(
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().height(150.dp),
        )
    }
}

private enum class Glyph { Menu, List, Grid, Sort, Close, Plus, Sparkle, Back, Search }

/** 纯 Canvas 图标，避免 material-icons-extended 依赖 */
@Composable
private fun HomeIcon(glyph: Glyph, modifier: Modifier = Modifier.size(24.dp)) {
    val tint = LocalContentColor.current
    Canvas(modifier) {
        val k = size.minDimension / 24f
        val stroke = 2.2f * k
        fun pt(x: Float, y: Float) = Offset(x * k, y * k)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) {
            drawLine(tint, pt(x1, y1), pt(x2, y2), stroke, cap = StrokeCap.Round)
        }
        fun rect(x: Float, y: Float, w: Float, h: Float) {
            drawRoundRect(
                color = tint,
                topLeft = pt(x, y),
                size = Size(w * k, h * k),
                cornerRadius = CornerRadius(1.3f * k),
                style = Stroke(stroke),
            )
        }
        when (glyph) {
            Glyph.Menu -> listOf(5f, 12f, 19f).forEach { line(3f, it, 21f, it) }
            Glyph.List -> { rect(3f, 4f, 18f, 6f); rect(3f, 14f, 18f, 6f) }
            Glyph.Grid -> {
                rect(3f, 3f, 7f, 7f); rect(14f, 3f, 7f, 7f)
                rect(3f, 14f, 7f, 7f); rect(14f, 14f, 7f, 7f)
            }
            Glyph.Sort -> {
                line(7f, 20f, 7f, 4f); line(3f, 8f, 7f, 4f)
                line(11f, 8f, 7f, 4f); line(17f, 4f, 17f, 20f)
                line(13f, 16f, 17f, 20f); line(21f, 16f, 17f, 20f)
            }
            Glyph.Close -> { line(5f, 5f, 19f, 19f); line(19f, 5f, 5f, 19f) }
            Glyph.Back -> {
                line(20f, 12f, 4f, 12f); line(11f, 5f, 4f, 12f); line(11f, 19f, 4f, 12f)
            }
            Glyph.Search -> {
                drawCircle(tint, radius = 6.5f * k, center = pt(10f, 10f), style = Stroke(stroke))
                line(15f, 15f, 21f, 21f)
            }
            Glyph.Plus -> { line(12f, 3f, 12f, 21f); line(3f, 12f, 21f, 12f) }
            Glyph.Sparkle -> {
                val star = Path().apply {
                    moveTo(11f * k, 1f * k)
                    lineTo(13.6f * k, 8.4f * k)
                    lineTo(21f * k, 11f * k)
                    lineTo(13.6f * k, 13.6f * k)
                    lineTo(11f * k, 21f * k)
                    lineTo(8.4f * k, 13.6f * k)
                    lineTo(1f * k, 11f * k)
                    lineTo(8.4f * k, 8.4f * k)
                    close()
                }
                drawPath(star, tint)
            }
        }
    }
}

/** 不访问 ViewModel / 磁盘，Preview 可独立渲染 */
private fun previewNotes(): List<HomeNoteItem> {
    fun note(name: String, title: String, excerpt: String, tags: Set<String> = emptySet(),
             checklist: List<HomeChecklistItem> = emptyList()): HomeNoteItem = HomeNoteItem(
        summary = NoteSummary(
            id = NoteId(VaultId("preview-vault"), NotePath("$name.md")),
            title = title,
            excerpt = excerpt,
            modifiedAt = Instant.parse("2026-10-31T12:00:00Z"),
            tags = tags,
        ),
        checklist = checklist,
        searchableText = "$title $excerpt",
    )
    return listOf(
        note("test", "测试", "现在看看怎么样 这个 UI"),
        note("git", "Git 忽略清单", "", checklist = listOf(
            HomeChecklistItem(".claude", false),
            HomeChecklistItem(".idea", false),
            HomeChecklistItem("CLAUDE.md", false),
            HomeChecklistItem("tests/", true),
        )),
        note("java", "Java 集合删除元素笔记", "Java 集合删除元素笔记\n1. 核心原则：使用迭代器 Iterator\n\n在遍历集合时，需要注意安全删除元素", tags = setOf("Java")),
        note("markdown", "Markdown 写作", "这是一个 Obsidian 风格的本地 Markdown 笔记", tags = setOf("笔记")),
    )
}

@Preview(name = "Home · Light", showBackground = true, widthDp = 393, heightDp = 820)
@Composable
private fun HomeLightPreview() {
    LoomaTheme {
        HomeScreen(
            state = HomeUiState(notes = previewNotes(), isLoading = false),
            onQueryChange = {}, onToggleLayout = {}, onToggleSort = {},
            onNoteClick = {}, onCreateNoteClick = {}, onMenuClick = {},
            onAssistantClick = {}, onAvatarClick = {}, onRetryClick = {},
        )
    }
}

@Preview(name = "Home · Dark", showBackground = true, widthDp = 393, heightDp = 820,
    uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun HomeDarkPreview() {
    LoomaTheme {
        HomeScreen(
            state = HomeUiState(notes = previewNotes(), isLoading = false),
            onQueryChange = {}, onToggleLayout = {}, onToggleSort = {},
            onNoteClick = {}, onCreateNoteClick = {}, onMenuClick = {},
            onAssistantClick = {}, onAvatarClick = {}, onRetryClick = {},
        )
    }
}

@Preview(name = "Full Screen Search · Results", showBackground = true, widthDp = 393, heightDp = 820)
@Composable
private fun HomeSearchResultsPreview() {
    LoomaTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column {
                Text("搜索 Java", modifier = Modifier.padding(20.dp),
                    style = MaterialTheme.typography.titleLarge)
                HomeSearchResults(
                    query = "Java",
                    items = previewNotes().filter { it.summary.title.contains("Java") },
                    onNoteClick = {},
                )
            }
        }
    }
}
