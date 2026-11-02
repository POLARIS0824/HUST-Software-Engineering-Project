package com.example.looma.feature.home.components

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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.looma.R
import com.example.looma.feature.home.HomeNoteItem
import com.example.looma.ui.markdown.MarkdownPreview
import com.example.looma.ui.theme.LoomaTokens

/** 单张笔记卡片：封面、标题、Markdown 正文开头与标签 */
@Composable
internal fun HomeNoteCard(note: HomeNoteItem, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = LoomaTokens.colors.card,
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
                    style = LoomaTokens.type.cardTitle,
                    color = LoomaTokens.colors.title,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (note.markdownSnippet.isBlank()) {
                    Text(
                        text = stringResource(R.string.home_empty_note),
                        style = LoomaTokens.type.cardBody,
                        color = LoomaTokens.colors.muted,
                    )
                } else {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                            .clipToBounds(),
                    ) {
                        MarkdownPreview(markdown = note.markdownSnippet)
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
