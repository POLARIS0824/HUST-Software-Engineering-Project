package main

import (
	"strings"
	"testing"
)

// 本文件覆盖 PR #1 第三轮评审中的低优先级遗留项：双链解析保留符。
//
// 旧实现对整个 [[...]] 内联做 "#"/"^" 保留符判断，于是别名里带 "#" 的
// 双链被整条丢弃：[[目标|C# 入门]] 里的 "C# 入门" 是别名，与锚点语法
// 无关，却让这条双链既不解析也不显示，读者看不出它指向哪里。

// TestL3_AliasWithHashIsNotDropped [[目标|C# 入门]] 这类别名含 "#" 的
// 双链必须正常解析，不能被整条丢弃。
func TestL3_AliasWithHashIsNotDropped(t *testing.T) {
	v := newTestVault(t)
	target := mustCreate(t, v, "目标")
	src := mustCreate(t, v, "来源")
	mustSave(t, v, src.ID, "参见 [[目标|C# 入门]]。")

	links := v.GetNoteLinks(src.ID)
	if len(links) != 1 {
		t.Fatalf("应解析出 1 条双链，得到 %d 条", len(links))
	}
	if links[0].Target != "目标" {
		t.Fatalf("目标应为「目标」，得到 %q", links[0].Target)
	}
	if links[0].Alias != "C# 入门" {
		t.Fatalf("别名应为「C# 入门」，得到 %q", links[0].Alias)
	}
	if links[0].State != string(linkResolved) || links[0].ResolvedNoteID != target.ID {
		t.Fatalf("双链应解析到目标，得到 %+v", links[0])
	}
}

// TestL3_AnchorAndFootnoteStillReserved 保留符只作用于目标名：
// [[#小节]] 与 [[^脚注]] 仍不是笔记双链。
func TestL3_AnchorAndFootnoteStillReserved(t *testing.T) {
	links := extractLinks("参见 [[#小节]] 与 [[^脚注]] 与 [[正常]]。")
	if len(links) != 1 {
		t.Fatalf("锚点与块引用不应被当作双链，得到 %d 条: %+v", len(links), links)
	}
	if links[0].Target != "正常" {
		t.Fatalf("应只保留「正常」，得到 %q", links[0].Target)
	}
}

// TestL3_AliasWithHashRoundTripsThroughCascade 别名含 # 的双链在级联
// 改写后仍应保持别名。
func TestL3_AliasWithHashRoundTripsThroughCascade(t *testing.T) {
	v := newTestVault(t)
	target := mustCreate(t, v, "旧标题")
	src := mustCreate(t, v, "来源")
	mustSave(t, v, src.ID, "参见 [[旧标题|C# 入门]]。")

	if _, _, err := v.RenameNoteReported(target.ID, "新标题"); err != nil {
		t.Fatalf("改名失败: %v", err)
	}

	body := v.readBody(v.notes[src.ID].meta)
	if !strings.Contains(body, "[[新标题|C# 入门]]") {
		t.Fatalf("级联后应保留别名，得到 %q", body)
	}
	links := v.GetNoteLinks(src.ID)
	if len(links) != 1 || links[0].Alias != "C# 入门" {
		t.Fatalf("级联后别名应保持，得到 %+v", links)
	}
}
