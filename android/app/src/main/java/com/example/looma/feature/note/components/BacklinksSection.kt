package com.example.looma.feature.note.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.looma.R
import com.example.looma.domain.model.NoteId
import com.example.looma.domain.wiki.Backlink
import com.example.looma.ui.theme.LoomaTokens

@Composable
fun BacklinksSection(
    backlinks: List<Backlink>,
    onOpen: (NoteId) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (backlinks.isEmpty()) return
    val colors = LoomaTokens.colors
    val type = LoomaTokens.type

    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalDivider(
            color = colors.divider,
            modifier = Modifier.padding(top = 24.dp),
        )
        Text(
            text = stringResource(R.string.note_backlinks_title, backlinks.size),
            style = type.sectionTitle,
            color = colors.title,
            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            backlinks.forEach { backlink ->
                Text(
                    text = backlink.title,
                    style = type.body,
                    color = colors.accent,
                    maxLines = 2,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(backlink.id) }
                        .padding(vertical = 8.dp),
                )
            }
        }
    }
}
