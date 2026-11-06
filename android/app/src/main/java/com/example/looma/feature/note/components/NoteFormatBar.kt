package com.example.looma.feature.note.components

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.looma.R
import com.example.looma.domain.markdown.MarkdownBlockStyle
import com.example.looma.domain.markdown.MarkdownCommand
import com.example.looma.domain.markdown.MarkdownFormatState
import com.example.looma.domain.markdown.MarkdownInlineStyle
import com.example.looma.domain.markdown.ToolbarItem
import com.example.looma.ui.components.LoomaIcon

@Composable
internal fun NoteFormatBar(
    formatState: MarkdownFormatState,
    canUndo: Boolean,
    canRedo: Boolean,
    onCommand: (MarkdownCommand) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onWikiLink: () -> Unit,
    toolbarOrder: List<ToolbarItem> = ToolbarItem.DEFAULT_ORDER,
    onInsertImage: () -> Unit = {},
    onInsertFile: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            toolbarOrder.filterNot { it == ToolbarItem.InsertImage || it == ToolbarItem.InsertFile }.forEach { item ->
                when (item) {
                    ToolbarItem.Undo -> HistoryButton(
                        icon = R.drawable.ic_editor_undo,
                        label = R.string.editor_undo,
                        enabled = canUndo,
                        onClick = onUndo,
                    )

                    ToolbarItem.Redo -> HistoryButton(
                        icon = R.drawable.ic_editor_redo,
                        label = R.string.editor_redo,
                        enabled = canRedo,
                        onClick = onRedo,
                    )

                    ToolbarItem.WikiLink -> IconButton(
                        onClick = onWikiLink,
                        modifier = Modifier.size(48.dp),
                        colors = IconButtonDefaults.iconButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    ) {
                        LoomaIcon(
                            iconRes = R.drawable.ic_editor_wikilink,
                            contentDescription = stringResource(R.string.editor_wikilink),
                            modifier = Modifier.size(22.dp),
                        )
                    }

                    ToolbarItem.InsertImage -> IconButton(
                        onClick = onInsertImage,
                        modifier = Modifier.size(48.dp),
                        colors = IconButtonDefaults.iconButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    ) {
                        LoomaIcon(
                            iconRes = R.drawable.ic_editor_preview,
                            contentDescription = "Insert image",
                            modifier = Modifier.size(22.dp),
                        )
                    }

                    ToolbarItem.InsertFile -> IconButton(
                        onClick = onInsertFile,
                        modifier = Modifier.size(48.dp),
                        colors = IconButtonDefaults.iconButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    ) {
                        LoomaIcon(
                            iconRes = R.drawable.folder_open_24px,
                            contentDescription = "Insert file",
                            modifier = Modifier.size(22.dp),
                        )
                    }

                    else -> {
                        FormatActionMap[item]?.let { action ->
                            FormatButton(action, formatState, onCommand)
                        }
                    }
                }
            }
        }
    }
}

internal data class FormatAction(
    val command: MarkdownCommand,
    @StringRes val label: Int,
    @DrawableRes val icon: Int,
    val isToggle: Boolean = true,
    val isActive: (MarkdownFormatState) -> Boolean,
)

