package com.example.looma.feature.home.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.looma.R
import com.example.looma.domain.model.FolderPath
import com.example.looma.feature.home.HomeFolderItem
import com.example.looma.ui.components.LoomaIcon
import com.example.looma.ui.theme.LoomaTokens

/** 网格里的文件夹：图标加名称，没有数量与预览 */
@Composable
internal fun HomeFolderCard(
    item: HomeFolderItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(68.dp),
        shape = RoundedCornerShape(20.dp),
        color = LoomaTokens.colors.card,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LoomaIcon(
                iconRes = R.drawable.folder_open_24px,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = Color.Unspecified,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = item.name,
                style = LoomaTokens.type.cardTitle,
                color = LoomaTokens.colors.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 列表里的文件夹分段行：无分隔线，无图标底圈，行间用 SegmentedGap */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun HomeFolderGroup(
    folders: List<HomeFolderItem>,
    onFolderClick: (FolderPath) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (folders.isEmpty()) return
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
    ) {
        folders.forEachIndexed { index, folder ->
            SegmentedListItem(
                onClick = { onFolderClick(folder.path) },
                shapes = ListItemDefaults.segmentedShapes(index, folders.size),
                colors = ListItemDefaults.segmentedColors(containerColor = LoomaTokens.colors.card),
                leadingContent = {
                    LoomaIcon(
                        iconRes = R.drawable.folder_open_24px,
                        contentDescription = null,
                        modifier = Modifier.padding(12.dp).size(24.dp),
                        tint = Color.Unspecified,
                    )
                },
            ) {
                Text(
                    text = folder.name,
                    style = LoomaTokens.type.folderName,
                    color = LoomaTokens.colors.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
