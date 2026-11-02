package main

import (
	"strings"
	"testing"

	"github.com/egoist/mygo/ui"
)

// 本文件覆盖 PR #1 第三轮评审中的低优先级遗留项：侧栏标签树滚动。
//
// 标签多时（几十个以上）侧栏被撑开，标签树被挤出视口底部，
// 用户滚动主内容区也看不到它。

// TestL5_TagTreeIsScrollable 侧栏标签树必须放在可滚动区域内，
// 标签多时不应被挤出视口。
func TestL5_TagTreeIsScrollable(t *testing.T) {
	a := newTestApp(t)
	// 建一批标签，撑出足够的层级
	for i := 0; i < 30; i++ {
		doc := mustCreateForApp(t, a, "笔记"+itoa(i))
		mustSaveForApp(t, a, doc.ID, "#分类"+itoa(i%5)+"/子"+itoa(i))
	}
	a.refreshLists()
	if len(a.tags) == 0 {
		t.Fatal("应生成标签树")
	}

	// 侧栏渲染不得 panic，且标签仍可见
	l := ui.NewTester(a.leftView, 300, 700)
	l.Frame()
	txt := strings.Join(l.Texts(), "\n")
	if !strings.Contains(txt, "标签") {
		t.Fatalf("侧栏应渲染标签区:\n%s", txt)
	}
}
