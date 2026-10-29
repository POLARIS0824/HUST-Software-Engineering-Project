package main

import (
	"context"
	"encoding/json"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"

	"github.com/egoist/mygo"
)

// Vault 是绑定给前端的笔记库服务。所有导出方法经 `mygo generate`
// 生成 TypeScript 客户端；并发由 mu 串行化保护。
type Vault struct {
	mu        sync.Mutex
	root      string // 工作区根目录；空表示尚未选择
	cfgPath   string
	notes     map[string]*indexedNote // 活跃笔记索引
	trashed   map[string]*NoteMeta    // 回收站元数据
	byTitle   map[string][]string     // titleLower -> noteID 列表
	pendingOp string                  // 当前未完成的操作记录 ID（简化单机协议）
}

// WorkspaceInfo 描述当前工作区状态。
type WorkspaceInfo struct {
	Root       string `json:"root"`
	NoteCount  int    `json:"noteCount"`
	TrashCount int    `json:"trashCount"`
	Ready      bool   `json:"ready"`
}

// WorkspaceStats 是变更事件的载荷。
type WorkspaceStats struct {
	NoteCount  int `json:"noteCount"`
	TrashCount int `json:"trashCount"`
}

// NoteSummary 是列表条目摘要。
type NoteSummary struct {
	ID           string   `json:"id"`
	Title        string   `json:"title"`
	RelativePath string   `json:"relativePath"`
	Tags         []string `json:"tags"`
	CreatedAtUtc int64    `json:"createdAtUtc"`
	UpdatedAtUtc int64    `json:"updatedAtUtc"`
	CharCount    int      `json:"charCount"`
	WordCount    int      `json:"wordCount"`
	Summary      string   `json:"summary"`
	Trashed      bool     `json:"trashed"`
}

// LinkView 是阅读页/面板消费的链接视图。
type LinkView struct {
	Target         string `json:"target"`
	Alias          string `json:"alias"`
	State          string `json:"state"` // RESOLVED / UNRESOLVED / AMBIGUOUS
	ResolvedNoteID string `json:"resolvedNoteId,omitempty"`
	Line           int    `json:"line"`
}

// NoteDocument 是打开的一篇笔记完整文档。
type NoteDocument struct {
	ID           string         `json:"id"`
	Title        string         `json:"title"`
	RelativePath string         `json:"relativePath"`
	Markdown     string         `json:"markdown"`
	CreatedAtUtc int64          `json:"createdAtUtc"`
	UpdatedAtUtc int64          `json:"updatedAtUtc"`
	Revision     int64          `json:"revision"`
	Tags         []ExtractedTag `json:"tags"`
	Links        []LinkView     `json:"links"`
	Stats        TextStats      `json:"stats"`
	Trashed      bool           `json:"trashed"`
}

// SaveReceipt 是一次成功落盘的回执。只有拿到回执 UI 才能显示"已保存"（BR-05）。
type SaveReceipt struct {
	Revision      int64          `json:"revision"`
	ContentSha256 string         `json:"contentSha256"`
	SavedAtUtc    int64          `json:"savedAtUtc"`
	Stats         TextStats      `json:"stats"`
	Tags          []ExtractedTag `json:"tags"`
	Links         []LinkView     `json:"links"`
}

// TagNode 是标签树的节点（FR-TAG-04/06）。
type TagNode struct {
	Name     string    `json:"name"`  // 展示名（保留首创大小写）
	Path     string    `json:"path"`  // 全路径小写，如 "考研/复习计划"
	Count    int       `json:"count"` // 直接关联笔记数
	Children []TagNode `json:"children"`
}

// BacklinkItem 是一条反向引用（FR-LINK-06/07）。
type BacklinkItem struct {
	SourceNoteID string `json:"sourceNoteId"`
	SourceTitle  string `json:"sourceTitle"`
	Line         int    `json:"line"`
	Snippet      string `json:"snippet"`
}

