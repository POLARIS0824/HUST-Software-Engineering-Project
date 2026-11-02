package com.example.looma.feature.note

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.text.TextRange
import com.example.looma.domain.markdown.MarkdownCommand
import com.example.looma.domain.markdown.MarkdownEditing
import com.example.looma.domain.markdown.TextSelection

/**
 * 把纯函数的编辑结果写回缓冲区。只替换前后缀之间真正变化的那一段，
 * 避免每次点按钮都整段重写，撤销栈和输入法组合状态都更干净。
 */
internal fun applyMarkdownCommand(buffer: TextFieldState, command: MarkdownCommand) {
    val oldText = buffer.text.toString()
    val caret = buffer.selection
    val result = MarkdownEditing.apply(
        command = command,
        text = oldText,
        selection = TextSelection(caret.start, caret.end),
    )

    val prefix = commonPrefixLength(oldText, result.text)
    val suffix = commonSuffixLength(oldText, result.text, prefix)
    val replacement = result.text.substring(prefix, result.text.length - suffix)
    val newStart = result.selection.start.coerceIn(0, result.text.length)
    val newEnd = result.selection.end.coerceIn(0, result.text.length)

    buffer.edit {
        replace(prefix, oldText.length - suffix, replacement)
        selection = TextRange(newStart, newEnd)
    }
}

private fun commonPrefixLength(first: String, second: String): Int {
    var index = 0
    while (index < first.length && index < second.length && first[index] == second[index]) index++
    return index
}

private fun commonSuffixLength(first: String, second: String, prefix: Int): Int {
    var index = 0
    while (
        index < first.length - prefix &&
        index < second.length - prefix &&
        first[first.length - 1 - index] == second[second.length - 1 - index]
    ) {
        index++
    }
    return index
}
