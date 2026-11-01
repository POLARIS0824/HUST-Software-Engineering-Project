package main

import (
	"archive/zip"
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"

	"github.com/egoist/mygo"
	"golang.org/x/text/encoding/simplifiedchinese"
)

// ImportFile 是导入计划中的单个文件。
type ImportFile struct {
	SourcePath string `json:"sourcePath"`
	FileName   string `json:"fileName"`
	TargetRel  string `json:"targetRel"` // 相对 notes/ 的预期路径
	Status     string `json:"status"`    // ok / conflict / invalid
	Reason     string `json:"reason,omitempty"`
}

// ImportPlan 是一次待确认的导入计划（同名冲突先询问，FR-DATA-01/AC-DATA-02）。
type ImportPlan struct {
	Files []ImportFile `json:"files"`
}

// ImportResult 是单个文件的导入结果。
type ImportResult struct {
	SourcePath string `json:"sourcePath"`
	FileName   string `json:"fileName"`
	TargetRel  string `json:"targetRel"`
	Status     string `json:"status"` // imported / renamed / overwritten / skipped / failed
	Reason     string `json:"reason,omitempty"`
	NoteID     string `json:"noteId,omitempty"`
}

// AttachmentResult 描述拖放导入的单个文件（.md 或附件）。
type AttachmentResult struct {
	FileName string `json:"fileName"`
	RelPath  string `json:"relPath"` // 笔记为相对 notes/ 的路径；附件为工作区相对的 attachments/ 引用路径
	Status   string `json:"status"`  // imported / renamed / copied / failed
	Reason   string `json:"reason,omitempty"`
}

// loadWorkspace 全量扫描工作区：读取元数据、发现孤立文件并补建
// RECOVERED 元数据、校准被外部修改过的正文哈希、清空内存索引并重建
// （BR-03 自愈，SCN-04）。
func (v *Vault) loadWorkspace() error {
	for _, d := range []string{dirNotes, dirAttachments, dirMetaNotes, dirMetaPending, dirTrash, dirTmp} {
		if err := os.MkdirAll(filepath.Join(v.root, filepath.FromSlash(d)), 0o755); err != nil {
			return err
		}
	}

	metaDir, pendingDir := v.metaDirs()
	metas := map[string]*NoteMeta{} // noteID -> meta
	entries, _ := os.ReadDir(metaDir)
	for _, e := range entries {
		if e.IsDir() || !strings.HasSuffix(e.Name(), ".json") {
			continue
		}
		if m, err := loadNoteMeta(filepath.Join(metaDir, e.Name())); err == nil && m != nil {
			metas[m.NoteID] = m
		}
	}

	// 先按 pending 日志对账（评审 #1：trash/restore/delete/rename 中断可恢复），
	// 再建立路径索引，保证元数据状态与文件位置一致。
	v.reconcilePending(pendingDir, metas)

	// 同一相对路径可能对应多条 ACTIVE 元数据：外部改名/拷贝备份、元数据损坏后
	// 重建、同步盘回滚都会造成。旧实现用 map 直接覆盖，谁是赢家取决于 map
	// 遍历顺序，导致同一文件在重启之间 noteID 跳变（保存/双链身份不稳定）。
	// 这里收集候选列表，交给 pickActiveMeta 按确定规则择优。
	activeByPath := map[string][]*NoteMeta{} // foldedRelPath -> 候选 ACTIVE 元数据
	for _, m := range metas {
		if m.State == stateActive {
			key := foldString(m.RelativePath)
			activeByPath[key] = append(activeByPath[key], m)
		}
	}

	v.notes = map[string]*indexedNote{}
	v.trashed = map[string]*NoteMeta{}
	v.byTitle = map[string][]string{}

	notesDir := v.notesDir()
	// claimed 记录本次装载已被磁盘文件认领的元数据：一条元数据只能绑定一个文件，
	// 未被任何文件认领的 ACTIVE 孤儿元数据保留在磁盘上，但绝不套用到新文件上，
	// 避免"外部搬家后旧身份抢占备份恢复的同名文件"。
	claimed := map[string]bool{} // noteID -> true
	filepath.WalkDir(notesDir, func(path string, d os.DirEntry, err error) error {
		if err != nil || d.IsDir() {
			return nil
		}
		ext := strings.ToLower(filepath.Ext(path))
		if ext != ".md" && ext != ".markdown" {
			return nil
		}
		rel, err := filepath.Rel(notesDir, path)
		if err != nil {
			return nil
		}
		rel = filepath.ToSlash(rel)
		info, err := d.Info()
		if err != nil {
			return nil
		}
		data, err := os.ReadFile(path)
		if err != nil {
			return nil
		}
		body := string(normalizeContent(data))
		diskSha := sha256Hex([]byte(body))

		// 匹配已有元数据（含同路径多条候选的确定性择优）；缺失则按文件时间推定。
		meta := pickActiveMeta(activeByPath[foldString(rel)], diskSha, claimed)
		if meta == nil {
			meta = &NoteMeta{
				SchemaVersion:  metaSchemaV1,
				NoteID:         newID(),
				RelativePath:   rel,
				CreatedAtUtc:   info.ModTime().UTC().UnixMilli(),
				UpdatedAtUtc:   info.ModTime().UTC().UnixMilli(),
				Revision:       1,
				ContentSha256:  diskSha,
				State:          stateActive,
				MetadataOrigin: originRecovered,
			}
			saveNoteMeta(metaDir, meta)
		} else if meta.ContentSha256 != diskSha {
			// 应用关闭期间正文被外部工具改过（或元数据来自备份/已损坏）：以磁盘为
			// 唯一事实源校准哈希。否则重启后第一次保存会被误判"笔记已被其他程序
			// 修改"，用户只能手动强制覆盖。Revision 保持不变：装载不算一次编辑。
			meta.ContentSha256 = diskSha
			saveNoteMeta(metaDir, meta)
		}
		claimed[meta.NoteID] = true
		v.indexNote(meta, body)
		return nil
	})

	// 回收站条目。
	trashEntries, _ := os.ReadDir(v.trashDir())
	for _, e := range trashEntries {
		if e.IsDir() || !strings.HasSuffix(e.Name(), ".md") {
			continue
		}
		id := strings.TrimSuffix(e.Name(), ".md")
		m, ok := metas[id]
		if ok {
			if m.State == stateTrashed {
				v.trashed[id] = m
				continue
			}
			// 兜底（pending 缺失时）：回收站副本存在且 notes/ 下原文已消失 →
			// 视为中断的移入回收站，补完状态（评审 #1）。
			if m.State == stateActive {
				if _, err := os.Stat(filepath.Join(notesDir, filepath.FromSlash(m.RelativePath))); err != nil {
					m.State = stateTrashed
					if m.TrashedAtUtc == 0 {
						m.TrashedAtUtc = nowMs()
					}
					saveNoteMeta(metaDir, m)
					v.trashed[id] = m
				}
			}
			continue
		}
		// 元数据丢失/损坏（同步盘回滚、杀毒软件、手工删除）：.trash/ 里的副本
		// 仍在，但旧实现只看元数据，条目会永久不可见、无法恢复或彻底删除。
		// 按文件 mtime 补建 RECOVERED+TRASHED 元数据（文件名即 noteID，身份不丢）。
		info, err := e.Info()
		if err != nil {
			continue
		}
		modAt := info.ModTime().UTC().UnixMilli()
		m = &NoteMeta{
			SchemaVersion: metaSchemaV1,
			NoteID:        id,
			RelativePath:  id + ".md", // 原路径已不可考，恢复后标题退化为 noteID
			CreatedAtUtc:  modAt,
			UpdatedAtUtc:  modAt,
			Revision:      1,
			State:         stateTrashed,
			// 移到回收站的时间不可考：用"发现时间"而不是 mtime，避免一条陈年
			// 孤儿文件按 30 天保留期被立即物理删除（先让用户看见并决定）。
			TrashedAtUtc:   nowMs(),
			MetadataOrigin: originRecovered,
		}
		if data, err := os.ReadFile(filepath.Join(v.trashDir(), e.Name())); err == nil {
			m.ContentSha256 = sha256Hex(normalizeContent(data))
		}
		saveNoteMeta(metaDir, m)
		v.trashed[id] = m
	}

	v.recomputeLinks()
	v.purgeExpiredTrash()
	return nil
}

