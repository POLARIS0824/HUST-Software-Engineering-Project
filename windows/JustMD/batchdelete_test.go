package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/egoist/mygo/ui"
)

// batchDeleteApp 构造一个有 3 篇笔记的应用（批量删除用例的公共脚手架）。
func batchDeleteApp(t *testing.T) (*app, []NoteDocument) {
	t.Helper()
	a := newTestApp(t)
	docs := []NoteDocument{}
	for _, title := range []string{"批量甲", "批量乙", "批量丙"} {
		doc := mustCreateForApp(t, a, title)
		mustSaveForApp(t, a, doc.ID, "正文 "+title)
		docs = append(docs, doc)
	}
	a.refreshLists()
	return a, docs
}

// TestBatchModeStartsEmpty 回归：进入批量删除状态时勾选集必须是空的。
// 沿用上一次的勾选会让用户误删没打算删的笔记。
func TestBatchModeStartsEmpty(t *testing.T) {
	a, _ := batchDeleteApp(t)
	a.batchPicked = map[string]bool{"残留": true}
	a.enterBatchMode()
	if !a.batchMode {
		t.Fatal("enterBatchMode 后应处于批量删除状态")
	}
	if len(a.batchPicked) != 0 {
		t.Fatalf("进入批量删除状态应清空勾选，实际残留 %d 项", len(a.batchPicked))
	}
}

// TestBatchTogglePick 回归：勾选与取消勾选必须真的翻转状态。
func TestBatchTogglePick(t *testing.T) {
	a, docs := batchDeleteApp(t)
	a.enterBatchMode()
	id := docs[0].ID
	if a.batchPicked[id] {
		t.Fatal("初始不应已勾选")
	}
	a.toggleBatchPick(id)
	if !a.batchPicked[id] {
		t.Fatal("toggleBatchPick 后应已勾选")
	}
	a.toggleBatchPick(id)
	if a.batchPicked[id] {
		t.Fatal("再次 toggleBatchPick 应取消勾选")
	}
}

// TestBatchPickedIDsFollowVisibleOrder 回归：被勾选的 ID 必须按当前
// 列表的显示顺序返回，且不含不在列表里的 ID。
func TestBatchPickedIDsFollowVisibleOrder(t *testing.T) {
	a, docs := batchDeleteApp(t)
	a.enterBatchMode()
	// 勾选顺序与列表顺序相反，验证返回时按列表顺序。
	a.toggleBatchPick(docs[2].ID)
	a.toggleBatchPick(docs[0].ID)

	// 期望顺序取自"当前可见列表里被勾选的那些"，
	// 而不是硬编码某几篇——列表按更新时间排序，顺序由数据决定。
	want := []string{}
	for _, id := range a.batchVisibleIDs() {
		if id == docs[0].ID || id == docs[2].ID {
			want = append(want, id)
		}
	}
	got := a.batchPickedIDs()
	if len(got) != 2 {
		t.Fatalf("应有 2 个被勾选的笔记，实际 %d：%v", len(got), got)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("应按列表顺序返回，期望 %v，实际 %v", want, got)
		}
	}
	// 不在可见列表里的 ID（这里用标签过滤制造）不应参与删除。
	a.tagFilter = "不存在的标签"
	if got := a.batchPickedIDs(); len(got) != 0 {
		t.Fatalf("被过滤掉的笔记不应出现在删除列表，实际 %v", got)
	}
}

// TestBatchDeleteRemovesSelectedOnly 回归：确认删除只删被勾选的，
// 未勾选的必须留在库里。
func TestBatchDeleteRemovesSelectedOnly(t *testing.T) {
	a, docs := batchDeleteApp(t)
	a.enterBatchMode()
	a.toggleBatchPick(docs[0].ID)
	a.toggleBatchPick(docs[2].ID)
	a.batchDeleteSelected()

	for _, i := range []int{0, 2} {
		doc, err := a.vault.OpenNote(docs[i].ID)
		if err != nil {
			t.Fatalf("被勾选的笔记应进回收站而不是消失: %v", err)
		}
		if !doc.Trashed {
			t.Fatalf("「%s」应被移入回收站", docs[i].Title)
		}
	}
	doc, err := a.vault.OpenNote(docs[1].ID)
	if err != nil {
		t.Fatalf("未勾选的笔记不应被删除: %v", err)
	}
	if doc.Trashed {
		t.Fatal("未勾选的笔记不应被移入回收站")
	}
}

