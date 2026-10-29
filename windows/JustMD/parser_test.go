package main

import (
	"reflect"
	"strings"
	"testing"
)

// ---- 标签提取（FR-TAG-01/02、AC-TAG-01/02）----------------------------------

func TestExtractTagsBasics(t *testing.T) {
	md := "#操作系统 #Android\n这里是测试内容，另外提一下 #android 架构。"
	tags := extractTags(md)
	lowers := map[string]string{}
	for _, tag := range tags {
		lowers[tag.NameLower] = tag.Name
	}
	if _, ok := lowers["操作系统"]; !ok {
		t.Fatalf("应提取 #操作系统，得到 %v", tags)
	}
	// 大小写折叠：#Android 与 #android 归并，展示保留首创形式。
	if name, ok := lowers["android"]; !ok || name != "Android" {
		t.Fatalf("#Android/#android 应折叠为一个标签且保留首创大小写，得到 %v", lowers)
	}
	if len(tags) != 2 {
		t.Fatalf("应恰好 2 个标签，得到 %v", tags)
	}
}

func TestExtractTagsExcludesHeadingsAndCode(t *testing.T) {
	md := "# 一级标题不是标签\n\n在 C++ 中使用 `#include <iostream>` 注释\n\n```go\n#注释行也不是标签\n```\n\n正规标签在这里：#考研/复习计划"
	tags := extractTags(md)
	if len(tags) != 1 {
		t.Fatalf("只应提取 #考研/复习计划，得到 %v", tags)
	}
	if tags[0].Name != "考研/复习计划" {
		t.Fatalf("标签名不正确: %v", tags[0])
	}
	if tags[0].Level != 2 {
		t.Fatalf("多级标签层级应为 2，得到 %d", tags[0].Level)
	}
}

func TestExtractTagsBoundaries(t *testing.T) {
	cases := map[string]bool{
		"文字 #tag1 文字":     true,
		"#tag2 开头":        true,
		"（#tag3）全角括号":     true,
		"字母#tag4 紧贴":      false, // 前面是字母，非法
		"# tag5 空格是标题":    false,
		"a#tag6":          false,
		"##tag7 深层标题":     false, // 前一字符是 #，视为标题
		"结尾标签 #tag8":      true,
		"斜杠后数字 #tag/9 结尾": true, // 基线正则允许斜杠后接数字
	}
	for input, want := range cases {
		tags := extractTags(input)
		if (len(tags) > 0) != want {
			t.Errorf("extractTags(%q) 提取数 %d，期望存在=%v", input, len(tags), want)
		}
	}
}

func TestExtractTagsMultiLevel(t *testing.T) {
	tags := extractTags("内容 #课业/软工/复习 结束")
	if len(tags) != 1 || tags[0].Name != "课业/软工/复习" || tags[0].Level != 3 {
		t.Fatalf("三级标签解析错误: %v", tags)
	}
}

// ---- 双链提取（FR-LINK-01、基线 4.6）-------------------------------------------

func TestExtractLinksBasics(t *testing.T) {
	md := "关于详情请参考 [[B]]。\n\n别名形态 [[TCP 拥塞|拥塞控制]] 在第二行。"
	links := extractLinks(md)
	if len(links) != 2 {
		t.Fatalf("应提取 2 个双链，得到 %v", links)
	}
	if links[0].Target != "B" || links[0].Alias != "" {
		t.Errorf("第一个双链解析错误: %+v", links[0])
	}
	if links[1].Target != "TCP 拥塞" || links[1].Alias != "拥塞控制" {
		t.Errorf("别名双链解析错误: %+v", links[1])
	}
	if links[1].Line != 3 {
		t.Errorf("第二个双链行号应为 3，得到 %d", links[1].Line)
	}
	if links[0].TargetLower != "b" {
		t.Errorf("目标应大小写折叠: %+v", links[0])
	}
}

