package main

import (
	"strconv"
	"strings"
	"time"

	"github.com/egoist/mygo"
	"github.com/egoist/mygo/ui/editor"
	"github.com/oneo-me/onevra/source/shells/sidebar"
)

// 保存状态（BR-05：只有落盘回执才算"已保存"）。
const (
	saveSaved  = "saved"
	saveDirty  = "dirty"
	saveSaving = "saving"
	saveError  = "error"
)

// 视图路由。
const (
	viewNotes    = "notes"
	viewTrash    = "trash"
	viewSettings = "settings"
)

// noteEditor 是一篇笔记的会话内编辑器状态：跨篇切换保留各自的
// 撤销/重做历史（FR-EDIT-08），磁盘文件仍是持久层事实源（BR-01）。
type noteEditor struct {
	state *editor.State
	saved uint64 // 已落盘的 Buffer.Version
}

// app 持有全部界面状态；视图方法每帧从它重建界面。
// 所有方法运行在主线程（视图/Window.Update/菜单回调），无需额外加锁；
// Vault 自身以互斥锁保护，供后台导入导出流程并发调用。
type app struct {
	vault *Vault
	win   *mygo.Window

	// 路由与列表缓存。
	view      string // viewNotes / viewTrash / viewSettings
	notes     []NoteSummary
	trash     []NoteSummary
	tags      []TagNode
	sort      string
	tagFilter string // 选中标签的小写全路径，"" 为不过滤

	// 搜索。
	query     string
	hits      []SearchHit
	searching bool

	// 当前笔记与编辑器。
	currentID    string
	currentTitle string
	currentRel   string
	currentStats TextStats
	editors      map[string]*noteEditor
	mode         string // "edit" / "read"
	saveState    string
	saveTimer    *time.Timer
	statsVersion uint64

	// 反链面板。
	backlinks     []BacklinkItem
	showBacklinks bool

	// 对话框与流程状态。
	confirm          confirmState
	importPlan       *ImportPlan
	importSkip       bool // 导入对话框的取消标记
	conflictSel      map[string]string
	importDialogOpen bool
	busy             string

	// pendingToast 在下一帧由视图弹出（Context.Toast 只能在视图构建期调用）。
	pendingToast string
}

// confirmState 是应用内确认弹窗（components.Dialog）的状态。
type confirmState struct {
	open    bool
	title   string
	message string
	danger  bool
	onOK    func()
}

func newApp(vault *Vault) *app {
	return &app{
		vault:     vault,
		view:      viewNotes,
		sort:      "updated_desc",
		editors:   map[string]*noteEditor{},
		mode:      "edit",
		saveState: saveSaved,
	}
}

// attach 记录窗口引用。视图与动作都经 onUIThread 回主线程。
func (a *app) attach(shell *sidebar.Shell) {
	a.win = shell.Window()
}

// onUIThread 在主线程运行 fn（后台流程回调用）。
func (a *app) onUIThread(fn func()) {
	if a.win == nil {
		fn()
		return
	}
	a.win.Update(func() { fn() })
}

// bootstrap 是窗口就绪后的首次装载。
func (a *app) bootstrap() {
	a.refreshLists()
	if a.vault.GetWorkspace().NoteCount > 0 {
		if list := a.vault.ListNotes(a.sort); len(list) > 0 {
			a.openNote(list[0].ID)
		}
	}
}

// refreshLists 重新拉取列表、标签与回收站缓存。
func (a *app) refreshLists() {
	a.notes = a.vault.ListNotes(a.sort)
	a.trash = a.vault.ListTrash()
	a.tags = a.vault.ListTags()
}

// visibleNotes 返回按标签过滤后的列表。
func (a *app) visibleNotes() []NoteSummary {
	if a.tagFilter == "" {
		return a.notes
	}
	out := []NoteSummary{}
	for _, n := range a.notes {
		for _, t := range n.Tags {
			if strings.HasPrefix(strings.ToLower(t), a.tagFilter) {
				out = append(out, n)
				break
			}
		}
	}
	return out
}

// ---- 笔记动作 ----------------------------------------------------------------

