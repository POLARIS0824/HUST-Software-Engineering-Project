package com.example.looma.ui.markdown

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.unit.TextUnit
import com.example.looma.domain.markdown.MathSpan
import com.example.looma.domain.markdown.collectMathSpans
import com.mikepenz.markdown.model.MarkdownAnnotator
import com.mikepenz.markdown.model.markdownAnnotator
import io.ratex.DisplayList
import io.ratex.RaTeXEngine
import io.ratex.RaTeXFontLoader
import io.ratex.compose.RaTeX
import io.ratex.measure
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes

@Immutable
internal class MathRenderState(
    internal val spans: Map<Int, MathSpan>,
    internal val displays: Map<Int, DisplayList>,
    val fontSize: TextUnit,
) {
    fun span(start: Int): MathSpan? = spans[start]

    fun display(start: Int): DisplayList? = displays[start]

    fun hasInlineMath(range: IntRange): Boolean =
        spans.values.any { !it.block && it.range.first in range }

    fun tag(start: Int): String = mathTag(start)

    fun blockDisplay(node: ASTNode): DisplayList? {
        val math = node.children.firstOrNull { it.type == GFMElementTypes.BLOCK_MATH } ?: return null
        if (spans[math.startOffset]?.block != true) return null
        return displays[math.startOffset]
    }
}

@Composable
internal fun rememberMathRenderState(
    body: String,
    fontSize: TextUnit,
    color: Color,
): MathRenderState {
    val spans = remember(body) { collectMathSpans(body) }
    val displayState = remember(spans, color) { mutableStateOf(emptyMap<Int, DisplayList>()) }
    LaunchedEffect(spans, color) {
        displayState.value = emptyMap()
        if (spans.isEmpty()) return@LaunchedEffect
        runCatching { RaTeXFontLoader.ensureLoaded() }
        val parsed = LinkedHashMap<Int, DisplayList>()
        spans.forEach { span ->
            val display = mathDisplays.get(span.latex, span.display, color)
                ?: runCatching { RaTeXEngine.parse(span.latex, span.display, color) }
                    .getOrNull()
                    ?.also { mathDisplays.put(span.latex, span.display, color, it) }
            if (display != null) {
                parsed[span.range.first] = display
                displayState.value = parsed.toMap()
            }
        }
    }
    val displays = displayState.value
    val spanByStart = remember(spans) { spans.associateBy { it.range.first } }

    return remember(spanByStart, displays, fontSize) {
        MathRenderState(
            spans = spanByStart,
            displays = displays,
            fontSize = fontSize,
        )
    }
}

@Composable
internal fun rememberMathInlineContent(
    state: MathRenderState,
    fontSize: TextUnit,
    range: IntRange? = null,
    maxWidth: TextUnit? = null,
): Map<String, InlineTextContent> {
    val density = LocalDensity.current
    val fontSizePx = with(density) { fontSize.toPx() }
    return remember(state, fontSize, fontSizePx, density, range, maxWidth) {
        buildMap {
            state.displays.forEach { (start, display) ->
                if (range != null && start !in range) return@forEach
                val span = state.spans[start] ?: return@forEach
                if (span.block) return@forEach
                val measured = display.measure(fontSizePx)
                val formulaWidth = with(density) { measured.widthPx.toSp() }
                val placeholderWidth = when {
                    maxWidth == null || formulaWidth <= maxWidth -> formulaWidth
                    else -> maxWidth
                }
                put(
                    mathTag(start),
                    InlineTextContent(
                        Placeholder(
                            width = placeholderWidth,
                            height = with(density) { measured.totalHeightPx.toSp() },
                            placeholderVerticalAlign = PlaceholderVerticalAlign.Center,
                        ),
                    ) { _ ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .horizontalScroll(rememberScrollState()),
                        ) {
                            RaTeX(displayList = display, fontSize = fontSize)
                        }
                    },
                )
            }
        }
    }
}

internal fun mathAnnotator(state: MathRenderState): MarkdownAnnotator =
    markdownAnnotator(config = loomaAnnotatorConfig) { content, child ->
        if (child.type != GFMElementTypes.INLINE_MATH && child.type != GFMElementTypes.BLOCK_MATH) {
            return@markdownAnnotator false
        }
        val span = state.span(child.startOffset) ?: return@markdownAnnotator false
        if (span.block || state.display(span.range.first) == null) return@markdownAnnotator false
        appendInlineContent(state.tag(span.range.first), content.substring(span.range))
        true
    }

@Composable
internal fun MathBlock(
    display: DisplayList,
    fontSize: TextUnit,
    padding: PaddingValues,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(padding)
            .horizontalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center,
    ) {
        RaTeX(displayList = display, fontSize = fontSize)
    }
}

private fun mathTag(start: Int): String ="looma-math-$start"

private object mathDisplays {
    private const val MAX_ENTRIES = 64
    private val entries = object : LinkedHashMap<String, DisplayList>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DisplayList>) =
            size > MAX_ENTRIES
    }

    @Synchronized
    fun get(latex: String, display: Boolean, color: Color): DisplayList? = entries[key(latex, display, color)]

    @Synchronized
    fun put(latex: String, display: Boolean, color: Color, displayList: DisplayList) {
        entries[key(latex, display, color)] = displayList
    }

    private fun key(latex: String, display: Boolean, color: Color): String =
"${display}:${color.value}:$latex"
}