// pickActiveMeta 在同一相对路径的多条 ACTIVE 元数据中做确定性择优：
// 磁盘哈希命中者优先（内容确实对得上），其次 Revision 大者，最后 NoteID
// 字典序最小者；已被其它文件认领的候选跳过。规则不依赖 map 遍历顺序，
// 因此同一文件在任意次重启后都得到同一个身份。
func pickActiveMeta(cands []*NoteMeta, diskSha string, claimed map[string]bool) *NoteMeta {
	var best *NoteMeta
	better := func(a, b *NoteMeta) bool {
		if b == nil {
			return true
		}
		aHit, bHit := a.ContentSha256 == diskSha, b.ContentSha256 == diskSha
		if aHit != bHit {
			return aHit
		}
		if a.Revision != b.Revision {
			return a.Revision > b.Revision
		}
		return a.NoteID < b.NoteID
	}
	for _, m := range cands {
		if m == nil || claimed[m.NoteID] {
			continue
		}
		if better(m, best) {
			best = m
		}
	}
	return best
}

// reconcilePending 按 pending 日志幂等补完中断的操作（设计 6.2、评审 #1）。
// 统一以文件系统为证据：操作已推进则补完元数据，未推进则回退保留旧态。
func (v *Vault) reconcilePending(pendingDir string, metas map[string]*NoteMeta) {
	metaDir, _ := v.metaDirs()
	entries, _ := os.ReadDir(pendingDir)
	for _, e := range entries {
		if e.IsDir() || !strings.HasSuffix(e.Name(), ".json") {
			continue
		}
		data, err := os.ReadFile(filepath.Join(pendingDir, e.Name()))
		if err != nil {
			continue
		}
		var op PendingOp
		if json.Unmarshal(data, &op) != nil {
			os.Remove(filepath.Join(pendingDir, e.Name()))
			continue
		}
		switch op.Kind {
		case "save":
			m, ok := metas[op.NoteID]
			if ok && m.RelativePath != "" {
				if body, err := os.ReadFile(filepath.Join(v.notesDir(), filepath.FromSlash(m.RelativePath))); err == nil &&
					sha256Hex(normalizeContent(body)) == op.NewSha {
					// 新版本已完整落盘：元数据补齐。
					if m.Revision < op.ExpectedRevision+1 {
						m.Revision = op.ExpectedRevision + 1
						m.ContentSha256 = op.NewSha
						saveNoteMeta(metaDir, m)
					}
				}
			}
		case "trash":
			// 文件已在 .trash/ 但元数据未提交 → 补完移入回收站。
			m := metas[op.NoteID]
			if m != nil && m.State == stateActive {
				if _, err := os.Stat(filepath.Join(v.trashDir(), op.NoteID+".md")); err == nil {
					if _, err := os.Stat(filepath.Join(v.notesDir(), filepath.FromSlash(m.RelativePath))); err != nil {
						m.State = stateTrashed
						if m.TrashedAtUtc == 0 {
							m.TrashedAtUtc = nowMs()
						}
						saveNoteMeta(metaDir, m)
					}
				}
			}
			// 文件仍在 notes/ → 操作未推进，回退保留活跃态。
		case "restore":
			// 文件已回到 notes/（NewRelPath）但元数据未提交 → 补完还原。
			m := metas[op.NoteID]
			if m != nil && m.State == stateTrashed {
				newRel := op.NewRelPath
				if newRel == "" {
					newRel = m.RelativePath
				}
				if _, err := os.Stat(filepath.Join(v.trashDir(), op.NoteID+".md")); err != nil {
					if _, err := os.Stat(filepath.Join(v.notesDir(), filepath.FromSlash(newRel))); err == nil {
						m.State = stateActive
						m.TrashedAtUtc = 0
						m.RelativePath = newRel
						saveNoteMeta(metaDir, m)
					}
				}
			}
			// 文件仍在 .trash/ → 操作未推进，回退保留回收站态。
		case "rename":
			// 文件已改名但元数据未提交 → 补完重命名，避免被当作孤立文件另建身份。
			m := metas[op.NoteID]
			if m != nil && m.State == stateActive && m.RelativePath == op.OldRelPath && op.NewRelPath != "" {
				if _, err := os.Stat(filepath.Join(v.notesDir(), filepath.FromSlash(op.NewRelPath))); err == nil {
					m.RelativePath = op.NewRelPath
					saveNoteMeta(metaDir, m)
				}
			}
		case "delete":
			// 回收站副本已移除 → 补完删除（清掉元数据）；仍在 → 回退保留。
			if _, err := os.Stat(filepath.Join(v.trashDir(), op.NoteID+".md")); err != nil {
				os.Remove(filepath.Join(metaDir, op.NoteID+".json"))
				delete(metas, op.NoteID)
			}
		}
		os.Remove(filepath.Join(pendingDir, e.Name()))
	}
}