// openNote 打开笔记（FR-NOTE-03），会话内保留其编辑器与撤销历史。
func (a *app) openNote(id string) {
	doc, err := a.vault.OpenNote(id)
	if err != nil || doc.Trashed {
		return
	}
	a.currentID = doc.ID
	a.currentTitle = doc.Title
	a.currentRel = doc.RelativePath
	a.currentStats = doc.Stats
	a.statsVersion = 0
	a.mode = "edit"
	a.saveState = saveSaved
	a.loadBacklinks(id)
	if _, ok := a.editors[id]; !ok {
		buf := editor.NewBuffer(doc.Markdown)
		if lang := editor.LanguageFor(doc.RelativePath); lang != nil {
			buf.SetLanguage(lang)
		}
		st := &editor.State{
			Buffer:     buf,
			SoftWrap:   true,
			WrapColumn: 80,
		}
		a.editors[id] = &noteEditor{state: st, saved: buf.Version()}
	}
}

// currentEditor 返回当前笔记的编辑器状态；无打开笔记时为 nil。
func (a *app) currentEditor() *noteEditor {
	if a.currentID == "" {
		return nil
	}
	return a.editors[a.currentID]
}

// createNote 新建空白笔记并进入编辑（FR-NOTE-01）。
func (a *app) createNote() {
	if !a.vault.GetWorkspace().Ready {
		a.view = viewSettings
		a.refreshLists()
		return
	}
	doc, err := a.vault.CreateNote("")
	if err != nil {
		return
	}
	a.refreshLists()
	a.openNote(doc.ID)
	a.view = viewNotes
}

// renameCurrent 重命名当前笔记（FR-NOTE-04，级联文件与引用）。
func (a *app) renameCurrent(title string) {
	if a.currentID == "" {
		return
	}
	doc, err := a.vault.RenameNote(a.currentID, title)
	if err != nil {
		return
	}
	a.currentTitle = doc.Title
	a.currentRel = doc.RelativePath
	a.refreshLists()
}

// ---- 自动保存（FR-EDIT-04/05/06、BR-04/05）------------------------------------

// currentDirty 判断当前编辑器是否有未落盘修改。
func (a *app) currentDirty() bool {
	ed := a.currentEditor()
	if ed == nil {
		return false
	}
	return ed.state.Buffer.Version() != ed.saved
}

// scheduleSave 为未落盘的修改安排 2 秒防抖保存（每次新输入重置）。
func (a *app) scheduleSave() {
	if a.saveTimer != nil || a.saveState == saveSaving || a.currentID == "" {
		return
	}
	if st := a.saveState; st != saveDirty && st != saveError {
		return
	}
	a.saveTimer = time.AfterFunc(2*time.Second, func() {
		a.onUIThread(func() {
			a.saveTimer = nil
			a.saveNow()
		})
	})
}

// saveNow 立即保存当前笔记；成功后更新回执状态与派生视图（BR-05）。
func (a *app) saveNow() {
	ed := a.currentEditor()
	if ed == nil || a.saveState == saveSaving {
		return
	}
	if ed.state.Buffer.Version() == ed.saved {
		a.saveState = saveSaved
		return
	}
	a.saveState = saveSaving
	receipt, err := a.vault.SaveContent(a.currentID, ed.state.Buffer.String())
	if err != nil {
		a.saveState = saveError // FR-EDIT-06：失败保留编辑态，可重试
		return
	}
	ed.saved = ed.state.Buffer.Version()
	ed.state.Buffer.MarkSaved()
	a.saveState = saveSaved
	a.currentStats = receipt.Stats
	a.statsVersion = ed.state.Buffer.Version()
	a.refreshLists()
	a.loadBacklinks(a.currentID)
	// 保存期间又输入了新内容：继续安排下一次保存。
	if ed.state.Buffer.Version() != ed.saved {
		a.saveState = saveDirty
		a.scheduleSave()
	}
}

// flushSave 在换篇等时机等待最新内容提交（FR-EDIT-05）。
func (a *app) flushSave() {
	if a.saveTimer != nil {
		a.saveTimer.Stop()
		a.saveTimer = nil
	}
	if a.currentDirty() || a.saveState == saveError {
		a.saveNow()
	}
}

// ---- 导航与视图动作 -------------------------------------------------------------

