package com.example.looma.feature.home.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.looma.domain.model.FolderPath
import com.example.looma.domain.model.NoteId
import com.example.looma.feature.home.HomeFilesState

/**
 * 文件视图的唯一列表：根目录和每个文件夹共用一套渲染。
 *
 * 文件夹永远排在笔记前面；根目录额外插入“最近”轮播。
 * 网格列数只由 isGridMode 决定，所以列表按钮在根目录和文件夹内都有效。
 */
@Composable
internal fun HomeFilesGrid(
    files: HomeFilesState,
    isGridMode: Boolean,
    onFolderClick: (FolderPath) -> Unit,
    onNoteClick: (NoteId) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(if (isGridMode) 2 else 1),
        modifier = modifier.fillMaxSize(),
        // 底部留白避开悬浮按钮
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 172.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalItemSpacing = 10.dp,
    ) {
        if (files.isAtRoot) {
            if (files.recents.isNotEmpty()) {
                item(span = StaggeredGridItemSpan.FullLine) {
                    HomeSectionHeader(title = "最近")
                }
                item(span = StaggeredGridItemSpan.FullLine) {
                    HomeRecentCarousel(items = files.recents, onNoteClick = onNoteClick)
                }
            }
            if (files.folders.isNotEmpty()) {
                item(span = StaggeredGridItemSpan.FullLine) {
                    HomeSectionHeader(
                        title = "文件夹",
                        trailing = "${files.folders.size} 个",
                        topPadding = 12.dp,
                    )
                }
                items(
                    items = files.folders,
                    key = { "folder:${it.path.relativePath}" },
                ) { folder ->
                    val open = { onFolderClick(folder.path) }
                    if (isGridMode) {
                        HomeFolderCard(item = folder, onClick = open)
                    } else {
                        HomeFolderRow(item = folder, onClick = open)
                    }
                }
            }
            if (files.notes.isNotEmpty()) {
                item(span = StaggeredGridItemSpan.FullLine) {
                    HomeSectionHeader(title = "笔记", topPadding = 12.dp)
                }
            }
        } else {
            if (files.isEmpty) {
                item(span = StaggeredGridItemSpan.FullLine) {
                    Text(
                        text = "这个文件夹是空的",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    )
                }
            }
            items(
                items = files.folders,
                key = { "folder:${it.path.relativePath}" },
                span = { StaggeredGridItemSpan.FullLine },
            ) { folder ->
                HomeFolderRow(item = folder, onClick = { onFolderClick(folder.path) })
            }
        }

        items(
            items = files.notes,
            key = { "note:${it.listKey}" },
        ) { note ->
            HomeNoteCard(note = note, onClick = { onNoteClick(note.id) })
        }
    }
}

@Composable
private fun HomeSectionHeader(
    title: String,
    trailing: String? = null,
    topPadding: Dp = 4.dp,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = topPadding, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        trailing?.let {
            Spacer(Modifier.width(8.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