// purgeExpiredTrash 物理清除超过 30 天的回收站条目（FR-NOTE-05）。
// 删除失败的条目保留在回收站，下次启动重试，保证磁盘与索引一致（评审 #10）。
func (v *Vault) purgeExpiredTrash() {
	cutoff := nowMs() - trashRetentionMs
	for _, m := range v.trashed {
		if m.TrashedAtUtc > 0 && m.TrashedAtUtc < cutoff {
			v.deleteForeverLocked(m)
		}
	}
}

// RebuildIndex 从 Markdown 事实源全量重建派生索引（FR-DATA-05、AC-DATA-03）。
func (v *Vault) RebuildIndex() (RebuildResult, error) {
	v.mu.Lock()
	defer v.mu.Unlock()
	if v.root == "" {
		return RebuildResult{}, ErrNoWorkspace
	}
	start := time.Now()
	before := map[string]*NoteMeta{}
	trashBefore := len(v.trashed) // 重建前回收站条目数，用于统计本次物理清理量
	// 先用当前内存状态清理一次过期条目：保留期判定可能已经因为时钟推进或
	// 调用方显式修改而失效，而 loadWorkspace 只会再按磁盘元数据判一次。
	v.purgeExpiredTrash()
	metaDir, _ := v.metaDirs()
	entries, _ := os.ReadDir(metaDir)
	for _, e := range entries {
		if !e.IsDir() && strings.HasSuffix(e.Name(), ".json") {
			if m, err := loadNoteMeta(filepath.Join(metaDir, e.Name())); err == nil && m != nil {
				before[m.NoteID] = m
			}
		}
	}
	if err := v.loadWorkspace(); err != nil {
		return RebuildResult{}, err
	}
	// 统计孤立元数据（对应文件已不存在）。
	orphan := 0
	for id, m := range before {
		if m.State == stateActive {
			if _, ok := v.notes[id]; !ok {
				orphan++
			}
		}
	}
	// loadWorkspace 里 purgeExpiredTrash 会物理删除过期回收站条目：条目数差
	// 就是本次实际清理的数量（旧实现从未给 PurgedTrash 赋值，UI 永远显示 0）。
	purged := trashBefore - len(v.trashed)
	if purged < 0 {
		// 重建时从磁盘新发现的回收站条目多于内存旧快照，差值对"清理量"无意义。
		purged = 0
	}
	return RebuildResult{
		NoteCount:        len(v.notes),
		TrashedNoteCount: len(v.trashed),
		OrphanMeta:       orphan,
		PurgedTrash:      purged,
		DurationMs:       time.Since(start).Milliseconds(),
	}, nil
}

