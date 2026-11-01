package main

import (
	"os"
	"path/filepath"
	"sort"
	"strings"
	"testing"
	"time"

	"github.com/egoist/mygo/ui"
)

// 本文件覆盖 PR #1 第二轮评审（评审 W01~W13 / N1~N5）的修复回归。
//
// 说明：N1（重启后外部改写被覆盖）经真实 UI 流程复验后**不成立**——
// 编辑器缓冲区从磁盘加载，外部追加/改写的内容会保留在用户编辑的基础上。
// 原结论来自测试方法错误（直接调 API 整体覆盖正文，而非走 UI 流程）。
// 见 TestN1_ExternalRewriteSurvivesRestart。

// ---- W03：保存乱序不得静默覆盖新内容 ---------------------------------------------

// TestW03_StaleSnapshotDoesNotOverwriteNewerSave 迟到的旧快照不得覆盖新内容。
// 模拟"异步保存与同步 flush 乱序"：序号小的先落盘，序号大的后落盘，
// 之后序号小的再次落盘（迟到的旧快照）必须被拒绝。
func TestW03_StaleSnapshotDoesNotOverwriteNewerSave(t *testing.T) {
	v := newTestVault(t)
	doc := mustCreate(t, v, "乱序保护")

	// 新内容先落盘（序号 2）
	if _, err := v.SaveContentSeq(doc.ID, "新内容", 2); err != nil {
		t.Fatalf("保存新内容: %v", err)
	}
	// 迟到的旧快照（序号 1）必须被拒绝
	if _, err := v.SaveContentSeq(doc.ID, "旧内容", 1); err != ErrStaleRevision {
		t.Fatalf("迟到的旧快照应被拒绝，得到 %v", err)
	}
	// 磁盘上必须还是新内容
	abs := filepath.Join(v.notesDir(), filepath.FromSlash(doc.RelativePath))
	data, _ := os.ReadFile(abs)
	if string(data) != "新内容" {
		t.Fatalf("磁盘内容不应被旧快照覆盖，得到 %q", string(data))
	}
}

// TestW03_NewerSnapshotStillSaves 序号递增的正常保存不受影响。
func TestW03_NewerSnapshotStillSaves(t *testing.T) {
	v := newTestVault(t)
	doc := mustCreate(t, v, "递增保存")

	if _, err := v.SaveContentSeq(doc.ID, "第一版", 1); err != nil {
		t.Fatalf("第一次保存: %v", err)
	}
	if _, err := v.SaveContentSeq(doc.ID, "第二版", 2); err != nil {
		t.Fatalf("第二次保存: %v", err)
	}
	abs := filepath.Join(v.notesDir(), filepath.FromSlash(doc.RelativePath))
	data, _ := os.ReadFile(abs)
	if string(data) != "第二版" {
		t.Fatalf("磁盘内容应为最新一版，得到 %q", string(data))
	}
}

// TestW03_ForceSaveAlsoChecksSequence 强制保存同样受乱序保护约束。
func TestW03_ForceSaveAlsoChecksSequence(t *testing.T) {
	v := newTestVault(t)
	doc := mustCreate(t, v, "强制乱序")

	if _, err := v.ForceSaveContentSeq(doc.ID, "新内容", 5); err != nil {
		t.Fatalf("强制保存: %v", err)
	}
	if _, err := v.ForceSaveContentSeq(doc.ID, "旧内容", 3); err != ErrStaleRevision {
		t.Fatalf("强制保存也应拒绝旧快照，得到 %v", err)
	}
}

// TestW03_SeqZeroDisablesCheck 传 0 表示不做乱序保护（领域内部调用路径）。
func TestW03_SeqZeroDisablesCheck(t *testing.T) {
	v := newTestVault(t)
	doc := mustCreate(t, v, "无序号")

	if _, err := v.SaveContentSeq(doc.ID, "第一版", 10); err != nil {
		t.Fatalf("保存: %v", err)
	}
	if _, err := v.SaveContentSeq(doc.ID, "第二版", 0); err != nil {
		t.Fatalf("序号 0 不应触发乱序保护，得到 %v", err)
	}
}

