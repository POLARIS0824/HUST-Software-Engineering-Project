package main

import (
	"os"
	"path/filepath"
	"sort"
	"strings"
)

// CategoryNode 是分类树的一个节点（FR-NOTE-02 的分类视图）。
//
// 分类不是独立的事实源：它就是一个 notes/ 下的真实目录，Markdown 文件
// 仍然是唯一事实源（BR-01）。这样导出 ZIP 天然保留层级、重建索引时
// 目录结构也不会丢。
type CategoryNode struct {
	Name string `json:"name"` // 本级目录名（保留原始大小写）
	// Path 是相对 notes/ 的正斜杠路径（如 "学习/第一章"），也是分类的稳定标识。
	Path string `json:"path"`
	// Count 是直接位于本分类里的活跃笔记数（不含子分类）。
	Count int `json:"count"`
	// Total 是整棵子树（含所有子孙分类）里的活跃笔记总数。
	Total int `json:"total"`
	// Children 恒为非 nil 切片：空分类序列化为 []，界面不必判 null。
	Children []CategoryNode `json:"children"`
}

// ListCategories 返回 notes/ 下的分类树。
//
// 扫描的是真实目录（含空目录、多级嵌套），笔记数来自内存索引：
// 空分类必须出现在树里，否则用户刚建好分类就"看不见"，也没法往里移动笔记。
// 结果按 Path 升序排列（父分类内部的 Children 同样按 Path 排序）。
func (v *Vault) ListCategories() []CategoryNode {
	v.mu.Lock()
	defer v.mu.Unlock()
	if v.root == "" {
		return []CategoryNode{}
	}

	// direct 记录每个分类目录里直接存放的活跃笔记数（parentDir 是相对 notes/ 的路径）。
	direct := map[string]int{}
	for _, n := range v.notes {
		direct[n.parentDir]++
	}

	// 目录节点先收集再按父子关系挂接：WalkDir 是自顶向下的，但这里显式
	// 组装可以保证"先有父再有子"的假设不依赖遍历细节。
	type dirNode struct {
		path     string
		name     string
		children []*dirNode
	}
	nodes := map[string]*dirNode{}
	notesDir := v.notesDir()
	filepath.WalkDir(notesDir, func(path string, d os.DirEntry, err error) error {
		if err != nil || !d.IsDir() {
			return nil
		}
		rel, relErr := filepath.Rel(notesDir, path)
		if relErr != nil || rel == "." {
			return nil
		}
		rel = filepath.ToSlash(rel)
		nodes[rel] = &dirNode{path: rel, name: rel[strings.LastIndex(rel, "/")+1:]}
		return nil
	})

	// 按路径字典序遍历挂接，兄弟节点天然有序；仍然显式排序，避免依赖
	// map 遍历顺序或 WalkDir 的具体实现细节。
	paths := make([]string, 0, len(nodes))
	for p := range nodes {
		paths = append(paths, p)
	}
	sort.Strings(paths)
	roots := make([]*dirNode, 0, len(paths))
	for _, p := range paths {
		node := nodes[p]
		if i := strings.LastIndex(p, "/"); i >= 0 {
			if parent, ok := nodes[p[:i]]; ok {
				parent.children = append(parent.children, node)
				continue
			}
		}
		roots = append(roots, node)
	}

	// 物化为值树：先算子树总数再回填 Total。
	var materialize func(d *dirNode) CategoryNode
	materialize = func(d *dirNode) CategoryNode {
		sort.Slice(d.children, func(i, j int) bool { return d.children[i].path < d.children[j].path })
		children := make([]CategoryNode, 0, len(d.children))
		total := direct[d.path]
		for _, child := range d.children {
			built := materialize(child)
			children = append(children, built)
			total += built.Total
		}
		return CategoryNode{
			Name:     d.name,
			Path:     d.path,
			Count:    direct[d.path],
			Total:    total,
			Children: children,
		}
	}
	sort.Slice(roots, func(i, j int) bool { return roots[i].path < roots[j].path })
	out := make([]CategoryNode, 0, len(roots))
	for _, root := range roots {
		out = append(out, materialize(root))
	}
	return out
}

// UnfiledNoteCount 返回"未分类"的活跃笔记数：正文直接位于 notes/ 根目录的笔记。
func (v *Vault) UnfiledNoteCount() int {
	v.mu.Lock()
	defer v.mu.Unlock()
	count := 0
	for _, n := range v.notes {
		if n.parentDir == "" {
			count++
		}
	}
	return count
}

