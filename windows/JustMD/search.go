package main

import (
	"sort"
	"strings"
	"unicode/utf8"
)

// indexedNote 是单篇笔记的派生索引条目（BR-02：全部可由 Markdown 重建）。
type indexedNote struct {
	meta          *NoteMeta
	title         string
	titleLower    string
	fileName      string
	fileNameLower string
	parentDir     string
	body          string // 原文，供摘要切片
	bodyFold      string // rune 一一对应的大小写折叠正文，用于连续文本匹配
	tags          []ExtractedTag
	links         []ExtractedLink
	// linkStates 与 links 等长；resolvedIDs 与 links 对齐，仅 RESOLVED 时有值。
	linkStates  []linkState
	resolvedIDs []string
	stats       TextStats
	summary     string
	fileSize    int64
}

type linkState string

const (
	linkResolved   linkState = "RESOLVED"
	linkUnresolved linkState = "UNRESOLVED"
	linkAmbiguous  linkState = "AMBIGUOUS"
)

// SearchHit 是一条检索结果（FR-SEARCH-02/05）。
type SearchHit struct {
	NoteID       string   `json:"noteId"`
	Title        string   `json:"title"`
	MatchedField string   `json:"matchedField"` // title / fileName / body
	Snippet      string   `json:"snippet"`
	Ranges       [][2]int `json:"ranges"` // snippet 内 rune 区间（高亮）
	Score        float64  `json:"score"`
	UpdatedAtUtc int64    `json:"updatedAtUtc"`
}

// searchTerms 按空白拆分查询词并折叠大小写（FR-SEARCH-01/03）。
func searchTerms(query string) []string {
	fields := strings.FieldsFunc(query, func(r rune) bool { return r == ' ' || r == '\t' || r == '\n' })
	terms := make([]string, 0, len(fields))
	for _, f := range fields {
		if f != "" {
			terms = append(terms, foldString(f))
		}
	}
	return terms
}

// search 执行全库检索：多关键词 AND，覆盖标题、文件名与正文，
// 中文按连续文本匹配、英文大小写不敏感（FR-SEARCH-01/02/03）。
// 返回按相关度降序的命中列表，附高亮摘要。
func search(notes map[string]*indexedNote, query string) []SearchHit {
	terms := searchTerms(query)
	if len(terms) == 0 {
		return nil
	}
	hits := []SearchHit{}
	for _, n := range notes {
		titleHit, fileHit, bodyTerm := false, false, ""
		allMatch := true
		for _, t := range terms {
			switch {
			case strings.Contains(n.titleLower, t):
				titleHit = true
			case strings.Contains(n.fileNameLower, t):
				fileHit = true
			case strings.Contains(n.bodyFold, t):
				if bodyTerm == "" {
					bodyTerm = t
				}
			default:
				allMatch = false
			}
		}
		if !allMatch {
			continue
		}

		score := 0.0
		matchedField := "body"
		switch {
		case titleHit:
			matchedField = "title"
			score += 10
		case fileHit:
			matchedField = "fileName"
			score += 5
		}
		if bodyTerm != "" {
			score += float64(strings.Count(n.bodyFold, bodyTerm))
		}

		snippet, ranges := firstBodySnippet(n, bodyTerm)
		if bodyTerm == "" {
			// 仅标题/文件名命中：摘要取正文开头，无体内高亮。
			snippet, ranges = snippetAround(n, "", 0, 0)
		}

		hits = append(hits, SearchHit{
			NoteID:       n.meta.NoteID,
			Title:        n.title,
			MatchedField: matchedField,
			Snippet:      snippet,
			Ranges:       ranges,
			Score:        score,
			UpdatedAtUtc: n.meta.UpdatedAtUtc,
		})
	}
	// 相关度降序，同分按更新时间新者优先。
	sort.Slice(hits, func(i, j int) bool {
		if hits[i].Score != hits[j].Score {
			return hits[i].Score > hits[j].Score
		}
		return hits[i].UpdatedAtUtc > hits[j].UpdatedAtUtc
	})
	return hits
}

// firstBodySnippet 返回首个命中词前后各 30 rune 的正文摘要与高亮区间。
func firstBodySnippet(n *indexedNote, termFolded string) (string, [][2]int) {
	if termFolded == "" {
		return "", nil
	}
	idx := strings.Index(n.bodyFold, termFolded)
	if idx < 0 {
		return "", nil
	}
	runeStart := utf8.RuneCountInString(n.bodyFold[:idx])
	matchRunes := utf8.RuneCountInString(termFolded)
	return snippetAround(n, termFolded, runeStart, matchRunes)
}

// snippetAround 以 bodyFold 的第 focusRune 个 rune 为中心取前后各 30 rune 摘要。
// 折叠串与原串 rune 一一对应，因此可直接按 rune 窗口切原文（foldString 的保证）。
func snippetAround(n *indexedNote, termFolded string, focusRune int, matchRunes ...int) (string, [][2]int) {
	const ctx = 30
	runes := []rune(n.body)
	if focusRune > len(runes) {
		focusRune = len(runes)
	}
	mLen := 0
	if termFolded != "" {
		mLen = utf8.RuneCountInString(termFolded)
		if len(matchRunes) > 0 && matchRunes[0] > 0 {
			mLen = matchRunes[0]
		}
	}
	start := focusRune - ctx
	if start < 0 {
		start = 0
	}
	end := focusRune + mLen + ctx
	if end > len(runes) {
		end = len(runes)
	}

	snippet := string(runes[start:end])
	var ranges [][2]int
	if mLen > 0 {
		ranges = append(ranges, [2]int{focusRune - start, focusRune - start + mLen})
	}
	return snippet, ranges
}