private val FormatActionMap: Map<ToolbarItem, FormatAction> = mapOf(
    ToolbarItem.Bold to FormatAction(
        MarkdownCommand.Bold,
        R.string.editor_bold,
        R.drawable.ic_editor_bold
    ) {
        MarkdownInlineStyle.Bold in it.inline
    },
    ToolbarItem.Italic to FormatAction(
        MarkdownCommand.Italic,
        R.string.editor_italic,
        R.drawable.ic_editor_italic
    ) {
        MarkdownInlineStyle.Italic in it.inline
    },
    ToolbarItem.Strikethrough to FormatAction(
        MarkdownCommand.Strikethrough,
        R.string.editor_strikethrough,
        R.drawable.ic_editor_strikethrough,
    ) {
        MarkdownInlineStyle.Strikethrough in it.inline
    },
    ToolbarItem.Highlight to FormatAction(
        MarkdownCommand.Highlight,
        R.string.editor_highlight,
        R.drawable.ic_editor_highlight,
    ) {
        MarkdownInlineStyle.Highlight in it.inline
    },
    ToolbarItem.Link to FormatAction(
        MarkdownCommand.Link,
        R.string.editor_link,
        R.drawable.ic_editor_link,
        isToggle = false
    ) {
        false
    },
    ToolbarItem.InlineCode to FormatAction(
        MarkdownCommand.InlineCode,
        R.string.editor_inline_code,
        R.drawable.ic_editor_code,
    ) {
        MarkdownInlineStyle.Code in it.inline
    },
    ToolbarItem.Heading1 to FormatAction(
        MarkdownCommand.Heading1,
        R.string.editor_h1,
        R.drawable.ic_editor_h1
    ) {
        it.headingLevel == 1
    },
    ToolbarItem.Heading2 to FormatAction(
        MarkdownCommand.Heading2,
        R.string.editor_h2,
        R.drawable.ic_editor_h2
    ) {
        it.headingLevel == 2
    },
    ToolbarItem.Heading3 to FormatAction(
        MarkdownCommand.Heading3,
        R.string.editor_h3,
        R.drawable.ic_editor_h3
    ) {
        it.headingLevel == 3
    },
    ToolbarItem.BulletList to FormatAction(
        MarkdownCommand.BulletList,
        R.string.editor_bullet_list,
        R.drawable.ic_editor_bullet_list,
    ) {
        it.block == MarkdownBlockStyle.BulletList
    },
    ToolbarItem.NumberedList to FormatAction(
        MarkdownCommand.NumberedList,
        R.string.editor_numbered_list,
        R.drawable.ic_editor_numbered_list,
    ) {
        it.block == MarkdownBlockStyle.NumberedList
    },
    ToolbarItem.Checklist to FormatAction(
        MarkdownCommand.Checklist,
        R.string.editor_checklist,
        R.drawable.ic_editor_checklist,
    ) {
        it.block == MarkdownBlockStyle.Checklist
    },
    ToolbarItem.Quote to FormatAction(
        MarkdownCommand.Quote,
        R.string.editor_quote,
        R.drawable.ic_editor_quote
    ) {
        it.block == MarkdownBlockStyle.Quote
    },
    ToolbarItem.CodeBlock to FormatAction(
        MarkdownCommand.CodeBlock,
        R.string.editor_code_block,
        R.drawable.ic_editor_code_block,
        isToggle = false,
    ) {
        false
    },
    ToolbarItem.Table to FormatAction(
        MarkdownCommand.Table,
        R.string.editor_table,
        R.drawable.ic_editor_table,
        isToggle = false,
    ) {
        false
    },
    ToolbarItem.Divider to FormatAction(
        MarkdownCommand.Divider,
        R.string.editor_divider,
        R.drawable.ic_editor_divider,
        isToggle = false,
    ) {
        false
    },
)

@Composable
private fun HistoryButton(
    @DrawableRes icon: Int,
    @StringRes label: Int,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp)) {
        LoomaIcon(
            iconRes = icon,
            contentDescription = stringResource(label),
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun FormatButton(
    action: FormatAction,
    formatState: MarkdownFormatState,
    onCommand: (MarkdownCommand) -> Unit,
) {
    if (!action.isToggle) {
        IconButton(
            onClick = { onCommand(action.command) },
            modifier = Modifier.size(48.dp),
            colors = IconButtonDefaults.iconButtonColors(
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        ) {
            LoomaIcon(
                iconRes = action.icon,
                contentDescription = stringResource(action.label),
                modifier = Modifier.size(22.dp),
            )
        }
        return
    }

    val checked = action.isActive(formatState)
    IconToggleButton(
        checked = checked,
        onCheckedChange = { onCommand(action.command) },
        modifier = Modifier.size(48.dp),
        colors = IconButtonDefaults.iconToggleButtonColors(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
            checkedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        LoomaIcon(
            iconRes = action.icon,
            contentDescription = stringResource(action.label),
            modifier = Modifier.size(22.dp),
        )
    }
}
