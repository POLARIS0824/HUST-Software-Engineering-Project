@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.example.looma.feature.vault

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.looma.R
import com.example.looma.ui.components.DelayedLoadingIndicator
import com.example.looma.ui.theme.LoomaTheme

/**
 * 纯 UI：从外部接收状态、向外部通知事件
 * 不直接操作 SAF / File / 权限 / Repository
 */
@Composable
fun VaultSetupScreen(
    onChooseFolderClick: () -> Unit,
    onConfirmClick: () -> Unit = {},
    selectedFolderName: String? = null,
    currentFolderName: String? = null,
    isBusy: Boolean = false,
    errorMessage: String? = null,
    modifier: Modifier = Modifier,
) {
    val content = when {
        selectedFolderName != null -> VaultContent.Pending(selectedFolderName, currentFolderName)
        currentFolderName != null -> VaultContent.Current(currentFolderName)
        else -> VaultContent.Empty
    }
    val motion = MaterialTheme.motionScheme

    // 限制最大内容宽度，平板上不会被拉得太宽
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 24.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            ) {
                VaultSetupHeader()
                Spacer(Modifier.height(32.dp))

                // 只要内容状态改变，新卡片就会淡入 + 从 92% 放大；旧卡片淡出
                AnimatedContent(
                    targetState = content,
                    transitionSpec = {
                        (fadeIn(motion.defaultEffectsSpec()) +
                            scaleIn(initialScale = 0.92f, animationSpec = motion.defaultSpatialSpec())) togetherWith
                            fadeOut(motion.fastEffectsSpec())
                    },
                    label = "vaultSetupContent",
                ) { target ->
                    when (target) {
                        VaultContent.Empty -> Unit
                        is VaultContent.Pending -> VaultFolderCard(
                            folderName = target.name,
                            pending = true,
                            previousFolderName = target.previous,
                        )
                        is VaultContent.Current -> VaultFolderCard(
                            folderName = target.name,
                            pending = false,
                        )
                    }
                }

                AnimatedVisibility(
                    visible = !errorMessage.isNullOrBlank(),
                    enter = fadeIn(motion.defaultEffectsSpec()),
                    exit = fadeOut(motion.fastEffectsSpec()),
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    ) {
                        Text(
                            text = errorMessage.orEmpty(),
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            VaultSetupActions(
                content = content,
                busy = isBusy,
                onChooseFolderClick = onChooseFolderClick,
                onConfirmClick = onConfirmClick,
            )
        }
    }
}

private sealed interface VaultContent {
    data object Empty : VaultContent
    data class Pending(val name: String, val previous: String?) : VaultContent
    data class Current(val name: String) : VaultContent
}

@Composable
private fun VaultSetupHeader() {
    Column(Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier.size(88.dp).background(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialShapes.Cookie9Sided.toShape(),
            ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.folder_open_24px),
                contentDescription = null,
                tint = Color.Unspecified,
                modifier = Modifier.size(44.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
        Text(
            text = "选择 Looma 笔记库",
            style = MaterialTheme.typography.headlineLargeEmphasized,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "你的 Markdown 笔记将保存在所选文件夹中",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun VaultFolderCard(
    folderName: String,
    pending: Boolean,
    previousFolderName: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = colors.primaryContainer,
            contentColor = colors.onPrimaryContainer,
        ),
    ) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(
                color = colors.primary,
                contentColor = colors.onPrimary,
                shape = CircleShape,
            ) {
                Text(
                    text = if (pending) "待连接" else "当前文件夹",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            Text(
                text = folderName.ifBlank { "未命名文件夹" },
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Surface(color = colors.surface, contentColor = colors.onSurface, shape = CircleShape) {
                Text(
                    text = if (pending) "等待确认" else "已连接",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            if (pending && previousFolderName != null) {
                HorizontalDivider(color = colors.onPrimaryContainer.copy(alpha = 0.16f))
                Text(
                    text = "当前文件夹：$previousFolderName",
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onPrimaryContainer.copy(alpha = 0.72f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun VaultSetupActions(
    content: VaultContent,
    busy: Boolean,
    onChooseFolderClick: () -> Unit,
    onConfirmClick: () -> Unit,
) {
    val height = ButtonDefaults.MediumContainerHeight
    if (content == VaultContent.Empty) {
        Button(
            onClick = onChooseFolderClick,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = height),
            contentPadding = ButtonDefaults.contentPaddingFor(height),
        ) {
            Text("选择文件夹", style = ButtonDefaults.textStyleFor(height))
        }
        return
    }

    val primaryLabel = if (busy) "正在连接…" else if (content is VaultContent.Pending) "确认" else "继续"
    val secondaryLabel = if (content is VaultContent.Pending) "重选" else "更换"
    // 窄屏纵向排列；宽屏连体按钮，主按钮的文字与指示器不会抢占空间
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val narrow = maxWidth < 305.dp
        if (narrow) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryVaultButton(primaryLabel, busy, onConfirmClick, Modifier.fillMaxWidth())
                FilledTonalButton(
                    onClick = onChooseFolderClick,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = height),
                ) { Text(secondaryLabel) }
            }
        } else {

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(
                    ButtonGroupDefaults.ConnectedSpaceBetween
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(
                    onClick = onChooseFolderClick,
                    enabled = !busy,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = height),
                    shape = ButtonGroupDefaults.connectedLeadingButtonShape,
                    contentPadding = ButtonDefaults.contentPaddingFor(height),
                ) {
                    Text(
                        text = secondaryLabel,
                        style = ButtonDefaults.textStyleFor(height),
                        maxLines = 1,
                    )
                }

                PrimaryVaultButton(
                    text = primaryLabel,
                    busy = busy,
                    onClick = onConfirmClick,
                    modifier = Modifier.weight(1f),
                    shape = ButtonGroupDefaults.connectedTrailingButtonShape,
                )
            }

        }
    }
}

@Composable
private fun PrimaryVaultButton(
    text: String,
    busy: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = ButtonDefaults.shape,
) {
    val height = ButtonDefaults.MediumContainerHeight
    Button(
        onClick = onClick,
        enabled = !busy,
        shape = shape,
        contentPadding = ButtonDefaults.contentPaddingFor(height),
        modifier = modifier.heightIn(min = height),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Text(
                text = text,
                style = ButtonDefaults.textStyleFor(height),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.size(6.dp))
            DelayedLoadingIndicator(loading = busy)
        }
    }
}

@Preview(name = "未连接", showBackground = true)
@Composable
private fun EmptyPreview() {
    LoomaTheme(dynamicColor = false) { VaultSetupScreen(onChooseFolderClick = {}) }
}

@Preview(name = "待连接", showBackground = true)
@Composable
private fun PendingPreview() {
    LoomaTheme(dynamicColor = false) {
        VaultSetupScreen(
            onChooseFolderClick = {},
            selectedFolderName = "My Markdown Vault",
        )
    }
}

@Preview(name = "已连接", showBackground = true)
@Composable
private fun CurrentPreview() {
    LoomaTheme(dynamicColor = false) {
        VaultSetupScreen(
            onChooseFolderClick = {},
            currentFolderName = "My Markdown Vault",
        )
    }
}