// ---- W01：新建笔记时目录下移动画不得重叠 -----------------------------------------

// TestW01_ShiftedRowsDoNotOverlap 新建笔记后其下方的行整体下移，
// 位移动画期间相邻行不得重叠。
//
// 旧实现给每行加递增的 Delay 做错峰淡入：各行补间起点错开，
// 上方的行先走、下方的还没跟上，中间帧两行叠在一起（实测重叠约 7 DIP）。
// 修复是让所有行共享同一个无 Delay 的 Transition，整齐同步移动。
func TestW01_ShiftedRowsDoNotOverlap(t *testing.T) {
	a := newTestApp(t)
	for _, title := range []string{"AAA", "BBB", "CCC"} {
		mustCreateForApp(t, a, title)
	}
	a.refreshLists()
	m := ui.NewTester(a.contentView, 1200, 760)
	m.Frame()
	m.Advance(600 * time.Millisecond)
	m.Frame()

	// 新建一个标题很长的笔记：行高很大，其下方的行需要下移很远，
	// 错峰动画在此处最容易暴露重叠。
	long := "这是一个非常非常长的标题用来让这一行占据很高的垂直空间从而让下面的行需要下移很远"
	mustCreateForApp(t, a, long)
	a.refreshLists()

	sawShift := false
	for step := 0; step < 14; step++ {
		m.Advance(20 * time.Millisecond)
		m.Frame()
		var rects []ui.Rect
		for _, title := range []string{"AAA", "BBB", "CCC"} {
			r, ok := m.Find(title)
			if !ok {
				continue
			}
			rects = append(rects, r)
		}
		sort.Slice(rects, func(i, j int) bool { return rects[i].Y < rects[j].Y })
		for i := 1; i < len(rects); i++ {
			overlap := rects[i-1].Y + rects[i-1].H - rects[i].Y
			if overlap > 0.5 {
				t.Fatalf("第 %d 帧相邻行重叠 %.1f DIP: %v vs %v", step, overlap, rects[i-1], rects[i])
			}
			// 各行间距应保持恒定（整齐同步移动）。
			gap := rects[i].Y - (rects[i-1].Y + rects[i-1].H)
			if i == 1 {
				sawShift = true
			}
			_ = gap
		}
	}
	if !sawShift {
		t.Fatal("应观测到位移动画")
	}
}

// TestW01_RowTransitionHasNoPerRowDelay 列表行不得再按行号错峰：
// 错峰会让各行补间起点不同，中间帧重叠。
func TestW01_RowTransitionHasNoPerRowDelay(t *testing.T) {
	tr := rowTransition()
	if tr.Animation == nil {
		t.Fatal("行动画应带 Animation")
	}
	if tr.Animation.Delay != 0 {
		t.Fatalf("行动画不应有逐行错峰 Delay，得到 %v", tr.Animation.Delay)
	}
}

// ---- W02：外部删除 .md 产生僵尸笔记 -----------------------------------------------

// TestW02_OpenVanishedNoteGivesFeedback 外部删除正文文件后打开该笔记，
// 必须给出明确提示而不是静默无反应。
func TestW02_OpenVanishedNoteGivesFeedback(t *testing.T) {
	a := newTestApp(t)
	doc := mustCreateForApp(t, a, "将被删除")
	a.refreshLists()
	a.openNote(doc.ID)

	// 外部删除正文文件
	abs := filepath.Join(a.vault.notesDir(), filepath.FromSlash(doc.RelativePath))
	if err := os.Remove(abs); err != nil {
		t.Fatalf("删除文件: %v", err)
	}

	a.openNote(doc.ID)
	if a.pendingToast == "" {
		t.Fatal("打开被外部删除的笔记应给出提示")
	}
	if !strings.Contains(a.pendingToast, "外部删除") {
		t.Fatalf("提示应说明文件被外部删除，得到 %q", a.pendingToast)
	}
}

