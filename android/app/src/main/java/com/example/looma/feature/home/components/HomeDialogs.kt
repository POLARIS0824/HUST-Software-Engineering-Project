@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.looma.feature.home.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.looma.R
import com.example.looma.domain.model.FolderPath
import com.example.looma.domain.model.NoteProperties
import com.example.looma.feature.home.HomeFileError
import com.example.looma.feature.home.HomeFileDialog
import com.example.looma.ui.components.LoomaIcon
import com.example.looma.ui.format.formatDateTime
import com.example.looma.ui.format.formatFileSize
import com.example.looma.ui.theme.LoomaTokens

/**
 * 重命名与新建文件夹共用同一个输入对话框，只有标题、初始值和按钮文案不同。
 *
 * 用自绘 Dialog 而不是 AlertDialog：输入法弹出时窗口按 imePadding 上移，
 * 确认按钮不会被键盘盖住。
 */
@Composable
internal fun HomeTextInputDialog(
    title: String,
    hint: String,
    initialValue: String,
    confirmLabel: String,
    error: HomeFileError?,
    busy: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by rememberSaveable(initialValue) { mutableStateOf(initialValue) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    val supporting: (@Composable () -> Unit)? = if (error != null) {
        { Text(fileErrorText(error)) }
    } else {
        null
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier.fillMaxSize().imePadding().padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp),
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    OutlinedTextField(
                        value = value,
                        onValueChange = { value = it },
                        label = { Text(hint) },
                        singleLine = true,
                        isError = error != null,
                        supportingText = supporting,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { onConfirm(value) }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(R.string.dialog_cancel))
                        }
                        TextButton(onClick = { onConfirm(value) }, enabled = !busy) {
                            Text(confirmLabel)
                        }
                    }
                }
            }
        }
    }
}

/** 文件属性：路径、大小、修改时间、字数与任务完成情况 */
@Composable
internal fun HomePropertiesDialog(
    properties: NoteProperties,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.home_info_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PropertyRow(
                    label = stringResource(R.string.home_info_path),
                    value = properties.path.relativePath.replace('/', '›'),
                )
                PropertyRow(
                    label = stringResource(R.string.home_info_size),
                    value = formatFileSize(context, properties.sizeBytes),
                )
                PropertyRow(
                    label = stringResource(R.string.home_info_modified),
                    value = formatDateTime(properties.modifiedAt),
                )
                PropertyRow(
                    label = stringResource(R.string.home_info_words),
                    value = properties.words.toString(),
                )
                PropertyRow(
                    label = stringResource(R.string.home_info_characters),
                    value = properties.characters.toString(),
                )
                if (properties.checkboxesTotal > 0) {
                    PropertyRow(
                        label = stringResource(R.string.home_info_checklist),
                        value = stringResource(
                            R.string.home_info_checklist_value,
                            properties.checkboxesDone,
                            properties.checkboxesTotal,
                        ),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_confirm)) }
        },
    )
}

@Composable
private fun PropertyRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = label,
            style = LoomaTokens.type.cardBody,
            color = LoomaTokens.colors.muted,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = LoomaTokens.type.cardBody,
            color = LoomaTokens.colors.title,
            modifier = Modifier.weight(1.2f),
        )
    }
}

/** 文件夹卡片上的操作菜单：重命名 / 移动 / 删除 */
@Composable
internal fun HomeFolderActionsDialog(
    folderName: String,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = folderName,
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FolderActionRow(
                    iconRes = R.drawable.ic_editor_edit,
                    label = stringResource(R.string.home_folder_action_rename),
                    onClick = onRename,
                )
                FolderActionRow(
                    iconRes = R.drawable.ic_move,
                    label = stringResource(R.string.home_folder_action_move),
                    onClick = onMove,
                )
                FolderActionRow(
                    iconRes = R.drawable.ic_delete,
                    label = stringResource(R.string.home_folder_action_delete),
                    onClick = onDelete,
                    destructive = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
        },
    )
}

@Composable
private fun FolderActionRow(
    iconRes: Int,
    label: String,
    onClick: () -> Unit,
    destructive: Boolean = false,
) {
    val contentColor = if (destructive) LoomaTokens.colors.error else LoomaTokens.colors.title
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LoomaIcon(
                iconRes = iconRes,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = contentColor,
            )
            Spacer(Modifier.width(14.dp))
            Text(
                text = label,
                style = LoomaTokens.type.cardTitle,
                color = contentColor,
            )
        }
    }
}

/** 移动目标：根目录加所有现存文件夹，点一行就搬过去 */
@Composable
internal fun HomeMoveFolderDialog(
    folderName: String,
    targets: List<FolderPath>,
    error: HomeFileError?,
    busy: Boolean,
    onSelect: (FolderPath) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.home_folder_move_title, folderName),
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    targets.forEach { target ->
                        MoveTargetRow(
                            label = if (target == FolderPath.Root) {
                                stringResource(R.string.home_breadcrumb_root)
                            } else {
                                target.relativePath.replace('/', '›')
                            },
                            enabled = !busy,
                            onClick = { onSelect(target) },
                        )
                    }
                }
                if (error != null) {
                    Text(
                        text = fileErrorText(error),
                        style = LoomaTokens.type.cardBody,
                        color = LoomaTokens.colors.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
        },
    )
}

@Composable
private fun MoveTargetRow(label: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LoomaIcon(
                iconRes = R.drawable.folder_open_24px,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = Color.Unspecified,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = label,
                style = LoomaTokens.type.cardBody,
                color = LoomaTokens.colors.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 删除文件夹前确认：明确告诉用户会有几篇笔记进回收站 */
@Composable
internal fun HomeDeleteFolderDialog(
    folderName: String,
    noteCount: Int,
    error: HomeFileError?,
    busy: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.home_folder_delete_title, folderName),
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = if (noteCount > 0) {
                        pluralStringResource(
                            R.plurals.home_folder_delete_message,
                            noteCount,
                            noteCount,
                        )
                    } else {
                        stringResource(R.string.home_folder_delete_message_empty)
                    },
                    style = LoomaTokens.type.cardBody,
                    color = LoomaTokens.colors.title,
                )
                if (error != null) {
                    Text(
                        text = fileErrorText(error),
                        style = LoomaTokens.type.cardBody,
                        color = LoomaTokens.colors.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !busy) {
                Text(
                    text = stringResource(R.string.home_folder_delete_confirm),
                    color = LoomaTokens.colors.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_cancel)) }
        },
    )
}

@Composable
private fun fileErrorText(error: HomeFileError): String = stringResource(
    when (error) {
        HomeFileError.EMPTY_NAME -> R.string.home_error_empty_name
        HomeFileError.INVALID_NAME -> R.string.home_error_invalid_name
        HomeFileError.NAME_TAKEN -> R.string.home_error_duplicate_name
        HomeFileError.RESTORE_CONFLICT -> R.string.home_error_duplicate_name
        HomeFileError.GENERIC -> R.string.home_error_action
    },
)
