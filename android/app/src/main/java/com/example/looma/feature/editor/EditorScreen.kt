package com.example.looma.feature.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.looma.ui.theme.LoomaTheme

/** The screen owns leave-confirmation UI; the navigator owns only back-stack changes. */
@Composable
fun EditorRoute(
    viewModel: EditorViewModel,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var confirmLeave by rememberSaveable { mutableStateOf(false) }

    fun requestLeave() {
        if (state.isDirty) confirmLeave = true else onNavigateUp()
    }

    // Only intercept system back while the document has unsaved changes.
    // Otherwise Navigation 3 handles back and predictive-back transitions itself.
    BackHandler(enabled = state.isDirty) { confirmLeave = true }

    EditorScreen(
        state = state,
        onContentChanged = viewModel::onContentChanged,
        onSave = { viewModel.save() },
        onNavigateUp = { requestLeave() },
        onRetry = viewModel::load,
        modifier = modifier,
    )

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("未保存的修改") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("直接返回将丢失本次修改")
                    TextButton(onClick = {
                        confirmLeave = false
                        onNavigateUp()
                    }) { Text("放弃修改并返回") }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !state.isSaving,
                    onClick = {
                        viewModel.save {
                            confirmLeave = false
                            onNavigateUp()
                        }
                    },
                ) { Text("保存并返回") }
            },
            dismissButton = {
                TextButton(onClick = { confirmLeave = false }) { Text("取消") }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    state: EditorUiState,
    onContentChanged: (String) -> Unit,
    onSave: () -> Unit,
    onNavigateUp: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(state.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    TextButton(onClick = onNavigateUp) { Text("返回") }
                },
                actions = {
                    TextButton(
                        onClick = onSave,
                        enabled = state.isDirty && !state.isSaving,
                    ) { Text(if (state.isSaving) "保存中" else "保存") }
                },
            )
        },
    ) { innerPadding ->
        when {
            state.isLoading -> Box(
                Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            else -> Column(
                Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.errorMessage != null) {
                    Text(
                        state.errorMessage,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = onRetry, enabled = !state.isDirty) { Text("重新加载") }
                }
                OutlinedTextField(
                    value = state.content,
                    onValueChange = onContentChanged,
                    label = { Text("Markdown") },
                    placeholder = { Text("# 新笔记") },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    enabled = state.errorMessage == null || state.isDirty,
                    textStyle = androidx.compose.material3.MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun EditorScreenPreview() {
    LoomaTheme(dynamicColor = false) {
        EditorScreen(
            state = EditorUiState(
                title = "测试笔记",
                content = "# 测试笔记\n\n这里是 Markdown 正文",
                savedContent = "# 测试笔记",
                isLoading = false,
            ),
            onContentChanged = {},
            onSave = {},
            onNavigateUp = {},
            onRetry = {},
        )
    }
}