// RebuildResult 是索引重建的结果（FR-DATA-05）。
type RebuildResult struct {
	NoteCount        int   `json:"noteCount"`
	TrashedNoteCount int   `json:"trashedCount"`
	OrphanMeta       int   `json:"orphanMeta"`
	PurgedTrash      int   `json:"purgedTrash"`
	DurationMs       int64 `json:"durationMs"`
}

// NotesChanged 在库发生任何变更后广播，驱动前端刷新列表。
var NotesChanged = mygo.NewEvent[WorkspaceStats]("notes-changed")

// MenuAction 把原生菜单点击转发给前端（payload 为动作名）。
var MenuAction = mygo.NewEvent[string]("menu-action")

// vaultConfig 是持久化在用户数据目录的应用配置。
type vaultConfig struct {
	Workspace string `json:"workspace"`
}

const untitledTitle = "未命名笔记"

// OpenVault 读取配置并加载工作区；工作区未配置时返回未就绪的空库。
func OpenVault() (*Vault, error) {
	v := &Vault{
		notes:   map[string]*indexedNote{},
		trashed: map[string]*NoteMeta{},
		byTitle: map[string][]string{},
	}
	dir, err := mygo.App.Path(mygo.PathUserData)
	if err != nil {
		return nil, err
	}
	v.cfgPath = filepath.Join(dir, "justmd-config.json")
	data, err := os.ReadFile(v.cfgPath)
	if err == nil {
		var cfg vaultConfig
		if json.Unmarshal(data, &cfg) == nil {
			v.root = cfg.Workspace
		}
	}
	if v.root != "" {
		if err := v.loadWorkspace(); err != nil {
			// 工作区目录被外部删除等情况：保持未就绪，让用户重新选择。
			v.root = ""
		}
	}
	return v, nil
}

// GetWorkspace 返回当前工作区信息。
func (v *Vault) GetWorkspace() WorkspaceInfo {
	v.mu.Lock()
	defer v.mu.Unlock()
	return v.workspaceInfo()
}

// ChooseWorkspace 弹出系统文件夹选择框并切换知识库（桌面端适配，对应 Android SAF）。
// 返回空字符串路径表示用户取消。
func (v *Vault) ChooseWorkspace(ctx context.Context) (WorkspaceInfo, error) {
	paths, err := mygo.Dialog.Open(mygo.OpenDialogOptions{
		Parent:            mygo.CallerWindow(ctx),
		Title:             "选择 JustMD 知识库文件夹",
		Directory:         true,
		CreateDirectories: true,
	})
	if err != nil {
		return WorkspaceInfo{}, err
	}
	if len(paths) == 0 || paths[0] == "" {
		return v.GetWorkspace(), nil
	}
	if err := v.switchWorkspace(paths[0]); err != nil {
		return WorkspaceInfo{}, err
	}
	return v.GetWorkspace(), nil
}

func (v *Vault) switchWorkspace(root string) error {
	v.mu.Lock()
	defer v.mu.Unlock()
	abs, err := filepath.Abs(root)
	if err != nil {
		return err
	}
	v.root = abs
	if err := v.loadWorkspace(); err != nil {
		v.root = ""
		return err
	}
	data, _ := json.MarshalIndent(vaultConfig{Workspace: abs}, "", "  ")
	if err := os.MkdirAll(filepath.Dir(v.cfgPath), 0o755); err != nil {
		return err
	}
	if err := os.WriteFile(v.cfgPath, data, 0o644); err != nil {
		return err
	}
	return nil
}

func (v *Vault) workspaceInfo() WorkspaceInfo {
	info := WorkspaceInfo{Root: v.root, Ready: v.root != ""}
	info.NoteCount = len(v.notes)
	info.TrashCount = len(v.trashed)
	return info
}

func (v *Vault) stats() WorkspaceStats {
	return WorkspaceStats{NoteCount: len(v.notes), TrashCount: len(v.trashed)}
}

func (v *Vault) broadcast() {
	NotesChanged.Broadcast(v.stats())
}

