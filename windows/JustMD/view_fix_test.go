package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/egoist/mygo/ui"
)

// TestTitleConflictKeepsDraftAndWarns 同名冲突不再静默加后缀、也不改写
// 用户正在输入的内容：保留原稿 + 行内提示，改到不冲突的标题才真正改名。
// （旧行为会把 " (1)" 塞回输入框，继续输入得到「标题 (1)abc」。）
func TestTitleConflictKeepsDraftAndWarns(t *testing.T) {
	a := newTestApp(t)
	doc := mustCreateForApp(t, a, "占位标题")
	other := mustCreateForApp(t, a, "占用标题")
	a.refreshLists()
	a.openNote(doc.ID)

	a.titleEdit = "占用标题"
	a.applyTitleDraft(false)
	if a.currentTitle != "占位标题" {
		t.Fatalf("同名冲突不应改名，得到 %q", a.currentTitle)
	}
	if a.titleWarn == "" {
		t.Fatal("同名冲突应给出行内提示")
	}
	if a.titleEdit != "占用标题" {
		t.Fatalf("冲突时不得改写用户输入，得到 %q", a.titleEdit)
	}
	if other.ID == "" {
		t.Fatal("另一篇笔记应存在")
	}

	// 改成不冲突的标题应立即改名并清掉提示。
	a.titleEdit = "合法新标题"
	a.applyTitleDraft(false)
	if a.currentTitle != "合法新标题" {
		t.Fatalf("合法标题应立即改名，得到 %q", a.currentTitle)
	}
	if a.titleWarn != "" {
		t.Fatalf("改名成功后提示应清空，得到 %q", a.titleWarn)
	}
	if a.titleEdit != "合法新标题" {
		t.Fatalf("合法标题不应改动输入缓冲，得到 %q", a.titleEdit)
	}
}

// TestTitleInvalidCharWaitsForSubmit 非法文件名字符在输入过程中只提示，
// 不把用户输入中途替换成下划线；回车提交时才用清洗后的标题改名。
func TestTitleInvalidCharWaitsForSubmit(t *testing.T) {
	a := newTestApp(t)
	doc := mustCreateForApp(t, a, "原标题")
	a.refreshLists()
	a.openNote(doc.ID)

	a.titleEdit = "a:b"
	a.applyTitleDraft(false)
	if a.currentTitle != "原标题" {
		t.Fatalf("输入过程中不应改名，得到 %q", a.currentTitle)
	}
	if a.titleEdit != "a:b" {
		t.Fatalf("输入过程中不得替换用户输入，得到 %q", a.titleEdit)
	}
	if a.titleWarn == "" {
		t.Fatal("非法字符应给出行内提示")
	}

	a.applyTitleDraft(true)
	if a.currentTitle != "a_b" {
		t.Fatalf("提交后应按清洗结果改名，得到 %q", a.currentTitle)
	}
	if a.titleEdit != "a_b" {
		t.Fatalf("提交后输入框应显示最终标题，得到 %q", a.titleEdit)
	}
}

// TestTagFilterRespectsPathBoundary 标签过滤按层级边界匹配：
// 选中「ab」命中「ab」与「ab/c」，但不命中「abc」。
func TestTagFilterRespectsPathBoundary(t *testing.T) {
	a := newTestApp(t)
	parent := mustCreateForApp(t, a, "多级标签")
	mustSaveForApp(t, a, parent.ID, "正文 #ab/c 结束")
	other := mustCreateForApp(t, a, "近似标签")
	mustSaveForApp(t, a, other.ID, "正文 #abc 结束")
	a.refreshLists()

	a.tagFilter = "ab"
	got := a.visibleNotes()
	if len(got) != 1 {
		t.Fatalf("层级过滤应只命中 1 篇，得到 %d 篇: %+v", len(got), got)
	}
	if got[0].ID != parent.ID {
		t.Fatalf("应命中 ab/c 那篇，得到 %q", got[0].Title)
	}
}

// TestImportSummaryReportsSkippedAndFailed 导入结束提示必须报出跳过与失败，
// 不能只报成功数（AC-DATA-02：损坏文件跳过要如实告知）。
func TestImportSummaryReportsSkippedAndFailed(t *testing.T) {
	results := []ImportResult{
		{FileName: "正常.md", Status: "imported"},
		{FileName: "改名.md", Status: "renamed"},
		{FileName: "损坏.md", Status: "skipped"},
		{FileName: "写不进去.md", Status: "failed"},
	}
	msg := importSummary(results)
	for _, want := range []string{"成功 2 篇", "跳过 1 个", "失败 1 个", "损坏.md"} {
		if !strings.Contains(msg, want) {
			t.Fatalf("导入提示缺少 %q: %q", want, msg)
		}
	}
}

