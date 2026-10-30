package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/egoist/mygo/ui"
)

// newTestApp 构造使用临时工作区的 app（不启动窗口）。
func newTestApp(t *testing.T) *app {
	t.Helper()
	v := &Vault{
		root:    t.TempDir(),
		notes:   map[string]*indexedNote{},
		trashed: map[string]*NoteMeta{},
		byTitle: map[string][]string{},
	}
	if err := v.loadWorkspace(); err != nil {
		t.Fatalf("loadWorkspace: %v", err)
	}
	a := newApp(v)
	a.refreshLists()
	return a
}

func mustCreateForApp(t *testing.T, a *app, title string) NoteDocument {
	t.Helper()
	doc, err := a.vault.CreateNote(title)
	if err != nil {
		t.Fatalf("CreateNote(%q): %v", title, err)
	}
	return doc
}

func mustSaveForApp(t *testing.T, a *app, id, body string) SaveReceipt {
	t.Helper()
	receipt, err := a.vault.SaveContent(id, body)
	if err != nil {
		t.Fatalf("SaveContent: %v", err)
	}
	return receipt
}

// uiSnapshot 用 Tester 无窗口渲染内容视图，返回视图中的全部文本。
func uiSnapshot(t *testing.T, a *app, arrange func()) string {
	t.Helper()
	if arrange != nil {
		arrange()
	}
	tester := ui.NewTester(a.contentView, 1200, 800)
	return strings.Join(tester.Texts(), "\n")
}

// TestRenderReaderSourceRewritesLinks 验证阅读源改写：已解析链接指向
// noteID，未解析链接指向标题（代码区内不处理），且不回写正文。
func TestRenderReaderSourceRewritesLinks(t *testing.T) {
	v := newTestVault(t)
	target := mustCreate(t, v, "B")
	a := mustCreate(t, v, "A")
	mustSave(t, v, a.ID, "参见 [[B|点这里]] 与 [[缺失]]。\n\n```\n[[代码区]]\n```\n")

	src := v.RenderReaderSource(a.ID)
	if !strings.Contains(src, "[点这里](justmd-note://"+target.ID+")") {
		t.Fatalf("已解析链接应指向 noteID: %q", src)
	}
	if !strings.Contains(src, "[缺失](justmd-open://") {
		t.Fatalf("未解析链接应指向编码标题: %q", src)
	}
	if strings.Contains(strings.Split(src, "```")[1], "justmd-") {
		t.Fatalf("代码区内不应改写: %q", src)
	}
	if body := v.readBody(v.notes[a.ID].meta); strings.Contains(body, "justmd-") {
		t.Fatalf("阅读改写不得回写正文: %q", body)
	}
}

// TestNoteAssetPathRejectsTraversal 验证阅读页图片路径的穿越防御
// （NFR-SEC-03）。
func TestNoteAssetPathRejectsTraversal(t *testing.T) {
	v := newTestVault(t)
	a := mustCreate(t, v, "图")
	mustSave(t, v, a.ID, "正文")
	if _, err := v.NoteAssetPath(a.ID, "../../逃逸.png"); err == nil {
		t.Fatal("越界路径应被拒绝")
	}
	if _, err := v.NoteAssetPath(a.ID, "https://x/y.png"); err == nil {
		t.Fatal("远程地址应被拒绝")
	}
	if _, err := v.NoteAssetPath(a.ID, "无.png"); !os.IsNotExist(err) {
		t.Fatalf("缺失文件应返回 ErrNotExist: %v", err)
	}
}

// TestNotesViewRenders 用 Tester 无窗口渲染，验证三页视图不 panic
// 且打开笔记后标题与保存状态可见。
func TestNotesViewRenders(t *testing.T) {
	a := newTestApp(t)
	doc := mustCreateForApp(t, a, "渲染测试")
	mustSaveForApp(t, a, doc.ID, "## 标题\n\n正文内容 #标签\n")

	a.openNote(doc.ID)
	a.refreshLists()

	notes := uiSnapshot(t, a, func() { a.view = viewNotes })
	for _, want := range []string{"渲染测试", "已保存"} {
		if !strings.Contains(notes, want) {
			t.Errorf("笔记页应包含 %q，得到:\n%s", want, notes)
		}
	}
	leftText := strings.Join(ui.NewTester(a.leftView, 240, 800).Texts(), "\n")
	for _, want := range []string{"全部笔记", "回收站", "设置与数据"} {
		if !strings.Contains(leftText, want) {
			t.Errorf("侧栏应包含 %q，得到:\n%s", want, leftText)
		}
	}

	trash := uiSnapshot(t, a, func() { a.setView(viewTrash) })
	if !strings.Contains(trash, "回收站") {
		t.Errorf("回收站页应渲染，得到:\n%s", trash)
	}
	settings := uiSnapshot(t, a, func() { a.setView(viewSettings) })
	if !strings.Contains(settings, "知识库位置") {
		t.Errorf("设置页应渲染，得到:\n%s", settings)
	}
}