// ListNotes 按排序选项返回活跃笔记列表（FR-NOTE-02）。
func (v *Vault) ListNotes(sortOption string) []NoteSummary {
	v.mu.Lock()
	defer v.mu.Unlock()
	out := make([]NoteSummary, 0, len(v.notes))
	for _, n := range v.notes {
		out = append(out, n.toSummary())
	}
	sortSummaries(out, sortOption)
	return out
}

// ListTrash 返回回收站列表（FR-NOTE-07）。
func (v *Vault) ListTrash() []NoteSummary {
	v.mu.Lock()
	defer v.mu.Unlock()
	out := make([]NoteSummary, 0, len(v.trashed))
	for _, m := range v.trashed {
		title := titleOfRelPath(m.RelativePath)
		out = append(out, NoteSummary{
			ID:           m.NoteID,
			Title:        title,
			RelativePath: m.RelativePath,
			Tags:         []string{},
			CreatedAtUtc: m.CreatedAtUtc,
			UpdatedAtUtc: m.UpdatedAtUtc,
			Trashed:      true,
		})
	}
	sortSummaries(out, "updated_desc")
	return out
}

func (n *indexedNote) toSummary() NoteSummary {
	tagNames := make([]string, 0, len(n.tags))
	for _, t := range n.tags {
		tagNames = append(tagNames, t.Name)
	}
	return NoteSummary{
		ID:           n.meta.NoteID,
		Title:        n.title,
		RelativePath: n.meta.RelativePath,
		Tags:         tagNames,
		CreatedAtUtc: n.meta.CreatedAtUtc,
		UpdatedAtUtc: n.meta.UpdatedAtUtc,
		CharCount:    n.stats.CharCount,
		WordCount:    n.stats.CJKCharCount + n.stats.EnWordCount,
		Summary:      n.summary,
	}
}

func sortSummaries(list []NoteSummary, option string) {
	less := func(a, b NoteSummary) bool { return a.UpdatedAtUtc > b.UpdatedAtUtc }
	switch option {
	case "updated_asc":
		less = func(a, b NoteSummary) bool { return a.UpdatedAtUtc < b.UpdatedAtUtc }
	case "created_desc":
		less = func(a, b NoteSummary) bool { return a.CreatedAtUtc > b.CreatedAtUtc }
	case "created_asc":
		less = func(a, b NoteSummary) bool { return a.CreatedAtUtc < b.CreatedAtUtc }
	case "title_asc":
		less = func(a, b NoteSummary) bool { return strings.ToLower(a.Title) < strings.ToLower(b.Title) }
	case "title_desc":
		less = func(a, b NoteSummary) bool { return strings.ToLower(a.Title) > strings.ToLower(b.Title) }
	}
	sort.SliceStable(list, func(i, j int) bool { return less(list[i], list[j]) })
}

// ListTags 返回全库标签树（大小写折叠计数，多级嵌套）。
func (v *Vault) ListTags() []TagNode {
	v.mu.Lock()
	defer v.mu.Unlock()

	type tagInfo struct {
		display string
		count   int
	}
	infos := map[string]*tagInfo{}
	for _, n := range v.notes {
		for _, t := range n.tags {
			info, ok := infos[t.NameLower]
			if !ok {
				info = &tagInfo{display: t.Name}
				infos[t.NameLower] = info
			}
			info.count++
		}
	}

	// 按层级拆分构建树。
	var roots []TagNode
	nodes := map[string]*TagNode{}
	var ensureNode func(path string) *TagNode
	ensureNode = func(path string) *TagNode {
		if node, ok := nodes[path]; ok {
			return node
		}
		parts := strings.Split(path, "/")
		parentPath := strings.Join(parts[:len(parts)-1], "/")
		node := &TagNode{Name: parts[len(parts)-1], Path: path, Children: []TagNode{}}
		if info, ok := infos[path]; ok {
			node.Name = info.display
			node.Count = info.count
		}
		nodes[path] = node
		if parentPath == "" {
			roots = append(roots, *node)
		} else {
			parent := ensureNode(parentPath)
			parent.Children = append(parent.Children, *node)
		}
		return node
	}
	paths := make([]string, 0, len(infos))
	for p := range infos {
		paths = append(paths, p)
	}
	sort.Strings(paths)
	for _, p := range paths {
		ensureNode(p)
	}
	// ensureNode 通过值拷贝追加根节点，回填最终状态。
	roots = roots[:0]
	for _, p := range paths {
		if !strings.Contains(p, "/") {
			roots = append(roots, *nodes[p])
		}
	}
	return roots
}

