package com.example.looma.feature.home

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.NotePath
import com.example.looma.domain.model.NoteSummary
import com.example.looma.domain.model.VaultId
import com.example.looma.feature.home.components.HomeSearchResults
import com.example.looma.ui.theme.LoomaTheme
import java.time.Instant

/**
 * Preview 数据与真实仓库无关；状态直接按 ViewModel 的产出规则预计算，
 * 这样 Preview 看到的列表顺序与搜索过滤和真机一致。
 */
private fun previewState(
    query: String = "",
    isGridMode: Boolean = true,
    notes: List<HomeNoteItem> = previewNotes(),
): HomeUiState = HomeUiState(
    vaultName = "Looma",
    notes = notes.orderedBy(HomeSort.LAST_MODIFIED),
    searchResults = notes.matching(query).orderedBy(HomeSort.LAST_MODIFIED),
    query = query,
    isGridMode = isGridMode,
    isLoading = false,
)

private fun previewNotes(): List<HomeNoteItem> {
    fun note(
        name: String,
        title: String,
        excerpt: String,
        tags: Set<String> = emptySet(),
        checklist: List<HomeChecklistItem> = emptyList(),
    ) = HomeNoteItem(
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
        note(
            "git",
            "Git 忽略清单",
            "",
            checklist = listOf(
                HomeChecklistItem(".claude", false),
                HomeChecklistItem(".idea", false),
                HomeChecklistItem("CLAUDE.md", false),
                HomeChecklistItem("tests/", true),
            ),
        ),
        note(
            "java",
            "Java 集合删除元素笔记",
            "Java 集合删除元素笔记\n1. 核心原则：使用迭代器 Iterator\n\n在遍历集合时，需要注意安全删除元素",
            tags = setOf("Java"),
        ),
        note(
            "markdown",
            "Markdown 写作",
            "这是一个 Obsidian 风格的本地 Markdown 笔记",
            tags = setOf("笔记"),
        ),
    )
}

@Preview(name = "首页 · 网格", showBackground = true, widthDp = 393, heightDp = 820)
@Composable
private fun HomeGridPreview() {
    LoomaTheme(dynamicColor = false) { HomeScreenPreview(previewState()) }
}

@Preview(
    name = "首页 · 列表 · 深色",
    showBackground = true,
    widthDp = 393,
    heightDp = 820,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun HomeListDarkPreview() {
    LoomaTheme(darkTheme = true, dynamicColor = false) {
        HomeScreenPreview(previewState(isGridMode = false))
    }
}

@Preview(name = "首页 · 空", showBackground = true, widthDp = 393, heightDp = 820)
@Composable
private fun HomeEmptyPreview() {
    LoomaTheme(dynamicColor = false) { HomeScreenPreview(previewState(notes = emptyList())) }
}

@Preview(name = "搜索结果", showBackground = true, widthDp = 393, heightDp = 820)
@Composable
private fun HomeSearchResultsPreview() {
    LoomaTheme(dynamicColor = false) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column {
                Text(
                    text = "搜索 Java",
                    modifier = Modifier.padding(20.dp),
                    style = MaterialTheme.typography.titleLarge,
                )
                HomeSearchResults(
                    query = "Java",
                    notes = previewNotes().matching("Java"),
                    onNoteClick = {},
                )
            }
        }
    }
}

/** Preview 只关心状态，事件一律给空实现 */
@Composable
private fun HomeScreenPreview(state: HomeUiState) {
    HomeScreen(
        state = state,
        onQueryChange = {},
        onToggleLayout = {},
        onToggleSort = {},
        onNoteClick = {},
        onCreateNoteClick = {},
        onMenuClick = {},
        onAssistantClick = {},
        onAvatarClick = {},
        onRetryClick = {},
    )
}
