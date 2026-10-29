package main

import (
	"regexp"
	"strings"
	"unicode"
	"unicode/utf8"
)

// ExtractedTag 是从正文提取的一个标签（FR-TAG-01）。
type ExtractedTag struct {
	Name      string `json:"name"`      // 首次出现的原始大小写形式
	NameLower string `json:"nameLower"` // 大小写折叠形式
	Level     int    `json:"level"`     // 层级深度，#a/b/c 为 3
}

// ExtractedLink 是正文中的一个 WikiLink（FR-LINK-01）。
type ExtractedLink struct {
	Target      string `json:"target"`      // [[...]] 内的目标笔记名（不含别名）
	TargetLower string `json:"targetLower"` // 大小写折叠目标
	Alias       string `json:"alias"`       // [[目标|别名]] 的别名，可为空
	Start       int    `json:"start"`       // 起始 rune 下标（含）
	End         int    `json:"end"`         // 结束 rune 下标（不含）
	Line        int    `json:"line"`        // 1 起始行号
}

// TextStats 是笔记的实时统计信息（FR-NOTE-08）。
type TextStats struct {
	CJKCharCount      int `json:"cjkCharCount"`
	EnWordCount       int `json:"enWordCount"`
	CharCount         int `json:"charCount"`
	ReadingTimeMinute int `json:"readingTimeMinute"` // 按 300 字/分钟估算
}

// tagChars 判断 rune 是否可作为标签字符（基线 4.5：汉字、字母、数字、下划线、短横线）。
func isTagRune(r rune) bool {
	switch {
	case unicode.Is(unicode.Han, r):
		return true
	case unicode.IsLetter(r) && r < 0x80:
		return true
	case unicode.IsDigit(r) && r < 0x80:
		return true
	case r == '_' || r == '-':
		return true
	}
	// 允许非 ASCII 的字母（如全角字母以外的拉丁扩展），与基线正则保持宽松一致：
	// 基线限定 A-Za-z0-9 与汉字，这里额外拒绝明显不属于标签的空白与符号。
	return false
}

// extractTags 从 Markdown 正文提取正文标签（FR-TAG-01/02）：
// 排除围栏代码块、行内代码与 Markdown 标题（"# 后跟空格"），
// 标签前必须是行首、空白或标点；执行大小写折叠去重，展示保留首创大小写。
func extractTags(markdown string) []ExtractedTag {
	var tags []ExtractedTag
	seen := map[string]int{} // nameLower -> tags 下标

	forEachProseSegment(markdown, func(seg string, segStartRune int) {
		runes := []rune(seg)
		for i := 0; i < len(runes); i++ {
			if runes[i] != '#' {
				continue
			}
			// "# " 开头是 ATX 标题，不是标签；行内 "#"+空格 同理。
			if i+1 >= len(runes) || !isTagRune(runes[i+1]) {
				continue
			}
			// 标签前必须是行首、空白或常见标点。
			if i > 0 {
				prev := runes[i-1]
				if isTagRune(prev) || prev == '#' || prev == '[' || prev == ']' || prev == '(' || prev == ')' {
					continue
				}
			}
			// 向右收集完整的（可能多级）标签。
			j := i + 1
			level := 1
			for j < len(runes) && (isTagRune(runes[j]) || runes[j] == '/') {
				if runes[j] == '/' {
					// 连续斜杠或以斜杠结尾都终止。
					if j+1 >= len(runes) || !isTagRune(runes[j+1]) {
						break
					}
					level++
				}
				j++
			}
			name := string(runes[i+1 : j])
			// 去掉结尾悬挂的 '/'（已在循环中保证不会收集，防御再裁一次）。
			name = strings.Trim(name, "/")
			if name == "" {
				continue
			}
			lower := foldString(name)
			if idx, ok := seen[lower]; ok {
				tags[idx].Level = max(tags[idx].Level, level)
			} else {
				seen[lower] = len(tags)
				tags = append(tags, ExtractedTag{Name: name, NameLower: lower, Level: level})
			}
			i = j - 1
		}
	})
	return tags
}

// extractLinks 从正文提取全部 WikiLink（FR-LINK-01，基线 4.6）。
// 代码块与行内代码中的 [[...]] 仍是普通文本？基线未排除代码区双链，
// 但按 BR-06（未知语法原样保留）与常见工具行为，代码区内不解析为链接。
func extractLinks(markdown string) []ExtractedLink {
	var links []ExtractedLink

	forEachProseSegment(markdown, func(seg string, segStartRune int) {
		runes := []rune(seg)
		for i := 0; i < len(runes); i++ {
			if runes[i] != '[' || i+1 >= len(runes) || runes[i+1] != '[' {
				continue
			}
			// 找配对的 "]]"，中间不允许换行与嵌套中括号。
			j := i + 2
			depth := 0
			closed := -1
			for ; j < len(runes); j++ {
				r := runes[j]
				if r == '\n' {
					break
				}
				if r == '[' {
					depth++
					continue
				}
				if r == ']' {
					if depth > 0 {
						depth--
						continue
					}
					if j+1 < len(runes) && runes[j+1] == ']' {
						closed = j
						j++
						break
					}
					// 单个 ']' 视为普通字符？基线禁止嵌套，遇到单 ']' 直接终止。
					break
				}
			}
			if closed < 0 {
				continue
			}
			inner := string(runes[i+2 : closed])
			if inner == "" || strings.ContainsAny(inner, "#^") {
				// 保留符：标题锚点与块引用不是笔记双链。
				i = closed + 1
				continue
			}
			target, alias := inner, ""
			if bar := strings.Index(inner, "|"); bar >= 0 {
				target, alias = inner[:bar], inner[bar+1:]
			}
			target = strings.TrimSpace(target)
			if target == "" || strings.ContainsAny(target, "[]|") {
				// 目标名本身不允许再含括号或分隔符（基线 4.6 禁止嵌套）。
				i = closed + 1
				continue
			}
			links = append(links, ExtractedLink{
				Target:      target,
				TargetLower: foldString(target),
				Alias:       strings.TrimSpace(alias),
				Start:       segStartRune + i,
				End:         segStartRune + closed + 2,
				Line:        lineOfRune(markdown, segStartRune+i),
			})
			i = closed + 1
		}
	})
	return links
}