// CreateNote 新建空白笔记（FR-NOTE-01）。
func (v *Vault) CreateNote(title string) (NoteDocument, error) {
	v.mu.Lock()
	defer v.mu.Unlock()
	return v.createNoteLocked(title)
}

// CreateNoteFromLink 供点击 unresolved 双链时一键创建（FR-LINK-05）。
func (v *Vault) CreateNoteFromLink(title string) (NoteDocument, error) {
	v.mu.Lock()
	defer v.mu.Unlock()
	return v.createNoteLocked(title)
}

func (v *Vault) createNoteLocked(title string) (NoteDocument, error) {
	if v.root == "" {
		return NoteDocument{}, ErrNoWorkspace
	}
	cleaned, _ := sanitizeTitle(title)
	if cleaned == "" {
		cleaned = untitledTitle
	}
	rel := uniqueRelPath(v.notesDir(), cleaned)
	now := nowMs()
	meta := &NoteMeta{
		SchemaVersion:  metaSchemaV1,
		NoteID:         newID(),
		RelativePath:   rel,
		CreatedAtUtc:   now,
		UpdatedAtUtc:   now,
		Revision:       1,
		ContentSha256:  sha256Hex(nil),
		State:          stateActive,
		MetadataOrigin: originOriginal,
	}
	if err := atomicWriteFile(filepath.Join(v.notesDir(), filepath.FromSlash(rel)), nil); err != nil {
		return NoteDocument{}, ErrSaveFailed
	}
	metaDir, _ := v.metaDirs()
	if err := saveNoteMeta(metaDir, meta); err != nil {
		return NoteDocument{}, ErrSaveFailed
	}
	v.indexNote(meta, "")
	v.recomputeLinks()
	v.broadcast()
	return v.openNoteLocked(meta.NoteID)
}

// OpenNote 打开笔记。正文总是从 .md 文件读取（BR-01 单一事实源）。
func (v *Vault) OpenNote(id string) (NoteDocument, error) {
	v.mu.Lock()
	defer v.mu.Unlock()
	return v.openNoteLocked(id)
}

func (v *Vault) openNoteLocked(id string) (NoteDocument, error) {
	n, ok := v.notes[id]
	if ok {
		data, err := os.ReadFile(filepath.Join(v.notesDir(), filepath.FromSlash(n.meta.RelativePath)))
		if err != nil {
			return NoteDocument{}, ErrNoteNotFound
		}
		body := string(normalizeContent(data))
		doc := NoteDocument{
			ID:           n.meta.NoteID,
			Title:        n.title,
			RelativePath: n.meta.RelativePath,
			Markdown:     body,
			CreatedAtUtc: n.meta.CreatedAtUtc,
			UpdatedAtUtc: n.meta.UpdatedAtUtc,
			Revision:     n.meta.Revision,
			Stats:        n.stats,
			Tags:         n.tags,
			Links:        n.linkViews(),
		}
		if doc.Tags == nil {
			doc.Tags = []ExtractedTag{}
		}
		return doc, nil
	}
	if m, ok := v.trashed[id]; ok {
		return NoteDocument{
			ID: m.NoteID, Title: titleOfRelPath(m.RelativePath),
			RelativePath: m.RelativePath, Markdown: "", Trashed: true,
			Tags: []ExtractedTag{}, Links: []LinkView{},
		}, nil
	}
	return NoteDocument{}, ErrNoteNotFound
}

