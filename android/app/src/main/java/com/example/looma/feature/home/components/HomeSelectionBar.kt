@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.looma.feature.home.components

import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.example.looma.R
import com.example.looma.ui.components.LoomaIcon
import com.example.looma.ui.theme.LoomaTokens

@Composable
internal fun HomeSelectionBar(
    title: String,
    showRename: Boolean,
    showInfo: Boolean,
    onCloseClick: () -> Unit,
    onRenameClick: () -> Unit,
    onMoveClick: () -> Unit,
    onInfoClick: () -> Unit,
    onDeleteClick: () -> Unit,
) {
    TopAppBar(
        title = {
            Text(
                text = title,
                style = LoomaTokens.type.pageTitle,
                color = LoomaTokens.colors.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
            IconButton(onClick = onRenameClick, enabled = showRename) {
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
            if (showInfo) {
                IconButton(onClick = onInfoClick) {
                    LoomaIcon(
                        iconRes = R.drawable.ic_info,
                        contentDescription = stringResource(R.string.home_selection_info),
                    )
                }
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
