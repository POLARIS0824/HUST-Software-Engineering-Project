package com.example.looma.feature.note.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.looma.R
import com.example.looma.feature.note.NoteUiState

/**
 * 写盘失败时贴在编辑区顶部的提示。
 *
 * 冲突时给的是两个都能用到底的动作：读回磁盘上的版本，或者带着留档覆盖。
 * 原来只有一个"重试"，而重试带着过期的修改时间，永远不可能成功。
 */
@Composable
internal fun NoteSaveBanner(
    state: NoteUiState,
    onRetry: () -> Unit,
    onReload: () -> Unit,
    onOverwrite: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.errorContainer) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(
                    if (state.isConflict) R.string.editor_conflict else R.string.editor_save_failed,
                ),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            if (state.isConflict) {
                TextButton(
                    onClick = onReload,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) {
                    Text(stringResource(R.string.editor_reload_from_disk))
                }
            }
            TextButton(
                onClick = if (state.isConflict) onOverwrite else onRetry,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                Text(stringResource(actionLabel(state)))
            }
        }
    }
}

/** 返回时冲刷失败：重试、放弃或取消，别让用户被卡在编辑页 */
@Composable
internal fun NoteLeaveDialog(
    state: NoteUiState,
    onRetry: () -> Unit,
    onOverwrite: () -> Unit,
    onDiscard: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_leave_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.editor_leave_message))
                TextButton(onClick = onDiscard) {
                    Text(stringResource(R.string.editor_discard_leave))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = if (state.isConflict) onOverwrite else onRetry) {
                Text(stringResource(actionLabel(state)))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.editor_cancel)) }
        },
    )
}

private fun actionLabel(state: NoteUiState): Int =
    if (state.isConflict) R.string.editor_overwrite else R.string.editor_retry_save