// gbkDecode 把 GBK 字节流转为 UTF-8 文本（基线 4.1：导入自动转码）。
func gbkDecode(data []byte) (string, error) {
	out, err := simplifiedchinese.GBK.NewDecoder().Bytes(data)
	if err != nil {
		return "", err
	}
	return string(out), nil
}

// errInvalidImportedText 表示来源文件不是可识别的文本（二进制/编码损坏）。
// FR-DATA-06：单篇失败跳过并报错，不中止整批导入。
var errInvalidImportedText = errors.New("无法识别为有效文本（已跳过）")

// decodeImportedText 是所有导入路径的唯一解码入口（基线 4.1）：
// 剥离 UTF-8 BOM、把 CRLF/CR 归一为 LF；不是合法 UTF-8 时按 GBK 解码；
// 解码结果含 NUL 字节（二进制）或 U+FFFD 替换字符（编码损坏）都判为无效文本。
// 返回的字节已规范化，调用方直接落盘即可，不要再用 normalizeContent 二次处理。
func decodeImportedText(data []byte) ([]byte, error) {
	text := normalizeContent(data)
	if !utf8.Valid(text) {
		decoded, err := gbkDecode(text)
		if err != nil {
			return nil, errInvalidImportedText
		}
		text = []byte(decoded)
		// GBK 解码器对部分非法字节会静默产出 U+FFFD 而不是报错，必须再验一次。
		if !utf8.Valid(text) {
			return nil, errInvalidImportedText
		}
	}
	if bytes.IndexByte(text, 0) >= 0 || bytes.ContainsRune(text, utf8.RuneError) {
		return nil, errInvalidImportedText
	}
	return text, nil
}

// PrepareImport 打开系统多选文件对话框并校验文件，返回导入计划。
// 目录会被递归展开并保留层级；GBK 编码自动转码（基线 4.1）。
func (v *Vault) PrepareImport(parent *mygo.Window) (ImportPlan, error) {
	v.mu.Lock()
	defer v.mu.Unlock()
	if v.root == "" {
		return ImportPlan{}, ErrNoWorkspace
	}
	paths, err := mygo.Dialog.Open(mygo.OpenDialogOptions{
		Parent: parent,
		Title:  "选择要导入的 Markdown 文件或文件夹",
		Filters: []mygo.FileFilter{
			{Name: "Markdown", Extensions: []string{"md", "markdown"}},
		},
		Multiple: true,
	})
	if err != nil {
		return ImportPlan{}, err
	}
	if len(paths) == 0 {
		return ImportPlan{}, nil
	}
	return v.buildImportPlan(paths), nil
}

// PrepareImportFolder 打开系统文件夹选择对话框并递归展开其中的 Markdown 文件。
// Windows 文件对话框一次只能在"选文件"和"选目录"之间二选一（Directory:true），
// 旧实现只开文件对话框，导致"导入文件夹"承诺永远无法达成（FR-DATA-01）。
func (v *Vault) PrepareImportFolder(parent *mygo.Window) (ImportPlan, error) {
	v.mu.Lock()
	defer v.mu.Unlock()
	if v.root == "" {
		return ImportPlan{}, ErrNoWorkspace
	}
	paths, err := mygo.Dialog.Open(mygo.OpenDialogOptions{
		Parent:    parent,
		Title:     "选择要导入的文件夹",
		Directory: true,
		Multiple:  true,
	})
	if err != nil {
		return ImportPlan{}, err
	}
	if len(paths) == 0 {
		return ImportPlan{}, nil
	}
	return v.buildImportPlan(paths), nil
}

// buildImportPlan 把用户选中的文件/文件夹路径构建成导入计划，供 PrepareImport
// 与 PrepareImportFolder 共用：目录递归展开、保留层级、逐项解码校验并标记冲突。
// 调用方必须持有 v.mu（会读取 v.notes 判断同名冲突）。
func (v *Vault) buildImportPlan(paths []string) ImportPlan {
	plan := ImportPlan{Files: []ImportFile{}}
	existing := map[string]bool{}
	for _, n := range v.notes {
		existing[foldString(n.meta.RelativePath)] = true
	}
	seenInPlan := map[string]bool{}

	addFile := func(abs, relUnder string) {
		base := filepath.Base(abs)
		ext := strings.ToLower(filepath.Ext(base))
		if ext != ".md" && ext != ".markdown" {
			return
		}
		data, err := os.ReadFile(abs)
		if err != nil {
			plan.Files = append(plan.Files, ImportFile{SourcePath: abs, FileName: base, Status: "invalid", Reason: "无法读取文件"})
			return
		}
		// 解码校验与真正落盘共用同一个入口，避免"计划显示可导入、执行时乱码入库"。
		if _, err := decodeImportedText(data); err != nil {
			plan.Files = append(plan.Files, ImportFile{SourcePath: abs, FileName: base, Status: "invalid", Reason: err.Error()})
			return
		}
		// 清洗文件名（保留相对层级），逐级清洗目录段。
		rel := relUnder
		dir := parentDirOfRelPath(rel)
		baseTitle, _ := sanitizeTitle(strings.TrimSuffix(base, ext))
		cleanRel := baseTitle + ".md"
		if dir != "" {
			var segs []string
			for _, seg := range strings.Split(dir, "/") {
				s, _ := sanitizeTitle(seg)
				segs = append(segs, s)
			}
			cleanRel = strings.Join(segs, "/") + "/" + cleanRel
		}
		status := "ok"
		if existing[foldString(cleanRel)] || seenInPlan[foldString(cleanRel)] {
			status = "conflict"
		}
		seenInPlan[foldString(cleanRel)] = true
		plan.Files = append(plan.Files, ImportFile{
			SourcePath: abs,
			FileName:   base,
			TargetRel:  cleanRel,
			Status:     status,
		})
	}

	for _, p := range paths {
		if info, err := os.Stat(p); err == nil && info.IsDir() {
			filepath.WalkDir(p, func(walkPath string, d os.DirEntry, err error) error {
				if err != nil || d.IsDir() {
					return nil
				}
				if rel, err := filepath.Rel(p, walkPath); err == nil {
					addFile(walkPath, filepath.ToSlash(rel))
				}
				return nil
			})
		} else {
			addFile(p, filepath.Base(p))
		}
	}
	return plan
}

