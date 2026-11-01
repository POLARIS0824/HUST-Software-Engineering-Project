package main

import (
	"strings"
	"testing"
)

func newIdx(id, title, rel, body string) *indexedNote {
	meta := &NoteMeta{NoteID: id, RelativePath: rel, State: stateActive}
	return &indexedNote{
		meta:          meta,
		title:         title,
		titleLower:    foldString(title),
		fileName:      rel,
		fileNameLower: foldString(rel),
		body:          body,
		bodyFold:      foldString(body),
	}
}

// ---- AC-SEARCH-02：中文连续文本匹配 ---------------------------------------------

func TestSearchChineseContinuousMatch(t *testing.T) {
	notes := map[string]*indexedNote{
		"a": newIdx("a", "网络协议笔记", "网络协议笔记.md",
			"前文……TCP 断开连接的 TIME_WAIT 状态将持续 2MSL 时间。后文……"),
		"b": newIdx("b", "计算机学院", "计算机学院.md", "计算机科学与技术"),
	}
	hits := search(notes, "TIME_WAIT")
	if len(hits) != 1 || hits[0].NoteID != "a" {
		t.Fatalf("应命中正文包含 TIME_WAIT 的笔记: %+v", hits)
	}
	if hits[0].MatchedField != "body" {
		t.Errorf("标题不含关键词时应为 body 命中: %s", hits[0].MatchedField)
	}

	hits = search(notes, "软件学院")
	if len(hits) != 0 {
		t.Errorf("「软件学院」不应命中任何一篇: %+v", hits)
	}
	hits = search(notes, "计算机")
	if len(hits) != 1 || hits[0].NoteID != "b" {
		t.Fatalf("「计算机」应命中 b: %+v", hits)
	}
}

// ---- AC-SEARCH-02：多关键词 AND -------------------------------------------------

func TestSearchMultiTermAND(t *testing.T) {
	notes := map[string]*indexedNote{
		"a": newIdx("a", "A", "A.md", "华中科技大学软件学院"),
		"b": newIdx("b", "B", "B.md", "计算机科学与技术"),
	}
	hits := search(notes, "科技 计算机") // 同一文件内同时包含
	if len(hits) != 0 {
		t.Errorf("分属两篇时 AND 查询应为空: %+v", hits)
	}
	hits = search(notes, "华中 科技")
	if len(hits) != 1 || hits[0].NoteID != "a" {
		t.Fatalf("同文件内 AND 应命中 a: %+v", hits)
	}
}

// ---- FR-SEARCH-03：英文大小写不敏感子串 -----------------------------------------

func TestSearchEnglishCaseInsensitive(t *testing.T) {
	notes := map[string]*indexedNote{
		"a": newIdx("a", "Zero-Copy 笔记", "zero-copy.md", "使用 sendfile 实现 ZERO COPY"),
	}
	hits := search(notes, "zero")
	if len(hits) != 1 {
		t.Fatalf("前缀子串应命中: %+v", hits)
	}
	hits = search(notes, "SENDfile")
	if len(hits) != 1 {
		t.Fatalf("大小写不敏感应命中: %+v", hits)
	}
}

// ---- FR-SEARCH-02：标题/文件名参与检索并优先 -------------------------------------

func TestSearchTitlePriority(t *testing.T) {
	notes := map[string]*indexedNote{
		"t": newIdx("t", "TCP 三次握手", "TCP 三次握手.md", "三次握手的过程……"),
		"b": newIdx("b", "网络基础", "网络基础.md", "提到 tcp 协议"),
	}
	hits := search(notes, "tcp")
	if len(hits) != 2 {
		t.Fatalf("两篇都应命中: %+v", hits)
	}
	if hits[0].NoteID != "t" || hits[0].MatchedField != "title" {
		t.Fatalf("标题命中应排在首位: %+v", hits)
	}
}

func TestSearchFileNameOnlyHit(t *testing.T) {
	notes := map[string]*indexedNote{
		"f": newIdx("f", "读书摘录", "zerocopy.md", "与文件名无关的正文"),
	}
	hits := search(notes, "zerocopy")
	if len(hits) != 1 || hits[0].MatchedField != "fileName" {
		t.Fatalf("仅文件名命中应为 fileName 字段: %+v", hits)
	}
	if hits[0].Score < 5 {
		t.Fatalf("文件名命中得分应高于纯正文: %v", hits[0].Score)
	}
	// 标题与文件名同时命中时标题优先展示。
	notes["t"] = newIdx("t", "zerocopy 指南", "指南.md", "无关")
	hits = search(notes, "zerocopy")
	if hits[0].NoteID != "t" || hits[0].MatchedField != "title" {
		t.Fatalf("标题命中应优先于文件名: %+v", hits)
	}
}

func TestFirstBodySnippetMissReturnsEmpty(t *testing.T) {
	n := newIdx("a", "标题", "标题.md", "正文内容")
	snippet, ranges := firstBodySnippet(n, "不存在的词")
	if snippet != "" || ranges != nil {
		t.Fatalf("未命中应返回空: %q %v", snippet, ranges)
	}
}

// ---- FR-SEARCH-05：摘要与高亮区间 ------------------------------------------------

func TestSearchSnippetWithContextAndRanges(t *testing.T) {
	pad := strings.Repeat("前", 40)
	body := pad + "这里提到 TIME_WAIT 状态持续两分钟。" + strings.Repeat("后", 40)
	notes := map[string]*indexedNote{
		"a": newIdx("a", "笔记", "笔记.md", body),
	}
	hits := search(notes, "TIME_WAIT")
	if len(hits) != 1 {
		t.Fatal("应命中")
	}
	hit := hits[0]
	if hit.Snippet == "" || len(hit.Ranges) != 1 {
		t.Fatalf("应返回摘要与高亮区间: %+v", hit)
	}
	chars := []rune(hit.Snippet)
	r := hit.Ranges[0]
	extracted := string(chars[r[0]:r[1]])
	if extracted != "TIME_WAIT" {
		t.Fatalf("高亮区间应恰好覆盖关键词，得到 %q", extracted)
	}
	// 命中词前后各约 30 字的上下文。
	if r[0] < 25 || len(chars)-r[1] < 25 {
		t.Fatalf("上下文截取范围异常: %d / %d", r[0], len(chars)-r[1])
	}
}

func TestSearchSnippetCJKOffsets(t *testing.T) {
	body := strings.Repeat("汉", 35) + "关键词出现" + strings.Repeat("字", 35)
	notes := map[string]*indexedNote{
		"a": newIdx("a", "T", "T.md", body),
	}
	hits := search(notes, "关键词")
	hit := hits[0]
	chars := []rune(hit.Snippet)
	r := hit.Ranges[0]
	if string(chars[r[0]:r[1]]) != "关键词" {
		t.Fatalf("中文命中区间错位: %q", string(chars[r[0]:r[1]]))
	}
}

// ---- 边界 ---------------------------------------------------------------------

func TestSearchEmptyQuery(t *testing.T) {
	notes := map[string]*indexedNote{"a": newIdx("a", "A", "A.md", "x")}
	if hits := search(notes, "   "); len(hits) != 0 {
		t.Fatalf("空查询应返回空: %+v", hits)
	}
}

func TestSearchSnippetAroundNoTerm(t *testing.T) {
	n := newIdx("a", "标题", "标题.md", "只有一句话")
	snippet, ranges := snippetAround(n, "", 0, 0)
	if ranges != nil {
		t.Fatalf("无词高亮应为空: %v", ranges)
	}
	if snippet != "只有一句话" {
		t.Fatalf("摘要错误: %q", snippet)
	}
}