func (n *indexedNote) linkViews() []LinkView {
	views := make([]LinkView, 0, len(n.links))
	for i, l := range n.links {
		views = append(views, LinkView{
			Target:         l.Target,
			Alias:          l.Alias,
			State:          string(n.linkStates[i]),
			ResolvedNoteID: n.resolvedIDs[i],
			Line:           l.Line,
		})
	}
	return views
}

// SaveContent 保存正文并原子落盘，返回回执（FR-EDIT-04/05/06、BR-04/05）。
func (v *Vault) SaveContent(id string, markdown string) (SaveReceipt, error) {
	v.mu.Lock()
	defer v.mu.Unlock()
	n, ok := v.notes[id]
	if !ok {
		return SaveReceipt{}, ErrNoteNotFound
	}
	content := string(normalizeContent([]byte(markdown)))
	sha := sha256Hex([]byte(content))

	metaDir, pendingDir := v.metaDirs()
	op := &PendingOp{
		OperationID:      newID(),
		NoteID:           id,
		Kind:             "save",
		OldSha:           n.meta.ContentSha256,
		NewSha:           sha,
		ExpectedRevision: n.meta.Revision,
	}
	if err := writePending(pendingDir, op); err != nil {
		return SaveReceipt{}, ErrSaveFailed
	}
	if err := atomicWriteFile(filepath.Join(v.notesDir(), filepath.FromSlash(n.meta.RelativePath)), []byte(content)); err != nil {
		return SaveReceipt{}, ErrSaveFailed
	}
	n.meta.Revision++
	n.meta.ContentSha256 = sha
	n.meta.UpdatedAtUtc = nowMs()
	if err := saveNoteMeta(metaDir, n.meta); err != nil {
		return SaveReceipt{}, ErrSaveFailed
	}
	clearPending(pendingDir, op.OperationID)

	v.indexNote(n.meta, content)
	v.recomputeLinks()
	// 广播刷新列表摘要与标签树（轻量，本地内存索引）。
	v.broadcast()
	return SaveReceipt{
		Revision:      n.meta.Revision,
		ContentSha256: sha,
		SavedAtUtc:    n.meta.UpdatedAtUtc,
		Stats:         v.notes[id].stats,
		Tags:          v.notes[id].tags,
		Links:         v.notes[id].linkViews(),
	}, nil
}

// RenameNote 重命名笔记并级联重命名底层文件（FR-NOTE-04），
// 同时自动更新其他笔记中已解析指向本笔记的 [[旧标题]]（FR-LINK-08 的自动策略）。
func (v *Vault) RenameNote(id string, newTitle string) (NoteDocument, error) {
	v.mu.Lock()
	defer v.mu.Unlock()
	n, ok := v.notes[id]
	if !ok {
		return NoteDocument{}, ErrNoteNotFound
	}
	cleaned, _ := sanitizeTitle(newTitle)
	if cleaned == "" {
		return NoteDocument{}, ErrTitleEmpty
	}
	if cleaned == n.title {
		return v.openNoteLocked(id)
	}

	oldTitle := n.title
	parent := n.parentDir
	desired := cleaned
	if parent != "" {
		desired = parent + "/" + cleaned
	}
	newRel := uniqueRelPath(v.notesDir(), strings.ReplaceAll(desired, "/", string(filepath.Separator)))
	// uniqueRelPath 在 notesDir 下展开；目录层级保留。
	newRel = filepath.ToSlash(filepath.Join(parent, filepath.Base(newRel)))
	// 冲突时 uniqueRelPath 追加了序号，实际标题以最终文件名为准。
	actualTitle := titleOfRelPath(newRel)

	// 先按重命名前的解析结果级联改写其他笔记中的 [[旧标题]]（FR-LINK-08），
	// 再执行文件重命名与索引重建。
	v.cascadeUpdateLinks(id, oldTitle, actualTitle)

	metaDir, pendingDir := v.metaDirs()
	op := &PendingOp{
		OperationID: newID(), NoteID: id, Kind: "rename",
		OldRelPath: n.meta.RelativePath, NewRelPath: newRel,
		NewSha: n.meta.ContentSha256, ExpectedRevision: n.meta.Revision,
	}
	if err := writePending(pendingDir, op); err != nil {
		return NoteDocument{}, ErrSaveFailed
	}
	oldAbs := filepath.Join(v.notesDir(), filepath.FromSlash(n.meta.RelativePath))
	newAbs := filepath.Join(v.notesDir(), filepath.FromSlash(newRel))
	if err := os.MkdirAll(filepath.Dir(newAbs), 0o755); err != nil {
		return NoteDocument{}, ErrSaveFailed
	}
	if err := os.Rename(oldAbs, newAbs); err != nil {
		return NoteDocument{}, ErrSaveFailed
	}
	n.meta.RelativePath = newRel
	n.meta.UpdatedAtUtc = nowMs()
	if err := saveNoteMeta(metaDir, n.meta); err != nil {
		return NoteDocument{}, ErrSaveFailed
	}
	clearPending(pendingDir, op.OperationID)

	// 重建本笔记的标题索引并重算全部链接解析。
	v.reindexOne(n, v.readBody(n.meta))
	v.recomputeLinks()
	v.broadcast()
	return v.openNoteLocked(id)
}