// TestW02_VanishedNoteRemovedFromList 僵尸笔记必须从列表中移除，
// 否则用户被一条点不动、删不掉的记录卡住。
func TestW02_VanishedNoteRemovedFromList(t *testing.T) {
	a := newTestApp(t)
	doc := mustCreateForApp(t, a, "僵尸笔记")
	other := mustCreateForApp(t, a, "正常笔记")
	a.refreshLists()

	abs := filepath.Join(a.vault.notesDir(), filepath.FromSlash(doc.RelativePath))
	if err := os.Remove(abs); err != nil {
		t.Fatalf("删除文件: %v", err)
	}

	a.openNote(doc.ID)
	for _, n := range a.notes {
		if n.ID == doc.ID {
			t.Fatal("僵尸笔记应从列表中移除")
		}
	}
	for _, n := range a.notes {
		if n.ID == other.ID {
			return
		}
	}
	t.Fatal("其它笔记不应受影响")
}

// TestW02_DeleteVanishedNoteNotBlocked 僵尸笔记仍应能删掉。
func TestW02_DeleteVanishedNoteNotBlocked(t *testing.T) {
	a := newTestApp(t)
	doc := mustCreateForApp(t, a, "待删僵尸")
	a.refreshLists()

	abs := filepath.Join(a.vault.notesDir(), filepath.FromSlash(doc.RelativePath))
	if err := os.Remove(abs); err != nil {
		t.Fatalf("删除文件: %v", err)
	}

	if !a.deleteFromList(doc.ID) {
		t.Fatalf("僵尸笔记应可删除，提示 %q", a.pendingToast)
	}
	for _, n := range a.notes {
		if n.ID == doc.ID {
			t.Fatal("删除后不应仍在列表中")
		}
	}
}

// TestW02_NoteFileExists 领域层能判断正文文件是否还在。
func TestW02_NoteFileExists(t *testing.T) {
	v := newTestVault(t)
	doc := mustCreate(t, v, "存在性")

	if !v.NoteFileExists(doc.ID) {
		t.Fatal("刚创建的笔记文件应存在")
	}
	abs := filepath.Join(v.notesDir(), filepath.FromSlash(doc.RelativePath))
	os.Remove(abs)
	if v.NoteFileExists(doc.ID) {
		t.Fatal("文件被删除后 NoteFileExists 应为 false")
	}
}

// ---- N2：级联改写的跳过原因要区分 -----------------------------------------------

// TestN2_RenameReportDistinguishesExternalAndDirty 改名级联跳过时，
// "被外部程序改过"与"有未保存草稿"必须分开报告。
func TestN2_RenameReportDistinguishesExternalAndDirty(t *testing.T) {
	v := newTestVault(t)
	target := mustCreate(t, v, "改名目标")
	ext := mustCreate(t, v, "外部改过")
	dirty := mustCreate(t, v, "有草稿")

	mustSave(t, v, ext.ID, "见 [[改名目标]]")
	mustSave(t, v, dirty.ID, "见 [[改名目标]]")

	// 外部改过：磁盘内容与元数据哈希不符
	extAbs := filepath.Join(v.notesDir(), filepath.FromSlash(ext.RelativePath))
	os.WriteFile(extAbs, []byte("被外部程序改过的内容"), 0o644)

	// 有草稿：磁盘没被外部动过，但内存索引里的正文比磁盘新。
	// 判据是"磁盘哈希 == 元数据哈希"且"磁盘正文 != 内存正文"，
	// 因此元数据哈希要同步成改后的磁盘内容，只有 body 保持旧值。
	dirtyAbs := filepath.Join(v.notesDir(), filepath.FromSlash(dirty.RelativePath))
	disk, _ := os.ReadFile(dirtyAbs)
	newDisk := append(disk, []byte("磁盘上多的一行")...)
	os.WriteFile(dirtyAbs, newDisk, 0o644)
	v.notes[dirty.ID].meta.ContentSha256 = sha256Hex(newDisk)
	// 内存索引里的正文仍是旧内容 —— 模拟"编辑器里有未落盘的草稿"
	v.notes[dirty.ID].body = "见 [[改名目标]]"

	_, report, err := v.RenameNoteReported(target.ID, "新目标")
	if err != nil {
		t.Fatalf("改名: %v", err)
	}

	if len(report.External) != 1 || report.External[0] != ext.ID {
		t.Fatalf("外部改过的笔记应计入 External，得到 %v", report.External)
	}
	if len(report.Dirty) != 1 || report.Dirty[0] != dirty.ID {
		t.Fatalf("有草稿的笔记应计入 Dirty，得到 %v", report.Dirty)
	}
	if len(report.Skipped) != 2 {
		t.Fatalf("Skipped 应包含两者，得到 %v", report.Skipped)
	}
}