// TestAutosaveSavesToDisk 验证原生侧的保存链路：编辑 Buffer 后 saveNow
// 规范化换行并原子落盘（BR-04/05）。
func TestAutosaveSavesToDisk(t *testing.T) {
	a := newTestApp(t)
	doc := mustCreateForApp(t, a, "自动保存")
	a.openNote(doc.ID)

	ed := a.currentEditor()
	ed.state.Buffer.Set("新内容\r\n第二行")
	a.saveNow()

	if a.saveState != saveSaved {
		t.Fatalf("保存后应为 saved，得到 %s", a.saveState)
	}
	body := a.vault.readBody(a.vault.notes[doc.ID].meta)
	if body != "新内容\n第二行" {
		t.Fatalf("落盘内容应规范化 LF: %q", body)
	}
	if ed.state.Buffer.Modified() {
		t.Fatal("MarkSaved 后 Buffer 不应再标记为已修改")
	}
	abs := filepath.Join(a.vault.notesDir(), filepath.FromSlash(a.vault.notes[doc.ID].meta.RelativePath))
	if data, err := os.ReadFile(abs); err != nil || string(data) != "新内容\n第二行" {
		t.Fatalf("磁盘内容错误: %q %v", data, err)
	}
}

// TestEditorStatePreservedAcrossNotes 验证换篇后编辑器（含未保存草稿）
// 保留：切走再切回，Buffer 内容与版本不变（FR-EDIT-08 的会话历史保留）。
func TestEditorStatePreservedAcrossNotes(t *testing.T) {
	a := newTestApp(t)
	first := mustCreateForApp(t, a, "第一篇")
	second := mustCreateForApp(t, a, "第二篇")
	mustSaveForApp(t, a, first.ID, "草稿内容")

	a.openNote(first.ID)
	draft := "未保存的新草稿"
	a.editors[first.ID].state.Buffer.Set(draft)

	a.openNote(second.ID)
	a.openNote(first.ID)

	if got := a.currentEditor().state.Buffer.String(); got != draft {
		t.Fatalf("切回后草稿应保留，得到 %q", got)
	}
}

// TestDeleteFromListSavesDraftFirst 验证删除当前笔记前先保存草稿
// （评审 #2：此前先 TrashNote 再 flushSave，保存会因文件已移走而失败丢稿）。
func TestDeleteFromListSavesDraftFirst(t *testing.T) {
	a := newTestApp(t)
	doc := mustCreateForApp(t, a, "待删除")
	a.openNote(doc.ID)
	a.currentEditor().state.Buffer.Set("未保存的草稿")

	a.deleteFromList(doc.ID)

	data, err := os.ReadFile(filepath.Join(a.vault.trashDir(), doc.ID+".md"))
	if err != nil {
		t.Fatalf("回收站文件应存在: %v", err)
	}
	if string(data) != "未保存的草稿" {
		t.Fatalf("删除前应先把草稿落盘: %q", data)
	}
	if a.currentID != "" {
		t.Fatal("删除后应清空当前笔记")
	}
}

// TestOpenNoteFlushesPreviousDraft 验证换篇时上一篇的草稿先落盘
// （评审 #2/#3：防抖定时器指向错误笔记时的丢稿窗口）。
func TestOpenNoteFlushesPreviousDraft(t *testing.T) {
	a := newTestApp(t)
	first := mustCreateForApp(t, a, "先打开")
	second := mustCreateForApp(t, a, "后打开")

	a.openNote(first.ID)
	a.currentEditor().state.Buffer.Set("换篇前的草稿")
	a.openNote(second.ID)

	body := a.vault.readBody(a.vault.notes[first.ID].meta)
	if body != "换篇前的草稿" {
		t.Fatalf("换篇时应落盘上一篇草稿: %q", body)
	}
}
