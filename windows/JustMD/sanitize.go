package main

import (
	"strings"
	"unicode/utf8"
)

// maxTitleBytes 是笔记标题（文件名去扩展名）允许的最大 UTF-8 字节数。
// 需求基线 4.2：文件名总长 1~255 字节，标题上限取 250，为冲突后缀 ".md" 留出余量。
const maxTitleBytes = 250

// windowsReserved 基线 4.2 的 Windows 保留文件名（不含扩展名部分）。
var windowsReserved = map[string]bool{
	"CON": true, "PRN": true, "AUX": true, "NUL": true,
	"COM1": true, "COM2": true, "COM3": true, "COM4": true, "COM5": true,
	"COM6": true, "COM7": true, "COM8": true, "COM9": true,
	"LPT1": true, "LPT2": true, "LPT3": true, "LPT4": true, "LPT5": true,
	"LPT6": true, "LPT7": true, "LPT8": true, "LPT9": true,
}

// sanitizeTitle 把用户输入的标题清洗为合法的文件名主体（BR-09）：
// 非法字符映射为下划线，剥离首尾空格与句点，拦截 Windows 保留名，
// 超限时按 rune 截断到 maxTitleBytes 字节。返回清洗后的标题与是否发生了修改。
//
// 方括号也一并映射（评审遗留项）：'[' 与 ']' 在 Windows 文件名里合法，
// 但会破坏双链语法。标题「笔记[草稿]」级联改写其它笔记时生成
// [[笔记[草稿]]]——解析器按嵌套括号处理，括号不配对，这条双链
// 既不解析也不显示，读者看不出它指向哪里。把方括号换成下划线，
// 标题与双链就都安全了。
func sanitizeTitle(raw string) (string, bool) {
	changed := false

	var b strings.Builder
	for _, r := range raw {
		switch {
		case r < 0x20 || r == 0x7f || strings.ContainsRune(`\/:*?"<>|[]`, r):
			b.WriteByte('_')
			changed = true
		default:
			b.WriteRune(r)
		}
	}
	title := b.String()

	trimmed := strings.Trim(title, " .")
	if trimmed != title {
		changed = true
		title = trimmed
	}

	if base := title; windowsReserved[strings.ToUpper(base)] {
		title = base + "_"
		changed = true
	}

	if len(title) > maxTitleBytes {
		title = truncateRunes(title, maxTitleBytes)
		changed = true
	}

	return title, changed
}

// truncateRunes 按 UTF-8 字节数上限截断字符串，不切断多字节字符。
func truncateRunes(s string, maxBytes int) string {
	if len(s) <= maxBytes {
		return s
	}
	cut := 0
	total := 0
	for i, r := range s {
		size := utf8.RuneLen(r)
		if total+size > maxBytes {
			break
		}
		total += size
		cut = i + size
	}
	return s[:cut]
}