// computeTextStats 统计汉字数、英文单词数、总字符数与预估阅读时长（FR-NOTE-08）。
func computeTextStats(markdown string) TextStats {
	stats := TextStats{CharCount: utf8.RuneCountInString(markdown)}
	inWord := false
	for _, r := range markdown {
		switch {
		case unicode.Is(unicode.Han, r):
			stats.CJKCharCount++
			inWord = false
		case unicode.IsLetter(r) && r < 0x80:
			if !inWord {
				stats.EnWordCount++
				inWord = true
			}
		default:
			inWord = false
		}
	}
	total := stats.CJKCharCount + stats.EnWordCount
	stats.ReadingTimeMinute = (total + 299) / 300
	return stats
}

// plainSummary 剥离 Markdown 标记后的前 limit rune 摘要，供列表卡片展示。
func plainSummary(markdown string, limit int) string {
	var b strings.Builder
	count := 0
	inFence := false
	for _, line := range strings.Split(markdown, "\n") {
		trimmed := strings.TrimSpace(line)
		if strings.HasPrefix(trimmed, "```") || strings.HasPrefix(trimmed, "~~~") {
			inFence = !inFence
			continue
		}
		if inFence {
			continue
		}
		if trimmed == "" || strings.HasPrefix(trimmed, "#") ||
			strings.HasPrefix(trimmed, "!") || strings.HasPrefix(trimmed, "---") {
			continue
		}
		text := stripInlineMarks(trimmed)
		for _, r := range text {
			b.WriteRune(r)
			count++
			if count >= limit {
				return b.String()
			}
		}
		b.WriteRune(' ')
		count++
		if count >= limit {
			break
		}
	}
	return strings.TrimSpace(b.String())
}

// linkRe 匹配 [文本](URL) 形式的链接。
var linkRe = regexp.MustCompile(`\[([^\]]*)\]\([^)]*\)`)

// stripInlineMarks 去掉行内 Markdown 强调标记、行内代码反引号与链接语法。
func stripInlineMarks(s string) string {
	out := linkRe.ReplaceAllString(s, "$1")
	repl := strings.NewReplacer(
		"**", "", "__", "", "*", "", "_", "", "~~", "", "`", "",
		"[", "", "]", "",
	)
	return repl.Replace(out)
}

// forEachProseSegment 把正文按"普通段落"切片回调，跳过围栏代码块与行内代码。
// segStartRune 是该段在全文中的起始 rune 下标。
func forEachProseSegment(markdown string, fn func(seg string, segStartRune int)) {
	lines := strings.SplitAfter(markdown, "\n")
	inFence := false
	fenceMark := ""
	runeBase := 0

	for _, line := range lines {
		trimmed := strings.TrimSpace(line)
		if !inFence {
			if strings.HasPrefix(trimmed, "```") || strings.HasPrefix(trimmed, "~~~") {
				inFence = true
				fenceMark = trimmed[:3]
				runeBase += utf8.RuneCountInString(line)
				continue
			}
		} else {
			if fenceMark != "" && strings.HasPrefix(trimmed, fenceMark) {
				inFence = false
			}
			runeBase += utf8.RuneCountInString(line)
			continue
		}
		// 行内代码按 span 切开，span 之间的普通文本回调。
		forEachOutsideInlineCode(line, runeBase, fn)
		runeBase += utf8.RuneCountInString(line)
	}
}

// forEachOutsideInlineCode 对一行中位于反引号 span 之外的部分回调。
func forEachOutsideInlineCode(line string, runeBase int, fn func(seg string, segStartRune int)) {
	runes := []rune(line)
	segStart := 0
	inCode := false
	for i := 0; i < len(runes); i++ {
		if runes[i] == '`' {
			if !inCode {
				if i > segStart {
					fn(string(runes[segStart:i]), runeBase+segStart)
				}
				inCode = true
			} else {
				inCode = false
				segStart = i + 1
			}
		}
	}
	if !inCode && len(runes) > segStart {
		fn(string(runes[segStart:]), runeBase+segStart)
	}
}

// lineOfRune 计算全文第 runeIdx 个 rune 所在的 1 起始行号。
func lineOfRune(s string, runeIdx int) int {
	line := 1
	count := 0
	for _, r := range s {
		if count >= runeIdx {
			break
		}
		if r == '\n' {
			line++
		}
		count++
	}
	return line
}

// foldString 大小写折叠（基线 4.5/4.6 的大小写不敏感匹配）。strings.Map 保持 rune 一一对应，
// 因此折叠串与原串的 rune 下标一致，可用于定位回原文。
func foldString(s string) string {
	return strings.Map(unicode.ToLower, s)
}
