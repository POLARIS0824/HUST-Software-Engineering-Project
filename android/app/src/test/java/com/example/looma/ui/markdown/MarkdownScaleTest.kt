package com.example.looma.ui.markdown

import androidx.compose.material3.Typography
import com.example.looma.ui.theme.loomaTypography
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Markdown 的标题与代码不自己定字号，全部由正文基准乘出来。
 *
 * 换正文角色时整条阶梯跟着走，页面不需要逐个改。
 */
class MarkdownScaleTest {

    private val body = loomaTypography(Typography()).body

    @Test
    fun `headings and code derive from the body base`() {
        val scale = loomaMarkdownScale(body)
        assertEquals(body.fontSize.value * 1.7f, scale.h1.fontSize.value, 0.01f)
        assertEquals(body.fontSize.value * 0.9f, scale.code.fontSize.value, 0.01f)
    }

    @Test
    fun `heading line height follows the heading size`() {
        val scale = loomaMarkdownScale(body)
        assertEquals(scale.h1.fontSize.value * 1.35f, scale.h1.lineHeight.value, 0.01f)
    }
}