// splitCategoryPath 把用户输入的分类路径逐级清洗成规范的正斜杠相对路径。
//
// 返回 ok=false 表示输入非法：空路径、绝对路径（含盘符/UNC）、出现空段
// （"a//b"、尾随 "/"），或某一段清洗后为空（".."、"."、全是非法字符）。
// 清洗用 sanitizeTitle，与笔记标题、导入目录段共用同一套文件名规则。
//
// 不同调用方对错误语义要求不同（CreateCategory 用 ErrPathOutside、
// MoveNoteToCategory 用 ErrTitleInvalid），因此这里只报告"是否合法"，
// 由调用方映射为对应的领域错误。
func splitCategoryPath(raw string) (string, bool) {
	raw = strings.TrimSpace(raw)
	raw = strings.ReplaceAll(raw, "\\", "/")
	// 绝对路径/盘符不做"静默钳制"，直接拒绝（NFR-SEC-03）。
	if raw == "" || strings.HasPrefix(raw, "/") ||
		filepath.IsAbs(filepath.FromSlash(raw)) ||
		filepath.VolumeName(filepath.FromSlash(raw)) != "" {
		return "", false
	}
	segments := strings.Split(raw, "/")
	cleaned := make([]string, 0, len(segments))
	for _, segment := range segments {
		// 空段不替用户猜层级；".." / "." 清洗后为空，同样落在这里。
		if strings.TrimSpace(segment) == "" {
			return "", false
		}
		segment, _ = sanitizeTitle(segment)
		if segment == "" {
			return "", false
		}
		cleaned = append(cleaned, segment)
	}
	return strings.Join(cleaned, "/"), true
}

// CreateCategory 在 notes/ 下创建分类目录（可多级），返回规范化的正斜杠路径。
//
// 已存在视为成功（幂等）：界面可以在"新建分类"对话框里被重复提交而不报错。
// 空名、绝对路径、越界路径一律拒绝（ErrPathOutside）。
func (v *Vault) CreateCategory(categoryPath string) (string, error) {
	v.mu.Lock()
	defer v.mu.Unlock()
	if v.root == "" {
		return "", ErrNoWorkspace
	}
	rel, ok := splitCategoryPath(categoryPath)
	if !ok {
		return "", ErrPathOutside
	}
	// 二次校验：即使清洗后仍要确认落点没有越出 notes/（NFR-SEC-03）。
	if _, err := safeRelPath(v.notesDir(), rel); err != nil {
		return "", ErrPathOutside
	}
	if err := os.MkdirAll(filepath.Join(v.notesDir(), filepath.FromSlash(rel)), 0o755); err != nil {
		return "", ErrSaveFailed
	}
	return rel, nil
}