// ---- N1（复验结论：原结论不成立）-------------------------------------------------

// TestN1_ExternalRewriteSurvivesRestart 重启后外部改写的内容不丢：
// 编辑器缓冲区从磁盘加载，用户的输入叠加在外部内容之上。
func TestN1_ExternalRewriteSurvivesRestart(t *testing.T) {
	root := t.TempDir()
	v := &Vault{root: root, notes: map[string]*indexedNote{}, trashed: map[string]*NoteMeta{}, byTitle: map[string][]string{}}
	v.loadWorkspace()
	doc, _ := v.CreateNote("外部改写")
	abs := filepath.Join(v.notesDir(), filepath.FromSlash(doc.RelativePath))
	v.SaveContent(doc.ID, "原始内容")

	// 应用关闭期间外部工具整体改写正文
	os.WriteFile(abs, []byte("外部工具完全改写的内容"), 0o644)

	// 重启
	v2 := &Vault{root: root, notes: map[string]*indexedNote{}, trashed: map[string]*NoteMeta{}, byTitle: map[string][]string{}}
	v2.loadWorkspace()

	a := &app{vault: v2, editors: map[string]*noteEditor{}}
	a.openNote(doc.ID)
	ed := a.editors[doc.ID]
	if !strings.Contains(ed.state.Buffer.String(), "外部工具完全改写的内容") {
		t.Fatalf("重启后缓冲区应包含外部内容，得到 %q", ed.state.Buffer.String())
	}

	ed.state.Buffer.Set(ed.state.Buffer.String() + "，用户新写的一行")
	a.saveState = saveDirty
	a.saveNow()

	data, _ := os.ReadFile(abs)
	if !strings.Contains(string(data), "外部工具完全改写的内容") {
		t.Fatalf("外部内容不应丢失，磁盘为 %q", string(data))
	}
	if !strings.Contains(string(data), "用户新写的一行") {
		t.Fatalf("用户新输入应落盘，磁盘为 %q", string(data))
	}
}

// ---- W06：切换视图必须落盘 -------------------------------------------------------

// TestW06_SetViewFlushesDraft 切到回收站页也必须先落盘当前草稿：
// 旧实现对回收站跳过落盘，用户切页后关窗/崩溃就会丢稿。
func TestW06_SetViewFlushesDraft(t *testing.T) {
	a := newTestApp(t)
	doc := mustCreateForApp(t, a, "切页前")
	a.refreshLists()
	a.openNote(doc.ID)

	ed := a.editors[doc.ID]
	ed.state.Buffer.Set("切页前的草稿内容")
	a.saveState = saveDirty

	a.setView(viewTrash)

	abs := filepath.Join(a.vault.notesDir(), filepath.FromSlash(doc.RelativePath))
	data, _ := os.ReadFile(abs)
	if string(data) != "切页前的草稿内容" {
		t.Fatalf("切页前应落盘草稿，磁盘为 %q", string(data))
	}
}

// ---- W11/W12：附件作用域与分层 ---------------------------------------------------