// TestBatchDeleteExitsMode 回归：确认删除后必须退出批量删除状态。
func TestBatchDeleteExitsMode(t *testing.T) {
	a, docs := batchDeleteApp(t)
	a.enterBatchMode()
	a.toggleBatchPick(docs[0].ID)
	a.batchDeleteSelected()
	if a.batchMode {
		t.Fatal("确认删除后应退出批量删除状态")
	}
	if len(a.batchPicked) != 0 {
		t.Fatalf("退出后应清空勾选，实际残留 %d 项", len(a.batchPicked))
	}
}

// TestBatchCancelExitsModeWithoutDeleting 回归：取消只退出状态，
// 一篇都不删。
func TestBatchCancelExitsModeWithoutDeleting(t *testing.T) {
	a, docs := batchDeleteApp(t)
	a.enterBatchMode()
	a.toggleBatchPick(docs[0].ID)
	a.exitBatchMode()
	if a.batchMode {
		t.Fatal("取消后应退出批量删除状态")
	}
	for _, d := range docs {
		doc, err := a.vault.OpenNote(d.ID)
		if err != nil {
			t.Fatalf("取消不应删除笔记: %v", err)
		}
		if doc.Trashed {
			t.Fatalf("取消不应移入回收站：%s", d.Title)
		}
	}
}

// TestBatchDeleteEmptySelection 回归：没勾选任何笔记就点确认，
// 不得删除任何东西（尤其不能误删全部）。
func TestBatchDeleteEmptySelection(t *testing.T) {
	a, docs := batchDeleteApp(t)
	a.enterBatchMode()
	a.batchDeleteSelected()
	for _, d := range docs {
		doc, err := a.vault.OpenNote(d.ID)
		if err != nil {
			t.Fatalf("空选确认不应删除笔记: %v", err)
		}
		if doc.Trashed {
			t.Fatalf("空选确认不应移入回收站：%s", d.Title)
		}
	}
}

// TestBatchDeleteDeletesCurrentNote 回归：批量删除包含当前打开的笔记时，
// 必须正确清掉当前笔记状态（不能留下悬空的 currentID）。
func TestBatchDeleteDeletesCurrentNote(t *testing.T) {
	a, docs := batchDeleteApp(t)
	a.openNote(docs[0].ID)
	a.enterBatchMode()
	a.toggleBatchPick(docs[0].ID)
	a.batchDeleteSelected()
	if a.currentID != "" {
		t.Fatalf("删除当前笔记后 currentID 应清空，实际 %q", a.currentID)
	}
	if _, ok := a.editors[docs[0].ID]; ok {
		t.Fatal("删除后不应保留编辑器缓存")
	}
}

// TestBatchDeleteSavesDraftFirst 回归（评审 #2 的批量版）：删除前必须
// 先落盘草稿，否则用户未保存的内容会随笔记一起进回收站丢在回收站里。
func TestBatchDeleteSavesDraftFirst(t *testing.T) {
	a, docs := batchDeleteApp(t)
	a.openNote(docs[0].ID)
	// 模拟"用户改了正文但还没自动保存"的状态。
	ed := a.currentEditor()
	if ed == nil {
		t.Fatal("应有当前笔记的编辑器")
	}
	ed.state.Buffer.Set("未保存的新内容")
	if ed.state.Buffer.Version() == ed.saved {
		t.Fatal("前置条件：缓冲区应与已保存版本不同")
	}

	a.enterBatchMode()
	a.toggleBatchPick(docs[0].ID)
	a.batchDeleteSelected()

	// 删除后正文文件已移到回收站（ID 命名），那里应是新内容，
	// 而不是删除前的旧内容——说明草稿确实在删除前落了盘。
	body, err := os.ReadFile(filepath.Join(a.vault.trashDir(), docs[0].ID+".md"))
	if err != nil {
		t.Fatalf("读取回收站中的笔记文件: %v", err)
	}
	if !strings.Contains(string(body), "未保存的新内容") {
		t.Fatalf("删除前应先落盘草稿，磁盘内容为 %q", string(body))
	}
}

