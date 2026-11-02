package com.example.looma.feature.note.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.looma.R
import com.example.looma.domain.wiki.WikiNote
import com.example.looma.domain.wiki.wikiNameOf
import com.example.looma.domain.wiki.wikiPathOf
import com.example.looma.ui.components.LoomaIcon
import com.example.looma.ui.theme.LoomaTokens

/**
 * 插入双链时的候选面板：边输入边按标题过滤，选一条就把 `[[标题]]` 写进正文。
 *
 * 候选来自打开笔记时那份索引快照（[com.example.looma.domain.wiki.WikiContext.linkables]），
 * 所以这里不读盘、不查库，输入再快也只在这个列表上过滤。
 */
@Composable
internal fun WikiLinkPanel(
    candidates: List<WikiNote>,
    onPick: (WikiNote) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val matches = remember(query, candidates) { filterCandidates(candidates, query) }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LoomaIcon(
                    iconRes = R.drawable.ic_search,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                        .focusRequester(focusRequester),
                    singleLine = true,
                    textStyle = LoomaTokens.type.cardBody.copy(color = LoomaTokens.colors.title),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    decorationBox = { inner ->
                        Box {
                            if (query.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.editor_wikilink_search),
                                    style = LoomaTokens.type.cardBody,
                                    color = LoomaTokens.colors.muted,
                                )
                            }
                            inner()
                        }
                    },
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                    LoomaIcon(
                        iconRes = R.drawable.ic_close,
                        contentDescription = stringResource(R.string.editor_cancel),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            if (matches.isEmpty()) {
                Text(
                    text = stringResource(R.string.editor_wikilink_empty),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                    style = LoomaTokens.type.cardMeta,
                    color = LoomaTokens.colors.muted,
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 216.dp)) {
                    items(matches, key = { it.id.path.relativePath }) { note ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                                .background(
                                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                                    shape = RoundedCornerShape(10.dp),
                                )
                                .clickable { onPick(note) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                text = note.title,
                                style = LoomaTokens.type.cardTitle,
                                color = LoomaTokens.colors.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = note.id.path.relativePath.substringBeforeLast('/'),
                                style = LoomaTokens.type.cardMeta,
                                color = LoomaTokens.colors.muted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 前缀优先，其次包含；大小写不敏感，空查询按标题序返回全部 */
internal fun filterCandidates(candidates: List<WikiNote>, query: String): List<WikiNote> {
    val needle = query.trim()
    if (needle.isEmpty()) return candidates.take(MAX_MATCHES)
    val folded = needle.lowercase()
    return candidates
        .asSequence()
        .filter { it.title.lowercase().contains(folded) }
        .sortedWith(
            compareBy(
                { if (it.title.lowercase().startsWith(folded)) 0 else 1 },
                { it.title.lowercase() },
            ),
        )
        .take(MAX_MATCHES)
        .toList()
}

private const val MAX_MATCHES = 6

/**
 * 候选写进正文时的引用文本。
 *
 * 双链解析认的是文件名（[com.example.looma.domain.wiki.WikiGraph.resolve]），不是 H1 标题，
 * 所以这里用文件名；标题与文件名不一致时补一个别名，读起来仍是人看的那个标题。
 * 同名笔记存在于不同目录时改用带目录的写法，避免选错。
 */
internal fun wikiReferenceFor(note: WikiNote, all: List<WikiNote>): String {
    val base = wikiNameOf(note.id.path)
    val duplicated = all.count { wikiNameOf(it.id.path).equals(base, ignoreCase = true) } > 1
    val target = if (duplicated) wikiPathOf(note.id.path) else base
    val alias = note.title.trim()
    return if (alias.isNotEmpty() && !alias.equals(target, ignoreCase = true)) {
        "$target|$alias"
    } else {
        target
    }
}