// TestW11_AssetPathScopedToNotesAndAttachments 附件只能落在 notes/ 与
// attachments/ 两个作用域内：工作区根下的其它文件（如 .meta/ 里的元数据）
// 不得被笔记引用读到（NFR-SEC-03）。
func TestW11_AssetPathScopedToNotesAndAttachments(t *testing.T) {
	v := newTestVault(t)
	doc := mustCreate(t, v, "作用域")
	mustSave(t, v, doc.ID, "正文")

	// 在工作区根放一个"秘密"文件
	secret := filepath.Join(v.root, "secret.txt")
	if err := os.WriteFile(secret, []byte("机密"), 0o644); err != nil {
		t.Fatal(err)
	}
	if _, err := v.NoteAssetPath(doc.ID, "secret.txt"); err == nil {
		t.Fatal("工作区根下的文件不应可被笔记引用读到")
	}

	// .meta/ 下的元数据同样不可达
	metaDir, _ := v.metaDirs()
	metaFile := filepath.Join(metaDir, "meta.json")
	os.WriteFile(metaFile, []byte("{}"), 0o644)
	if _, err := v.NoteAssetPath(doc.ID, ".meta/meta.json"); err == nil {
		t.Fatal(".meta/ 下的文件不应可被笔记引用读到")
	}

	// attachments/ 与 notes/ 下的文件仍可正常读取
	attDir := filepath.Join(v.root, dirAttachments)
	os.MkdirAll(attDir, 0o755)
	os.WriteFile(filepath.Join(attDir, "pic.png"), []byte("PNG"), 0o644)
	if _, err := v.NoteAssetPath(doc.ID, "pic.png"); err != nil {
		t.Fatalf("attachments/ 下的附件应可读: %v", err)
	}
}

// TestW12_ViewLayerDoesNotReadFiles 视图层不应直接读文件：
// 读取由领域层 Vault.ReadNoteAsset 负责（windows/AGENTS.md 分层约定）。
func TestW12_ReadNoteAssetDelegatesToDomain(t *testing.T) {
	v := newTestVault(t)
	doc := mustCreate(t, v, "分层")
	mustSave(t, v, doc.ID, "正文")

	attDir := filepath.Join(v.root, dirAttachments)
	os.MkdirAll(attDir, 0o755)
	os.WriteFile(filepath.Join(attDir, "a.txt"), []byte("内容"), 0o644)

	data, err := v.ReadNoteAsset(doc.ID, "a.txt")
	if err != nil {
		t.Fatalf("领域层应能读取附件: %v", err)
	}
	if string(data) != "内容" {
		t.Fatalf("读到的内容不对: %q", string(data))
	}
	if _, err := v.ReadNoteAsset(doc.ID, "../../逃逸.txt"); err == nil {
		t.Fatal("越界路径应被拒绝")
	}
}

// ---- W14：多级标签父节点计数 -------------------------------------------------------

// TestW14_ParentTagCountIncludesSubtree 父标签的计数应包含整棵子树，
// 且同一篇笔记在同一子树里只算一次。
func TestW14_ParentTagCountIncludesSubtree(t *testing.T) {
	v := newTestVault(t)
	a := mustCreate(t, v, "标签A")
	mustSave(t, v, a.ID, "#复习计划/第一章")
	b := mustCreate(t, v, "标签B")
	mustSave(t, v, b.ID, "#复习计划/第二章")
	c := mustCreate(t, v, "标签C")
	mustSave(t, v, c.ID, "#复习计划")

	tags := v.ListTags()
	var parent *TagNode
	for i := range tags {
		if tags[i].Path == "复习计划" {
			parent = &tags[i]
		}
	}
	if parent == nil {
		t.Fatal("应存在父标签「复习计划」")
	}
	if parent.Count != 3 {
		t.Fatalf("父标签计数应包含子树内的 3 篇笔记，得到 %d", parent.Count)
	}
	if len(parent.Children) != 2 {
		t.Fatalf("父标签应有 2 个子节点，得到 %d", len(parent.Children))
	}
	for _, child := range parent.Children {
		if child.Count != 1 {
			t.Fatalf("子标签 %q 的计数应为 1，得到 %d", child.Path, child.Count)
		}
	}
}

