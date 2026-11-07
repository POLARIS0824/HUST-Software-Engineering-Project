package com.example.looma.ui.markdown

import androidx.compose.material3.Typography
import com.example.looma.ui.theme.loomaTypography
import org.junit.Assert.assertEquals
import org.junit.Test

class MarkdownScaleTest {

    private val type = loomaTypography(Typography())

    @Test
    fun `reading hierarchy stays distinct from compact card previews`() {
        val reading = loomaMarkdownScale(type)
        val preview = loomaMarkdownScale(type, compact = true)
        org.junit.Assert.assertTrue(reading.h1.fontSize > preview.h1.fontSize)
        org.junit.Assert.assertTrue(reading.h1.fontSize > type.noteBody.fontSize)
        org.junit.Assert.assertTrue(reading.h2.fontSize >= reading.h3.fontSize)
        assertEquals(type.noteBody.lineHeight, reading.code.lineHeight)
    }

    @Test
    fun `code and prose have separate font families`() {
        val scale = loomaMarkdownScale(type)
        org.junit.Assert.assertNotEquals(type.noteBody.fontFamily, scale.code.fontFamily)
    }
}
