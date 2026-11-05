package com.example.looma.feature.home.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.looma.R
import com.example.looma.feature.home.HomeNoteItem
import com.example.looma.ui.markdown.MarkdownPreview
import com.example.looma.ui.theme.LoomaTokens

/**
 * 单张笔记卡片：封面、标题、Markdown 正文开头与标签。
 *
 * 长按进入选择模式，选择期间用主色描边；
 * 点击行为由调用方决定（选择期间是切换选中，否则打开笔记）。
 */
@Composable
internal fun HomeNoteCard(
    note: HomeNoteItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(20.dp)
    val selectLabel = stringResource(R.string.home_select_note_label)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .combinedClickable(
                onLongClickLabel = selectLabel,
                onLongClick = onLongClick,
                onClick = onClick,
            )
            .semantics { selected = isSelected },
        shape = shape,
        // 选中只加描边，不改底色：整块换色会让卡片里的 Markdown 和标签一起被染色
        color = LoomaTokens.colors.card,
        border = BorderStroke(
            if (isSelected) 2.dp else 1.dp,
            if (isSelected) LoomaTokens.colors.accent else LoomaTokens.colors.divider,
        ),
    ) {
        Column {
            note.coverPath?.let { path ->
                NoteCoverImage(
                    path = path,
                    modifier = Modifier.fillMaxWidth().height(150.dp),
                )
            }
            Column(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = note.summary.title,
                    style = LoomaTokens.type.homeNoteTitle,
                    color = LoomaTokens.colors.title,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (note.markdownSnippet.isBlank()) {
                    Text(
                        text = stringResource(R.string.home_empty_note),
                        style = LoomaTokens.type.homeNotePreview.body,
                        color = LoomaTokens.colors.muted,
                    )
                } else {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp)
                            .clipToBounds(),
                    ) {
                        MarkdownPreview(
                            markdown = note.markdownSnippet,
                            typography = LoomaTokens.type.homeNotePreview,
                            textColor = LoomaTokens.colors.body,
                        )
                    }
                }
                if (note.summary.tags.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        note.summary.tags.take(2).forEach { tag ->
                            Box(Modifier.weight(1f, fill = false)) { HomeChip("#$tag") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeChip(label: String) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = LoomaTokens.colors.chip,
    ) {
        Text(
            text = label,
            modifier = Modifier.widthIn(max = 150.dp).padding(horizontal = 10.dp, vertical = 7.dp),
            style = LoomaTokens.type.cardMeta,
            color = LoomaTokens.colors.onChip,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
