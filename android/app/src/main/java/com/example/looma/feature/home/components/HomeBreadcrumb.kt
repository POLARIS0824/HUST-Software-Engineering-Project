package com.example.looma.feature.home.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.looma.R
import com.example.looma.feature.home.HomeCrumb
import com.example.looma.ui.components.LoomaIcon

/** 文件夹层级提示：返回箭头加“首页 / 父级 / 当前” */
@Composable
internal fun HomeBreadcrumb(
    crumbs: List<HomeCrumb>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (crumbs.isEmpty()) return
    Row(
        modifier = modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            LoomaIcon(
                iconRes = R.drawable.ic_arrow_back,
                contentDescription = "返回上一级",
                modifier = Modifier.size(22.dp),
            )
        }
        Text(
            text = crumbs.joinToString(separator = " / ") { it.label },
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
