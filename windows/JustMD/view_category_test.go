package main

import (
	"strings"
	"testing"

	"github.com/egoist/mygo/ui"
)

// newCategoryTestApp 构造带分类与笔记的测试 app：
// 根目录两篇（未分类），课程/软件工程 一篇，课程/算法 空分类。
func newCategoryTestApp(t *testing.T) (a *app, unfiled []NoteDocument, filed NoteDocument) {
	t.Helper()
	a = newTestApp(t)
	for _, title := range []string{"大纲", "随手记"} {
		doc := mustCreateForApp(t, a, title)
		mustSaveForApp(t, a, doc.ID, "正文内容 #标签")
		unfiled = append(unfiled, doc)
	}
	if _, err := a.vault.CreateCategory("课程/软件工程"); err != nil {
		t.Fatalf("CreateCategory: %v", err)
	}
	if _, err := a.vault.CreateCategory("课程/算法"); err != nil {
		t.Fatalf("CreateCategory: %v", err)
	}
	filed = mustCreateForApp(t, a, "需求分析")
	mustSaveForApp(t, a, filed.ID, "需求分析正文")
	if _, err := a.vault.MoveNoteToCategory(filed.ID, "课程/软件工程"); err != nil {
		t.Fatalf("MoveNoteToCategory: %v", err)
	}
	a.refreshLists()
	return a, unfiled, filed
}

// TestCategorySidebarShowsTree 侧栏出现「分类」分区：未分类、各级分类、新建入口。
func TestCategorySidebarShowsTree(t *testing.T) {
	a, _, _ := newCategoryTestApp(t)
	l := ui.NewTester(a.leftView, 300, 800)
	l.Frame()
	txt := texts(l)
	for _, want := range []string{"分类", "未分类", "课程", "软件工程", "算法", "新建分类…"} {
		if !strings.Contains(txt, want) {
			t.Fatalf("侧栏缺少 %q:\n%s", want, txt)
		}
	}
	// 计数：未分类 2 篇，课程子树 1 篇。
	if a.unfiledCount != 2 {
		t.Fatalf("未分类计数应为 2，实际 %d", a.unfiledCount)
	}
}

// TestCategoryFilterSelectsSubtree 点分类只显示该分类（含子分类）的笔记；
// 再点一次取消过滤；与标签过滤互斥。
func TestCategoryFilterSelectsSubtree(t *testing.T) {
	a, _, filed := newCategoryTestApp(t)
	a.toggleCategoryFilter("课程")
	if got := len(a.visibleNotes()); got != 1 {
		t.Fatalf("「课程」应只命中 1 篇（含子分类），实际 %d", got)
	}
	if a.visibleNotes()[0].ID != filed.ID {
		t.Fatalf("命中的应是已归类笔记，实际 %q", a.visibleNotes()[0].Title)
	}
	// 再点同一分类取消。
	a.toggleCategoryFilter("课程")
	if a.catFiltered || len(a.visibleNotes()) != 3 {
		t.Fatalf("取消分类过滤后应显示全部 3 篇，实际 filtered=%v n=%d", a.catFiltered, len(a.visibleNotes()))
	}
	// 选中分类会清掉标签过滤，选中标签会清掉分类过滤。
	a.toggleTagFilter("标签")
	a.toggleCategoryFilter("课程")
	if a.tagFilter != "" {
		t.Fatalf("选中分类后标签过滤应清空，实际 %q", a.tagFilter)
	}
	a.toggleTagFilter("标签")
	if a.catFiltered {
		t.Fatal("选中标签后分类过滤应清空")
	}
}

// TestUnfiledFilterShowsOnlyRootNotes 「未分类」过滤只显示 notes/ 根目录的笔记，
// 正是"无主笔记"的入口。
func TestUnfiledFilterShowsOnlyRootNotes(t *testing.T) {
	a, unfiled, _ := newCategoryTestApp(t)
	a.toggleCategoryFilter("")
	if !a.catFiltered || a.catFilter != "" {
		t.Fatalf("应处于未分类过滤: filtered=%v filter=%q", a.catFiltered, a.catFilter)
	}
	got := a.visibleNotes()
	if len(got) != len(unfiled) {
		t.Fatalf("未分类应 %d 篇，实际 %d", len(unfiled), len(got))
	}
	for _, n := range got {
		if n.Category != "" {
			t.Fatalf("未分类列表混入了分类笔记: %q → %q", n.Title, n.Category)
		}
	}
}

// TestMoveNoteToCategoryAction 移动笔记：文件搬到分类目录、列表与提示同步更新，
// 标题与正文不变（双链不受影响）。
func TestMoveNoteToCategoryAction(t *testing.T) {
	a := newTestApp(t)
	doc := mustCreateForApp(t, a, "待归类")
	mustSaveForApp(t, a, doc.ID, "参见 [[另一篇]] 的内容")
	a.refreshLists()

	if err := a.moveNoteToCategory(doc.ID, "课程/软件工程"); err != nil {
		t.Fatalf("移动失败: %v", err)
	}
	if a.pendingToast == "" || !strings.Contains(a.pendingToast, "软件工程") {
		t.Fatalf("移动后应有提示，实际 %q", a.pendingToast)
	}
	var got *NoteSummary
	for i := range a.notes {
		if a.notes[i].ID == doc.ID {
			got = &a.notes[i]
		}
	}
	if got == nil {
		t.Fatal("移动后笔记应仍在列表里")
	}
	if got.Category != "课程/软件工程" {
		t.Fatalf("分类应为 课程/软件工程，实际 %q", got.Category)
	}
	if got.Title != "待归类" {
		t.Fatalf("移动不应改标题，实际 %q", got.Title)
	}
	body := a.vault.readBody(a.vault.notes[doc.ID].meta)
	if !strings.Contains(body, "参见 [[另一篇]] 的内容") {
		t.Fatalf("移动不应改正文，实际 %q", body)
	}

	// 移回未分类。
	if err := a.moveNoteToCategory(doc.ID, ""); err != nil {
		t.Fatalf("移回未分类失败: %v", err)
	}
	a.refreshLists()
	for _, n := range a.notes {
		if n.ID == doc.ID && n.Category != "" {
			t.Fatalf("移回后分类应为空，实际 %q", n.Category)
		}
	}
}

