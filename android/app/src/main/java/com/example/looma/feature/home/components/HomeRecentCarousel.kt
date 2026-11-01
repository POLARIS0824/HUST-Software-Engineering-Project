package com.example.looma.feature.home.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.looma.domain.model.NoteId
import com.example.looma.feature.home.HomeRecentItem

/**
 * 最近笔记的横向卡片流，用 Material 的多浏览轮播做出 Google Files 的效果：
 * 完整卡片加右侧露出的一角，滑动时卡片宽度会收缩。
 *
 * 摘要和标题都放在卡片内部，卡片高度固定，露出的半张卡不会飘出一行被裁掉的标题。
 */
@Composable
internal fun HomeRecentCarousel(
    items: List<HomeRecentItem>,
    onNoteClick: (NoteId) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    val carouselState = rememberCarouselState { items.size }

    HorizontalMultiBrowseCarousel(
        state = carouselState,
        preferredItemWidth = RecentCardWidth,
        itemSpacing = 10.dp,
        contentPadding = PaddingValues(horizontal = 0.dp),
        modifier = modifier.fillMaxWidth(),
    ) { index ->
        val item = items[index]
        val shape = RoundedCornerShape(20.dp)
        HomeRecentCard(
            item = item,
            onClick = { onNoteClick(item.note.id) },
            modifier = Modifier.maskClip(shape),
        )
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
        modifier = modifier.fillMaxWidth().height(140.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 14.dp)) {
            Text(
                text = item.snippet,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = item.note.summary.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private val RecentCardWidth = 232.dp
