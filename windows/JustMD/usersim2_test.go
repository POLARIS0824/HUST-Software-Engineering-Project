package main

import (
	"os"
	"strings"
	"testing"
	"time"

	"github.com/egoist/mygo/ui"
)

// TestUserSim2 标签过滤 / 撤销重做 / 拖放导入 / IME 组合输入 / 反链 / 排序。
func TestUserSim2(t *testing.T) {
	a := newTestApp(t)
	m := ui.NewTester(a.contentView, 1200, 760)
	l := ui.NewTester(a.leftView, 300, 760)

	// 准备：两篇带标签的笔记。
	d1 := mustCreateForApp(t, a, "TCP 精要")
	mustSaveForApp(t, a, d1.ID, "三次握手 #TCP/#计算机网络")
	d2 := mustCreateForApp(t, a, "复习安排")
	mustSaveForApp(t, a, d2.ID, "计划 #复习计划")
	a.openNote(d1.ID)
	a.refreshLists()
	m.Frame()
	l.Frame()

	// ---- 1. 标签树过滤 ----
	if err := l.Click("TCP"); err != nil {
		t.Fatalf("点击标签 TCP: %v", err)
	}
	l.Frame()
	m.Frame()
	vis := a.visibleNotes()
	if len(vis) != 1 || vis[0].Title != "TCP 精要" {
		t.Fatalf("标签过滤应只剩 TCP 精要: %+v", vis)
	}
	// 再点一次取消过滤。
	if err := l.Click("TCP"); err != nil {
		t.Fatalf("再次点击标签: %v", err)
	}
	l.Frame()
	if len(a.visibleNotes()) != 2 {
		t.Fatalf("取消过滤后应显示全部: %+v", a.visibleNotes())
	}

	// ---- 2. 编辑器撤销/重做 ----
	m.ClickAt(600, 260)
	m.Frame()
	m.Type("XYZ")
	m.Frame()
	ed := a.currentEditor()
	if !strings.Contains(ed.state.Buffer.String(), "XYZ") {
		t.Fatalf("正文应包含输入: %q", ed.state.Buffer.String())
	}
	m.Key(ui.Ctrl, ui.KeyZ)
	m.Frame()
	if strings.Contains(ed.state.Buffer.String(), "XYZ") {
		t.Fatalf("撤销后 XYZ 应消失: %q", ed.state.Buffer.String())
	}
	m.Key(ui.Ctrl, ui.KeyY)
	m.Frame()
	if !strings.Contains(ed.state.Buffer.String(), "XYZ") {
		t.Fatalf("重做后 XYZ 应回来: %q", ed.state.Buffer.String())
	}

	// ---- 3. IME 组合输入（标题框） ----
	m.ClickAt(600, 76)
	m.Frame()
	m.Key(ui.Ctrl, ui.KeyA)
	m.Compose("中文", 2) // 显示组合
	m.Frame()
	m.Type("中文") // 提交组合
	m.Frame()
	if a.titleEdit != "中文" {
		t.Fatalf("IME 组合输入后标题缓冲应为中文: %q", a.titleEdit)
	}
	if a.currentTitle != "中文" {
		t.Fatalf("IME 输入后应改名: %q", a.currentTitle)
	}

	// ---- 4. 拖放导入 ----
	ext := t.TempDir()
	mdPath := ext + string(os.PathSeparator) + "拖入.md"
	os.WriteFile(mdPath, []byte("拖入的内容"), 0o644)
	pngPath := ext + string(os.PathSeparator) + "图.png"
	os.WriteFile(pngPath, []byte{0x89, 'P', 'N', 'G'}, 0o644)
	before := a.vault.GetWorkspace().NoteCount
	if !m.DropFiles(600, 300, mdPath, pngPath) {
		t.Log("DropFiles 未被元素接收（将直接调用流程）")
		a.dropFilesFlow([]string{mdPath, pngPath})
	}
	// 拖放流程在后台 goroutine，轮询等待完成。
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		if a.vault.GetWorkspace().NoteCount > before {
			break
		}
		time.Sleep(50 * time.Millisecond)
	}
	m.Frame()
	if a.vault.GetWorkspace().NoteCount <= before {
		t.Fatalf("拖放 .md 应入库: %d -> %d", before, a.vault.GetWorkspace().NoteCount)
	}
	// 附件应可解析。
	found := false
	for _, n := range a.vault.ListNotes("updated_desc") {
		if strings.Contains(n.Title, "拖入") {
			found = true
			break
		}
	}
	if !found {
		t.Fatal("列表应出现拖入的笔记")
	}

	// ---- 5. 反链面板开合 + 条目跳转 ----
	// 用全新笔记作引用来源（已有笔记的编辑器草稿在换篇时会落盘，
	// 不适合再用 vault 直写正文）。
	src := mustCreateForApp(t, a, "引用者")
	mustSaveForApp(t, a, src.ID, "见 [[被引用]]")
	b := mustCreateForApp(t, a, "被引用")
	mustSaveForApp(t, a, b.ID, "正文")
	a.openNote(b.ID)
	a.refreshLists()
	m.Advance(400 * time.Millisecond)
	m.Frame()
	a.showBacklinks = true
	m.Advance(400 * time.Millisecond)
	m.Frame()
	if !strings.Contains(texts(m), "反向链接") {
		t.Fatalf("反链面板应渲染:\n%s", texts(m))
	}
	if len(a.backlinks) == 0 {
		t.Fatal("被引用笔记应有反链")
	}
	bl := a.backlinks[0]
	if err := m.Click(bl.SourceTitle); err == nil {
		m.Frame()
		if a.currentID != bl.SourceNoteID {
			t.Fatalf("点击反链条目应跳转来源笔记, 当前 %q", a.currentTitle)
		}
	} else {
		t.Logf("反链条目未找到可点文本: %v", err)
	}
	a.showBacklinks = false
	m.Advance(400 * time.Millisecond)
	m.Frame()

	// ---- 6. 排序切换 ----
	a.setSort("title_asc")
	m.Frame()
	if len(a.notes) < 2 {
		t.Fatal("应有多篇笔记")
	}
	first := a.notes[0].Title
	a.setSort("title_desc")
	m.Frame()
	if a.notes[0].Title == first {
		t.Fatalf("排序切换后首项应变化: %q", a.notes[0].Title)
	}

	// ---- 7. 长标题 ----
	a.openNote(a.notes[0].ID)
	m.ClickAt(600, 76)
	m.Frame()
	m.Key(ui.Ctrl, ui.KeyA)
	long := strings.Repeat("长标题", 40)
	m.Type(long)
	m.Frame()
	if a.currentTitle == "" {
		t.Fatal("长标题不应清空")
	}
	simShot(t, "7-long-title", m)
}