// TestMoveDialogRendersAndMoves 右键「移动到分类…」的弹窗：列出目标分类，
// 选中后点「移动到所选分类」真正搬走。
func TestMoveDialogRendersAndMoves(t *testing.T) {
	a := newTestApp(t)
	doc := mustCreateForApp(t, a, "弹窗移动")
	mustSaveForApp(t, a, doc.ID, "内容")
	if _, err := a.vault.CreateCategory("课程/软件工程"); err != nil {
		t.Fatalf("CreateCategory: %v", err)
	}
	a.refreshLists()
	a.openNote(doc.ID)

	m := ui.NewTester(a.contentView, 1200, 760)
	m.Frame()
	if err := m.Click("📁 未分类"); err != nil {
		t.Fatalf("点击分类 chip: %v", err)
	}
	m.Frame()
	if !a.catDialog.open || a.catDialog.noteID != doc.ID {
		t.Fatalf("应打开移动弹窗: %+v", a.catDialog)
	}
	txt := texts(m)
	for _, want := range []string{"移动到分类", "把「弹窗移动」移动到", "未分类（根目录）", "软件工程"} {
		if !strings.Contains(txt, want) {
			t.Fatalf("弹窗缺少 %q:\n%s", want, txt)
		}
	}
	// 选中目标分类后确认。
	a.catDialog.target = "课程/软件工程"
	m.Frame()
	if err := m.Click("移动到所选分类"); err != nil {
		t.Fatalf("点击确认: %v", err)
	}
	m.Frame()
	if a.catDialog.open {
		t.Fatal("移动成功后弹窗应关闭")
	}
	a.refreshLists()
	for _, n := range a.notes {
		if n.ID == doc.ID && n.Category != "课程/软件工程" {
			t.Fatalf("应移动到 课程/软件工程，实际 %q", n.Category)
		}
	}
}

// TestNewCategoryDialogCreatesCategory 侧栏「新建分类…」弹窗：输入多级路径后创建成功。
func TestNewCategoryDialogCreatesCategory(t *testing.T) {
	a := newTestApp(t)
	a.refreshLists()
	m := ui.NewTester(a.contentView, 900, 600)
	m.Frame()

	a.openNewCategoryDialog()
	m.Frame()
	if !a.catDialog.open {
		t.Fatal("应打开新建分类弹窗")
	}
	a.catDialog.newName = "课程/软件工程"
	m.Frame()
	if err := m.Click("新建"); err != nil {
		t.Fatalf("点击新建: %v", err)
	}
	m.Frame()
	if !categoryPathExists(a.categories, "课程/软件工程") {
		t.Fatalf("新建后分类树应包含 课程/软件工程，实际 %+v", a.categories)
	}
	if a.catDialog.newName != "" {
		t.Fatalf("创建成功后输入框应清空，实际 %q", a.catDialog.newName)
	}
}

// TestDeleteEmptyCategory 删除空分类成功；有笔记的分类被拒绝并给出提示。
func TestDeleteEmptyCategory(t *testing.T) {
	a, _, _ := newCategoryTestApp(t)
	if err := a.deleteCategory("课程/算法"); err != nil {
		t.Fatalf("删除空分类失败: %v", err)
	}
	if categoryPathExists(a.categories, "课程/算法") {
		t.Fatal("空分类删除后不应出现在树里")
	}
	// 非空分类：领域层拒绝，界面给业务提示。
	if err := a.deleteCategory("课程/软件工程"); err == nil {
		t.Fatal("非空分类不应允许删除")
	}
	if a.pendingToast == "" {
		t.Fatal("删除非空分类应有提示")
	}
	if !categoryPathExists(a.categories, "课程/软件工程") {
		t.Fatal("被拒绝的分类应仍在树里")
	}
}

// TestCategoryFilterEmptyState 分类下没有笔记时给出去向提示，而不是谎称"还没有笔记"。
func TestCategoryFilterEmptyState(t *testing.T) {
	a, _, _ := newCategoryTestApp(t)
	a.toggleCategoryFilter("课程/算法")
	m := ui.NewTester(a.contentView, 1200, 760)
	m.Frame()
	txt := texts(m)
	if !strings.Contains(txt, "里还没有笔记") {
		t.Fatalf("空分类应显示分类内空状态:\n%s", txt)
	}
	if !strings.Contains(txt, "查看全部笔记") {
		t.Fatalf("空分类应提供返回全部笔记的入口:\n%s", txt)
	}
}

// TestCategoryDeletedClearsFilter 分类被删除后过滤自动清除，不留下"空列表无选中项"。
func TestCategoryDeletedClearsFilter(t *testing.T) {
	a, _, _ := newCategoryTestApp(t)
	a.toggleCategoryFilter("课程/算法")
	if !a.catFiltered {
		t.Fatal("应处于分类过滤")
	}
	if err := a.vault.DeleteCategory("课程/算法"); err != nil {
		t.Fatalf("DeleteCategory: %v", err)
	}
	a.refreshLists()
	if a.catFiltered {
		t.Fatalf("分类被删后过滤应清除，实际 filter=%q", a.catFilter)
	}
	if got := len(a.visibleNotes()); got != 3 {
		t.Fatalf("清除过滤后应显示 3 篇，实际 %d", got)
	}
}
