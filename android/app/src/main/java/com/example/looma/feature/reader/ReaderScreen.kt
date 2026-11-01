package com.example.looma.feature.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.looma.domain.markdown.MarkdownIndex
import com.example.looma.ui.markdown.MarkdownBody
import com.example.looma.ui.theme.LoomaTheme

/** 阅读页只负责展示和转发点击，导航与写盘分别由导航图和 ViewModel 拥有。 */
@Composable
fun ReaderRoute(
    viewModel: ReaderViewModel,
    onNavigateUp: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // 从编辑页返回时文件已经变了，回到前台重新读一次。
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.reload() }

    ReaderScreen(
        state = state,
        onCheckboxToggle = viewModel::onCheckboxToggled,
        onNavigateUp = onNavigateUp,
        onEdit = onEdit,
        onRetry = viewModel::reload,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    state: ReaderUiState,
    onCheckboxToggle: (position: Int, checked: Boolean) -> Unit,
    onNavigateUp: () -> Unit,
    onEdit: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        state.document?.title.orEmpty(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    TextButton(onClick = onNavigateUp) { Text("返回") }
                },
                actions = {
                    TextButton(onClick = onEdit, enabled = state.document != null) { Text("编辑") }
                },
            )
        },
    ) { innerPadding ->
        val document = state.document
        when {
            state.isLoading && document == null -> Box(
                Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            document == null -> Column(
                Modifier.fillMaxSize().padding(innerPadding).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(state.errorMessage ?: "笔记无法打开", color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onRetry) { Text("重新加载") }
            }

            else -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.errorMessage != null) {
                    Text(state.errorMessage, color = MaterialTheme.colorScheme.error)
                }
                MarkdownBody(
                    document = document,
                    onCheckboxToggle = onCheckboxToggle,
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ReaderScreenPreview() {
    val source = """
        # 周会记录

        正文里有一个 #标签 和 [[另一篇笔记]]。

        > [!note] 提示
        > 引用里的 **粗体** 和 `code`。

        - [ ] 未完成
        - [x] 已完成

        | 列 | 值 |
        |---|---|
        | a | b |
    """.trimIndent()

    LoomaTheme(dynamicColor = false) {
        ReaderScreen(
            state = ReaderUiState(
                document = MarkdownIndex.index(source, "周会记录"),
                isLoading = false,
            ),
            onCheckboxToggle = { _, _ -> },
            onNavigateUp = {},
            onEdit = {},
            onRetry = {},
        )
    }
}
