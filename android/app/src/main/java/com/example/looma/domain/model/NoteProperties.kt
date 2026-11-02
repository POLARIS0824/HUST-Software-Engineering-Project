package com.example.looma.domain.model

import java.time.Instant

/** “文件属性”弹窗要展示的事实，全部来自磁盘与正文本身 */
data class NoteProperties(
    val path: NotePath,
    val sizeBytes: Long,
    val modifiedAt: Instant,
    val words: Int,
    val characters: Int,
    val checkboxesDone: Int,
    val checkboxesTotal: Int,
)

/**
 * 字数与字符数。
 *
 * 中文按字计数（与 Obsidian 状态栏一致），拉丁文字按空白切分，
 * 两者相加才是用户看到的“字数”。
 */
object NoteTextStats {

    fun words(text: String): Int {
        var count = 0
        var inLatinToken = false
        for (character in text) {
            if (character.isCjk()) {
                count++
                inLatinToken = false
                continue
            }
            if (character.isWhitespace()) {
                inLatinToken = false
                continue
            }
            if (!inLatinToken) {
                count++
                inLatinToken = true
            }
        }
        return count
    }

    fun characters(text: String): Int = text.count { !it.isWhitespace() }

    private fun Char.isCjk(): Boolean =
        this in '\u4E00'..'\u9FFF' ||
            this in '\u3400'..'\u4DBF' ||
            this in '\u3040'..'\u30FF' ||
            this in '\uAC00'..'\uD7AF'
}
