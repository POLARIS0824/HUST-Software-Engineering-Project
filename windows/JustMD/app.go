package main

import (
	"strconv"
	"strings"
	"time"

	"github.com/egoist/mygo"
	"github.com/egoist/mygo/ui"
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
	// writing 在异步落盘进行中非 nil，关闭即表示写完。同步 flush 前先等它，
	// 否则"旧快照的异步保存"可能晚于 flush 落盘，把新内容覆盖回旧内容。
	writing chan struct{}
	// saveSeq 是本篇保存请求的序号（评审 W03）：每次发起落盘就 +1，
	// 序号越大表示内容越新。落盘时携带它，领域层据此拒绝迟到的旧快照，
	// 避免异步保存与同步 flush 乱序时静默覆盖新内容。
	saveSeq int64
}

// nextSaveSeq 为本次保存请求分配序号。
func (ed *noteEditor) nextSaveSeq() int64 {
	ed.saveSeq++
	return ed.saveSeq
}

// waitWriting 等待该笔记在飞的异步保存结束（没有则立即返回）。
func (ed *noteEditor) waitWriting() {
	if ed.writing != nil {
		<-ed.writing
		ed.writing = nil
	}
}

// app 持有全部界面状态；视图方法每帧从它重建界面。
// 所有方法运行在主线程（视图/Window.Update/菜单回调），无需额外加锁；
// Vault 自身以互斥锁保护，供后台导入导出流程并发调用。
type app struct {
	vault *Vault
	win   *mygo.Window

	// 路由与列表缓存。
	view         string // viewNotes / viewTrash / viewSettings
	notes        []NoteSummary
	trash        []NoteSummary
	tags         []TagNode
	categories   []CategoryNode // 分类树（notes/ 下的真实目录）
	unfiledCount int            // 未分类（notes/ 根目录）的笔记数
	sort         string
	tagFilter    string // 选中标签的小写全路径，"" 为不过滤
	catFilter    string // 选中分类路径；与 catFiltered 配合，"" 单独表示未分类
	catFiltered  bool

	// 分类弹窗：移动笔记到分类，或从侧栏新建分类（noteID 为空即仅新建）。
	catDialog catDialogState

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
	titleEdit  string
	titleWarn  string // 标题草稿的行内提示（非法字符/同名冲突），空表示无
	focusTitle bool   // 新建笔记后把焦点交给标题框，键入即命名
	editors    map[string]*noteEditor
	mode       string // "edit" / "read"
	readerSrc  string // 阅读页 Markdown 快照（进入阅读模式时生成，避免逐帧重算）
	saveState  string
	saveTimer  *time.Timer
	// saveErrorVersion 记录保存失败时编辑器的缓冲区版本号：用来判断
	// 用户在失败之后是否又继续输入（评审 N3）。版本号变了才重新启用
	// 自动保存，避免失败后原地不动时陷入无限重试。
	saveErrorVersion uint64
	// saveErrorExternal 记录上一次失败是否是"笔记被其它程序修改"：
	// 这类冲突只有用户显式确认强制覆盖才能解决（FR-EDIT-06），
	// 自动重试必然再次失败，只会反复读盘并刷屏提示，不重新启用。
	saveErrorExternal bool
	// closePending 记录"因保存失败正在等待用户决定是否退出"：在用户
	// 做出选择（重试保存 / 放弃并退出）之前，一律拦住关窗，
	// 杜绝"点两下关闭就丢稿"的路径（评审 N3）。
	closePending bool
	// closeAbandoned 记录用户已明确选择"放弃未保存的改动并退出"：
	// 之后的关窗请求不再拦截。
	closeAbandoned bool
	// shred 是正在播放的删除动画（碎纸机）。动画期间笔记仍在库里，
	// 播完才真正移入回收站——见 shred.go 的设计说明。
	shred        *shredAnim
	statsVersion uint64

	// 批量删除状态。
	//
	// batchMode 表示正处于批量删除状态：笔记列表每行标题右侧显示复选框，
	// 列表下方显示「确认」「取消」两个按钮。
	batchMode bool
	// batchPicked 是被勾选的笔记 ID 集合。map 的值无意义，只看键。
	batchPicked map[string]bool

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
	// onCancel 是点「取消」时的回调。为 nil 时「取消」只关闭弹窗。
	// 用于"保存失败请确认是否退出"这类场景：取消 = 重试保存而非放弃。
	onCancel func()
	// okLabel / cancelLabel 覆盖按钮文案（默认「确定」/「取消」）。
	// "保存失败请确认是否退出"需要明确区分"重试保存"与"放弃并退出"。
	okLabel     string
	cancelLabel string
}

// catDialogState 是分类弹窗的状态：一个弹窗两种用途——
// noteID 非空 = 把该笔记移动到分类；noteID 为空 = 只新建分类。
type catDialogState struct {
	open      bool
	noteID    string
	noteTitle string
	target    string // 选中的分类路径；"" 表示未分类（根目录）
	newName   string // 新建分类输入（支持多级 a/b）
}

