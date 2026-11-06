package com.example.looma.feature.home.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
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
                            .heightIn(max = 160.dp)
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
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        note.summary.tags.take(2).forEach { tag -> HomeChip("#$tag") }
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
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = LoomaTokens.type.cardMeta,
            color = LoomaTokens.colors.onChip,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
