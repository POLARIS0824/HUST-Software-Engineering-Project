package com.example.looma.ui.markdown

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import com.example.looma.domain.markdown.MarkdownDocument
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCheckBox
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography

/**
 * 全项目唯一调用 Markdown 渲染库的地方。
 *
 * 只吃 [MarkdownDocument]，不认识文件、路径和 ViewModel。复选框点击报原文坐标，
 * 由 feature 层决定怎么写回去。换渲染库时只改这个文件。
 */
@Composable
fun MarkdownBody(
    document: MarkdownDocument,
    onCheckboxToggle: (position: Int, checked: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentToggle = rememberUpdatedState(onCheckboxToggle)
    val components = remember(document.body, document.bodyStart) {
        markdownComponents(
            checkbox = { model ->
                CheckboxItem(
                    model = model,
                    bodyStart = document.bodyStart,
                    onToggle = { position, checked -> currentToggle.value(position, checked) },
                )
            },
        )
    }

    Markdown(
        content = document.body,
        colors = markdownColor(),
        typography = markdownTypography(),
        components = components,
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun CheckboxItem(
    model: MarkdownComponentModel,
    bodyStart: Int,
    onToggle: (position: Int, checked: Boolean) -> Unit,
) {
    val position = bodyStart + model.node.startOffset
    MarkdownCheckBox(
        content = model.content,
        node = model.node,
        style = model.typography.text,
        checkedIndicator = { checked, indicatorModifier ->
            Checkbox(
                checked = checked,
                onCheckedChange = { onToggle(position, it) },
                modifier = indicatorModifier,
            )
        },
    )
}