func newApp(vault *Vault) *app {
	return &app{
		vault:       vault,
		view:        viewNotes,
		sort:        "updated_desc",
		editors:     map[string]*noteEditor{},
		mode:        "edit",
		saveState:   saveSaved,
		batchPicked: map[string]bool{},
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

// refreshLists 重新拉取列表、标签、分类与回收站缓存。
func (a *app) refreshLists() {
	a.notes = a.vault.ListNotes(a.sort)
	a.trash = a.vault.ListTrash()
	a.tags = a.vault.ListTags()
	a.categories = a.vault.ListCategories()
	a.unfiledCount = a.vault.UnfiledNoteCount()
	a.dropMissingTagFilter()
	a.dropMissingCategoryFilter()
}

// dropMissingTagFilter 清掉已不存在的标签过滤：删掉最后一篇带该标签的笔记后，
// 旧实现保留过滤条件，列表显示"还没有笔记"，侧栏也没有任何选中项，
// 用户无法判断是过滤残留还是数据没了。
func (a *app) dropMissingTagFilter() {
	if a.tagFilter == "" {
		return
	}
	if tagPathExists(a.tags, a.tagFilter) {
		return
	}
	a.tagFilter = ""
}

// tagPathExists 在标签树里查找路径。
func tagPathExists(nodes []TagNode, path string) bool {
	for _, n := range nodes {
		if n.Path == path || tagPathExists(n.Children, path) {
			return true
		}
	}
	return false
}

// dropMissingCategoryFilter 分类被删除（或改名）后清掉过滤：
// 否则列表空着、侧栏却没有任何选中项，用户不知道是过滤残留还是笔记没了。
func (a *app) dropMissingCategoryFilter() {
	if !a.catFiltered || a.catFilter == "" {
		return // 未分类（根目录）永远存在，不需要校验
	}
	if categoryPathExists(a.categories, a.catFilter) {
		return
	}
	a.catFiltered = false
	a.catFilter = ""
}

// categoryPathExists 在分类树里查找路径。
func categoryPathExists(nodes []CategoryNode, path string) bool {
	for _, n := range nodes {
		if n.Path == path || categoryPathExists(n.Children, path) {
			return true
		}
	}
	return false
}

// categoryDisplayName 返回分类的展示名：最后一级目录名；根目录显示「未分类」。
func categoryDisplayName(path string) string {
	if path == "" {
		return "未分类"
	}
	if i := strings.LastIndex(path, "/"); i >= 0 {
		return path[i+1:]
	}
	return path
}

// refreshReaderSnapshot 在笔记集合变化后重算阅读快照：
// 快照里的已解析双链指向 noteID，目标被删除/回收后仍会保留旧链接，
// 点进去会提示"尚未创建"并建出以 noteID 命名的垃圾笔记。
func (a *app) refreshReaderSnapshot() {
	if a.mode != "read" || a.currentID == "" {
		return
	}
	a.readerSrc = a.vault.RenderReaderSource(a.currentID)
}

// visibleNotes 返回按标签与分类过滤后的列表。
// 标签匹配按层级边界：选中「a」命中「a」与「a/b」，但不命中「ab」；
// 分类按目录子树匹配：选中「课程」命中「课程」及「课程/软件工程」。
// 两个过滤同时生效（AND），但界面里选中其中一个时会清掉另一个。
func (a *app) visibleNotes() []NoteSummary {
	if a.tagFilter == "" && !a.catFiltered {
		return a.notes
	}
	out := make([]NoteSummary, 0, len(a.notes))
	for _, n := range a.notes {
		if a.catFiltered && !noteInCategory(n, a.catFilter) {
			continue
		}
		if a.tagFilter != "" && !noteHasTag(n, a.tagFilter) {
			continue
		}
		out = append(out, n)
	}
	return out
}

// noteInCategory 判断笔记是否属于该分类（含子分类）；path=="" 表示未分类。
func noteInCategory(n NoteSummary, path string) bool {
	if path == "" {
		return n.Category == ""
	}
	return n.Category == path || strings.HasPrefix(n.Category, path+"/")
}

// noteHasTag 判断笔记是否有该标签（含子标签）。
func noteHasTag(n NoteSummary, filter string) bool {
	for _, t := range n.Tags {
		lower := strings.ToLower(t)
		if lower == filter || strings.HasPrefix(lower, filter+"/") {
			return true
		}
	}
	return false
}

// clearFilters 清空标签与分类过滤（切导航页时用）。
func (a *app) clearFilters() {
	a.tagFilter = ""
	a.catFilter = ""
	a.catFiltered = false
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
		// 打不开时不能静默返回（评审 W02）：用户看到的是"点了没反应"。
		// 若正文文件**确实**已被外部删除，这是一条僵尸笔记——提示并从列表移除，
		// 让用户能继续操作；否则给出具体原因。
		//
		// 用 NoteVanished 而不是 !NoteFileExists（评审 #4）：权限不足、
		// 共享冲突、网络盘断开都会让 Stat 失败，但文件其实还在。
		// 那种情况下误删索引与元数据，笔记下次启动就变成新的 RECOVERED
		// 身份，双链全部断开——比"点不开"严重得多。
		if a.vault.NoteVanished(id) {
			title, dropErr := a.vault.DropVanishedNote(id)
			if dropErr == nil {
				delete(a.editors, id)
				a.refreshLists()
				a.refreshReaderSnapshot()
				a.pendingToast = "「" + title + "」的文件已被外部删除，已从列表移除"
				return
			}
		}
		a.pendingToast = "打开笔记失败：" + errText(err)
		return
	}
	a.currentID = doc.ID
	a.currentTitle = doc.Title
	a.currentRel = doc.RelativePath
	a.currentStats = doc.Stats
	a.titleEdit = doc.Title
	a.titleWarn = ""
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
	// 正在按分类过滤时，新建的笔记直接落进该分类：否则用户刚"进入分类"
	// 点新建，笔记却出现在未分类里，还得多一步移动。
	if a.catFiltered && a.catFilter != "" {
		if moved, moveErr := a.vault.MoveNoteToCategory(doc.ID, a.catFilter); moveErr == nil {
			doc = moved
		}
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
// 返回错误供界面给出业务化提示（NFR-USE-04），不静默失败。
// 级联会改写其它笔记的磁盘正文：用 RenameReport.Affected 让这些笔记的
// 编辑器缓存重新从磁盘加载，否则缓存里的旧正文会在下次保存时覆盖级联结果。
func (a *app) renameCurrent(title string) error {
	if a.currentID == "" {
		return ErrNoteNotFound
	}
	doc, report, err := a.vault.RenameNoteReported(a.currentID, title)
	if err != nil {
		return err
	}
	a.currentTitle = doc.Title
	a.currentRel = doc.RelativePath
	if doc.Title != title {
		// 领域层可能归一化标题（截断等）：输入框如实显示最终标题。
		a.titleEdit = doc.Title
	}
	a.reloadEditors(report.Affected)
	// 两种未同步的原因要分开提示（评审 N2）：旧实现一律说"已被其它程序修改"，
	// 用户看到提示却找不到哪一篇被改了。
	if len(report.External) > 0 {
		a.pendingToast = "有 " + itoa(len(report.External)) + " 篇笔记已被其它程序修改，其中的引用未同步更新"
	} else if len(report.Dirty) > 0 {
		a.pendingToast = "有 " + itoa(len(report.Dirty)) + " 篇笔记有未保存的修改，其中的引用未同步更新"
	}
	if a.mode == "read" {
		// 级联改写可能已变更本篇正文，刷新阅读快照。
		a.readerSrc = a.vault.RenderReaderSource(a.currentID)
	}
	a.refreshLists()
	return nil
}

// titleTaken 判断是否已有别的笔记使用该标题（大小写不敏感，FR-NOTE-04）。
func (a *app) titleTaken(title string) bool {
	lower := strings.ToLower(strings.TrimSpace(title))
	if lower == "" {
		return false
	}
	for _, n := range a.notes {
		if n.ID != a.currentID && strings.ToLower(n.Title) == lower {
			return true
		}
	}
	return false
}

// applyTitleDraft 处理标题输入框的即时编辑（FR-NOTE-01/04）。
//
// 规则：合法且不冲突的标题立即改名（文件与引用同步）；含非法字符或与
// 其它笔记同名时，**保留用户原稿**、只给行内提示，提交（Enter）后再
// 用清洗后的标题改名。这样输入框里的字不会被中途替换（旧实现会把
// "a:b" 直接变成 "a_b"、把冲突后缀 " (1)" 塞回输入框并让光标跳走）。
func (a *app) applyTitleDraft(submitted bool) {
	trimmed := strings.TrimSpace(a.titleEdit)
	if trimmed == "" {
		// 空标题是合法草稿（决策 1）：提交时回填原标题，删除过程中不回弹。
		a.titleWarn = ""
		if submitted {
			a.titleEdit = a.currentTitle
		}
		return
	}
	cleaned, changed := sanitizeTitle(trimmed)
	if changed {
		a.titleWarn = "文件名不能包含 \\ / : * ? \" < > | 等字符，回车后自动替换为下划线"
		if submitted {
			a.titleEdit = cleaned
			if cleaned != a.currentTitle && !a.titleTaken(cleaned) {
				a.commitRename(cleaned)
			}
		}
		return
	}
	if a.titleTaken(cleaned) {
		a.titleWarn = "已有同名笔记，请换一个标题"
		return
	}
	if cleaned != a.currentTitle {
		a.commitRename(cleaned)
		return
	}
	a.titleWarn = ""
}

// commitRename 执行改名并把领域错误转成行内提示。
func (a *app) commitRename(title string) {
	if err := a.renameCurrent(title); err != nil {
		a.titleWarn = err.Error()
		return
	}
	a.titleWarn = ""
}

// ---- 分类（notes/ 下的真实文件夹）--------------------------------------------

// toggleCategoryFilter 选中/取消分类过滤（再点同一分类即取消）。
// 分类与标签互斥：选中分类会清掉标签过滤，避免两个过滤叠加后列表空得莫名其妙。
func (a *app) toggleCategoryFilter(path string) {
	a.view = viewNotes
	a.tagFilter = ""
	if a.catFiltered && a.catFilter == path {
		a.catFiltered = false
		a.catFilter = ""
	} else {
		a.catFiltered = true
		a.catFilter = path
	}
	a.refreshLists()
}

// toggleTagFilter 选中/取消标签过滤（再点同一标签即取消）；与分类互斥。
func (a *app) toggleTagFilter(path string) {
	a.view = viewNotes
	a.catFiltered = false
	a.catFilter = ""
	if a.tagFilter == path {
		a.tagFilter = ""
	} else {
		a.tagFilter = path
	}
	a.refreshLists()
}

// openMoveDialog 打开"移动到分类"弹窗；currentCategory 是笔记当前所在分类。
func (a *app) openMoveDialog(id, title, currentCategory string) {
	a.catDialog = catDialogState{open: true, noteID: id, noteTitle: title, target: currentCategory}
}

// openNewCategoryDialog 从侧栏打开"新建分类"弹窗。
func (a *app) openNewCategoryDialog() {
	a.catDialog = catDialogState{open: true}
}

// moveNoteToCategory 把笔记移到分类（path=="" 表示移回未分类/根目录）。
// 只搬文件、标题与正文不变，所以编辑器和双链都不需要重建。
func (a *app) moveNoteToCategory(id, path string) error {
	doc, err := a.vault.MoveNoteToCategory(id, path)
	if err != nil {
		a.pendingToast = "移动失败：" + err.Error()
		return err
	}
	if a.currentID == id {
		a.currentRel = doc.RelativePath
	}
	a.refreshLists()
	a.pendingToast = "已把「" + doc.Title + "」移动到「" + categoryDisplayName(path) + "」"
	return nil
}

// createCategory 新建分类（支持多级 a/b），返回领域层清洗后的规范路径。
func (a *app) createCategory(path string) (string, error) {
	cleaned, err := a.vault.CreateCategory(path)
	if err != nil {
		a.pendingToast = "新建分类失败：" + err.Error()
		return "", err
	}
	a.refreshLists()
	return cleaned, nil
}

// deleteCategory 删除空分类；分类里还有笔记/子分类时由领域层拒绝。
func (a *app) deleteCategory(path string) error {
	if err := a.vault.DeleteCategory(path); err != nil {
		a.pendingToast = "删除分类失败：" + err.Error()
		return err
	}
	if a.catFiltered && a.catFilter == path {
		a.catFiltered = false
		a.catFilter = ""
	}
	a.refreshLists()
	a.pendingToast = "已删除分类「" + categoryDisplayName(path) + "」"
	return nil
}

// currentCategory 返回当前笔记所在分类（无打开笔记时为 ""）。
func (a *app) currentCategory() string {
	if a.currentID == "" {
		return ""
	}
	return parentDirOfRelPath(a.currentRel)
}

// ---- 自动保存（FR-EDIT-04/05/06、BR-04/05）------------------------------------

// scheduleSave 为未落盘的修改安排 2 秒自动保存（节流：变脏后每 2 秒落盘一次）。
//
// 这里刻意**不做防抖**（停止输入后再等 2 秒才落盘）：防抖的经典缺陷是用户
// 持续不停输入时定时器被永远重置、永远不保存，写长文可能几十分钟不落盘。
// 节流让丢失窗口恒定在 2 秒以内，对笔记类应用比减少 I/O 更重要。
//
// 保存失败（saveError）后**不再自动重试已失败的那一次**：旧实现每 2 秒重试一次，
// 外部修改/磁盘故障时会无限读盘并把同一条 Toast 反复重置、永久驻留。
// 但用户在失败之后又继续输入时会重新启用自动保存（见 autosave）。
// 失败后由用户点徽章显式重试（FR-EDIT-06）。
func (a *app) scheduleSave() {
	if a.saveTimer != nil || a.saveState == saveSaving || a.saveState == saveError || a.currentID == "" {
		return
	}
	if a.saveState != saveDirty {
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
	seq := ed.nextSaveSeq()
	if a.currentID == id {
		a.saveState = saveSaving
	}
	run := func() {
		var receipt SaveReceipt
		var err error
		if force {
			receipt, err = a.vault.ForceSaveContentSeq(id, snap.String(), seq)
		} else {
			receipt, err = a.vault.SaveContentSeq(id, snap.String(), seq)
		}
		a.onUIThread(func() { a.applySaveResult(id, snap.Version(), receipt, err) })
	}
	if a.win == nil {
		run()
	} else {
		done := make(chan struct{})
		ed.writing = done
		go func() {
			defer close(done)
			run()
		}()
	}
	return true
}

// flushSaveNote 同步保存指定笔记的未落盘草稿，返回是否成功（无草稿也算成功）。
// 用于换篇、删除、导出/重建/换库前的一致性保障（评审 #2/#3）。
// 先等该篇在飞的异步保存结束，保证最后落盘的是最新内容。
func (a *app) flushSaveNote(id string) bool {
	if id == "" {
		return true
	}
	ed := a.editors[id]
	if ed == nil {
		return true
	}
	ed.waitWriting()
	if ed.state.Buffer.Version() == ed.saved {
		return true
	}
	snap := ed.state.Buffer.Snapshot()
	seq := ed.nextSaveSeq()
	receipt, err := a.vault.SaveContentSeq(id, snap.String(), seq)
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

// onCloseRequest 关窗前同步落盘全部草稿（FR-EDIT-05）。
// 保存失败时拦住关窗并让用户决定（评审 N3）：
//
//	旧实现只拦一次，用户再点一次关闭就放行 → 草稿直接丢失。
//	现在失败时弹出确认框，用户可以「重试保存」或明确「放弃并退出」；
//	在用户做出选择之前一律拦住，不存在"点两下就丢稿"的路径。
func (a *app) onCloseRequest(e *mygo.CloseEvent) {
	// 用户已明确选择"放弃未保存的改动并退出"：放行。
	if a.closeAbandoned {
		return
	}
	if a.flushSaveAll() {
		a.closePending = false
		a.confirm.open = false
		a.confirm.onOK = nil
		return
	}
	// 已经在等用户选择：继续拦，直到用户明确「放弃并退出」。
	if e != nil {
		e.PreventDefault()
	}
	if a.closePending {
		return
	}
	a.closePending = true
	a.confirm = confirmState{
		open:        true,
		title:       "草稿尚未保存",
		message:     "有草稿保存失败（磁盘写入出错，或笔记已被其它程序修改）。你可以重试保存，或放弃未保存的改动并退出——放弃后这些内容将无法找回。",
		danger:      true,
		okLabel:     "放弃并退出",
		cancelLabel: "重试保存",
		// 「重试保存」= 留在应用里，用户可以修好磁盘再试。
		onCancel: func() {
			a.closePending = false
			// 立刻再试一次保存；成功则下次关窗即可正常退出。
			a.flushSaveAll()
			if a.win != nil {
				a.win.Update(func() {})
			}
		},
		// 「放弃并退出」= 用户明确选择丢弃未保存内容。
		onOK: func() {
			a.closePending = false
			a.closeAbandoned = true
			if a.win != nil {
				a.win.Close()
			}
		},
	}
	if a.win != nil {
		a.win.Update(func() {}) // 触发一帧，让确认框显示出来
	}
}

// restoreFromTrash 还原回收站笔记（FR-NOTE-07）。
// 失败时给出业务化提示并返回错误：旧实现丢弃错误仍弹"已恢复笔记"，
// 用户会以为数据回来了，其实还在回收站。
func (a *app) restoreFromTrash(id string) error {
	if _, err := a.vault.RestoreFromTrash(id); err != nil {
		a.pendingToast = "恢复失败：" + err.Error()
		return err
	}
	a.refreshLists()
	a.refreshReaderSnapshot()
	return nil
}

// ---- 删除动画（碎纸机，见 shred.go）------------------------------------------

// startShred 开始删除某篇笔记的碎纸动画；已有一场动画则先立即结算。
// 动画期间不删除：真正删除在 finishShred 里执行，行本身就是动画的素材。
func (a *app) startShred(c *ui.Context, n NoteSummary) {
	if a.shred != nil {
		// 连续删除：上一场先结算，避免两篇笔记都卡在"待删除"。
		a.finishShred(c)
	}
	a.shred = &shredAnim{note: n, dur: shredDuration, seed: uint32(time.Now().UnixNano())}
}

// shredProgress 返回指定笔记的删除动画进度；不在动画中返回 -1。
// 首次调用时记录起点（Tester 的虚拟时钟也由此对齐）。
func (a *app) shredProgress(c *ui.Context, id string) float32 {
	if a.shred == nil || a.shred.note.ID != id {
		return -1
	}
	a.shred.begin(c.Now())
	return a.shred.progress(c.Now())
}

// finishShred 结束删除动画并真正移入回收站（成功后给出 5 秒以上撤销窗口）。
func (a *app) finishShred(c *ui.Context) {
	s := a.shred
	if s == nil {
		return
	}
	a.shred = nil
	a.deleteNoteNow(c, s.note)
}

// deleteNoteNow 立即把笔记移入回收站并给出撤销 Toast（不播放动画）。
// 系统要求"减少动态效果"（Preferences.ReduceMotion）时走这条路径，
// 无障碍场景下不做碎纸动画。
func (a *app) deleteNoteNow(c *ui.Context, n NoteSummary) {
	wasCurrent := a.currentID == n.ID
	if !a.deleteFromList(n.ID) {
		return // 删除未发生（保存失败/领域错误），deleteFromList 已给出提示
	}
	title, id := n.Title, n.ID
	c.ToastAction("已将「"+title+"」移入回收站", "撤销", func() {
		if err := a.restoreFromTrash(id); err != nil {
			return // 恢复失败已由 restoreFromTrash 提示，不能让撤销键静默无效
		}
		if wasCurrent {
			a.openNote(id)
		}
		c.Toast("已撤销删除")
	})
}

// applySaveResult 把落盘回执应用回界面状态（主线程）。
func (a *app) applySaveResult(id string, ver uint64, receipt SaveReceipt, err error) {
	ed := a.editors[id]
	if ed == nil {
		return
	}
	// 过期回执：此前已有更新的版本落盘（例如 flush 抢先写入），
	// 不能再拿旧回执改状态，否则会把"已保存"标回旧内容。
	if err == nil && ver < ed.saved {
		return
	}
	if err != nil {
		// 业务化提示（NFR-USE-04）：外部修改给出可操作指引，其余直接给领域措辞。
		// 只在出错这一次提示，不做后台重试，避免提示无限刷屏。
		switch err {
		case ErrExternalEdit:
			if a.currentID == id {
				a.saveState = saveError
				// 记住失败时的版本号：用户之后又输入才重新启用自动保存（评审 N3）。
				a.saveErrorVersion = ed.state.Buffer.Version()
				// 外部修改冲突必须由用户显式确认强制覆盖，自动重试必然再失败。
				a.saveErrorExternal = true
			}
			a.pendingToast = "笔记已被其它程序修改：点「保存失败」可强制覆盖"
		case ErrStaleRevision:
			// 迟到的旧快照被领域层拦下：磁盘上已经是更新的内容，不是失败。
			//
			// 但**绝不能**在这里把当前缓冲区标记为已保存（评审 #5）：
			// 旧实现执行 ed.saved = ed.state.Buffer.Version() + MarkSaved()，
			// 于是编辑器里尚未落盘的新输入被误标为"已保存"，随后的 flush
			// 看到 Version()==saved 就直接跳过——用户的新内容永远写不进磁盘，
			// 界面上却一直显示"已保存"。
			//
			// 正确做法：忽略这份过期回执，让缓冲区版本与已保存版本保持各自的
			// 真实值；未落盘的内容由下一次自动保存带走。
			//
			// 这里也不进入 saveError 态：这不是失败，磁盘上是更新后的正确内容。
			// 若缓冲区确实还有未落盘的内容，状态应回到"正在输入/待保存"，
			// 由 autosave 重新安排保存；若缓冲区已与磁盘一致则是"已保存"。
			a.pendingToast = "已忽略一次迟到的旧保存（磁盘上已是更新内容）"
			if a.currentID == id {
				if ed.state.Buffer.Version() != ed.saved {
					a.saveState = saveDirty
					a.scheduleSave()
				} else {
					a.saveState = saveSaved
				}
			}
		default:
			if a.currentID == id {
				a.saveState = saveError
				a.saveErrorVersion = ed.state.Buffer.Version()
				a.saveErrorExternal = false
			}
			a.pendingToast = err.Error()
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

// setView 切换视图。切换前先落盘当前草稿（评审 W06）：
// 旧实现对回收站页跳过落盘，用户切到回收站后编辑器仍留着未保存的内容，
// 若此时关窗或崩溃就会丢稿。落盘是无害的：回收站页并不编辑正文，
// 同步写一次不会影响任何视图状态。
func (a *app) setView(v string) {
	a.flushSave()
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
	// 正文文件**确实**已被外部删除（评审 W02）：没有草稿可保存，也没有内容可移入
	// 回收站，直接把这条僵尸笔记从索引里清掉——不能因为文件不在了
	// 就让用户被一条删不掉的记录卡住。
	//
	// 用 NoteVanished 而不是 !NoteFileExists（评审 #4）：只有拿到明确的
	// "文件不存在"才当作僵尸笔记；权限/共享冲突/网络盘断开时文件其实还在，
	// 误删索引会让笔记下次启动变成新的 RECOVERED 身份、双链全断。
	if a.vault.NoteVanished(id) {
		title, err := a.vault.DropVanishedNote(id)
		if err == nil {
			delete(a.editors, id)
			if a.currentID == id {
				a.currentID = ""
				a.backlinks = nil
			}
			a.refreshLists()
			a.refreshReaderSnapshot()
			a.pendingToast = "「" + title + "」的文件已被外部删除，已从列表移除"
			return true
		}
	}
	if !a.flushSaveNote(id) {
		a.pendingToast = "保存失败，已中止删除"
		return false
	}
	if _, err := a.vault.TrashNote(id); err != nil {
		// 删除失败必须明确反馈（FR-UI-02），不能点了像没点。
		a.pendingToast = "移入回收站失败：" + err.Error()
		return false
	}
	if a.currentID == id {
		a.currentID = ""
		a.backlinks = nil
	}
	delete(a.editors, id)
	a.refreshLists()
	a.refreshReaderSnapshot()
	return true
}

// ---- 批量删除（bulk delete）---------------------------------------------------

// enterBatchMode 进入批量删除状态：笔记列表每行标题右侧出现复选框，
// 列表下方出现「确认」「取消」两个按钮。
//
// 进入时清空上一次的勾选：用户上一次勾了哪几篇与这一次无关，
// 沿用旧勾选会让用户误删没打算删的笔记。
func (a *app) enterBatchMode() {
	a.batchMode = true
	a.batchPicked = map[string]bool{}
}

// exitBatchMode 退出批量删除状态并清空勾选。
func (a *app) exitBatchMode() {
	a.batchMode = false
	a.batchPicked = map[string]bool{}
}

// toggleBatchPick 勾选/取消勾选一篇笔记。
func (a *app) toggleBatchPick(id string) {
	if a.batchPicked == nil {
		a.batchPicked = map[string]bool{}
	}
	if a.batchPicked[id] {
		delete(a.batchPicked, id)
		return
	}
	a.batchPicked[id] = true
}

// batchVisibleIDs 返回当前列表里可见的笔记 ID，按显示顺序。
//
// 检索态显示的是检索结果而不是全部笔记，因此两态要分别取：
// 否则用户在检索结果里勾了 2 篇，批量删除却把全部笔记都算进去。
func (a *app) batchVisibleIDs() []string {
	ids := []string{}
	if a.query != "" && strings.TrimSpace(a.query) != "" {
		for _, h := range a.hits {
			ids = append(ids, h.NoteID)
		}
		return ids
	}
	for _, n := range a.visibleNotes() {
		ids = append(ids, n.ID)
	}
	return ids
}

// batchPickedIDs 返回被勾选的笔记 ID，按当前列表顺序排列。
//
// 顺序取自当前可见列表：批量删除要按用户看到的顺序处理，
// 便于与"勾了哪几篇"对上；勾选集里可能残留已被外部删除或
// 过滤掉的笔记 ID，这些不在列表里，不参与删除。
func (a *app) batchPickedIDs() []string {
	ids := []string{}
	for _, id := range a.batchVisibleIDs() {
		if a.batchPicked[id] {
			ids = append(ids, id)
		}
	}
	return ids
}

// batchDeleteSelected 删除被勾选的笔记并退出批量删除状态。
//
// 删除语义与单篇删除一致：移入回收站（可撤销），不是彻底删除。
// 逐篇独立处理：一篇失败（保存失败、磁盘错误）不影响其余，
// 失败原因汇总提示，不静默吞掉。
//
// 碎纸机动画不用于批量删除：一次只播一篇，逐篇播会让 N 篇笔记
// 排队等动画，删除十几篇就变成十几秒的等待；批量删除以效率为先。
func (a *app) batchDeleteSelected() {
	ids := a.batchPickedIDs()
	if len(ids) == 0 {
		a.pendingToast = "请先勾选要删除的笔记"
		return
	}
	// 退出批量删除状态：无论删除结果如何都退出，避免用户面对
	// 一堆已经删掉的复选框继续点「确认」。
	a.exitBatchMode()

	deleted := 0
	failed := 0
	firstErr := ""
	for _, id := range ids {
		if a.deleteFromListQuiet(id) {
			deleted++
			continue
		}
		failed++
		if firstErr == "" {
			firstErr = id
		}
	}
	a.refreshLists()
	a.refreshReaderSnapshot()

	switch {
	case failed == 0:
		a.pendingToast = "已将 " + itoa(deleted) + " 篇笔记移入回收站"
	case deleted == 0:
		a.pendingToast = "删除失败：" + a.batchDeleteReason(firstErr)
	default:
		a.pendingToast = "已删除 " + itoa(deleted) + " 篇，" + itoa(failed) + " 篇失败：" + a.batchDeleteReason(firstErr)
	}
}

// batchDeleteReason 返回某篇笔记删除失败的原因（用户可读）。
func (a *app) batchDeleteReason(id string) string {
	if id == "" {
		return "未知原因"
	}
	for _, n := range a.notes {
		if n.ID == id {
			return "「" + n.Title + "」删除失败"
		}
	}
	return "部分笔记删除失败"
}

// deleteFromListQuiet 走单篇删除的完整链路，但不弹提示：
// 批量删除要自己汇总结果，逐篇弹提示会刷屏。
//
// 与 deleteFromList 的差别只有"是否提示"，删除语义完全一致：
// 先落盘草稿（评审 #2），再移入回收站。
func (a *app) deleteFromListQuiet(id string) bool {
	if a.vault.NoteVanished(id) {
		// 正文文件已被外部删除：没有内容可移入回收站，
		// 把这条僵尸笔记从索引里清掉即可。
		if _, err := a.vault.DropVanishedNote(id); err == nil {
			delete(a.editors, id)
			if a.currentID == id {
				a.currentID = ""
				a.backlinks = nil
			}
			return true
		}
	}
	if !a.flushSaveNote(id) {
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
	return true
}

// reloadEditors 在磁盘正文被本应用改写（改名级联、导入覆盖）后刷新编辑器缓存：
// 缓存里还是旧正文，用户再输入一个字，2 秒自动保存就会把整篇旧正文写回磁盘，
// 静默覆盖刚写入的新内容（链接回退、导入内容丢失）。
// 只在缓存无未保存修改时重载；有草稿的保留，交给保存时的外部修改检测拦下。
func (a *app) reloadEditors(ids []string) {
	reloadCurrent := false
	for _, id := range ids {
		ed := a.editors[id]
		if ed == nil {
			continue
		}
		if ed.state.Buffer.Version() != ed.saved {
			continue // 有草稿：不丢用户的输入
		}
		delete(a.editors, id)
		if a.currentID == id {
			reloadCurrent = true
		}
	}
	if reloadCurrent {
		mode := a.mode
		a.openNote(a.currentID)
		a.mode = mode
		if mode == "read" {
			a.readerSrc = a.vault.RenderReaderSource(a.currentID)
		}
	}
}

// ---- 后台流程（导入/导出/重建/换库）---------------------------------------------

func (a *app) busyStart(msg string) { a.busy = msg }

func (a *app) busyEnd() { a.busy = "" }

// importFlow 打开系统多选对话框并准备导入计划；有冲突时进入逐项决策弹窗。
// 阻塞式对话框在后台 goroutine 中调用。
func (a *app) importFlow() {
	a.onUIThread(func() { a.busyStart("正在准备导入…") })
	plan, err := a.vault.PrepareImport(a.win)
	a.finishImportPrepare(plan, err)
}

// importFolderFlow 导入整个文件夹（保留层级，FR-DATA-01）。
// 系统对话框一次只能选文件或选目录，因此单独入口，避免"文案说支持文件夹、
// 对话框却选不中目录"的旧缺陷。
func (a *app) importFolderFlow() {
	a.onUIThread(func() { a.busyStart("正在准备导入…") })
	plan, err := a.vault.PrepareImportFolder(a.win)
	a.finishImportPrepare(plan, err)
}

// finishImportPrepare 把导入计划的准备结果接回界面（主线程）。
func (a *app) finishImportPrepare(plan ImportPlan, err error) {
	a.onUIThread(func() {
		a.busyEnd()
		if err != nil {
			a.pendingToast = "导入失败：" + err.Error()
			return
		}
		if len(plan.Files) == 0 {
			return // 用户取消对话框，或目录里没有 .md：保持安静，不弹噪音提示
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
				a.pendingToast = "导入失败：" + err.Error()
				return
			}
			a.refreshLists()
			// 覆盖导入会改写已存在笔记的磁盘正文：必须让它们的编辑器缓存
			// 重新从磁盘加载，否则用户再输入一个字就把旧正文写回去。
			overwritten := []string{}
			for _, r := range results {
				if r.Status == "overwritten" && r.NoteID != "" {
					overwritten = append(overwritten, r.NoteID)
				}
			}
			a.reloadEditors(overwritten)
			a.refreshReaderSnapshot()
			a.pendingToast = importSummary(results)
		})
	}()
}

// importSummary 汇总导入结果：成功数之外必须报出跳过与失败的数量和文件名，
// 否则损坏文件/用户选择跳过的文件会静默消失（AC-DATA-02、FR-DATA-06）。
func importSummary(results []ImportResult) string {
	ok, skipped, failed := 0, 0, 0
	names := []string{}
	for _, r := range results {
		switch r.Status {
		case "imported", "renamed", "overwritten":
			ok++
		case "skipped":
			skipped++
			if len(names) < 3 {
				names = append(names, r.FileName)
			}
		default:
			failed++
			if len(names) < 3 {
				names = append(names, r.FileName)
			}
		}
	}
	msg := "导入完成：成功 " + itoa(ok) + " 篇"
	if skipped > 0 {
		msg += "，跳过 " + itoa(skipped) + " 个"
	}
	if failed > 0 {
		msg += "，失败 " + itoa(failed) + " 个"
	}
	if len(names) > 0 {
		detail := strings.Join(names, "、")
		if more := skipped + failed - len(names); more > 0 {
			detail += " 等"
		}
		msg += "（" + detail + "）"
	}
	return msg
}

// exportVaultFlow 打包完整知识库（FR-DATA-03）。
func (a *app) exportVaultFlow() {
	path, err := a.vault.ExportVault(a.win)
	if err != nil {
		a.onUIThread(func() { a.pendingToast = "导出失败：" + err.Error() })
		return
	}
	if path == "" {
		return // 用户取消保存对话框
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
			a.pendingToast = "重建索引失败：" + err.Error()
			return
		}
		a.editors = map[string]*noteEditor{}
		a.currentID = ""
		a.readerSrc = ""
		a.refreshLists()
		a.pendingToast = "索引重建完成：" + strconv.Itoa(result.NoteCount) + " 篇，耗时 " + strconv.FormatInt(result.DurationMs, 10) + " ms"
	})
}

// chooseWorkspaceFlow 弹出文件夹选择并切换知识库。
func (a *app) chooseWorkspaceFlow() {
	info, err := a.vault.ChooseWorkspace(a.win)
	if err != nil {
		a.onUIThread(func() { a.pendingToast = "切换知识库失败：" + err.Error() })
		return
	}
	a.onUIThread(func() {
		if !info.Ready {
			a.pendingToast = "未选择知识库文件夹"
			return
		}
		a.editors = map[string]*noteEditor{}
		a.currentID = ""
		a.readerSrc = ""
		a.view = viewNotes
		a.refreshLists()
		if len(a.notes) > 0 {
			a.openNote(a.notes[0].ID)
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
