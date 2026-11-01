package com.example.looma.feature.home.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.looma.domain.model.NoteId
import com.example.looma.feature.home.HomeNoteItem

/**
 * 首页笔记流：网格/列表只切换列数，布局与内容不变。
 * 排序在 ViewModel 完成，这里只负责渲染 [notes]。
 */
@Composable
internal fun HomeNoteGrid(
    notes: List<HomeNoteItem>,
    isGridMode: Boolean,
    onNoteClick: (NoteId) -> Unit,
) {
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(if (isGridMode) 2 else 1),
        modifier = Modifier.fillMaxSize(),
        // 底部留白避开悬浮按钮
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 172.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalItemSpacing = 10.dp,
    ) {
        items(notes, key = { it.listKey }) { note ->
            HomeNoteCard(note = note, onClick = { onNoteClick(note.id) })
        }
    }
}
