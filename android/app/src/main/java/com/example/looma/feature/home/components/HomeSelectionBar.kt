@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.looma.feature.home.components

import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.example.looma.R
import com.example.looma.feature.home.HomeUiState
import com.example.looma.ui.components.LoomaIcon
import com.example.looma.ui.theme.LoomaTokens

/**
 * 选中笔记后的顶栏：左边退出选择，中间是选中数量，右边是文件动作。
 *
 * 与 Keep 一样只替换顶栏内容，页面和列表保持原位；单篇才能重命名和看属性，
 * 多选只保留“移动”和“删除”。
 */
@Composable
internal fun HomeSelectionBar(
    state: HomeUiState,
    onCloseClick: () -> Unit,
    onRenameClick: () -> Unit,
    onMoveClick: () -> Unit,
    onInfoClick: () -> Unit,
    onDeleteClick: () -> Unit,
) {
    val single = state.selection.size == 1
    TopAppBar(
        title = {
            Text(
                text = stringResource(R.string.home_selection_count, state.selection.size),
                style = LoomaTokens.type.pageTitle,
                color = LoomaTokens.colors.title,
            )
        },
        navigationIcon = {
            IconButton(onClick = onCloseClick) {
                LoomaIcon(
                    iconRes = R.drawable.ic_close,
                    contentDescription = stringResource(R.string.home_selection_close),
                )
            }
        },
        actions = {
            IconButton(onClick = onRenameClick, enabled = single) {
                LoomaIcon(
                    iconRes = R.drawable.ic_editor_edit,
                    contentDescription = stringResource(R.string.home_selection_rename),
                )
            }
            IconButton(onClick = onMoveClick) {
                LoomaIcon(
                    iconRes = R.drawable.ic_move,
                    contentDescription = stringResource(R.string.home_selection_move),
                )
            }
            IconButton(onClick = onInfoClick, enabled = single) {
                LoomaIcon(
                    iconRes = R.drawable.ic_info,
                    contentDescription = stringResource(R.string.home_selection_info),
                )
            }
            IconButton(onClick = onDeleteClick) {
                LoomaIcon(
                    iconRes = R.drawable.ic_delete,
                    contentDescription = stringResource(R.string.home_selection_delete),
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = LoomaTokens.colors.page,
            navigationIconContentColor = LoomaTokens.colors.title,
            actionIconContentColor = LoomaTokens.colors.title,
        ),
    )
}