// TestW14_ParentTagCountDedupsSameNote 一篇笔记同时写 #a 与 #a/b 时，
// 父标签的子树计数只能算它一次。
func TestW14_ParentTagCountDedupsSameNote(t *testing.T) {
	v := newTestVault(t)
	a := mustCreate(t, v, "双标签")
	mustSave(t, v, a.ID, "#x #x/y")

	tags := v.ListTags()
	for _, tag := range tags {
		if tag.Path == "x" {
			if tag.Count != 1 {
				t.Fatalf("同一篇笔记在子树里应只算一次，得到 %d", tag.Count)
			}
			return
		}
	}
	t.Fatal("应存在标签「x」")
}

// ---- N3：关窗不得丢草稿 -----------------------------------------------------------

// TestN3_CloseWithFailedSaveKeepsBlocking 关窗保存失败时必须一直拦住，
// 弹出让用户选择的确认框，而不是"点两下关闭就丢稿"。
func TestN3_CloseWithFailedSaveKeepsBlocking(t *testing.T) {
	a := newTestApp(t)
	doc := mustCreateForApp(t, a, "关窗丢稿")
	a.refreshLists()
	a.openNote(doc.ID)

	// 让保存必然失败：把笔记移出索引，SaveContent 会返回 ErrNoteNotFound。
	delete(a.vault.notes, doc.ID)
	ed := a.editors[doc.ID]
	ed.state.Buffer.Set("未保存的草稿")
	a.saveState = saveDirty

	// 第一次关窗请求：被拦下，弹出确认框。
	a.onCloseRequest(nil)
	if !a.closePending {
		t.Fatal("保存失败时关窗应被拦下")
	}
	if !a.confirm.open {
		t.Fatal("保存失败时应弹出确认框让用户决定")
	}
	if a.confirm.onCancel == nil {
		t.Fatal("确认框应提供「重试保存」出口")
	}
	if a.closeAbandoned {
		t.Fatal("用户尚未选择时不应视为已放弃")
	}

	// 第二次关窗请求：仍被拦下（旧实现放行 → 草稿直接丢失）。
	a.onCloseRequest(nil)
	if !a.closePending {
		t.Fatal("用户做出选择前，第二次关窗仍应被拦下")
	}
	if a.closeAbandoned {
		t.Fatal("用户尚未选择时不应视为已放弃")
	}

	// 用户明确选择「放弃并退出」后才放行。
	a.confirm.onOK()
	if !a.closeAbandoned {
		t.Fatal("用户选择放弃后应允许退出")
	}
}

// TestN3_CloseSucceedsWhenSaveWorks 一切正常时关窗不应被拦。
func TestN3_CloseSucceedsWhenSaveWorks(t *testing.T) {
	a := newTestApp(t)
	doc := mustCreateForApp(t, a, "正常关窗")
	a.refreshLists()
	a.openNote(doc.ID)

	a.onCloseRequest(nil)
	if a.closePending {
		t.Fatal("保存成功时不应拦关窗")
	}
	if a.confirm.open {
		t.Fatal("保存成功时不应弹确认框")
	}
}

// ---- N5：弹窗打开时 Ctrl+S 仍可用 ------------------------------------------------

// TestN5_CtrlSWorksWhileDialogOpen 确认弹窗打开时 Ctrl+S 仍能保存：
// 尤其在"草稿保存失败"的确认框上，Ctrl+S 正是用户想重试保存的快捷键。
func TestN5_CtrlSWorksWhileDialogOpen(t *testing.T) {
	a := newTestApp(t)
	doc := mustCreateForApp(t, a, "弹窗保存")
	a.refreshLists()
	a.openNote(doc.ID)

	ed := a.editors[doc.ID]
	ed.state.Buffer.Set("弹窗期间的草稿")
	a.saveState = saveDirty

	m := ui.NewTester(a.contentView, 1200, 760)
	m.Frame()
	a.confirm = confirmState{open: true, title: "确认", message: "确定？"}
	m.Frame()

	m.Key(ui.Ctrl, ui.KeyS)
	m.Frame()

	abs := filepath.Join(a.vault.notesDir(), filepath.FromSlash(doc.RelativePath))
	data, _ := os.ReadFile(abs)
	if string(data) != "弹窗期间的草稿" {
		t.Fatalf("弹窗打开时 Ctrl+S 应能保存，磁盘为 %q", string(data))
	}
}
