package com.example.looma.feature.home.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.looma.R
import com.example.looma.feature.home.HomeCrumb
import com.example.looma.ui.components.LoomaIcon
import com.example.looma.ui.theme.LoomaTokens

@Composable
internal fun HomeBreadcrumb(
    crumbs: List<HomeCrumb>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (crumbs.isEmpty()) return
    val rootLabel = stringResource(R.string.home_breadcrumb_root)
    Row(
        modifier = modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            LoomaIcon(
                iconRes = R.drawable.ic_arrow_back,
                contentDescription = stringResource(R.string.home_breadcrumb_back_description),
                modifier = Modifier.size(22.dp),
            )
        }
        Text(
            text = crumbs.joinToString(separator = " / ") { it.label ?: rootLabel },
            style = LoomaTokens.type.breadcrumb,
            color = LoomaTokens.colors.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