// cascadeUpdateLinks 把其他笔记中解析指向 targetID 的 [[旧标题]] 原子改写为新标题。
func (v *Vault) cascadeUpdateLinks(targetID, oldTitle, newTitle string) int {
	oldLower := foldString(oldTitle)
	count := 0
	for _, other := range v.notes {
		runes := []rune(other.body)
		// 从后往前改写，保持前面的区间有效。
		type rewrite struct {
			start, end int
			text       string
		}
		var rewrites []rewrite
		for i, l := range other.links {
			if other.resolvedIDs[i] != targetID || other.linkStates[i] != linkResolved {
				continue
			}
			if l.TargetLower != oldLower {
				continue
			}
			inner := l.Target
			if l.Alias != "" {
				inner = newTitle + "|" + l.Alias
			} else {
				inner = newTitle
			}
			rewrites = append(rewrites, rewrite{l.Start, l.End, "[[" + inner + "]]"})
		}
		if len(rewrites) == 0 {
			continue
		}
		for i := len(rewrites) - 1; i >= 0; i-- {
			rw := rewrites[i]
			if rw.start > len(runes) || rw.end > len(runes) {
				continue
			}
			runes = append(runes[:rw.start], append([]rune(rw.text), runes[rw.end:]...)...)
		}
		newBody := string(runes)
		if err := atomicWriteFile(filepath.Join(v.notesDir(), filepath.FromSlash(other.meta.RelativePath)), []byte(newBody)); err != nil {
			continue
		}
		other.meta.Revision++
		other.meta.UpdatedAtUtc = nowMs()
		metaDir, _ := v.metaDirs()
		saveNoteMeta(metaDir, other.meta)
		v.reindexOne(other, newBody)
		count++
	}
	return count
}