func TestExtractLinksExclusions(t *testing.T) {
	md := "`[[行内代码]]` 与代码 span 后的 [[真链接]]。\n\n嵌套 [[a[b]]] 与保留符 [[锚#点]]。\n\n未闭合 [[ dangling\n\n合法 [[ok]]"
	links := extractLinks(md)
	if len(links) != 2 {
		t.Fatalf("应只提取行外双链，得到 %v", links)
	}
	if links[0].Target != "真链接" || links[1].Target != "ok" {
		t.Fatalf("提取目标错误: %+v", links)
	}
}

func TestExtractLinksWithinFencedCodeIgnored(t *testing.T) {
	md := "正文 [[真链接]]\n\n```\n[[代码块里不算]]\n```\n\n再来一个 [[第二个]]"
	links := extractLinks(md)
	if len(links) != 2 {
		t.Fatalf("代码块中的双链应被忽略，得到 %v", links)
	}
}

// ---- 统计与摘要（FR-NOTE-08）---------------------------------------------------

func TestComputeTextStats(t *testing.T) {
	stats := computeTextStats("软件工程是指导软件开发的学科。 Software engineering rocks!")
	if stats.CJKCharCount != 14 {
		t.Errorf("汉字数应为 14，得到 %d", stats.CJKCharCount)
	}
	if stats.EnWordCount != 3 {
		t.Errorf("英文词数应为 3，得到 %d", stats.EnWordCount)
	}
	if stats.ReadingTimeMinute != 1 {
		t.Errorf("阅读时长估算错误: %d", stats.ReadingTimeMinute)
	}
}

func TestPlainSummary(t *testing.T) {
	md := "# 标题\n\n正文第一段，**加粗**与`代码`。\n\n```go\n代码块不进摘要\n```\n\n第二段。"
	summary := plainSummary(md, 120)
	if strings.Contains(summary, "#") || strings.Contains(summary, "**") || strings.Contains(summary, "代码块") {
		t.Errorf("摘要不应包含标题/强调标记/代码块: %q", summary)
	}
	if !strings.Contains(summary, "正文第一段") || !strings.Contains(summary, "第二段") {
		t.Errorf("摘要应包含正文: %q", summary)
	}
}

func TestFoldStringRuneAligned(t *testing.T) {
	orig := "ABC 中文 ß"
	folded := foldString(orig)
	if len([]rune(folded)) != len([]rune(orig)) {
		t.Fatalf("折叠必须保持 rune 一一对应，才能用于定位回原文")
	}
}

// ---- 工具函数 ---------------------------------------------------------------

func TestLineOfRune(t *testing.T) {
	s := "第一行\n第二行 [[B]]\n第三行"
	links := extractLinks(s)
	if len(links) != 1 || links[0].Line != 2 {
		t.Fatalf("行号定位错误: %+v", links)
	}
}

func TestStripInlineMarks(t *testing.T) {
	out := stripInlineMarks("[链接文本](https://example.com) 与 **加粗**")
	if strings.Contains(out, "https://") || strings.Contains(out, "**") || strings.Contains(out, "[") {
		t.Errorf("未正确剥离标记: %q", out)
	}
	if !strings.Contains(out, "链接文本") {
		t.Errorf("链接文本应保留: %q", out)
	}
}

func TestExtractLinksPositionsCoverWholeLink(t *testing.T) {
	md := "前缀 [[目标|别名]] 后缀"
	links := extractLinks(md)
	if len(links) != 1 {
		t.Fatalf("应提取 1 个链接")
	}
	runes := []rune(md)
	segment := string(runes[links[0].Start:links[0].End])
	if segment != "[[目标|别名]]" {
		t.Errorf("rune 区间应恰好覆盖 [[目标|别名]]，得到 %q", segment)
	}
	if !reflect.DeepEqual(links[0].Alias, "别名") {
		t.Errorf("别名解析错误: %+v", links[0])
	}
}
