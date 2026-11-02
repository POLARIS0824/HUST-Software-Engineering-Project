package main

import (
	"strings"
	"testing"
)

// 本文件覆盖 PR #1 第三轮评审中的低优先级遗留项：标题里的方括号。
//
// '[' 与 ']' 在 Windows 文件名里合法，但会破坏双链语法。标题「笔记[草稿]」
// 级联改写其它笔记时生成 [[笔记[草稿]]]——解析器按嵌套括号处理，括号不配对，
// 这条双链既不解析也不显示，读者看不出它指向哪里。

// TestL4_BracketsInTitleAreSanitized 标题里的方括号必须被清洗，
// 否则级联生成的双链不可解析。
func TestL4_BracketsInTitleAreSanitized(t *testing.T) {
	got, changed := sanitizeTitle("笔记[草稿]")
	if !changed {
		t.Fatal("方括号应被清洗")
	}
	if strings.ContainsAny(got, "[]") {
		t.Fatalf("清洗后不应残留方括号，得到 %q", got)
	}
}

// TestL4_BracketTitleProducesResolvableLink 标题含方括号时，
// 级联改写生成的双链必须仍能解析。
func TestL4_BracketTitleProducesResolvableLink(t *testing.T) {
	v := newTestVault(t)
	// 用 sanitizeTitle 模拟用户输入含方括号的标题
	title, _ := sanitizeTitle("笔记[草稿]")
	target := mustCreate(t, v, title)
	src := mustCreate(t, v, "来源")
	mustSave(t, v, src.ID, "参见 [["+title+"]]。")

	if _, report, err := v.RenameNoteReported(target.ID, "改过的标题"); err != nil {
		t.Fatalf("改名失败: %v", err)
	} else if len(report.Affected) != 1 {
		t.Fatalf("应级联改写 1 篇，得到 %+v", report)
	}

	// 生成的双链必须能被解析
	links := v.GetNoteLinks(src.ID)
	if len(links) != 1 {
		t.Fatalf("应解析出 1 条双链，得到 %d 条", len(links))
	}
	if links[0].State != string(linkResolved) {
		t.Fatalf("级联生成的双链应可解析，得到 %+v", links[0])
	}
}
