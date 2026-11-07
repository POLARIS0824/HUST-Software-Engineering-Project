package com.example.looma.ui.markdown

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import com.example.looma.domain.markdown.WikiImageFlavour
import com.mikepenz.markdown.annotator.DefaultAnnotatorSettings
import com.mikepenz.markdown.annotator.buildMarkdownAnnotatedString
import com.mikepenz.markdown.model.MarkdownAnnotator
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.utils.MARKDOWN_TAG_IMAGE_URL
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.parser.MarkdownParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownPreviewImagesTest {
    @Test
    fun `cover embed creates no text or inline placeholder in the preview`() {
        val rendered = paragraph("![[cover.png]]", previewMarkdownAnnotator)
        assertEquals("", rendered.text)
        assertFalse(rendered.hasImagePlaceholder())
    }

    @Test
    fun `inline images disappear while surrounding emphasis and code remain`() {
        val rendered = paragraph(
            "Before **important** ![Cover](cover.png) after `![[literal.png]]`",
            previewMarkdownAnnotator,
        )
        assertTrue(rendered.text.contains("important"))
        assertTrue(rendered.text.contains("Before"))
        assertTrue(rendered.text.contains("after"))
        assertTrue(rendered.text.contains("![[literal.png]]"))
        assertFalse(rendered.text.contains("Cover"))
        assertFalse(rendered.hasImagePlaceholder())
        assertTrue(rendered.spanStyles.isNotEmpty())
    }

    @Test
    fun `reading annotations still include the image placeholder`() {
        val rendered = paragraph("![[cover.png]]", markdownAnnotator())
        assertTrue(rendered.hasImagePlaceholder())
    }

    private fun paragraph(source: String, annotator: MarkdownAnnotator): AnnotatedString {
        val root = MarkdownParser(WikiImageFlavour()).buildMarkdownTreeFromString(source)
        val paragraph = root.children.first { it.type == MarkdownElementTypes.PARAGRAPH }
        val settings = DefaultAnnotatorSettings(
            linkTextSpanStyle = TextLinkStyles(),
            codeSpanStyle = SpanStyle(),
            highlightSpanStyle = SpanStyle(),
            annotator = annotator,
        )
        return buildAnnotatedString { buildMarkdownAnnotatedString(source, paragraph, settings) }
    }

    private fun AnnotatedString.hasImagePlaceholder(): Boolean =
        getStringAnnotations(0, length).any { it.item.startsWith(MARKDOWN_TAG_IMAGE_URL) }
}
