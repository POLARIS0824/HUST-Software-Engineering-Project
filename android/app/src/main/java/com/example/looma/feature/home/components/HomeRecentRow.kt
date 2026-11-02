package com.example.looma.feature.home.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.looma.R
import com.example.looma.domain.model.NoteId
import com.example.looma.feature.home.HomeRecentItem
import com.example.looma.ui.markdown.MarkdownPreview
import com.example.looma.ui.theme.LoomaTokens

/**
 * 最近笔记的横向卡片行，保持固定卡宽，一屏能露出下一张。
 *
 * 卡片只靠底色和页面区分，不画描边，所以露出的半张卡不会被切出一道缺角的轮廓。
 * 标题在卡片内顶部，正文用只读 Markdown 渲染，超出卡片高度的部分直接裁掉。
 */
@Composable
internal fun HomeRecentRow(
    items: List<HomeRecentItem>,
    onNoteClick: (NoteId) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(items = items, key = { it.note.listKey }) { item ->
            HomeRecentCard(
                item = item,
                onClick = { onNoteClick(item.note.id) },
            )
        }
    }
}

@Composable
private fun HomeRecentCard(
    item: HomeRecentItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.width(RecentCardWidth).height(RecentCardHeight),
        shape = RoundedCornerShape(20.dp),
        color = LoomaTokens.colors.card,
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                text = item.note.summary.title,
                style = LoomaTokens.type.cardTitle,
                color = LoomaTokens.colors.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().weight(1f).clipToBounds()) {
                if (item.markdown.isBlank()) {
                    Text(
                        text = stringResource(R.string.home_empty_note),
                        style = LoomaTokens.type.cardBody,
                        color = LoomaTokens.colors.muted,
                    )
                } else {
                    MarkdownPreview(markdown = item.markdown)
                }
                // 固定高度必然裁到半行，用底色渐变收尾，避免露出被切开的字形
                Box(
                    Modifier.matchParentSize().background(
                        Brush.verticalGradient(
                            0.72f to Color.Transparent,
                            1f to LoomaTokens.colors.card,
                        ),
                    ),
                )
            }
        }
    }
}

private val RecentCardWidth = 168.dp
private val RecentCardHeight = 152.dp
