package com.example.looma.feature.home.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridScope
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.looma.R
import com.example.looma.domain.model.FolderPath
import com.example.looma.domain.model.NoteId
import com.example.looma.feature.home.HomeFilesState
import com.example.looma.feature.home.HomeFolderItem
import com.example.looma.ui.theme.LoomaTokens

/**
 * 文件视图的唯一列表：根目录和每个文件夹共用一套渲染。
 *
 * 文件夹永远排在笔记前面；根目录额外插入“最近”一行。
 * 列表形态把当前层的文件夹收进一张分组卡片，网格形态每列各自一张卡片，
 * 所以列数只由 isGridMode 决定，列表按钮在根目录和文件夹内都有效。
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
                    HomeSectionHeader(title = stringResource(R.string.home_section_recents))
                }
                item(span = StaggeredGridItemSpan.FullLine) {
                    HomeRecentRow(items = files.recents, onNoteClick = onNoteClick)
                }
            }
            if (files.folders.isNotEmpty()) {
                item(span = StaggeredGridItemSpan.FullLine) {
                    HomeSectionHeader(
                        title = stringResource(R.string.home_section_folders),
                        trailing = stringResource(R.string.home_folder_count, files.folders.size),
                        topPadding = 12.dp,
                    )
                }
                folderSection(files.folders, isGridMode, onFolderClick)
            }
            if (files.notes.isNotEmpty()) {
                item(span = StaggeredGridItemSpan.FullLine) {
                    HomeSectionHeader(
                        title = stringResource(R.string.home_section_notes),
                        topPadding = 12.dp,
                    )
                }
            }
        } else {
            if (files.isEmpty) {
                item(span = StaggeredGridItemSpan.FullLine) {
                    Text(
                        text = stringResource(R.string.home_empty_folder),
                        style = LoomaTokens.type.emptyState,
                        color = LoomaTokens.colors.muted,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    )
                }
            }
            folderSection(files.folders, isGridMode, onFolderClick)
        }

        items(
            items = files.notes,
            key = { "note:${it.listKey}" },
        ) { note ->
            HomeNoteCard(note = note, onClick = { onNoteClick(note.id) })
        }
    }
}

/** 列表形态一整行装下所有文件夹，网格形态逐个平铺 */
private fun LazyStaggeredGridScope.folderSection(
    folders: List<HomeFolderItem>,
    isGridMode: Boolean,
    onFolderClick: (FolderPath) -> Unit,
) {
    if (folders.isEmpty()) return
    if (isGridMode) {
        items(
            items = folders,
            key = { "folder:${it.path.relativePath}" },
        ) { folder ->
            HomeFolderCard(item = folder, onClick = { onFolderClick(folder.path) })
        }
    } else {
        item(span = StaggeredGridItemSpan.FullLine) {
            HomeFolderGroup(folders = folders, onFolderClick = onFolderClick)
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
            style = LoomaTokens.type.sectionTitle,
            color = LoomaTokens.colors.title,
        )
        trailing?.let {
            Spacer(Modifier.width(8.dp))
            Text(
                text = it,
                style = LoomaTokens.type.count,
                color = LoomaTokens.colors.muted,
            )
        }
    }
}
