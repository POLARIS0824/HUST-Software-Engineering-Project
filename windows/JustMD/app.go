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
	query       string
	hits        []SearchHit
	searching   bool
	searchSeq   int         // 检索序号：丢弃过期的后台检索结果
	searchTimer *time.Timer // 输入防抖

	// 当前笔记与编辑器。
	currentID    string
	currentTitle string
	currentRel   string
	currentStats TextStats
	// titleEdit 是标题输入框的持久编辑缓冲。不能绑定到每帧重建的局部
	// 变量：删空后改名会被 ErrTitleEmpty 拒绝，旧字符随绑定值回弹，
	// 表现为"最后一个字符删不掉"。空值是合法草稿：不改名、不回弹，
	// 提交（Enter）或换篇时回填真实标题。
	titleEdit    string
	focusTitle   bool // 新建笔记后把焦点交给标题框，键入即命名
	editors      map[string]*noteEditor
	mode         string // "edit" / "read"
	readerSrc    string // 阅读页 Markdown 快照（进入阅读模式时生成，避免逐帧重算）
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
// 切换前先落盘上一篇的未保存草稿，避免防抖定时器指向错误笔记而丢稿。
func (a *app) openNote(id string) {
	if a.currentID != "" && a.currentID != id {
		if !a.flushSaveNote(a.currentID) {
			a.pendingToast = "上一篇笔记保存失败，草稿仍保留在编辑器中"
		}
	}
	doc, err := a.vault.OpenNote(id)
	if err != nil || doc.Trashed {
		return
	}
	a.currentID = doc.ID
	a.currentTitle = doc.Title
	a.currentRel = doc.RelativePath
	a.currentStats = doc.Stats
	a.titleEdit = doc.Title
	a.focusTitle = false
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
	// 新笔记的标题框以空草稿 + 占位符呈现：键入即替换，
	// 不会把字接在"未命名笔记"后面。
	a.titleEdit = ""
	a.focusTitle = true
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
	if doc.Title != title {
		// 冲突加了后缀等情形：输入框如实显示最终标题。
		a.titleEdit = doc.Title
	}
	if a.mode == "read" {
		// 级联改写可能已变更本篇正文，刷新阅读快照。
		a.readerSrc = a.vault.RenderReaderSource(a.currentID)
	}
	a.refreshLists()
}

// ---- 自动保存（FR-EDIT-04/05/06、BR-04/05）------------------------------------

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
// 落盘 I/O 在后台 goroutine 执行（评审 #8），无窗口（测试）时同步执行。
func (a *app) saveNow() {
	a.saveNote(a.currentID, false)
}

// saveNowForce 强制保存当前笔记（跳过外部修改检测），供"保存失败"徽章重试（评审 #7）。
func (a *app) saveNowForce() {
	a.saveNote(a.currentID, true)
}

// saveNote 保存指定笔记的编辑器内容。force 时跳过外部修改检测强制覆盖。
// 返回 false 表示无可保存内容或该笔记已在保存中。
func (a *app) saveNote(id string, force bool) bool {
	if id == "" {
		return false
	}
	ed := a.editors[id]
	if ed == nil {
		return false
	}
	if ed.state.Buffer.Version() == ed.saved {
		if a.currentID == id {
			a.saveState = saveSaved
		}
		return true
	}
	if a.currentID == id && a.saveState == saveSaving {
		return false
	}
	snap := ed.state.Buffer.Snapshot()
	if a.currentID == id {
		a.saveState = saveSaving
	}
	run := func() {
		var receipt SaveReceipt
		var err error
		if force {
			receipt, err = a.vault.ForceSaveContent(id, snap.String())
		} else {
			receipt, err = a.vault.SaveContent(id, snap.String())
		}
		a.onUIThread(func() { a.applySaveResult(id, snap.Version(), receipt, err) })
	}
	if a.win == nil {
		run()
	} else {
		go run()
	}
	return true
}

// flushSaveNote 同步保存指定笔记的未落盘草稿，返回是否成功（无草稿也算成功）。
// 用于换篇、删除、导出/重建/换库前的一致性保障（评审 #2/#3）。
func (a *app) flushSaveNote(id string) bool {
	if id == "" {
		return true
	}
	ed := a.editors[id]
	if ed == nil || ed.state.Buffer.Version() == ed.saved {
		return true
	}
	snap := ed.state.Buffer.Snapshot()
	receipt, err := a.vault.SaveContent(id, snap.String())
	a.applySaveResult(id, snap.Version(), receipt, err)
	return err == nil
}

