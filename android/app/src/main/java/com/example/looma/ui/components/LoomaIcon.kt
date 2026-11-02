package com.example.looma.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource

/**
 * 统一入口：图标一律用 res/drawable 里的 VectorDrawable，
 * 由调用方（IconButton / Icon）决定尺寸，颜色跟随 LocalContentColor。
 * 这样不必依赖 material-icons-extended，也不必手写 Canvas 路径。
 */
@Composable
fun LoomaIcon(
    @DrawableRes iconRes: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    Icon(
        painter = painterResource(iconRes),
        contentDescription = contentDescription,
        modifier = modifier,
        tint = tint,
    )
}
