package main

import (
	"encoding/json"
	"net/url"
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

	// writeSeq 记录每篇笔记最后一次成功落盘的内容序号（评审 W03）。
	// 用于拒绝迟到的旧快照：同一篇里序号越大表示内容越新，落盘时若发现
	// 本篇已落盘过更大的序号，这一份就是旧快照，不应覆盖新内容。
	// 只在内存中维护：重启后序号归零，由 UI 层重新分配，不会误判。
	writeSeq map[string]int64
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
			// 必须清空索引（评审 #1）：留着旧库的笔记会让后续保存
			// 在 root=="" 时落到进程 CWD，凭空写出 notes/ 与 .meta/。
			v.resetWorkspaceState()
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
func (v *Vault) ChooseWorkspace(parent *mygo.Window) (WorkspaceInfo, error) {
	paths, err := mygo.Dialog.Open(mygo.OpenDialogOptions{
		Parent:            parent,
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

// switchWorkspace 切换知识库根目录。
//
// 失败时必须把内存状态清干净（评审 #1）：
// 旧实现在 loadWorkspace 失败时只把 root 置空，却留着上一座知识库的索引，
// 于是 GetWorkspace().Ready 为 false 但 v.notes 仍是旧库的笔记。
// 后续任何保存都会在 root=="" 的情况下按 filepath.Join("", "notes", ...)
// 落到**进程当前工作目录**下凭空写出 notes/ 与 .meta/——用户换库失败一次，
// 磁盘上就多出一堆不知哪来的文件。
//
// 因此这里的规则是：root 为空时一切写操作一律拒绝，绝不落到 CWD。
func (v *Vault) switchWorkspace(root string) error {
	v.mu.Lock()
	defer v.mu.Unlock()
	abs, err := filepath.Abs(root)
	if err != nil {
		return err
	}
	// 先切到新根目录再装载：装载失败时下方会把 root 清空并清空索引。
	v.root = abs
	if err := v.loadWorkspace(); err != nil {
		v.resetWorkspaceState()
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

// resetWorkspaceState 把工作区状态清空：root 置空、索引与回收站清空。
// 用于工作区装载失败（目录被外部删除、权限不足等）——此时磁盘上的事实源
// 已经不可用，留着旧索引只会让后续操作写到错误的地方（评审 #1）。
func (v *Vault) resetWorkspaceState() {
	v.root = ""
	v.notes = map[string]*indexedNote{}
	v.trashed = map[string]*NoteMeta{}
	v.byTitle = map[string][]string{}
	v.writeSeq = nil
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
		// notes 是直接使用该标签的笔记 ID 集合。
		// 用集合而非计数，是为了让父节点的计数能按"笔记数"去重：
		// 一篇笔记同时写了 #a 与 #a/b 时，子树里它只能算一次。
		notes map[string]bool
	}
	infos := map[string]*tagInfo{}
	for id, n := range v.notes {
		for _, t := range n.tags {
			info, ok := infos[t.NameLower]
			if !ok {
				info = &tagInfo{display: t.Name, notes: map[string]bool{}}
				infos[t.NameLower] = info
			}
			info.notes[id] = true
		}
	}

	// 按层级拆分构建指针树：之前的值拷贝建树会在子孙回填时丢失更新，
	// 且"仅无斜杠路径作根"漏掉了多级标签的父节点（整棵树消失）。
	type pnode struct {
		name string
		path string
		// own 是直接使用该标签的笔记 ID 集合。
		own map[string]bool
		// cnt 是子树内去重后的笔记数（含自身与所有子孙，评审 W14）。
		cnt int
		// subtree 是整棵子树里出现过的笔记 ID 集合（物化时回填）。
		subtree map[string]bool
		kids    []*pnode
	}
	nodes := map[string]*pnode{}
	var ensure func(path string) *pnode
	ensure = func(path string) *pnode {
		if p, ok := nodes[path]; ok {
			return p
		}
		parts := strings.Split(path, "/")
		p := &pnode{name: parts[len(parts)-1], path: path, own: map[string]bool{}}
		nodes[path] = p
		if info, ok := infos[path]; ok {
			// 展示名取本级段的原始大小写（info.display 是含斜杠的全路径）。
			segs := strings.Split(info.display, "/")
			p.name = segs[len(segs)-1]
			p.own = info.notes
		}
		if parent := strings.Join(parts[:len(parts)-1], "/"); parent != "" {
			pp := ensure(parent)
			pp.kids = append(pp.kids, p)
		}
		return p
	}
	paths := make([]string, 0, len(infos))
	for p := range infos {
		paths = append(paths, p)
	}
	sort.Strings(paths)
	for _, p := range paths {
		ensure(p)
	}

	// 物化为值树返回，按路径稳定排序。
	//
	// 父节点的计数是**整棵子树**内去重后的笔记数（评审 W14）：
	// 旧实现只统计"直接关联该路径"的笔记，#复习计划/第一章 的父节点
	// #复习计划 显示 0 而子节点有值，侧栏树里容易被误解成没有笔记。
	// 去重是必要的：一篇笔记同时写了 #a 与 #a/b，在 a 的子树里只能算一次。
	var materialize func(p *pnode) TagNode
	materialize = func(p *pnode) TagNode {
		sort.Slice(p.kids, func(i, j int) bool { return p.kids[i].path < p.kids[j].path })
		// 先收集子孙，再算自己的计数。
		children := make([]TagNode, 0, len(p.kids))
		subtree := map[string]bool{}
		for id := range p.own {
			subtree[id] = true
		}
		for _, k := range p.kids {
			child := materialize(k)
			children = append(children, child)
			for id := range k.subtree {
				subtree[id] = true
			}
		}
		p.subtree = subtree
		node := TagNode{Name: p.name, Path: p.path, Count: len(subtree), Children: children}
		return node
	}
	roots := []string{}
	for path := range nodes {
		if !strings.Contains(path, "/") {
			roots = append(roots, path)
		}
	}
	sort.Strings(roots)
	out := []TagNode{}
	for _, path := range roots {
		out = append(out, materialize(nodes[path]))
	}
	return out
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

// NoteFileExists 判断笔记的正文文件是否仍在磁盘上（评审 W02）。
// 内存索引在运行期不监听文件系统，外部删除 .md 后索引里会留下"僵尸笔记"：
// 列表里还在、点不开、也删不掉。界面据此给出提示并清理索引。
func (v *Vault) NoteFileExists(id string) bool {
	v.mu.Lock()
	defer v.mu.Unlock()
	n, ok := v.notes[id]
	if !ok {
		return false
	}
	abs := filepath.Join(v.notesDir(), filepath.FromSlash(n.meta.RelativePath))
	_, err := os.Stat(abs)
	return err == nil
}

// DropVanishedNote 把正文文件已被外部删除的笔记从内存索引中移除（评审 W02）。
// 只清索引与元数据，不动回收站与磁盘上的其它文件：
// 文件已经不在了，留下元数据只会让下次启动再重建一条孤儿。
// 返回被移除的笔记标题，供界面提示用户。
func (v *Vault) DropVanishedNote(id string) (string, error) {
	v.mu.Lock()
	defer v.mu.Unlock()
	n, ok := v.notes[id]
	if !ok {
		return "", ErrNoteNotFound
	}
	abs := filepath.Join(v.notesDir(), filepath.FromSlash(n.meta.RelativePath))
	if _, err := os.Stat(abs); err == nil {
		// 文件还在，不是僵尸笔记，不做处理。
		return "", ErrNoteNotFound
	}
	title := n.title
	delete(v.notes, id)
	// 标题索引里也要摘掉，否则同名解析会指向已经不存在的 noteID。
	lower := n.titleLower
	kept := v.byTitle[lower][:0]
	for _, cid := range v.byTitle[lower] {
		if cid != id {
			kept = append(kept, cid)
		}
	}
	if len(kept) == 0 {
		delete(v.byTitle, lower)
	} else {
		v.byTitle[lower] = kept
	}
	// 元数据一并移除：文件已不存在，留着只会变成孤儿元数据。
	metaDir, _ := v.metaDirs()
	os.Remove(filepath.Join(metaDir, id+".json"))
	// 指向它的双链变成未解析，反链面板也要随之更新。
	v.recomputeLinks()
	return title, nil
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
// 落盘前以哈希校验磁盘内容与上次回执一致；被外部修改时返回 ErrExternalEdit
// 且不覆盖（评审 #7）。确认覆盖外部修改用 ForceSaveContent。
func (v *Vault) SaveContent(id string, markdown string) (SaveReceipt, error) {
	return v.saveContent(id, markdown, false, 0)
}

// SaveContentSeq 与 SaveContent 相同，但携带写入序号做乱序保护（评审 W03）。
//
// seq 是「这份内容在编辑器里的先后序号」：同一篇笔记里，序号越大表示内容越新。
// 落盘时若发现本篇已落盘过序号更大的内容，说明这一份是迟到的旧快照，
// 返回 ErrStaleRevision 且不覆盖。seq 为 0 表示不做乱序保护（领域内部调用：
// 导入、级联改写等不涉及编辑竞态的路径）。
//
// 为什么不用「基线版本号」判定：两份内容可能基于同一个基线（都是用户在
// 同一段编辑里先后产生的），基线版本相同就无法区分新旧；而按序号比较，
// 「旧快照后落盘」会被准确拦下，「新快照后落盘」正常生效。
func (v *Vault) SaveContentSeq(id string, markdown string, seq int64) (SaveReceipt, error) {
	return v.saveContent(id, markdown, false, seq)
}

// ForceSaveContent 跳过外部修改检测强制落盘（用户显式确认覆盖后调用）。
func (v *Vault) ForceSaveContent(id string, markdown string) (SaveReceipt, error) {
	return v.saveContent(id, markdown, true, 0)
}

// ForceSaveContentSeq 与 ForceSaveContent 相同，但携带写入序号做乱序保护。
func (v *Vault) ForceSaveContentSeq(id string, markdown string, seq int64) (SaveReceipt, error) {
	return v.saveContent(id, markdown, true, seq)
}

func (v *Vault) saveContent(id string, markdown string, force bool, seq int64) (SaveReceipt, error) {
	v.mu.Lock()
	defer v.mu.Unlock()
	// 未选择工作区时一律拒绝写入（评审 #1）。
	// 旧实现只判 v.notes[id] 是否存在，而工作区装载失败后 root 被置空、
	// 索引却还留着旧库的笔记，于是 filepath.Join("", "notes", ...) 落到
	// **进程当前工作目录**下，凭空写出 notes/ 与 .meta/。
	// 这里把"没有工作区"作为独立的前置条件拦下，绝不落到 CWD。
	if v.root == "" {
		return SaveReceipt{}, ErrNoWorkspace
	}
	n, ok := v.notes[id]
	if !ok {
		return SaveReceipt{}, ErrNoteNotFound
	}
	// 乱序保护（评审 W03）：本篇已落盘过更新的内容，这一份是迟到的旧快照，
	// 落盘会静默覆盖用户的新输入。必须在哈希校验之前判断——旧快照的哈希
	// 与磁盘不符，下面会先返回 ErrExternalEdit，同样能拦住，但错误语义不对：
	// 那不是外部修改，是本进程内的保存竞态。
	if seq > 0 && seq <= v.writeSeq[id] {
		return SaveReceipt{}, ErrStaleRevision
	}
	abs := filepath.Join(v.notesDir(), filepath.FromSlash(n.meta.RelativePath))
	if !force {
		if disk, err := os.ReadFile(abs); err == nil {
			if sha256Hex(normalizeContent(disk)) != n.meta.ContentSha256 {
				return SaveReceipt{}, ErrExternalEdit
			}
		} else if !os.IsNotExist(err) {
			return SaveReceipt{}, ErrSaveFailed
		}
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
	if err := atomicWriteFile(abs, []byte(content)); err != nil {
		// 正文一个字节都没变，这条 pending 意图已无意义：清理掉，
		// 免得残留日志让下次启动的对账做无用功。
		clearPending(pendingDir, op.OperationID)
		return SaveReceipt{}, ErrSaveFailed
	}
	n.meta.Revision++
	n.meta.ContentSha256 = sha
	n.meta.UpdatedAtUtc = nowMs()
	if err := saveNoteMeta(metaDir, n.meta); err != nil {
		// 正文已落盘、元数据未提交：必须保留 pending，下次启动
		// loadWorkspace 会按 NewSha 幂等补完元数据（设计 6.2）。
		return SaveReceipt{}, ErrSaveFailed
	}
	clearPending(pendingDir, op.OperationID)

	// 记录本次落盘的内容序号：更大的序号代表更新的内容，迟到的旧快照
	// 会因 seq <= writeSeq[id] 被拒（评审 W03）。
	if seq > 0 {
		if v.writeSeq == nil {
			v.writeSeq = map[string]int64{}
		}
		v.writeSeq[id] = seq
	}

	v.indexNote(n.meta, content)
	// 保存不改变标题集合：只重解析本篇出链，避免全量重算（评审 #8）。
	v.resolveNoteLinks(v.notes[id])
	// 广播刷新列表摘要与标签树（轻量，本地内存索引）。
	return SaveReceipt{
		Revision:      n.meta.Revision,
		ContentSha256: sha,
		SavedAtUtc:    n.meta.UpdatedAtUtc,
		Stats:         v.notes[id].stats,
		Tags:          v.notes[id].tags,
		Links:         v.notes[id].linkViews(),
	}, nil
}

// RenameReport 是一次重命名对其它笔记的级联影响（FR-LINK-08 自动策略）。
// Affected 记录正文已被自动改写并重新落盘/重建索引的来源笔记 ID——界面要用
// 这些 ID 丢弃已缓存的编辑器 buffer，否则编辑器里仍显示 [[旧标题]]；
// Skipped 记录因磁盘已被外部修改（或读盘失败）而保持原文、需要用户手动
// 处理的来源笔记 ID。
type RenameReport struct {
	Affected []string `json:"affected"`
	Skipped  []string `json:"skipped"`
	// External 是因磁盘正文被外部程序改过而跳过的笔记 ID。
	External []string `json:"external"`
	// Dirty 是因编辑器里还有未落盘草稿而跳过的笔记 ID：
	// 磁盘内容没被外部动过，只是内存里的正文比磁盘新。
	Dirty []string `json:"dirty"`
}

// emptyRenameReport 返回可直接 JSON 序列化的空报告（nil 切片会序列化成 null）。
func emptyRenameReport() RenameReport {
	return RenameReport{
		Affected: []string{},
		Skipped:  []string{},
		External: []string{},
		Dirty:    []string{},
	}
}

// RenameNote 重命名笔记并级联重命名底层文件（FR-NOTE-04），
// 同时自动更新其他笔记中已解析指向本笔记的 [[旧标题]]（FR-LINK-08 的自动策略）。
// 保持原签名，内部委托 RenameNoteReported；需要级联报告的调用方用后者。
func (v *Vault) RenameNote(id string, newTitle string) (NoteDocument, error) {
	doc, _, err := v.RenameNoteReported(id, newTitle)
	return doc, err
}

// RenameNoteReported 是 RenameNote 的带报告版本（FR-LINK-08、设计 6.2）。
//
// 落盘顺序（审计缺陷 1 的修复）：
//  1. 先写 pending 意图：崩溃恢复靠它把"打算改成哪个文件名"补完，同时它也是
//     级联改写的前置闸门——pending 写失败就提前返回，其它笔记一个字都不会被改到。
//  2. 级联改写其它笔记（逐篇读盘 + 哈希校验，见 cascadeUpdateLinks）。
//  3. 正文文件 os.Rename。
//  4. 保存本笔记 meta、清理 pending、重命名后重建索引与链接解析。
//
// 任一步之后失败都会回滚已级联改写的正文与元数据，避免留下
// "[[新标题]] 指向不存在的笔记" 的破损链接与消失的反链。
func (v *Vault) RenameNoteReported(id string, newTitle string) (NoteDocument, RenameReport, error) {
	v.mu.Lock()
	defer v.mu.Unlock()
	n, ok := v.notes[id]
	if !ok {
		return NoteDocument{}, emptyRenameReport(), ErrNoteNotFound
	}
	cleaned, _ := sanitizeTitle(newTitle)
	if cleaned == "" {
		return NoteDocument{}, emptyRenameReport(), ErrTitleEmpty
	}
	if cleaned == n.title {
		doc, err := v.openNoteLocked(id)
		return doc, emptyRenameReport(), err
	}

	oldTitle := n.title
	parent := n.parentDir
	desired := cleaned
	if parent != "" {
		desired = parent + "/" + cleaned
	}
	desiredRel := filepath.ToSlash(desired) + ".md"
	var newRel string
	if foldString(desiredRel) == foldString(n.meta.RelativePath) {
		// 仅大小写变化：Windows 文件系统不区分大小写，uniqueRelPath 会把
		// 自身文件误判为冲突而追加序号，这里直接采用目标名（评审 #5）。
		newRel = desiredRel
	} else {
		newRel = uniqueRelPath(v.notesDir(), strings.ReplaceAll(desired, "/", string(filepath.Separator)))
		// uniqueRelPath 在 notesDir 下展开；目录层级保留。
		newRel = filepath.ToSlash(filepath.Join(parent, filepath.Base(newRel)))
	}
	// 冲突时 uniqueRelPath 追加了序号，实际标题以最终文件名为准。
	actualTitle := titleOfRelPath(newRel)

	metaDir, pendingDir := v.metaDirs()
	op := &PendingOp{
		OperationID: newID(), NoteID: id, Kind: "rename",
		OldRelPath: n.meta.RelativePath, NewRelPath: newRel,
		NewSha: n.meta.ContentSha256, ExpectedRevision: n.meta.Revision,
	}
	// 为什么先写 pending：旧实现先级联改正文、后写 pending，pending 失败时
	// 直接返回，磁盘上的 [[旧标题]] 已被改成新标题，链接全部变成 UNRESOLVED、
	// 反链消失且无从回滚（审计实测）。先写意图可把这个窗口关掉。
	if err := writePending(pendingDir, op); err != nil {
		return NoteDocument{}, emptyRenameReport(), ErrSaveFailed
	}

	report := emptyRenameReport()
	// cascadePersistErr 记录"级联记录写不进 pending"的致命错误。
	// 记录不下来就回滚不了，崩溃后会留下破损链接，因此必须中止整个改名。
	var cascadePersistErr error
	// changes 是已级联改写的笔记；persistCascade 在回调里需要它来回滚，
	// 因此先声明再由 cascadeUpdateLinks 赋值。
	var changes []cascadeChange
	// 崩溃安全（评审 #2）：级联每改写一篇，就把该篇的改写前正文追加进
	// pending 文件。这样"级联已成功、本篇尚未改名"时崩溃，重启对账能
	// 依据 pending 把这一批精确回滚，而不是留下指向不存在笔记的 [[新标题]]
	// 与凭空消失的反链。
	//
	// 追加而非覆盖：pending 里已有的级联记录必须保留，
	// 每追加一次都要重新落盘整个 pending 文件（内容很小，成本可忽略）。
	persistCascade := func(items []PendingCascadeNote) error {
		op.Cascade = append(op.Cascade, items...)
		// pending 写失败时不能继续级联：记录不下来就回滚不了，
		// 崩溃后会留下破损链接。此时中止并回滚已改写的部分。
		if err := writePending(pendingDir, op); err != nil {
			cascadePersistErr = ErrSaveFailed
			return ErrSaveFailed
		}
		return nil
	}
	var cascadeErr error
	changes, cascadeErr = v.cascadeUpdateLinks(id, oldTitle, actualTitle, &report, persistCascade)
	if cascadeErr != nil {
		// 级联中途失败（含 pending 记不下来）：把已改写的全部恢复回去。
		v.rollbackCascade(changes)
		clearPending(pendingDir, op.OperationID)
		return NoteDocument{}, emptyRenameReport(), cascadeErr
	}
	if cascadePersistErr != nil {
		return NoteDocument{}, emptyRenameReport(), cascadePersistErr
	}
	// rollbackAll 是后续步骤失败的统一善后：恢复级联正文 + 清掉 pending。
	rollbackAll := func() {
		v.rollbackCascade(changes)
		clearPending(pendingDir, op.OperationID)
	}

	oldRel := n.meta.RelativePath
	oldAbs := filepath.Join(v.notesDir(), filepath.FromSlash(oldRel))
	newAbs := filepath.Join(v.notesDir(), filepath.FromSlash(newRel))
	if err := os.MkdirAll(filepath.Dir(newAbs), 0o755); err != nil {
		rollbackAll()
		return NoteDocument{}, emptyRenameReport(), ErrSaveFailed
	}
	if err := os.Rename(oldAbs, newAbs); err != nil {
		rollbackAll()
		return NoteDocument{}, emptyRenameReport(), ErrSaveFailed
	}
	n.meta.RelativePath = newRel
	// 改名不顶起更新时间：否则在标题框逐字输入时卡片按"更新时间"
	// 排序会不停跳到列表顶部（自测复现）。正文更新才计入更新时间。
	if err := saveNoteMeta(metaDir, n.meta); err != nil {
		// 元数据没提交成功：先把文件改回原名，再回滚级联改写，
		// 让磁盘与索引都回到"什么也没发生"的状态。
		n.meta.RelativePath = oldRel
		os.Rename(newAbs, oldAbs)
		rollbackAll()
		return NoteDocument{}, emptyRenameReport(), ErrSaveFailed
	}
	clearPending(pendingDir, op.OperationID)

	// 重建本笔记的标题索引并重算全部链接解析。
	v.reindexOne(n, v.readBody(n.meta))
	v.recomputeLinks()
	doc, err := v.openNoteLocked(id)
	// 排序让界面按稳定顺序刷新，避免 map 遍历顺序带来的抖动。
	sort.Strings(report.Affected)
	sort.Strings(report.Skipped)
	return doc, report, err
}

// cascadeChange 是一次级联改写的回滚快照：正文与元数据都要能恢复原样。
type cascadeChange struct {
	noteID  string
	relPath string   // 改写时笔记所在路径（重命名回滚时仍是旧路径）
	oldBody string   // 改写前的磁盘正文（已归一化）
	newBody string   // 改写后写入的正文（用于崩溃对账时判断是否已改写）
	meta    NoteMeta // 改写前的元数据副本
}

// pendingCascadeNote 把回滚快照转成 pending 里可序列化的形式。
func (c cascadeChange) pendingCascadeNote() PendingCascadeNote {
	return PendingCascadeNote{
		NoteID:          c.noteID,
		RelPath:         c.relPath,
		OldBody:         c.oldBody,
		NewBody:         c.newBody,
		OldRevision:     c.meta.Revision,
		OldUpdatedAtUtc: c.meta.UpdatedAtUtc,
	}
}

// cascadeUpdateLinks 把其他笔记中解析指向 targetID 的 [[旧标题]] 原子改写为新标题。
// 返回已改写的回滚快照（调用方在后续步骤失败时据此恢复），以及首个致命错误。
//
// 外部修改保护（审计缺陷 2）：旧实现直接用内存 other.body 整篇回写磁盘，
// 若该笔记在本次会话中被外部程序改过，用户新写的内容会被静默覆盖（实测整段消失）。
// 因此逐篇先读盘，只有「磁盘正文哈希 == 元数据记录的 ContentSha256」且
// 「磁盘正文与内存正文一致」时才改写；否则计入 Skipped 跳过，把控制权交还用户。
//
// 崩溃安全（评审 #2）：每改写一篇就把它的改写前正文追加进 pending 文件
// （onCascade 回调），这样"级联已成功、本篇尚未改名"时崩溃，重启对账能
// 依据 pending 把这一批精确回滚，而不是留下指向不存在笔记的 [[新标题]]。
//
// onCascade 返回错误时立即中止级联：记录不下来就回滚不了。
func (v *Vault) cascadeUpdateLinks(targetID, oldTitle, newTitle string, report *RenameReport, onCascade func([]PendingCascadeNote) error) ([]cascadeChange, error) {
	oldLower := foldString(oldTitle)
	metaDir, _ := v.metaDirs()
	changes := []cascadeChange{}
	for id, other := range v.notes {
		// 从内存索引里挑出「已解析指向 targetID、写法仍是旧标题」的链接。
		// 抽取位置按 rune 计，下面在磁盘正文的 rune 上从后往前改写。
		type linkRewrite struct {
			start, end int
			text       string
		}
		var rewrites []linkRewrite
		for i, l := range other.links {
			if other.linkStates[i] != linkResolved || other.resolvedIDs[i] != targetID {
				continue
			}
			if l.TargetLower != oldLower {
				continue
			}
			inner := newTitle
			if l.Alias != "" {
				inner = newTitle + "|" + l.Alias
			}
			rewrites = append(rewrites, linkRewrite{l.Start, l.End, "[[" + inner + "]]"})
		}
		if len(rewrites) == 0 {
			continue
		}

		abs := filepath.Join(v.notesDir(), filepath.FromSlash(other.meta.RelativePath))
		disk, err := os.ReadFile(abs)
		if err != nil {
			// 读不到磁盘正文就无法确认内存索引仍然有效，宁可跳过也不猜测。
			report.Skipped = append(report.Skipped, id)
			continue
		}
		diskBody := string(normalizeContent(disk))
		// 两种跳过原因要分开（评审 N2）：提示语不同，处理方式也不同。
		//   1) 磁盘哈希与元数据不符 —— 确实被外部程序改过；
		//   2) 磁盘正文与内存正文不符 —— 只是这篇在编辑器里有未落盘的草稿，
		//      磁盘本身没被外部动过。旧实现把两者都报成"已被其它程序修改"，
		//      用户看到提示却找不到哪一篇被改了。
		if sha256Hex([]byte(diskBody)) != other.meta.ContentSha256 {
			report.External = append(report.External, id)
			report.Skipped = append(report.Skipped, id)
			continue
		}
		if diskBody != other.body {
			report.Dirty = append(report.Dirty, id)
			report.Skipped = append(report.Skipped, id)
			continue
		}

		runes := []rune(diskBody)
		for i := len(rewrites) - 1; i >= 0; i-- {
			rw := rewrites[i]
			if rw.start < 0 || rw.end > len(runes) || rw.start >= rw.end {
				continue
			}
			runes = append(runes[:rw.start], append([]rune(rw.text), runes[rw.end:]...)...)
		}
		newBody := string(runes)
		if newBody == diskBody {
			continue
		}

		snapshot := cascadeChange{noteID: id, relPath: other.meta.RelativePath, oldBody: diskBody, newBody: newBody, meta: *other.meta}
		// 崩溃安全（评审 #2）：**先**把改写前正文记进 pending，再落盘。
		// 顺序很重要——如果先落盘再记 pending，崩在两者之间时，
		// 重启对账既看不到 pending 里的旧正文，磁盘上又已是新标题，
		// 这一篇就永远回不去了。
		if onCascade != nil {
			if err := onCascade([]PendingCascadeNote{snapshot.pendingCascadeNote()}); err != nil {
				return changes, err
			}
		}
		if err := atomicWriteFile(abs, []byte(newBody)); err != nil {
			return changes, ErrSaveFailed
		}
		// 正文已落盘：同步元数据的修订号/哈希/更新时间，避免后续保存把这次
		// 自动改写误判成外部修改（评审 #7）。
		other.meta.Revision++
		other.meta.UpdatedAtUtc = nowMs()
		other.meta.ContentSha256 = sha256Hex([]byte(newBody))
		if err := saveNoteMeta(metaDir, other.meta); err != nil {
			// 元数据提交失败：把这一篇的正文也退回去，保持磁盘自洽。
			atomicWriteFile(abs, []byte(diskBody))
			*other.meta = snapshot.meta
			return changes, ErrSaveFailed
		}
		v.reindexOne(other, newBody)
		v.resolveNoteLinks(other)
		changes = append(changes, snapshot)
		report.Affected = append(report.Affected, id)
	}
	return changes, nil
}

// rollbackCascade 尽力把已级联改写的笔记恢复成改写前的正文与元数据。
// 已在错误路径上，单条失败不向外传播；结束时全量重算链接，
// 保证链接状态与恢复后的标题集合一致（不会留下破损链接）。
func (v *Vault) rollbackCascade(changes []cascadeChange) {
	if len(changes) == 0 {
		return
	}
	metaDir, _ := v.metaDirs()
	for i := len(changes) - 1; i >= 0; i-- {
		ch := changes[i]
		n, ok := v.notes[ch.noteID]
		if !ok {
			continue
		}
		atomicWriteFile(filepath.Join(v.notesDir(), filepath.FromSlash(ch.relPath)), []byte(ch.oldBody))
		*n.meta = ch.meta
		saveNoteMeta(metaDir, n.meta)
		v.reindexOne(n, ch.oldBody)
	}
	v.recomputeLinks()
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
		// 正文文件已被外部删除（评审 W02）：此时没有可移入回收站的内容，
		// 但删除意图仍然成立——把这条僵尸笔记从索引里清掉即可，
		// 不能让用户被一条点不动、删不掉的记录卡住。
		if os.IsNotExist(err) {
			n.meta.State = stateTrashed
			n.meta.TrashedAtUtc = nowMs()
			saveNoteMeta(metaDir, n.meta)
			clearPending(pendingDir, op.OperationID)
			delete(v.notes, id)
			lower := n.titleLower
			kept := v.byTitle[lower][:0]
			for _, cid := range v.byTitle[lower] {
				if cid != id {
					kept = append(kept, cid)
				}
			}
			if len(kept) == 0 {
				delete(v.byTitle, lower)
			} else {
				v.byTitle[lower] = kept
			}
			v.recomputeLinks()
			return v.stats(), nil
		}
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
	title := titleOfRelPath(m.RelativePath)
	parent := parentDirOfRelPath(m.RelativePath)
	rel := filepath.ToSlash(filepath.Join(parent, filepath.Base(uniqueRelPath(filepath.Join(v.notesDir(), filepath.FromSlash(parent)), title))))
	metaDir, pendingDir := v.metaDirs()
	op := &PendingOp{
		OperationID: newID(), NoteID: id, Kind: "restore",
		OldRelPath: m.RelativePath, NewRelPath: rel, NewSha: m.ContentSha256,
		ExpectedRevision: m.Revision,
	}
	if err := writePending(pendingDir, op); err != nil {
		return NoteDocument{}, ErrSaveFailed
	}
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
	return v.openNoteLocked(id)
}

// DeleteForever 彻底粉碎回收站中的单篇笔记（FR-NOTE-07）。
// 先写 pending 意图再物理删除；删除失败返回错误并保持磁盘与索引一致（评审 #10）。
func (v *Vault) DeleteForever(id string) (WorkspaceStats, error) {
	v.mu.Lock()
	defer v.mu.Unlock()
	m, ok := v.trashed[id]
	if !ok {
		return WorkspaceStats{}, ErrNoteNotFound
	}
	if err := v.deleteForeverLocked(m); err != nil {
		return WorkspaceStats{}, err
	}
	return v.stats(), nil
}

// EmptyTrash 清空回收站。单条失败不中断其余条目，结束时返回首个错误。
func (v *Vault) EmptyTrash() (WorkspaceStats, error) {
	v.mu.Lock()
	defer v.mu.Unlock()
	ids := make([]string, 0, len(v.trashed))
	for id := range v.trashed {
		ids = append(ids, id)
	}
	var firstErr error
	for _, id := range ids {
		if err := v.deleteForeverLocked(v.trashed[id]); err != nil && firstErr == nil {
			firstErr = err
		}
	}
	return v.stats(), firstErr
}

// deleteForeverLocked 物理删除一篇回收站笔记（文件 + 元数据）。
// 只有两处删除都成功才更新内存状态，避免"磁盘删失败但索引已移除"的不一致。
func (v *Vault) deleteForeverLocked(m *NoteMeta) error {
	metaDir, pendingDir := v.metaDirs()
	op := &PendingOp{
		OperationID: newID(), NoteID: m.NoteID, Kind: "delete",
		OldRelPath: m.RelativePath, NewSha: m.ContentSha256,
		ExpectedRevision: m.Revision,
	}
	if err := writePending(pendingDir, op); err != nil {
		return ErrSaveFailed
	}
	if err := removeIfExists(filepath.Join(v.trashDir(), m.NoteID+".md")); err != nil {
		return ErrSaveFailed
	}
	if err := removeIfExists(filepath.Join(metaDir, m.NoteID+".json")); err != nil {
		return ErrSaveFailed
	}
	clearPending(pendingDir, op.OperationID)
	delete(v.trashed, m.NoteID)
	return nil
}

// removeIfExists 删除文件；不存在视为成功，其余错误原样返回。
func removeIfExists(path string) error {
	if err := os.Remove(path); err != nil && !os.IsNotExist(err) {
		return err
	}
	return nil
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

// LookupNote 返回笔记是否存在且活跃。
func (v *Vault) LookupNote(id string) bool {
	v.mu.Lock()
	defer v.mu.Unlock()
	_, ok := v.notes[id]
	return ok
}

// NoteDir 返回笔记所在目录（相对 notes/，根目录为 ""）。
func (v *Vault) NoteDir(id string) string {
	v.mu.Lock()
	defer v.mu.Unlock()
	if n, ok := v.notes[id]; ok {
		return n.parentDir
	}
	return ""
}

// NoteAssetPath 解析笔记内引用的本地资源路径（评审 #4）：
// 依次尝试笔记目录相对、notes/ 根相对、工作区相对（attachments/… 前缀引用）
// 与 attachments/ 目录相对（裸文件名），校验不越出对应根后返回绝对路径
// （NFR-SEC-03）。url 含协议头时拒绝。
func (v *Vault) NoteAssetPath(id, url string) (string, error) {
	if strings.Contains(url, "://") || strings.HasPrefix(url, "data:") {
		return "", ErrPathOutside
	}
	v.mu.Lock()
	rel := ""
	if n, ok := v.notes[id]; ok {
		rel = n.meta.RelativePath
	}
	root := v.root
	v.mu.Unlock()
	if rel == "" {
		return "", ErrNoteNotFound
	}
	dir := parentDirOfRelPath(rel)
	type candidate struct{ base, rel string }
	cands := []candidate{}
	if dir != "" {
		cands = append(cands, candidate{filepath.Join(root, dirNotes), dir + "/" + url})
	}
	// 作用域限于 notes/ 与 attachments/（评审 W11）。
	// 旧实现还把工作区根作为候选，![](secret.txt) 就能读到根下的任意文件，
	// 连 .meta/ 里的元数据 JSON 也在射程内——与 NFR-SEC-03
	// 「附件不得越界读取」相悖。附件本来就落在 attachments/，
	// 正文在 notes/，根目录不是附件位置，去掉它不影响正常引用。
	//
	// 引用路径可以是工作区相对（"attachments/x.png"），
	// 也可以是相对 attachments/ 本身（"x.png"），两种都要认。
	notesBase := filepath.Join(root, dirNotes)
	attBase := filepath.Join(root, dirAttachments)
	cands = append(cands,
		candidate{notesBase, url},
		candidate{attBase, url},
		candidate{notesBase, strings.TrimPrefix(url, dirNotes+"/")},
		candidate{attBase, strings.TrimPrefix(url, dirAttachments+"/")},
	)
	for _, cand := range cands {
		clean, err := safeRelPath(cand.base, cand.rel)
		if err != nil {
			continue
		}
		abs := filepath.Join(cand.base, filepath.FromSlash(clean))
		if st, err := os.Stat(abs); err == nil && !st.IsDir() {
			return abs, nil
		}
	}
	return "", os.ErrNotExist
}

// ReadNoteAsset 读取笔记引用的本地资源内容（评审 W12）。
//
// 读文件属于领域职责：视图层不直接操作文件系统（windows/AGENTS.md）。
// 路径解析与越界校验仍在 NoteAssetPath 里，这里只负责按解析出的路径取字节。
func (v *Vault) ReadNoteAsset(id, url string) ([]byte, error) {
	abs, err := v.NoteAssetPath(id, url)
	if err != nil {
		return nil, err
	}
	return os.ReadFile(abs)
}

// readerScheme 已解析/未解析双链在阅读源中的协议前缀。
const (
	readerSchemeNote = "justmd-note://" // 目标为 noteID
	readerSchemeOpen = "justmd-open://" // 目标为待创建/歧义的标题
)

// RenderReaderSource 返回阅读页用的正文：把 [[双链]] 改写为 Markdown 链接
// （代码区内不处理），已解析链接指向目标 noteID，未解析/歧义链接指向目标标题。
// 阅读器只读该快照，不回写正文（FR-EDIT-01、BR-06）。
func (v *Vault) RenderReaderSource(id string) string {
	v.mu.Lock()
	defer v.mu.Unlock()
	n, ok := v.notes[id]
	if !ok {
		return ""
	}
	runes := []rune(n.body)
	for i := len(n.links) - 1; i >= 0; i-- {
		l := n.links[i]
		if l.Start > len(runes) || l.End > len(runes) || l.Start >= l.End {
			continue
		}
		label := l.Alias
		if label == "" {
			label = l.Target
		}
		var linkURL string
		switch n.linkStates[i] {
		case linkResolved:
			linkURL = readerSchemeNote + n.resolvedIDs[i]
		default:
			linkURL = readerSchemeOpen + url.PathEscape(l.Target)
		}
		rewritten := []rune("[" + label + "](" + linkURL + ")")
		runes = append(runes[:l.Start], append(rewritten, runes[l.End:]...)...)
	}
	return string(runes)
}