// MoveNoteToCategory 把笔记移动到指定分类目录（FR-NOTE-02 的整理动作）。
//
// 移动是**纯搬文件**：文件名、标题、正文、标签、双链一个字都不改——改名会
// 破坏双链（其它笔记里的 [[标题]] 会失效），所以这里只换所在目录。
// categoryPath 为空表示移回 notes/ 根目录（未分类）。
//
// 落盘顺序（与 RenameNote 同型）：
//  1. 先写 pending 意图（Kind:"move"，记录旧/新相对路径）。崩溃恢复靠它
//     把"文件已经搬走、元数据还没提交"的半截操作补完。
//  2. MkdirAll 目标目录、os.Rename 搬文件。
//  3. 更新 meta.RelativePath 并 saveNoteMeta。
//  4. 清 pending、重建本篇索引（parentDir 变了）、重算链接解析。
//
// 失败处理：
//   - 第 2 步之前/之中失败：文件还没动，清掉 pending 返回 ErrSaveFailed；
//   - 第 3 步（元数据）失败：尽量把文件搬回原目录恢复原状；若连搬回都失败，
//     文件已在新目录而元数据仍是旧路径，**保留 pending**，让下次装载的
//     reconcilePending 按 move 分支补完（否则该文件会被当成孤立文件另建
//     RECOVERED 身份，noteID 与双链全部漂移）。
func (v *Vault) MoveNoteToCategory(noteID, categoryPath string) (NoteDocument, error) {
	v.mu.Lock()
	defer v.mu.Unlock()
	if v.root == "" {
		return NoteDocument{}, ErrNoWorkspace
	}
	n, ok := v.notes[noteID]
	if !ok {
		return NoteDocument{}, ErrNoteNotFound
	}

	// 目标分类：空串表示根目录（未分类），否则逐级清洗。
	dir := ""
	if strings.TrimSpace(categoryPath) != "" {
		cleaned, valid := splitCategoryPath(categoryPath)
		if !valid {
			// 空段/非法字符按"标题非法"报告：界面只需提示用户改分类名。
			return NoteDocument{}, ErrTitleInvalid
		}
		dir = cleaned
	}
	if dir != "" {
		if _, err := safeRelPath(v.notesDir(), dir); err != nil {
			return NoteDocument{}, ErrTitleInvalid
		}
	}

	oldRel := filepath.ToSlash(n.meta.RelativePath)
	// 只换目录，文件名保持不变。
	base := filepath.Base(filepath.FromSlash(oldRel))
	newRel := base
	if dir != "" {
		newRel = dir + "/" + base
	}

	// 幂等：目标与当前路径相同，或仅大小写差异（Windows 文件系统不区分大小写，
	// 这种"移动"不改变任何东西）——直接返回当前文档，不搬文件、不写 pending。
	if foldString(newRel) == foldString(oldRel) {
		doc, err := v.openNoteLocked(noteID)
		return doc, err
	}

	// 目标分类下已有同名 .md（大小写不敏感）时拒绝：**绝不静默加序号**，
	// 改名会破坏所有指向本篇的双链（[[标题]] 找不到目标），让用户自己改名。
	targetDirAbs := v.notesDir()
	if dir != "" {
		targetDirAbs = filepath.Join(v.notesDir(), filepath.FromSlash(dir))
	}
	if entries, err := os.ReadDir(targetDirAbs); err == nil {
		for _, entry := range entries {
			if entry.IsDir() {
				continue
			}
			if foldString(entry.Name()) == foldString(base) {
				return NoteDocument{}, ErrCategoryConflict
			}
		}
	} else if !os.IsNotExist(err) {
		// 目标目录存在但读不了（权限/共享冲突）：状态未知，不能盲目搬动。
		return NoteDocument{}, ErrSaveFailed
	}

	metaDir, pendingDir := v.metaDirs()
	op := &PendingOp{
		OperationID:      newID(),
		NoteID:           noteID,
		Kind:             "move",
		OldRelPath:       oldRel,
		NewRelPath:       newRel,
		NewSha:           n.meta.ContentSha256,
		ExpectedRevision: n.meta.Revision,
	}
	if err := writePending(pendingDir, op); err != nil {
		return NoteDocument{}, ErrSaveFailed
	}

	oldAbs := filepath.Join(v.notesDir(), filepath.FromSlash(oldRel))
	newAbs := filepath.Join(v.notesDir(), filepath.FromSlash(newRel))
	if err := os.MkdirAll(filepath.Dir(newAbs), 0o755); err != nil {
		clearPending(pendingDir, op.OperationID)
		return NoteDocument{}, ErrSaveFailed
	}
	if err := os.Rename(oldAbs, newAbs); err != nil {
		// 文件一个字节都没动，这条 pending 意图已无意义。
		clearPending(pendingDir, op.OperationID)
		return NoteDocument{}, ErrSaveFailed
	}

	// 移动到新目录后更新元数据里的相对路径。正文、标题、修订号、更新时间都不变：
	// 搬家不是编辑，不应让卡片按"更新时间"跳到列表顶部。
	n.meta.RelativePath = newRel
	if err := saveNoteMeta(metaDir, n.meta); err != nil {
		if rbErr := os.Rename(newAbs, oldAbs); rbErr == nil {
			// 搬回成功：磁盘与元数据都回到"什么都没发生"的状态。
			n.meta.RelativePath = oldRel
			clearPending(pendingDir, op.OperationID)
			return NoteDocument{}, ErrSaveFailed
		}
		// 文件已在新目录、元数据仍是旧路径：保留 pending 交给崩溃对账补完。
		return NoteDocument{}, ErrSaveFailed
	}
	clearPending(pendingDir, op.OperationID)

	// parentDir 变了，需要重新派生索引；同目录同名歧义消解也可能因换目录而变，
	// 因此重算全部链接（标题集合没变，但同目录优先规则依赖 parentDir）。
	v.reindexOne(n, v.readBody(n.meta))
	v.recomputeLinks()
	doc, err := v.openNoteLocked(noteID)
	return doc, err
}

// DeleteCategory 删除分类目录。
//
// 只允许删除空分类：其下既不能有笔记文件，也不能有子分类（任何目录项都算非空）。
// 分类不存在视为成功（幂等）。删除分类只删空目录本身，绝不递归删文件——
// 正文是唯一事实源，误删目录等于丢笔记。
func (v *Vault) DeleteCategory(categoryPath string) error {
	v.mu.Lock()
	defer v.mu.Unlock()
	if v.root == "" {
		return ErrNoWorkspace
	}
	rel, ok := splitCategoryPath(categoryPath)
	if !ok {
		// 空路径就是 notes/ 根目录：那不是分类，不允许删。
		return ErrPathOutside
	}
	if _, err := safeRelPath(v.notesDir(), rel); err != nil {
		return ErrPathOutside
	}
	abs := filepath.Join(v.notesDir(), filepath.FromSlash(rel))
	info, err := os.Stat(abs)
	if os.IsNotExist(err) {
		return nil // 幂等：已经不存在。
	}
	if err != nil {
		return ErrSaveFailed
	}
	if !info.IsDir() {
		return ErrPathOutside
	}
	entries, err := os.ReadDir(abs)
	if err != nil {
		return ErrSaveFailed
	}
	if len(entries) > 0 {
		return ErrCategoryNotEmpty
	}
	if err := os.Remove(abs); err != nil && !os.IsNotExist(err) {
		return ErrSaveFailed
	}
	return nil
}