// ApplyImport 按用户逐项决策执行导入计划。
// action: rename（默认，追加序号）/ overwrite（先备份 .md.bak）/ skip。
// 执行阶段重新校验来源与目标路径（评审 #9）：不信任 UI 传入的计划。
func (v *Vault) ApplyImport(plan ImportPlan, actions map[string]string) ([]ImportResult, error) {
	v.mu.Lock()
	defer v.mu.Unlock()
	if v.root == "" {
		return nil, ErrNoWorkspace
	}
	results := []ImportResult{}
	for _, f := range plan.Files {
		res := ImportResult{SourcePath: f.SourcePath, FileName: f.FileName, TargetRel: f.TargetRel}
		if f.Status != "invalid" {
			clean, err := safeRelPath(v.notesDir(), f.TargetRel)
			if err != nil {
				res.Status = "failed"
				res.Reason = "目标路径非法"
				results = append(results, res)
				continue
			}
			f.TargetRel = clean
			res.TargetRel = clean
			if st, err := os.Stat(f.SourcePath); err != nil || st.IsDir() {
				res.Status = "failed"
				res.Reason = "源文件不存在或不是普通文件"
				results = append(results, res)
				continue
			}
		}
		switch {
		case f.Status == "invalid":
			res.Status = "skipped"
			res.Reason = f.Reason
		case f.Status == "conflict":
			switch actions[f.SourcePath] {
			case "overwrite":
				if err := v.importOverwrite(f, &res); err != nil {
					res.Status = "failed"
					res.Reason = err.Error()
				}
			case "skip":
				res.Status = "skipped"
				res.Reason = "用户选择跳过"
			default: // rename
				v.importAsNew(f, &res, true)
			}
		default:
			v.importAsNew(f, &res, false)
		}
		results = append(results, res)
	}
	v.recomputeLinks()
	return results, nil
}

func (v *Vault) importAsNew(f ImportFile, res *ImportResult, allowRename bool) {
	rel := f.TargetRel
	if allowRename {
		dir := parentDirOfRelPath(rel)
		base := titleOfRelPath(rel)
		rel = filepath.ToSlash(filepath.Join(dir, filepath.Base(uniqueRelPath(filepath.Join(v.notesDir(), filepath.FromSlash(dir)), base))))
	} else if _, err := os.Lstat(filepath.Join(v.notesDir(), filepath.FromSlash(rel))); err == nil {
		res.Status = "failed"
		res.Reason = "目标文件已存在"
		return
	}
	if err := v.writeImportedNote(rel, f.SourcePath); err != nil {
		res.Status = "failed"
		res.Reason = err.Error()
		return
	}
	res.TargetRel = rel
	res.NoteID = v.noteIDByRel(rel)
	if allowRename && rel != f.TargetRel {
		res.Status = "renamed"
	} else {
		res.Status = "imported"
	}
}

func (v *Vault) importOverwrite(f ImportFile, res *ImportResult) error {
	abs := filepath.Join(v.notesDir(), filepath.FromSlash(f.TargetRel))
	data, err := os.ReadFile(f.SourcePath)
	if err != nil {
		return fmt.Errorf("读取源文件失败")
	}
	// 与新建导入共用唯一解码入口：GBK 自动转码、无效文本拒绝（基线 4.1）。
	content, err := decodeImportedText(data)
	if err != nil {
		return err
	}
	// 先原子备份原文件，新内容落盘成功后移除备份（基线 4.3）。
	backup := abs + ".bak"
	if orig, err := os.ReadFile(abs); err == nil {
		if err := atomicWriteFile(backup, orig); err != nil {
			return fmt.Errorf("备份原文件失败")
		}
	}
	if err := atomicWriteFile(abs, content); err != nil {
		return fmt.Errorf("写入失败")
	}
	os.Remove(backup)

	// 更新或补建元数据。
	if id := v.noteIDByRel(f.TargetRel); id != "" {
		if n, ok := v.notes[id]; ok {
			n.meta.UpdatedAtUtc = nowMs()
			n.meta.ContentSha256 = sha256Hex(content)
			n.meta.Revision++
			metaDir, _ := v.metaDirs()
			saveNoteMeta(metaDir, n.meta)
			v.reindexOne(n, string(content))
			res.NoteID = id
		}
	} else {
		v.writeImportedNote(f.TargetRel, f.SourcePath)
		res.NoteID = v.noteIDByRel(f.TargetRel)
	}
	res.Status = "overwritten"
	return nil
}

