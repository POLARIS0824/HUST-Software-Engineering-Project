package com.example.looma.feature.note.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.looma.R
import com.example.looma.feature.note.NoteMode
import com.example.looma.ui.components.LoomaIcon
import com.example.looma.ui.theme.LoomaTokens

/**
 * 笔记页顶栏只保留两个动作：左上角返回，右上角在阅读和编辑之间切换。
 * 标题由正文里的 H1 承担，顶栏不再重复一遍，和 Keep 的留白一致。
 */
@Composable
internal fun NoteTopBar(
    mode: NoteMode,
    onNavigateUp: () -> Unit,
    onToggleMode: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = LoomaTokens.colors.page,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(64.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onNavigateUp) {
                LoomaIcon(
                    iconRes = R.drawable.ic_arrow_back,
                    contentDescription = stringResource(R.string.editor_back),
                )
            }
            Spacer(Modifier.weight(1f))
            val toggle = onToggleMode
            if (toggle != null) {
                IconButton(onClick = toggle) {
                    LoomaIcon(
                        iconRes = if (mode == NoteMode.Read) {
                            R.drawable.ic_editor_edit
                        } else {
                            R.drawable.ic_editor_preview
                        },
                        contentDescription = stringResource(
                            if (mode == NoteMode.Read) R.string.note_edit else R.string.note_read,
                        ),
                    )
                }
            }
        }
    }
}
