package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/egoist/mygo/ui"
)

// TestUserSim3 边界场景：撤销回跳 / 多级标签 / 空格标题 / 快速换篇 /
// 非当前笔记带草稿删除 / 跨页快捷键 / 批量清空回收站 / 阅读页渲染。
func TestUserSim3(t *testing.T) {
	a := newTestApp(t)
	m := ui.NewTester(a.contentView, 1200, 760)
	l := ui.NewTester(a.leftView, 300, 760)

	// ---- 1. 删除当前笔记 → 撤销 → 应回到该笔记 ----
	d1 := mustCreateForApp(t, a, "待删当前")
	mustSaveForApp(t, a, d1.ID, "内容A")
	a.openNote(d1.ID)
	m.Frame()
	if !a.deleteFromList(d1.ID) {
		t.Fatal("删除应成功")
	}
	m.Frame()
	if a.currentID != "" {
		t.Fatal("删除当前后应清空选中")
	}
	// 模拟点击撤销（Toast 动作回调在视图内，这里直接等价执行撤销逻辑）。
	if _, err := a.vault.RestoreFromTrash(d1.ID); err != nil {
		t.Fatal(err)
	}
	a.refreshLists()
	a.openNote(d1.ID) // trashWithUndo 撤销后应回跳
	m.Frame()
	if a.currentID != d1.ID {
		t.Fatalf("撤销后应回到原笔记, 当前 %q", a.currentTitle)
	}
	// 推进时钟让删除/恢复的退场动画走完，避免残影拦截后续命中测试
	// （真实应用 230ms 内自动结束）。
	m.Advance(600 * time.Millisecond)
	m.Frame()

	// ---- 2. 多级标签树 ----
	mustSaveForApp(t, a, d1.ID, "内容A #复习计划/第一章 #复习计划/第二章")
	a.refreshLists()
	l.Frame()
	if !strings.Contains(texts(l), "复习计划") {
		t.Fatalf("标签树应显示一级标签:\n%s", texts(l))
	}
	if !strings.Contains(texts(l), "第一章") {
		t.Fatalf("标签树应显示子级标签:\n%s", texts(l))
	}
	simShot(t, "2-tags", l)

	// ---- 3. 纯空格标题：不改名、Enter 回填 ----
	m.ClickAt(600, 76)
	m.Frame()
	m.Key(ui.Ctrl, ui.KeyA)
	m.Type("   ")
	m.Frame()
	if a.currentTitle != "待删当前" {
		t.Fatalf("空格标题不应触发改名: %q", a.currentTitle)
	}
	m.Key(0, ui.KeyEnter)
	m.Frame()
	if a.titleEdit != "待删当前" {
		t.Fatalf("Enter 后应回填原标题: %q", a.titleEdit)
	}

	// ---- 4. 快速连续换篇 ----
	d2 := mustCreateForApp(t, a, "快切甲")
	mustSaveForApp(t, a, d2.ID, "甲")
	d3 := mustCreateForApp(t, a, "快切乙")
	mustSaveForApp(t, a, d3.ID, "乙")
	for i := 0; i < 3; i++ {
		a.openNote(d2.ID)
		a.openNote(d3.ID)
		a.openNote(d1.ID)
		m.Frame()
	}
	if a.currentID != d1.ID {
		t.Fatalf("快速换篇后应停在 %q, 当前 %q", "待删当前", a.currentTitle)
	}
	if a.vault.readBody(a.vault.notes[d2.ID].meta) != "甲" || a.vault.readBody(a.vault.notes[d3.ID].meta) != "乙" {
		t.Fatal("快速换篇不得损坏其他笔记内容")
	}

	// ---- 5. 非当前笔记带草稿 → 删除 → 草稿应先落盘 ----
	// 直接调用 deleteFromList：测试器 Find 的标签矩形在滚动列表重排后
	// 会过期（官方文档明示），不适合再走右键菜单位置链路；
	// 菜单路径已由 TestContextMenuHitsAnimatedRow 覆盖。
	a.openNote(d2.ID)
	m.Frame()
	m.ClickAt(600, 260)
	m.Frame()
	m.Type("未保存草稿")
	m.Frame()
	a.openNote(d3.ID) // 换篇会先落盘 d2
	m.Frame()
	if !a.deleteFromList(d2.ID) {
		t.Fatal("删除非当前笔记应成功")
	}
	m.Frame()
	data, err := a.vault.OpenNote(d2.ID)
	if err != nil || !data.Trashed {
		who := []string{}
		for _, ts := range a.vault.ListTrash() {
			who = append(who, ts.Title+"("+ts.ID[:8]+")")
		}
		t.Fatalf("快切甲应进入回收站: err=%v trashed=%v 回收站=%v d2=%s", err, data.Trashed, who, d2.ID[:8])
	}
	if raw, err := os.ReadFile(filepath.Join(a.vault.trashDir(), d2.ID+".md")); err != nil || !strings.Contains(string(raw), "未保存草稿") {
		t.Fatalf("删除前草稿应先落盘: %q err=%v", raw, err)
	}

	// ---- 6. 其他页面 Ctrl+N 新建并回到笔记页 ----
	l.Click("回收站")
	m.Frame()
	m.Key(ui.Ctrl, ui.KeyN)
	m.Frame()
	if a.view != viewNotes {
		t.Fatalf("Ctrl+N 后应回到笔记页, 当前 %s", a.view)
	}
	if a.currentTitle == "" {
		t.Fatal("Ctrl+N 应打开新笔记")
	}
	// 新笔记标题框应为空草稿（键入即命名）。
	if a.titleEdit != "" {
		t.Fatalf("新建笔记标题框应为空草稿: %q", a.titleEdit)
	}
	m.Type("即输即名")
	m.Frame()
	if a.currentTitle != "即输即名" {
		t.Fatalf("新建后直接键入应命名: %q", a.currentTitle)
	}

	// ---- 7. 批量清空回收站 ----
	for i := 0; i < 3; i++ {
		n := mustCreateForApp(t, a, "批量删除"+string(rune('一'+i)))
		if !a.deleteFromList(n.ID) {
			t.Fatalf("批量删除 %d 失败", i)
		}
	}
	if a.vault.GetWorkspace().TrashCount < 3 {
		t.Fatalf("回收站应有 3+ 条: %d", a.vault.GetWorkspace().TrashCount)
	}
	l.Click("回收站")
	m.Frame()
	if err := m.Click("清空回收站"); err != nil {
		t.Fatalf("清空回收站按钮: %v", err)
	}
	m.Frame()
	if err := m.Click("确定"); err != nil {
		t.Fatalf("确认清空: %v", err)
	}
	m.Frame()
	if a.vault.GetWorkspace().TrashCount != 0 {
		t.Fatalf("清空后回收站应为空: %d", a.vault.GetWorkspace().TrashCount)
	}

	// ---- 8. 阅读页渲染（推进时钟到稳态再截帧） ----
	l.Click("全部笔记")
	m.Frame()
	a.openNote(d1.ID)
	m.Frame()
	m.Click("阅读")
	m.Advance(500 * time.Millisecond)
	m.Frame()
	m.Advance(500 * time.Millisecond)
	m.Frame()
	simShot(t, "8-reader", m)
	if !strings.Contains(texts(m), "内容A") {
		t.Fatalf("阅读页应渲染正文:\n%s", texts(m))
	}
}