// TestSaveErrorStopsAutoRetry 保存失败后不再每 2 秒自动重试：
// saveError 状态下 scheduleSave 不排定时器，继续输入也不改回 dirty。
func TestSaveErrorStopsAutoRetry(t *testing.T) {
	a := newTestApp(t)
	doc := mustCreateForApp(t, a, "失败重试")
	a.refreshLists()
	a.openNote(doc.ID)
	m := ui.NewTester(a.contentView, 1200, 760)
	m.Frame()

	a.saveState = saveError
	a.titleWarn = ""
	m.ClickAt(600, 260)
	m.Frame()
	m.Type("q")
	m.Frame()

	if a.saveTimer != nil {
		t.Fatal("保存失败后不应再排定自动重试定时器")
	}
	if a.saveState != saveError {
		t.Fatalf("保存失败态不应被静默改回，得到 %q", a.saveState)
	}
}

// TestDialogBlocksGlobalShortcuts 确认弹窗打开时不响应 Ctrl+N：
// 否则弹窗背后会新建笔记，关掉弹窗后用户面对的是另一篇。
func TestDialogBlocksGlobalShortcuts(t *testing.T) {
	a := newTestApp(t)
	doc := mustCreateForApp(t, a, "现有笔记")
	a.refreshLists()
	a.openNote(doc.ID)
	m := ui.NewTester(a.contentView, 1200, 760)
	m.Frame()

	a.confirm = confirmState{open: true, title: "彻底删除", message: "确定？"}
	m.Frame()
	before := a.vault.GetWorkspace().NoteCount
	m.Key(ui.Ctrl, ui.KeyN)
	m.Frame()
	if got := a.vault.GetWorkspace().NoteCount; got != before {
		t.Fatalf("弹窗打开时 Ctrl+N 不应新建笔记：%d → %d", before, got)
	}
}

// TestRestoreFromTrashFailureToasts 回收站文件丢失时恢复必须失败并提示，
// 不能再弹"已恢复笔记"。
func TestRestoreFromTrashFailureToasts(t *testing.T) {
	a := newTestApp(t)
	doc := mustCreateForApp(t, a, "待恢复")
	if _, err := a.vault.TrashNote(doc.ID); err != nil {
		t.Fatalf("TrashNote: %v", err)
	}
	a.refreshLists()
	// 模拟回收站文件被外部删除/同步到一半。
	trashFile := filepath.Join(a.vault.root, dirTrash, doc.ID+".md")
	if err := os.Remove(trashFile); err != nil {
		t.Fatalf("删除回收站文件: %v", err)
	}

	if err := a.restoreFromTrash(doc.ID); err == nil {
		t.Fatal("回收站文件缺失时恢复应返回错误")
	}
	if a.pendingToast == "" {
		t.Fatal("恢复失败应给出提示")
	}
	if strings.Contains(a.pendingToast, "已恢复") {
		t.Fatalf("恢复失败不得提示成功: %q", a.pendingToast)
	}
}

// TestRenameCascadeRefreshesEditors 改名级联改写其它笔记磁盘正文后，
// 已打开过的笔记编辑器缓存必须从磁盘重载：否则用户再输入一个字，
// 2 秒防抖就把整篇旧正文写回磁盘，级联结果被静默覆盖。
func TestRenameCascadeRefreshesEditors(t *testing.T) {
	a := newTestApp(t)
	target := mustCreateForApp(t, a, "目标B")
	source := mustCreateForApp(t, a, "来源A")
	mustSaveForApp(t, a, source.ID, "参见 [[目标B]] 结束\n")
	a.refreshLists()

	m := ui.NewTester(a.contentView, 1200, 760)
	// 先打开来源笔记，让它进入编辑器缓存；再切到目标并改名。
	a.openNote(source.ID)
	m.Frame()
	a.openNote(target.ID)
	m.Frame()

	a.titleEdit = "改名B"
	a.applyTitleDraft(false)
	m.Frame()
	if a.currentTitle != "改名B" {
		t.Fatalf("改名应成功，得到 %q", a.currentTitle)
	}

	a.openNote(source.ID)
	m.Frame()
	ed := a.currentEditor()
	if ed == nil {
		t.Fatal("来源笔记应有编辑器")
	}
	if got := ed.state.Buffer.String(); !strings.Contains(got, "改名B") {
		t.Fatalf("级联后编辑器缓存应从磁盘重载，实际正文 %q", got)
	}
}

// TestSettingsOffersFolderImport 设置页要真的给出文件夹导入入口：
// 旧实现只有文件对话框（选不中目录），"导入整个文件夹"只是文案。
func TestSettingsOffersFolderImport(t *testing.T) {
	a := newTestApp(t)
	text := uiSnapshot(t, a, func() { a.view = viewSettings })
	for _, want := range []string{"导入 Markdown 文件…", "导入文件夹…"} {
		if !strings.Contains(text, want) {
			t.Fatalf("设置页缺少入口 %q，实际：%s", want, text)
		}
	}
}
