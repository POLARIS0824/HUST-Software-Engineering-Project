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

enum class LoomaDestination(
    @StringRes val label: Int,
    @DrawableRes val icon: Int,
) {
    Notes(R.string.drawer_notes, R.drawable.ic_note),
    Trash(R.string.drawer_trash, R.drawable.ic_delete),
    Settings(R.string.drawer_settings, R.drawable.ic_settings),
}

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
