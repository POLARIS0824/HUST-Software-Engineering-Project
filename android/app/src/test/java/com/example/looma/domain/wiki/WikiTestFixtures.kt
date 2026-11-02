package com.example.looma.domain.wiki

import com.example.looma.domain.model.NoteId
import com.example.looma.domain.model.NotePath
import com.example.looma.domain.model.VaultId

/** 测试里只需要路径和标题，VaultId 固定一个 */
internal fun wikiNote(
    path: String,
    title: String = path.substringAfterLast('/').removeSuffix(".md"),
): WikiNote = WikiNote(id = NoteId(VaultId("vault"), NotePath(path)), title = title)

internal fun linksByPath(vararg pairs: Pair<String, List<String>>): Map<NoteId, List<String>> =
    pairs.associate { (path, targets) -> NoteId(VaultId("vault"), NotePath(path)) to targets }