// flushSave 在换篇等时机等待最新内容提交（FR-EDIT-05）。
func (a *app) flushSave() {
	if a.saveTimer != nil {
		a.saveTimer.Stop()
		a.saveTimer = nil
	}
	a.flushSaveNote(a.currentID)
}

// flushSaveAll 同步保存全部编辑器草稿；任一失败返回 false。
func (a *app) flushSaveAll() bool {
	if a.saveTimer != nil {
		a.saveTimer.Stop()
		a.saveTimer = nil
	}
	ok := true
	for id := range a.editors {
		if !a.flushSaveNote(id) {
			ok = false
		}
	}
	return ok
}

// flushAllOrToast 保存全部草稿供破坏性/全局操作前置调用；失败提示并返回 false（评审 #3）。
func (a *app) flushAllOrToast() bool {
	if a.flushSaveAll() {
		return true
	}
	a.pendingToast = "保存失败，已中止当前操作"
	return false
}

// applySaveResult 把落盘回执应用回界面状态（主线程）。
func (a *app) applySaveResult(id string, ver uint64, receipt SaveReceipt, err error) {
	ed := a.editors[id]
	if ed == nil {
		return
	}
	if err != nil {
		if a.currentID == id {
			a.saveState = saveError
		}
		if err == ErrExternalEdit {
			a.pendingToast = "笔记已被其他程序修改：点击「保存失败」强制覆盖"
		}
		return
	}
	if ver >= ed.saved {
		ed.saved = ver
	}
	// 保存期间又有新输入时不得 MarkSaved（会把新输入误标为已保存）。
	if ed.state.Buffer.Version() == ver {
		ed.state.Buffer.MarkSaved()
	}
	if a.currentID != id {
		return
	}
	a.saveState = saveSaved
	a.currentStats = receipt.Stats
	a.statsVersion = ver
	a.refreshLists()
	a.loadBacklinks(id)
	// 保存期间又输入了新内容：继续安排下一次保存。
	if ed.state.Buffer.Version() != ed.saved {
		a.saveState = saveDirty
		a.scheduleSave()
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

// scheduleSearch 输入防抖 + 后台检索（评审 #8：避免每次按键同步全库检索）。
// 结果按序号丢弃过期数据；a.searching 驱动列表区的 Spinner。
func (a *app) scheduleSearch() {
	query := a.query
	a.searchSeq++
	seq := a.searchSeq
	if a.searchTimer != nil {
		a.searchTimer.Stop()
		a.searchTimer = nil
	}
	if strings.TrimSpace(query) == "" {
		a.hits = nil
		a.searching = false
		return
	}
	a.searching = true
	a.searchTimer = time.AfterFunc(250*time.Millisecond, func() {
		hits := a.vault.Search(query) // Vault 自带锁，可在后台线程调用
		a.onUIThread(func() {
			if seq != a.searchSeq {
				return // 已有更新的检索，丢弃过期结果
			}
			a.searchTimer = nil
			a.hits = hits
			a.searching = false
		})
	})
}

// deleteFromList 先保存草稿并确认成功，再移入回收站（评审 #2：避免丢稿）。
// 返回是否实际执行了删除。
func (a *app) deleteFromList(id string) bool {
	if !a.flushSaveNote(id) {
		a.pendingToast = "保存失败，已中止删除"
		return false
	}
	if _, err := a.vault.TrashNote(id); err != nil {
		return false
	}
	if a.currentID == id {
		a.currentID = ""
		a.backlinks = nil
	}
	delete(a.editors, id)
	a.refreshLists()
	return true
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

// dropFilesFlow 处理拖放导入（评审 #6）：.md 入库、其余进 attachments/。
// vault 侧可从后台线程调用；界面状态经 onUIThread 回写。
func (a *app) dropFilesFlow(paths []string) {
	a.onUIThread(func() { a.busyStart("正在导入拖放的文件…") })
	results, err := a.vault.ImportDroppedFiles(paths)
	a.onUIThread(func() {
		a.busyEnd()
		if err != nil {
			a.pendingToast = "拖放导入失败"
			return
		}
		notes, atts, failed := 0, 0, 0
		for _, r := range results {
			switch r.Status {
			case "imported", "renamed":
				notes++
			case "copied":
				atts++
			default:
				failed++
			}
		}
		a.refreshLists()
		parts := []string{}
		if notes > 0 {
			parts = append(parts, itoa(notes)+" 篇笔记")
		}
		if atts > 0 {
			parts = append(parts, itoa(atts)+" 个附件")
		}
		if failed > 0 {
			parts = append(parts, itoa(failed)+" 个失败")
		}
		if len(parts) == 0 {
			return
		}
		a.pendingToast = "拖放导入完成：" + strings.Join(parts, "，")
	})
}
