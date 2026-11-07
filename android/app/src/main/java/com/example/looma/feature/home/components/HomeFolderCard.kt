package com.example.looma.feature.home.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.looma.R
import com.example.looma.domain.model.FolderPath
import com.example.looma.feature.home.HomeFolderItem
import com.example.looma.ui.components.LoomaIcon
import com.example.looma.ui.theme.LoomaTokens

@Composable
internal fun HomeFolderCard(
    item: HomeFolderItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FolderRow(
        item = item,
        shape = RoundedCornerShape(20.dp),
        isSelected = isSelected,
        nameLines = 2,
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun HomeFolderGroup(
    folders: List<HomeFolderItem>,
    selected: FolderPath?,
    onFolderClick: (HomeFolderItem) -> Unit,
    onFolderLongClick: (HomeFolderItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (folders.isEmpty()) return
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
    ) {
        folders.forEachIndexed { index, folder ->
            FolderRow(
                item = folder,
                shape = segmentedShape(index, folders.size),
                isSelected = folder.path == selected,
                nameLines = 1,
                onClick = { onFolderClick(folder) },
                onLongClick = { onFolderLongClick(folder) },
            )
        }
    }
}

private fun segmentedShape(index: Int, count: Int) = RoundedCornerShape(
    topStart = if (index == 0) 28.dp else 4.dp,
    topEnd = if (index == 0) 28.dp else 4.dp,
    bottomStart = if (index == count - 1) 28.dp else 4.dp,
    bottomEnd = if (index == count - 1) 28.dp else 4.dp,
)

@Composable
private fun FolderRow(
    item: HomeFolderItem,
    shape: Shape,
    isSelected: Boolean,
    nameLines: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val actionsLabel = stringResource(R.string.home_folder_actions_description)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(shape)
            .semantics { selected = isSelected }
            .combinedClickable(
                onLongClickLabel = actionsLabel,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        shape = shape,
        color = LoomaTokens.colors.card,
        border = if (isSelected) BorderStroke(2.dp, LoomaTokens.colors.accent) else null,
    ) {
        FolderRowContent(item, nameLines)
    }
}

@Composable
private fun FolderRowContent(item: HomeFolderItem, nameLines: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LoomaIcon(
            iconRes = R.drawable.folder_open_24px,
            contentDescription = null,
            modifier = Modifier.size(26.dp),
            tint = Color.Unspecified,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = item.name,
            style = LoomaTokens.type.folderName,
            color = LoomaTokens.colors.title,
            maxLines = nameLines,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(vertical = 12.dp),
        )
    }
}
