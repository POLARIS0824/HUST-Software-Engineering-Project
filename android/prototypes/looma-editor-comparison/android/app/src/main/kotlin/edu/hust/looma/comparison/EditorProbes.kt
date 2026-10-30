package edu.hust.looma.comparison

import org.json.JSONArray
import org.json.JSONObject

suspend fun runEditorProbes(port: EditorPort): String {
    val corpus = listOf(
        "basic" to "# 标题\n\n正文 **加粗** 与 `code`。\n",
        "reference" to "# 引用\n\n[说明][ref]\n\n[ref]: https://example.com \"标题\"\n",
        "unknown" to "::: custom-box\n未知扩展  &nbsp;  保留空格\n:::\n",
        "html" to "<span data-private=\"retain\">正文</span>\n<script>window.executed=true</script>\n",
        "unicode" to "# Unicode\n\n中文、🙂、e\u0301、\t制表符\n",
        "gfm" to "# 表格\n\n| 名称 | 状态 |\n| --- | --- |\n| 中文 | 保留 |\n\n- [ ] 任务\n\n```md\n# code [[not-link]]\n```\n",
        "crlf-normalized" to "# CRLF\r\n\r\n规范化为 LF\r\n",
        "long-source" to ("# 长文\n\n" + "中文段落 source\n".repeat(4200))
    )
    val results = JSONArray()
    for ((id, original) in corpus) {
        val input = normalizedSource(original) + "\nTarget🙂 end.\n"
        try {
            val noteId = "probe-$id"
            val loaded = port.selectNote(NoteDocument(noteId, input))
            val start = input.indexOf("Target🙂")
            port.command(EditorCommand.Select(start + 8, start))
            val selected = port.snapshotForRead()
            val bold = port.command(EditorCommand.Bold)
            val expectedBold = input.replace("Target🙂", "**Target🙂**")
            val undone = port.command(EditorCommand.Undo)
            val redone = port.command(EditorCommand.Redo)
            val read = port.snapshotForRead()
            port.selectNote(NoteDocument("probe-other", "other note\n"))
            val reopened = port.selectNote(NoteDocument(noteId, "must not overwrite existing state"))
            val afterReopenUndo = port.command(EditorCommand.Undo)
            val selectedAgain = port.command(EditorCommand.Select(input.length, input.length))
            val inserted = port.command(EditorCommand.Insert("追加🙂\n"))
            val appendUndo = port.command(EditorCommand.Undo)
            results.put(JSONObject().put("id", id).put("original", original).put("input", input)
                .put("loaded", loaded.json()).put("selected", selected.json()).put("bold", bold.json())
                .put("undone", undone.json()).put("redone", redone.json()).put("read", read.json())
                .put("reopened", reopened.json()).put("afterReopenUndo", afterReopenUndo.json())
                .put("inserted", inserted.json()).put("appendUndo", appendUndo.json())
                .put("exactLoad", loaded.text == input).put("directionalSelection", selected.selection == Utf16Selection(start + 8, start))
                .put("exactBold", bold.text == expectedBold).put("exactUndo", undone.text == input)
                .put("exactRedo", redone.text == expectedBold).put("readSourceMatches", read.text == expectedBold)
                .put("reopenRetainsSource", reopened.text == expectedBold).put("reopenRetainsSelection", reopened.selection == read.selection)
                .put("reopenRetainsUndo", afterReopenUndo.text == input)
                .put("exactAppend", inserted.text == input + "追加🙂\n").put("exactAppendUndo", appendUndo.text == input))
        } catch (error: Exception) {
            results.put(JSONObject().put("id", id).put("input", input).put("error", error.javaClass.name).put("message", error.message))
        }
    }
    return JSONObject().put("engine", BuildConfig.EDITOR_KIND)
        .put("method", "Same live editor port: select source; reversed UTF16 selection including emoji; Bold; Undo; Redo; read snapshot; switch note/reopen; Undo; append; Undo")
        .put("lineEndingPolicy", "CRLF and CR normalize to LF at the document boundary")
        .put("scope", "Backend command observations, not IME, performance, renderer parity or permanent-save acceptance")
        .put("cases", results).toString(2)
}