// TestBatchModeShowsCheckboxAndButtons 回归（UI 层）：进入批量删除状态后，
// 列表每行标题右侧要出现复选框，底部要有「确认删除」与「取消」两个按钮。
func TestBatchModeShowsCheckboxAndButtons(t *testing.T) {
	a, docs := batchDeleteApp(t)
	m := ui.NewTester(a.contentView, 1200, 800)
	m.Frame()

	// 未进入批量状态时不应有确认删除按钮。
	if m.HasText("确认删除") {
		t.Fatal("未进入批量删除状态时不应显示「确认删除」")
	}

	a.enterBatchMode()
	m.Frame()
	if !m.HasText("确认删除") {
		t.Fatalf("批量删除状态应显示「确认删除」按钮，实际文本 %v", m.Texts())
	}
	if !m.HasText("取消") {
		t.Fatalf("批量删除状态应显示「取消」按钮，实际文本 %v", m.Texts())
	}
	// 每篇笔记一个勾选框（无障碍名称是"勾选 <标题>"）。
	for _, d := range docs {
		if !m.HasText("勾选 " + d.Title) {
			t.Fatalf("应显示「勾选 %s」复选框，实际文本 %v", d.Title, m.Texts())
		}
	}
}

// TestBatchCheckboxTogglesSelection 回归（UI 层）：点标题右侧的复选框
// 应该切换勾选状态，而不是打开笔记。
//
// 关键：必须点**两次**。只点一次的话，"每次点击都置为已勾选"这种
// 错误实现与真正的"切换"表现完全一样，测不出差别；
// 点第二次必须回到未勾选，才能证明它是切换而不是置位。
func TestBatchCheckboxTogglesSelection(t *testing.T) {
	a, docs := batchDeleteApp(t)
	a.enterBatchMode()
	m := ui.NewTester(a.contentView, 1200, 800)
	m.Frame()

	before := a.currentID
	if err := m.Click("勾选 " + docs[0].Title); err != nil {
		t.Fatalf("点击复选框: %v", err)
	}
	m.Frame()
	if !a.batchPicked[docs[0].ID] {
		t.Fatal("点击复选框应勾选该笔记")
	}
	if a.currentID != before {
		t.Fatalf("点击复选框不应打开笔记，currentID 从 %q 变成 %q", before, a.currentID)
	}

	// 再点一次必须取消勾选（是"切换"而非"置位"）。
	if err := m.Click("勾选 " + docs[0].Title); err != nil {
		t.Fatalf("再次点击复选框: %v", err)
	}
	m.Frame()
	if a.batchPicked[docs[0].ID] {
		t.Fatal("再次点击复选框应取消勾选")
	}
}

