package com.example.looma.ui.markdown

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.looma.domain.markdown.MarkdownIndex
import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.NotePath
import com.example.looma.domain.model.VaultId
import com.example.looma.domain.wiki.WikiContext
import com.example.looma.domain.wiki.normalizeWikiName
import com.example.looma.ui.theme.LoomaTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 双链在渲染层的行为：点得到、点不到、点错地方。
 *
 * 这里只证明"能不能点、点到的是哪个目标"；解析后去跳转还是提示由 feature 层决定。
 */
@RunWith(AndroidJUnit4::class)
class WikiLinkTapTest {

    @get:Rule
    val rule = createComposeRule()

    private val target = NoteId(VaultId("vault"), NotePath("NoteA.md"))

    private fun wikiFor(name: String): WikiContext =
        WikiContext(mapOf(normalizeWikiName(name) to target))

    private fun show(markdown: String, wiki: WikiContext, onClick: (String) -> Unit) {
        rule.setContent {
            LoomaTheme(dynamicColor = false) {
                MarkdownBody(
                    document = MarkdownIndex.index(markdown, "兜底"),
                    onCheckboxToggle = { _, _ -> },
                    wiki = wiki,
                    onWikiLinkClick = onClick,
                )
            }
        }
    }

    @Test
    fun tappingAResolvedLinkReportsItsTarget() {
        var clicked: String? = null
        show("[[NoteA]]", wikiFor("NoteA")) { clicked = it }

        rule.onNodeWithText("[[NoteA]]").performClick()

        assertEquals("NoteA", clicked)
    }

    @Test
    fun tappingAnUnresolvedLinkStillReportsTheTarget() {
        var clicked: String? = null
        show("[[Missing]]", WikiContext.Empty) { clicked = it }

        rule.onNodeWithText("[[Missing]]").performClick()

        assertEquals("Missing", clicked)
    }

    @Test
    fun aLinkInsideInlineCodeIsNotTappable() {
        var clicked: String? = null
        show("`[[NoteA]]`", wikiFor("NoteA")) { clicked = it }

        rule.onNodeWithText("[[NoteA]]", substring = true).performClick()

        assertNull(clicked)
    }

    @Test
    fun tappingALinkInsideAHeadingReportsItsTarget() {
        var clicked: String? = null
        show("# 见 [[NoteA]]", wikiFor("NoteA")) { clicked = it }

        rule.onNodeWithText("[[NoteA]]", substring = true).performClick()

        assertEquals("NoteA", clicked)
    }
}