// writeImportedNote 写入一个新笔记文件并建立元数据与索引。
// 正文必须经 decodeImportedText 解码（BOM/CRLF 归一、GBK 转 UTF-8、拒绝二进制），
// 这是导入链路的唯一落盘入口。
func (v *Vault) writeImportedNote(rel, sourcePath string) error {
	data, err := os.ReadFile(sourcePath)
	if err != nil {
		return fmt.Errorf("读取源文件失败")
	}
	content, err := decodeImportedText(data)
	if err != nil {
		return err
	}
	if err := atomicWriteFile(filepath.Join(v.notesDir(), filepath.FromSlash(rel)), content); err != nil {
		return fmt.Errorf("写入失败")
	}
	now := nowMs()
	info, _ := os.Stat(sourcePath)
	created := now
	if info != nil {
		created = info.ModTime().UTC().UnixMilli()
	}
	meta := &NoteMeta{
		SchemaVersion:  metaSchemaV1,
		NoteID:         newID(),
		RelativePath:   rel,
		CreatedAtUtc:   created,
		UpdatedAtUtc:   now,
		Revision:       1,
		ContentSha256:  sha256Hex(content),
		State:          stateActive,
		MetadataOrigin: originOriginal,
	}
	metaDir, _ := v.metaDirs()
	if err := saveNoteMeta(metaDir, meta); err != nil {
		return fmt.Errorf("写入元数据失败")
	}
	v.indexNote(meta, string(content))
	return nil
}

func (v *Vault) noteIDByRel(rel string) string {
	for id, n := range v.notes {
		if strings.EqualFold(n.meta.RelativePath, rel) {
			return id
		}
	}
	return ""
}

// ExportNote 导出单篇为标准 .md（FR-DATA-02）。返回写入路径，取消为空串。
func (v *Vault) ExportNote(parent *mygo.Window, id string) (string, error) {
	v.mu.Lock()
	n, ok := v.notes[id]
	if !ok {
		v.mu.Unlock()
		return "", ErrNoteNotFound
	}
	rel := n.meta.RelativePath
	v.mu.Unlock()

	data, err := os.ReadFile(filepath.Join(v.notesDir(), filepath.FromSlash(rel)))
	if err != nil {
		return "", ErrNoteNotFound
	}
	path, err := mygo.Dialog.Save(mygo.SaveDialogOptions{
		Parent:      parent,
		Title:       "导出笔记",
		DefaultPath: titleOfRelPath(rel) + ".md",
		Filters:     []mygo.FileFilter{{Name: "Markdown", Extensions: []string{"md"}}},
	})
	if err != nil || path == "" {
		return "", err
	}
	if err := atomicWriteFile(path, normalizeContent(data)); err != nil {
		return "", ErrSaveFailed
	}
	return path, nil
}

// ExportManifest 是 ZIP 包内 manifest.json 的条目（基线 4.7，schemaVersion 1.0）。
type ExportManifest struct {
	Generator        string          `json:"generator"`
	ExportTimestamp  string          `json:"exportTimestamp"`
	SchemaVersion    string          `json:"schemaVersion"`
	TotalNotes       int             `json:"totalNotes"`
	TotalAttachments int             `json:"totalAttachments"`
	NotesIndex       []ManifestEntry `json:"notesIndex"`
}

// ManifestEntry 是 manifest.json 中单篇笔记的校验与索引信息。
type ManifestEntry struct {
	NoteID         string   `json:"noteId"`
	RelativePath   string   `json:"relativePath"`
	SHA256         string   `json:"sha256"`
	Tags           []string `json:"tags"`
	OutboundLinks  []string `json:"outboundLinks"`
	CreatedAt      string   `json:"createdAt"`
	UpdatedAt      string   `json:"updatedAt"`
	Revision       int64    `json:"revision"`
	MetadataOrigin string   `json:"metadataOrigin"`
}

// exportNoteSnapshot 是导出时冻结的单篇笔记快照（写包期间不再持锁读索引）。
type exportNoteSnapshot struct {
	meta  NoteMeta
	body  string
	tags  []ExtractedTag
	links []ExtractedLink
}