func (a *app) loadBacklinks(id string) {
	a.backlinks = a.vault.GetBacklinks(id)
}

func (a *app) setView(v string) {
	if v != viewTrash {
		a.flushSave()
	}
	a.view = v
	a.refreshLists()
}

func (a *app) setSort(option string) {
	a.sort = option
	a.refreshLists()
}

func (a *app) searchNow() {
	if strings.TrimSpace(a.query) == "" {
		a.hits = nil
		a.searching = false
		return
	}
	a.hits = a.vault.Search(a.query)
	a.searching = false
}

func (a *app) deleteFromList(id string) {
	a.vault.TrashNote(id)
	if a.currentID == id {
		a.flushSave()
		a.currentID = ""
		a.backlinks = nil
	}
	a.refreshLists()
}

// ---- 后台流程（导入/导出/重建/换库）---------------------------------------------

func (a *app) busyStart(msg string) { a.busy = msg }

func (a *app) busyEnd() { a.busy = "" }

// importFlow 打开系统多选对话框并准备导入计划；有冲突时进入逐项决策弹窗。
// 阻塞式对话框在后台 goroutine 中调用。
func (a *app) importFlow() {
	a.onUIThread(func() { a.busyStart("正在准备导入…") })
	plan, err := a.vault.PrepareImport(a.win)
	a.onUIThread(func() {
		a.busyEnd()
		if err != nil {
			return
		}
		if len(plan.Files) == 0 {
			return
		}
		hasConflict := false
		for _, f := range plan.Files {
			if f.Status == "conflict" {
				hasConflict = true
			}
		}
		if !hasConflict {
			// 无冲突直接执行，不经弹窗。
			a.applyImport(plan, nil)
			return
		}
		a.importPlan = &plan
		a.importDialogOpen = true
		a.conflictSel = map[string]string{}
		for _, f := range plan.Files {
			if f.Status == "conflict" {
				a.conflictSel[f.SourcePath] = "rename"
			}
		}
		a.refreshLists()
	})
}

// applyImport 执行导入计划并在完成后刷新（FR-DATA-01、AC-DATA-02）。
func (a *app) applyImport(plan ImportPlan, actions map[string]string) {
	a.onUIThread(func() {
		a.importPlan = nil
		a.importDialogOpen = false
		a.busyStart("正在导入…")
	})
	go func() {
		results, err := a.vault.ApplyImport(plan, actions)
		a.onUIThread(func() {
			a.busyEnd()
			if err != nil {
				return
			}
			a.refreshLists()
			ok := 0
			for _, r := range results {
				if r.Status == "imported" || r.Status == "renamed" || r.Status == "overwritten" {
					ok++
				}
			}
			a.pendingToast = "导入完成：" + strconv.Itoa(ok) + " 篇"
		})
	}()
}

// exportVaultFlow 打包完整知识库（FR-DATA-03）。
func (a *app) exportVaultFlow() {
	path, err := a.vault.ExportVault(a.win)
	if err != nil || path == "" {
		return
	}
	a.onUIThread(func() {
		a.pendingToast = "已导出到 " + path
	})
}

// rebuildFlow 从 Markdown 事实源重建索引（FR-DATA-05）。
func (a *app) rebuildFlow() {
	result, err := a.vault.RebuildIndex()
	a.onUIThread(func() {
		if err != nil {
			return
		}
		a.editors = map[string]*noteEditor{}
		a.currentID = ""
		a.refreshLists()
		a.pendingToast = "索引重建完成：" + strconv.Itoa(result.NoteCount) + " 篇，耗时 " + strconv.FormatInt(result.DurationMs, 10) + " ms"
	})
}

// chooseWorkspaceFlow 弹出文件夹选择并切换知识库。
func (a *app) chooseWorkspaceFlow() {
	info, err := a.vault.ChooseWorkspace(a.win)
	if err != nil {
		return
	}
	a.onUIThread(func() {
		if info.Ready {
			a.editors = map[string]*noteEditor{}
			a.currentID = ""
			a.view = viewNotes
			a.refreshLists()
			if len(a.notes) > 0 {
				a.openNote(a.notes[0].ID)
			}
		}
	})
}
