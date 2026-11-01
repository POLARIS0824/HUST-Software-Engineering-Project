package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

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

// TestTitleBoxDeletesToEmptyWithoutBounce 回归"最后一个字符删不掉"：
// 删空后改名被 ErrTitleEmpty 拒绝，旧字符不得随绑定值回弹进输入框；
// 空草稿期间保持为空，输入新标题后正常改名。
func TestTitleBoxDeletesToEmptyWithoutBounce(t *testing.T) {
	a := newTestApp(t)
	doc := mustCreateForApp(t, a, "(")
	mustSaveForApp(t, a, doc.ID, "正文")
	a.openNote(doc.ID)
	a.refreshLists()

	m := ui.NewTester(a.contentView, 1200, 700)
	m.Frame()
	// 标题输入框在编辑区首行（列表列 300 宽之后）；用坐标点击，
	// 避免按文本 "(" 命中列表卡片里的同名标题。
	m.ClickAt(600, 76)
	m.Frame()
	m.Key(ui.Ctrl, ui.KeyA)
	m.TypeKey(0, ui.KeyBackspace, "")
	m.Frame()
	if a.titleEdit != "" {
		t.Fatalf("删空后编辑缓冲应为空，得到 %q", a.titleEdit)
	}
	// 后续帧不得把旧字符回弹进输入框（值回同步 bug）。
	m.Advance(300 * time.Millisecond)
	m.Frame()
	m.Frame()
	if a.titleEdit != "" {
		t.Fatalf("后续帧不得回弹旧字符: %q", a.titleEdit)
	}
	if a.currentTitle != "(" {
		t.Fatalf("空草稿不应触发改名，原标题应保留: %q", a.currentTitle)
	}
	// 输入新标题应正常改名。
	m.Type("新标题")
	m.Frame()
	if a.currentTitle != "新标题" {
		t.Fatalf("输入新标题后应改名，得到 %q", a.currentTitle)
	}
}

// TestContextMenuHitsAnimatedRow 回归：列表行进出场动画期间右键，
// 菜单必须命中目标行本身。几何类进出场（位移/重排滑动）会让绘制位置
// 与命中盒不一致，曾导致右键删掉的是邻居行。
func TestContextMenuHitsAnimatedRow(t *testing.T) {
	a := newTestApp(t)
	first := mustCreateForApp(t, a, "待删当前")
	mustSaveForApp(t, a, first.ID, "内容A")
	a.openNote(first.ID)
	second := mustCreateForApp(t, a, "快切甲")
	mustSaveForApp(t, a, second.ID, "甲")
	third := mustCreateForApp(t, a, "快切乙")
	mustSaveForApp(t, a, third.ID, "乙")

	m := ui.NewTester(a.contentView, 1200, 760)
	a.refreshLists()
	// 不推进时钟：行仍处于进出场动画窗口内。
	for i := 0; i < 2; i++ {
		a.openNote(second.ID)
		a.openNote(third.ID)
		a.openNote(first.ID)
		m.Frame()
	}
	if err := m.RightClick("快切甲"); err != nil {
		t.Fatalf("右键卡片: %v", err)
	}
	if err := m.ChooseMenuItem("移入回收站"); err != nil {
		t.Fatalf("选择菜单项: %v", err)
	}
	m.Frame()
	// 删除先播放碎纸动画（约 0.56s），推进虚拟时钟后才真正移入回收站。
	m.Advance(700 * time.Millisecond)
	m.Frame()
	data, _ := a.vault.OpenNote(second.ID)
	if !data.Trashed {
		t.Fatal("应删除被右键的「快切甲」，而不是邻居行")
	}
	if _, err := a.vault.OpenNote(first.ID); err != nil {
		t.Fatal("邻居「待删当前」不应被误删")
	}
}

// TestAnimationTransitionsSmoke 驱动各动画状态切换并推进虚拟时钟，
// 断言渲染不崩溃且关键内容可见。本用例曾回归出 Onevra components.Tabs
// 在 paint-only 帧持有过期 Element 的崩溃；现覆盖侧栏胶囊、反链面板
// 宽度收展、编辑/阅读换页与页面切换的动画路径。
func TestAnimationTransitionsSmoke(t *testing.T) {
	a := newTestApp(t)
	doc := mustCreateForApp(t, a, "动画冒烟")
	mustSaveForApp(t, a, doc.ID, "内容 [[动画冒烟]] #标签")
	a.openNote(doc.ID)
	a.refreshLists()

	m := ui.NewTester(a.contentView, 1200, 700)
	m.Frame()

	// 反链面板：展开（宽度弹簧）→ 推进 → 收起。
	a.showBacklinks = true
	m.Frame()
	m.Advance(120 * time.Millisecond)
	m.Frame()
	if txt := strings.Join(m.Texts(), "\n"); !strings.Contains(txt, "反向链接") {
		t.Fatalf("展开后应显示反链面板:\n%s", txt)
	}
	a.showBacklinks = false
	m.Advance(400 * time.Millisecond)
	m.Frame()
	m.Advance(500 * time.Millisecond)
	m.Frame()

	// 编辑 → 阅读 → 编辑（内容进出场换页）。
	a.mode = "read"
	a.readerSrc = a.vault.RenderReaderSource(a.currentID)
	m.Frame()
	m.Advance(120 * time.Millisecond)
	m.Frame()
	m.Advance(400 * time.Millisecond)
	m.Frame()
	a.mode = "edit"
	m.Frame()
	m.Advance(400 * time.Millisecond)
	m.Frame()

	// 页面切换（进出场过渡）。
	a.setView(viewTrash)
	m.Frame()
	m.Advance(120 * time.Millisecond)
	m.Frame()
	m.Advance(400 * time.Millisecond)
	m.Frame()
	if txt := strings.Join(m.Texts(), "\n"); !strings.Contains(txt, "回收站") {
		t.Fatalf("回收站页应渲染:\n%s", txt)
	}
	a.setView(viewNotes)
	m.Advance(500 * time.Millisecond)
	m.Frame()

	// 侧栏胶囊：切换选中项并推进弹簧。
	l := ui.NewTester(a.leftView, 300, 700)
	l.Frame()
	if err := l.Click("回收站"); err != nil {
		t.Fatal(err)
	}
	l.Frame()
	l.Advance(120 * time.Millisecond)
	l.Frame()
	l.Advance(500 * time.Millisecond)
	l.Frame()
}