// TestBatchConfirmButtonDeletes 回归（UI 层）：点「确认删除」按钮
// 真的删掉被勾选的笔记。
func TestBatchConfirmButtonDeletes(t *testing.T) {
	a, docs := batchDeleteApp(t)
	a.enterBatchMode()
	m := ui.NewTester(a.contentView, 1200, 800)
	m.Frame()

	// 通过界面勾选：点复选框。
	if err := m.Click("勾选 " + docs[1].Title); err != nil {
		t.Fatalf("点击复选框: %v", err)
	}
	m.Frame()
	if !a.batchPicked[docs[1].ID] {
		t.Fatal("复选框点击未生效")
	}
	// 再点「确认删除」。
	if err := m.Click("确认删除"); err != nil {
		t.Fatalf("点击确认删除: %v", err)
	}
	m.Frame()

	doc, err := a.vault.OpenNote(docs[1].ID)
	if err != nil {
		t.Fatalf("被勾选的笔记应进回收站: %v", err)
	}
	if !doc.Trashed {
		t.Fatal("被勾选的笔记应已移入回收站")
	}
	if a.batchMode {
		t.Fatal("确认删除后应退出批量删除状态")
	}
}

// TestBatchCancelButtonExits 回归（UI 层）：点「取消」只退出状态，不删除。
func TestBatchCancelButtonExits(t *testing.T) {
	a, docs := batchDeleteApp(t)
	a.enterBatchMode()
	m := ui.NewTester(a.contentView, 1200, 800)
	m.Frame()

	if err := m.Click("勾选 " + docs[0].Title); err != nil {
		t.Fatalf("点击复选框: %v", err)
	}
	m.Frame()
	if err := m.Click("取消"); err != nil {
		t.Fatalf("点击取消: %v", err)
	}
	m.Frame()

	if a.batchMode {
		t.Fatal("点「取消」应退出批量删除状态")
	}
	doc, err := a.vault.OpenNote(docs[0].ID)
	if err != nil {
		t.Fatalf("取消不应删除笔记: %v", err)
	}
	if doc.Trashed {
		t.Fatal("取消不应移入回收站")
	}
}

// TestBatchDeleteButtonEntersMode 回归（UI 层）：笔记目录栏的「批量删除」
// 按钮按下后进入批量删除状态。
func TestBatchDeleteButtonEntersMode(t *testing.T) {
	a, _ := batchDeleteApp(t)
	m := ui.NewTester(a.contentView, 1200, 800)
	m.Frame()

	if a.batchMode {
		t.Fatal("初始不应处于批量删除状态")
	}
	// 图标按钮的无障碍名就是标签。
	if err := m.Click("批量删除"); err != nil {
		t.Fatalf("点击批量删除按钮: %v", err)
	}
	m.Frame()
	if !a.batchMode {
		t.Fatal("点「批量删除」应进入批量删除状态")
	}
}

// TestBatchModeRowClickToggles 回归（UI 层）：批量删除状态下点笔记标题
// 应切换勾选，而不是打开笔记。
func TestBatchModeRowClickToggles(t *testing.T) {
	a, docs := batchDeleteApp(t)
	a.enterBatchMode()
	m := ui.NewTester(a.contentView, 1200, 800)
	m.Frame()

	before := a.currentID
	if err := m.Click("批量甲"); err != nil {
		t.Fatalf("点击笔记标题: %v", err)
	}
	m.Frame()
	if !a.batchPicked[docs[0].ID] {
		t.Fatal("批量删除状态下点标题应勾选该笔记")
	}
	if a.currentID != before {
		t.Fatalf("批量删除状态下点标题不应打开笔记，currentID 从 %q 变成 %q", before, a.currentID)
	}
}

// TestBatchDeleteSkipsPickedOutsideList 回归：勾选集里残留了不在当前
// 列表里的 ID（如切过标签过滤）时，批量删除不得误删它。
func TestBatchDeleteSkipsPickedOutsideList(t *testing.T) {
	a, docs := batchDeleteApp(t)
	a.enterBatchMode()
	// 直接把一篇不在可见列表里的笔记塞进勾选集。
	a.batchPicked[docs[2].ID] = true
	a.tagFilter = "不存在的标签"
	a.batchDeleteSelected()

	doc, err := a.vault.OpenNote(docs[2].ID)
	if err != nil {
		t.Fatalf("笔记不应被删除: %v", err)
	}
	if doc.Trashed {
		t.Fatal("不在当前列表里的笔记不应被批量删除")
	}
}