// TrashNote 将笔记移入回收站（BR-11）。撤销与恢复共用 RestoreFromTrash。
func (v *Vault) TrashNote(id string) (WorkspaceStats, error) {
	v.mu.Lock()
	defer v.mu.Unlock()
	n, ok := v.notes[id]
	if !ok {
		return WorkspaceStats{}, ErrNoteNotFound
	}
	metaDir, pendingDir := v.metaDirs()
	op := &PendingOp{
		OperationID: newID(), NoteID: id, Kind: "trash",
		OldRelPath: n.meta.RelativePath, NewSha: n.meta.ContentSha256,
		ExpectedRevision: n.meta.Revision,
	}
	if err := writePending(pendingDir, op); err != nil {
		return WorkspaceStats{}, ErrSaveFailed
	}
	src := filepath.Join(v.notesDir(), filepath.FromSlash(n.meta.RelativePath))
	dst := filepath.Join(v.trashDir(), n.meta.NoteID+".md")
	if err := os.MkdirAll(v.trashDir(), 0o755); err != nil {
		return WorkspaceStats{}, ErrSaveFailed
	}
	if err := os.Rename(src, dst); err != nil {
		return WorkspaceStats{}, ErrSaveFailed
	}
	n.meta.State = stateTrashed
	n.meta.TrashedAtUtc = nowMs()
	if err := saveNoteMeta(metaDir, n.meta); err != nil {
		return WorkspaceStats{}, ErrSaveFailed
	}
	clearPending(pendingDir, op.OperationID)

	v.trashed[id] = n.meta
	delete(v.notes, id)
	v.recomputeLinks()
	v.broadcast()
	return v.stats(), nil
}

// RestoreFromTrash 还原笔记至原路径；原路径被占用时自动追加序号（FR-NOTE-05/07）。
func (v *Vault) RestoreFromTrash(id string) (NoteDocument, error) {
	v.mu.Lock()
	defer v.mu.Unlock()
	m, ok := v.trashed[id]
	if !ok {
		return NoteDocument{}, ErrNoteNotFound
	}
	metaDir, pendingDir := v.metaDirs()
	op := &PendingOp{
		OperationID: newID(), NoteID: id, Kind: "restore",
		OldRelPath: m.RelativePath, NewSha: m.ContentSha256,
		ExpectedRevision: m.Revision,
	}
	if err := writePending(pendingDir, op); err != nil {
		return NoteDocument{}, ErrSaveFailed
	}
	title := titleOfRelPath(m.RelativePath)
	parent := parentDirOfRelPath(m.RelativePath)
	rel := filepath.ToSlash(filepath.Join(parent, filepath.Base(uniqueRelPath(filepath.Join(v.notesDir(), filepath.FromSlash(parent)), title))))
	src := filepath.Join(v.trashDir(), id+".md")
	dst := filepath.Join(v.notesDir(), filepath.FromSlash(rel))
	if err := os.MkdirAll(filepath.Dir(dst), 0o755); err != nil {
		return NoteDocument{}, ErrSaveFailed
	}
	if err := os.Rename(src, dst); err != nil {
		return NoteDocument{}, ErrSaveFailed
	}
	m.State = stateActive
	m.TrashedAtUtc = 0
	m.RelativePath = rel
	if err := saveNoteMeta(metaDir, m); err != nil {
		return NoteDocument{}, ErrSaveFailed
	}
	clearPending(pendingDir, op.OperationID)

	meta := *m
	delete(v.trashed, id)
	v.indexNote(&meta, v.readBody(&meta))
	v.recomputeLinks()
	v.broadcast()
	return v.openNoteLocked(id)
}

// DeleteForever 彻底粉碎回收站中的单篇笔记（FR-NOTE-07）。
func (v *Vault) DeleteForever(id string) (WorkspaceStats, error) {
	v.mu.Lock()
	defer v.mu.Unlock()
	if _, ok := v.trashed[id]; !ok {
		return WorkspaceStats{}, ErrNoteNotFound
	}
	os.Remove(filepath.Join(v.trashDir(), id+".md"))
	os.Remove(filepath.Join(v.metaDir(), id+".json"))
	delete(v.trashed, id)
	v.recomputeLinks()
	v.broadcast()
	return v.stats(), nil
}

// EmptyTrash 清空回收站。
func (v *Vault) EmptyTrash() (WorkspaceStats, error) {
	v.mu.Lock()
	defer v.mu.Unlock()
	ids := make([]string, 0, len(v.trashed))
	for id := range v.trashed {
		ids = append(ids, id)
	}
	for _, id := range ids {
		os.Remove(filepath.Join(v.trashDir(), id+".md"))
		os.Remove(filepath.Join(v.metaDir(), id+".json"))
		delete(v.trashed, id)
	}
	v.broadcast()
	return v.stats(), nil
}

