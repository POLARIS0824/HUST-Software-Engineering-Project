package com.example.looma.navigation

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DrawerState
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.looma.R
import com.example.looma.ui.components.LoomaIcon
import com.example.looma.ui.theme.LoomaTokens

/** 侧边栏的三个入口；顺序与 Keep 的 Notes / Trash / Settings 一致 */
enum class LoomaDestination(
    @StringRes val label: Int,
    @DrawableRes val icon: Int,
) {
    Notes(R.string.drawer_notes, R.drawable.ic_note),
    Trash(R.string.drawer_trash, R.drawable.ic_delete),
    Settings(R.string.drawer_settings, R.drawable.ic_settings),
}

/**
 * 侧边栏是应用级外壳，不是某个 feature 的一部分：
 * 它只知道三个顶层入口和当前高亮项，具体路由由调用方决定。
 */
@Composable
fun LoomaDrawer(
    drawerState: DrawerState,
    current: LoomaDestination?,
    vaultName: String,
    gesturesEnabled: Boolean,
    onSelect: (LoomaDestination) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    ModalNavigationDrawer(
        modifier = modifier,
        drawerState = drawerState,
        gesturesEnabled = gesturesEnabled,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = LoomaTokens.colors.sheet,
                // 侧边栏永远不铺满屏幕：窄屏取 86%，宽屏最多 320dp，右侧始终露出内容
                modifier = Modifier.fillMaxWidth(0.86f).widthIn(max = 320.dp),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 28.dp, end = 28.dp, top = 24.dp, bottom = 12.dp),
                ) {
                    val appName = stringResource(R.string.app_name)
                    Text(
                        text = appName,
                        style = LoomaTokens.type.pageTitle,
                        color = LoomaTokens.colors.title,
                    )
                    // 笔记库和 App 同名时只显示一次，避免标题重复
                    if (vaultName.isNotBlank() && vaultName != appName) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = vaultName,
                            style = LoomaTokens.type.cardMeta,
                            color = LoomaTokens.colors.muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                LoomaDestination.entries.forEach { destination ->
                    NavigationDrawerItem(
                        label = {
                            Text(
                                text = stringResource(destination.label),
                                style = LoomaTokens.type.body,
                            )
                        },
                        icon = {
                            LoomaIcon(
                                iconRes = destination.icon,
                                contentDescription = null,
                            )
                        },
                        selected = destination == current,
                        onClick = { onSelect(destination) },
                        shape = RoundedCornerShape(28.dp),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                    )
                }
            }
        },
        content = content,
    )
}