// writeVaultZip 把当前知识库写成 ZIP 包体：notes/ + attachments/ + manifest.json。
// 独立于任何系统对话框，真实导出流程与单元测试共用这段写包逻辑（FR-DATA-03）。
// 包内路径统一 UTF-8（NFR-PORT-03），附件递归保留 attachments/ 下的子目录。
func (v *Vault) writeVaultZip(w io.Writer) error {
	v.mu.Lock()
	if v.root == "" {
		v.mu.Unlock()
		return ErrNoWorkspace
	}
	snapshots := make([]exportNoteSnapshot, 0, len(v.notes))
	for _, n := range v.notes {
		snapshots = append(snapshots, exportNoteSnapshot{meta: *n.meta, body: n.body, tags: n.tags, links: n.links})
	}
	attDir := v.attachmentsDir()
	// 旧实现只遍历 attachments/ 顶层，正文里引用的 attachments/sub/x.png 会被
	// 静默丢出备份包；这里递归收集并保留相对子路径。
	attRels := []string{}
	filepath.WalkDir(attDir, func(path string, d os.DirEntry, err error) error {
		if err != nil || d.IsDir() {
			return nil
		}
		rel, err := filepath.Rel(attDir, path)
		if err != nil {
			return nil
		}
		attRels = append(attRels, filepath.ToSlash(rel))
		return nil
	})
	v.mu.Unlock()

	sort.Slice(snapshots, func(i, j int) bool { return snapshots[i].meta.RelativePath < snapshots[j].meta.RelativePath })
	sort.Strings(attRels)

	// 基线 4.7：generator 固定 "JustMD v1.1"，schemaVersion "1.0"。
	manifest := ExportManifest{
		Generator:       "JustMD v1.1",
		ExportTimestamp: time.Now().Format("2006-01-02T15:04:05Z07:00"),
		SchemaVersion:   "1.0",
		TotalNotes:      len(snapshots),
		NotesIndex:      []ManifestEntry{},
	}

	zw := zip.NewWriter(w)
	writeZip := func(name string, data []byte) error {
		f, err := zw.Create(name)
		if err != nil {
			return err
		}
		_, err = f.Write(data)
		return err
	}

	for _, sn := range snapshots {
		zipName := "notes/" + sn.meta.RelativePath
		body := []byte(sn.body)
		if err := writeZip(zipName, body); err != nil {
			return ErrSaveFailed
		}
		tags := []string{}
		for _, t := range sn.tags {
			tags = append(tags, t.Name)
		}
		links := []string{}
		for _, l := range sn.links {
			links = append(links, l.Target)
		}
		manifest.NotesIndex = append(manifest.NotesIndex, ManifestEntry{
			NoteID:         sn.meta.NoteID,
			RelativePath:   zipName,
			SHA256:         sha256Hex(body),
			Tags:           tags,
			OutboundLinks:  links,
			CreatedAt:      msToISO(sn.meta.CreatedAtUtc),
			UpdatedAt:      msToISO(sn.meta.UpdatedAtUtc),
			Revision:       sn.meta.Revision,
			MetadataOrigin: sn.meta.MetadataOrigin,
		})
	}

	writtenAtts := 0
	for _, rel := range attRels {
		data, err := os.ReadFile(filepath.Join(attDir, filepath.FromSlash(rel)))
		if err != nil {
			continue // 快照后被外部删除的附件跳过，不因单个附件中断整包导出
		}
		if err := writeZip(dirAttachments+"/"+rel, data); err != nil {
			return ErrSaveFailed
		}
		writtenAtts++
	}
	// 只统计真正写进包的附件，保证 manifest 与实际条目一致。
	manifest.TotalAttachments = writtenAtts

	mb, err := json.MarshalIndent(manifest, "", "  ")
	if err != nil {
		return ErrSaveFailed
	}
	if err := writeZip("manifest.json", mb); err != nil {
		return ErrSaveFailed
	}
	if err := zw.Close(); err != nil {
		return ErrSaveFailed
	}
	return nil
}

// ExportVault 弹系统保存对话框并导出完整知识库 ZIP（FR-DATA-03）。
// 包体写入全部委托 writeVaultZip，时间戳为 ISO-8601（基线 4.4）。
func (v *Vault) ExportVault(parent *mygo.Window) (string, error) {
	v.mu.Lock()
	ready := v.root != ""
	v.mu.Unlock()
	if !ready {
		return "", ErrNoWorkspace
	}

	path, err := mygo.Dialog.Save(mygo.SaveDialogOptions{
		Parent:      parent,
		Title:       "导出完整知识库为 ZIP",
		DefaultPath: fmt.Sprintf("JustMD_Backup_%s.zip", time.Now().Format("20060102T150405")),
		Filters:     []mygo.FileFilter{{Name: "ZIP 归档", Extensions: []string{"zip"}}},
	})
	if err != nil || path == "" {
		return "", err
	}

	out, err := os.Create(path)
	if err != nil {
		return "", ErrSaveFailed
	}
	defer out.Close()
	if err := v.writeVaultZip(out); err != nil {
		return "", err
	}
	return path, nil
}

// ImportDroppedFiles 处理拖放的文件/文件夹：.md 导入为笔记（同名自动重命名），
// 图片等复制进 attachments/（NFR-SEC-03 只允许工作区内引用）。
// 文件夹会递归展开：内部 .md 走导入分支，其余文件仍按附件复制（旧实现把
// 目录当成附件读，必然失败）；导入完成后重算双链，保证反链与阅读页链接
// 立即可用（旧实现要等下一次增删改或重启才恢复）。
func (v *Vault) ImportDroppedFiles(paths []string) ([]AttachmentResult, error) {
	v.mu.Lock()
	defer v.mu.Unlock()
	if v.root == "" {
		return nil, ErrNoWorkspace
	}
	out := []AttachmentResult{}
	for _, p := range paths {
		info, err := os.Stat(p)
		if err != nil {
			out = append(out, AttachmentResult{FileName: filepath.Base(p), Status: "failed", Reason: "无法读取文件"})
			continue
		}
		if info.IsDir() {
			out = append(out, v.importDroppedDir(p)...)
			continue
		}
		out = append(out, v.importDroppedFile(p))
	}
	v.recomputeLinks()
	return out, nil
}

