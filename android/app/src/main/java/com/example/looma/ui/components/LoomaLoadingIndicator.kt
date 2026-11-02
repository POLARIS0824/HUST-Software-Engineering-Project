@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.example.looma.ui.components

import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size

/**
 * 页面级加载动画，统一用 M3 Expressive 的 LoadingIndicator（非 contained 版本）。
 *
 * 颜色取主题的主色而不是 LocalContentColor：页面级的等待不属于任何容器内容，
 * 用 onBackground 会在浅色主题下变成近黑色，看起来像没跟随主题。
 */
@Composable
fun LoomaLoadingIndicator(
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    LoadingIndicator(
        modifier = modifier.size(size),
        color = color,
    )
}