// GetBacklinks 返回解析指向当前笔记的全部来源（FR-LINK-06/07）。
func (v *Vault) GetBacklinks(id string) []BacklinkItem {
	v.mu.Lock()
	defer v.mu.Unlock()
	items := []BacklinkItem{}
	// 便于反链排序：按更新时间新者优先。
	type itemWithTime struct {
		item BacklinkItem
		at   int64
	}
	var found []itemWithTime
	for _, n := range v.notes {
		for i, l := range n.links {
			if n.linkStates[i] != linkResolved || n.resolvedIDs[i] != id {
				continue
			}
			runes := []rune(n.body)
			start := l.Start - 40
			if start < 0 {
				start = 0
			}
			end := l.End + 40
			if end > len(runes) {
				end = len(runes)
			}
			found = append(found, itemWithTime{
				item: BacklinkItem{
					SourceNoteID: n.meta.NoteID,
					SourceTitle:  n.title,
					Line:         l.Line,
					Snippet:      string(runes[start:end]),
				},
				at: n.meta.UpdatedAtUtc,
			})
		}
	}
	sort.SliceStable(found, func(i, j int) bool { return found[i].at > found[j].at })
	for _, f := range found {
		items = append(items, f.item)
	}
	return items
}

// Search 全库全文检索（FR-SEARCH-01~05）。
func (v *Vault) Search(query string) []SearchHit {
	v.mu.Lock()
	defer v.mu.Unlock()
	return search(v.notes, query)
}

// GetNoteLinks 提供指定笔记的链接解析视图（阅读页跳转/候选列表用）。
func (v *Vault) GetNoteLinks(id string) []LinkView {
	v.mu.Lock()
	defer v.mu.Unlock()
	if n, ok := v.notes[id]; ok {
		return n.linkViews()
	}
	return []LinkView{}
}

// ResolveLinkTargets 返回与目标标题同名（大小写不敏感）的候选笔记（AMBIGUOUS 消解）。
func (v *Vault) ResolveLinkTargets(title string) []NoteSummary {
	v.mu.Lock()
	defer v.mu.Unlock()
	out := []NoteSummary{}
	for _, id := range v.byTitle[foldString(strings.TrimSpace(title))] {
		if n, ok := v.notes[id]; ok {
			out = append(out, n.toSummary())
		}
	}
	sortSummaries(out, "title_asc")
	return out
}

// helper 访问器 ---------------------------------------------------------------

// metaDirs 返回元数据目录与 pending 日志目录。
func (v *Vault) metaDirs() (string, string) {
	return filepath.Join(v.root, filepath.FromSlash(dirMetaNotes)),
		filepath.Join(v.root, filepath.FromSlash(dirMetaPending))
}

func (v *Vault) metaDir() string {
	return filepath.Join(v.root, filepath.FromSlash(dirMetaNotes))
}

func (v *Vault) notesDir() string       { return filepath.Join(v.root, dirNotes) }
func (v *Vault) trashDir() string       { return filepath.Join(v.root, dirTrash) }
func (v *Vault) attachmentsDir() string { return filepath.Join(v.root, dirAttachments) }

func (v *Vault) readBody(m *NoteMeta) string {
	data, err := os.ReadFile(filepath.Join(v.notesDir(), filepath.FromSlash(m.RelativePath)))
	if err != nil {
		return ""
	}
	return string(normalizeContent(data))
}

// titleOfRelPath 从相对路径取标题（去扩展名）。
func titleOfRelPath(rel string) string {
	base := filepath.Base(filepath.FromSlash(rel))
	return strings.TrimSuffix(base, filepath.Ext(base))
}

// parentDirOfRelPath 返回相对目录（根目录为 ""）。
func parentDirOfRelPath(rel string) string {
	dir := filepath.ToSlash(filepath.Dir(filepath.FromSlash(rel)))
	if dir == "." || dir == "/" {
		return ""
	}
	return dir
}