// importDroppedDir 递归导入拖放的文件夹，返回其中每个文件的处理结果；
// 文件夹里没有任何可导入内容时也返回一条结果，让用户看到"确实处理过"。
func (v *Vault) importDroppedDir(dir string) []AttachmentResult {
	out := []AttachmentResult{}
	filepath.WalkDir(dir, func(path string, d os.DirEntry, err error) error {
		if err != nil || d.IsDir() {
			return nil
		}
		out = append(out, v.importDroppedFile(path))
		return nil
	})
	if len(out) == 0 {
		out = append(out, AttachmentResult{
			FileName: filepath.Base(dir),
			Status:   "failed",
			Reason:   "文件夹内没有可导入的文件",
		})
	}
	return out
}

// importDroppedFile 处理拖放中的单个普通文件：Markdown 导入为笔记，其余复制为附件。
func (v *Vault) importDroppedFile(p string) AttachmentResult {
	ext := strings.ToLower(filepath.Ext(p))
	base := filepath.Base(p)
	if ext == ".md" || ext == ".markdown" {
		title, _ := sanitizeTitle(strings.TrimSuffix(base, ext))
		f := ImportFile{SourcePath: p, FileName: base, TargetRel: title + ".md"}
		var imported ImportResult
		v.importAsNew(f, &imported, true)
		return AttachmentResult{
			FileName: base,
			RelPath:  imported.TargetRel,
			Status:   imported.Status,
			Reason:   imported.Reason,
		}
	}
	return v.copyDroppedAttachment(p)
}

// copyDroppedAttachment 把非 Markdown 文件复制进 attachments/，返回真实引用路径。
func (v *Vault) copyDroppedAttachment(src string) AttachmentResult {
	base := filepath.Base(src)
	res := AttachmentResult{FileName: base}
	if err := os.MkdirAll(v.attachmentsDir(), 0o755); err != nil {
		res.Status = "failed"
		res.Reason = "无法创建附件目录"
		return res
	}
	data, err := os.ReadFile(src)
	if err != nil {
		res.Status = "failed"
		res.Reason = "无法读取文件"
		return res
	}
	name := uniqueAttachmentName(v.attachmentsDir(), base, data)
	if err := atomicWriteFile(filepath.Join(v.attachmentsDir(), name), data); err != nil {
		res.Status = "failed"
		res.Reason = "复制失败"
		return res
	}
	res.Status = "copied"
	res.RelPath = dirAttachments + "/" + name
	return res
}

// uniqueAttachmentName 生成不覆盖已有附件的文件名：基础名 = 秒级时间戳 +
// 清洗后的文件名；若该名已存在（同一批拖入两个 shot.png、或历史上已有同名
// 附件），依次追加 _2、_3…，返回真正落盘的文件名。旧实现按秒级时间戳命名，
// 同批同名文件会静默互相覆盖，只剩最后一个。
func uniqueAttachmentName(dir, base string, data []byte) string {
	ext := strings.ToLower(filepath.Ext(base))
	stem := sanitizeAttachmentName(strings.TrimSuffix(base, ext))
	// 去掉首尾的分隔性字符，避免生成 "_.png" 这类怪异名字。
	stem = strings.Trim(stem, "._-")
	if stem == "" {
		// 中文/特殊字符被清洗空时用内容短哈希兜底，仍能保证可区分。
		stem = "attachment_" + sha256Hex(data)[:8]
	}
	if ext = sanitizeAttachmentName(ext); ext == "." {
		ext = ""
	}
	ts := time.Now().Format("20060102_150405")
	candidate := ts + "_" + stem + ext
	for i := 2; ; i++ {
		_, statErr := os.Lstat(filepath.Join(dir, candidate))
		if os.IsNotExist(statErr) {
			return candidate
		}
		if statErr != nil {
			// 目录不可访问时无法确认是否冲突：直接返回当前候选交给写盘报错，
			// 不能在这里无限循环。
			return candidate
		}
		// 已存在：换下一个候选名，绝不覆盖已有附件。
		candidate = fmt.Sprintf("%s_%d_%s%s", ts, i, stem, ext)
	}
}

// sanitizeAttachmentName 清洗附件文件名（保守：仅保留安全字符）。
func sanitizeAttachmentName(name string) string {
	var b strings.Builder
	for _, r := range name {
		switch {
		case r >= 'a' && r <= 'z', r >= 'A' && r <= 'Z', r >= '0' && r <= '9',
			r == '.', r == '_', r == '-':
			b.WriteRune(r)
		case unicode.Is(unicode.Han, r):
			b.WriteRune(r)
		default:
			b.WriteByte('_')
		}
	}
	return b.String()
}

// msToISO 把 UTC 毫秒时间戳转 ISO-8601 本地时区字符串（基线 4.4）。
func msToISO(ms int64) string {
	return time.UnixMilli(ms).Format("2006-01-02T15:04:05Z07:00")
}
