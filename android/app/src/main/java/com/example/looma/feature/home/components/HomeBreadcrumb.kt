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

/** 文件夹层级提示：返回箭头加“首页 / 父级 / 当前” */
@Composable
internal fun HomeBreadcrumb(
    crumbs: List<HomeCrumb>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (crumbs.isEmpty()) return
    // 根标签属于展示层；HomeVaultIndex 只产出路径与各段名称
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
