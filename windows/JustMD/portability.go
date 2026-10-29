package main

import (
	"archive/zip"
	"context"
	"encoding/json"
	"fmt"
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

// AttachmentResult 描述拖入的附件文件。
type AttachmentResult struct {
	FileName string `json:"fileName"`
	RelPath  string `json:"relPath"` // 相对 notes/ 的引用路径
	Status   string `json:"status"`
	Reason   string `json:"reason,omitempty"`
}

// loadWorkspace 全量扫描工作区：读取元数据、发现孤立文件并补建
// RECOVERED 元数据、清空内存索引并重建（BR-03 自愈，SCN-04）。
func (v *Vault) loadWorkspace() error {
	for _, d := range []string{dirNotes, dirAttachments, dirMetaNotes, dirMetaPending, dirTrash, dirTmp} {
		if err := os.MkdirAll(filepath.Join(v.root, filepath.FromSlash(d)), 0o755); err != nil {
			return err
		}
	}

	metaDir, pendingDir := v.metaDirs()
	metas := map[string]*NoteMeta{}  // noteID -> meta
	byPath := map[string]*NoteMeta{} // activePath -> meta
	entries, _ := os.ReadDir(metaDir)
	for _, e := range entries {
		if e.IsDir() || !strings.HasSuffix(e.Name(), ".json") {
			continue
		}
		if m, err := loadNoteMeta(filepath.Join(metaDir, e.Name())); err == nil && m != nil {
			metas[m.NoteID] = m
			if m.State == stateActive {
				byPath[foldString(m.RelativePath)] = m
			}
		}
	}

	v.notes = map[string]*indexedNote{}
	v.trashed = map[string]*NoteMeta{}
	v.byTitle = map[string][]string{}

	notesDir := v.notesDir()
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
		// 匹配已有元数据；缺失则按文件时间推定（RECOVERED）。
		meta := byPath[foldString(rel)]
		if meta == nil {
			meta = &NoteMeta{
				SchemaVersion:  metaSchemaV1,
				NoteID:         newID(),
				RelativePath:   rel,
				CreatedAtUtc:   info.ModTime().UTC().UnixMilli(),
				UpdatedAtUtc:   info.ModTime().UTC().UnixMilli(),
				Revision:       1,
				State:          stateActive,
				MetadataOrigin: originRecovered,
			}
			if data, err := os.ReadFile(path); err == nil {
				meta.ContentSha256 = sha256Hex(normalizeContent(data))
			}
			saveNoteMeta(metaDir, meta)
		}
		data, err := os.ReadFile(path)
		if err != nil {
			return nil
		}
		v.indexNote(meta, string(normalizeContent(data)))
		return nil
	})

	// 回收站条目。
	trashEntries, _ := os.ReadDir(v.trashDir())
	for _, e := range trashEntries {
		if e.IsDir() || !strings.HasSuffix(e.Name(), ".md") {
			continue
		}
		id := strings.TrimSuffix(e.Name(), ".md")
		if m, ok := metas[id]; ok && m.State == stateTrashed {
			v.trashed[id] = m
		}
	}

	v.recomputeLinks()
	v.reconcilePending(pendingDir, metas)
	v.purgeExpiredTrash()
	return nil
}

// reconcilePending 按 pending 日志幂等补完中断的操作（设计 6.2）。
// 正文以哈希校验为准：新内容已在则确保元数据一致，否则丢弃临时文件保留旧版。
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
		if op.Kind == "save" {
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
		}
		// 其余情况（rename/trash/restore/delete）：文件系统状态已经是最终态或回退态，
		// 由 loadWorkspace 的全量扫描对齐；仅清理日志。
		os.Remove(filepath.Join(pendingDir, e.Name()))
	}
}

// purgeExpiredTrash 物理清除超过 30 天的回收站条目（FR-NOTE-05）。
func (v *Vault) purgeExpiredTrash() {
	cutoff := nowMs() - trashRetentionMs
	for id, m := range v.trashed {
		if m.TrashedAtUtc > 0 && m.TrashedAtUtc < cutoff {
			os.Remove(filepath.Join(v.trashDir(), id+".md"))
			os.Remove(filepath.Join(v.metaDir(), id+".json"))
			delete(v.trashed, id)
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
	return RebuildResult{
		NoteCount:        len(v.notes),
		TrashedNoteCount: len(v.trashed),
		OrphanMeta:       orphan,
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

// PrepareImport 打开系统多选对话框并校验文件，返回导入计划。
// 目录会被递归展开并保留层级；GBK 编码自动转码（基线 4.1）。
func (v *Vault) PrepareImport(ctx context.Context) (ImportPlan, error) {
	v.mu.Lock()
	defer v.mu.Unlock()
	if v.root == "" {
		return ImportPlan{}, ErrNoWorkspace
	}
	paths, err := mygo.Dialog.Open(mygo.OpenDialogOptions{
		Parent: mygo.CallerWindow(ctx),
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
		if !utf8.Valid(data) {
			decoded, err := gbkDecode(data)
			if err != nil {
				plan.Files = append(plan.Files, ImportFile{SourcePath: abs, FileName: base, Status: "invalid", Reason: "无法识别为有效文本（已跳过）"})
				return
			}
			data = []byte(decoded)
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
	return plan, nil
}

// ApplyImport 按用户逐项决策执行导入计划。
// action: rename（默认，追加序号）/ overwrite（先备份 .md.bak）/ skip。
func (v *Vault) ApplyImport(ctx context.Context, plan ImportPlan, actions map[string]string) ([]ImportResult, error) {
	v.mu.Lock()
	defer v.mu.Unlock()
	if v.root == "" {
		return nil, ErrNoWorkspace
	}
	results := []ImportResult{}
	for _, f := range plan.Files {
		res := ImportResult{SourcePath: f.SourcePath, FileName: f.FileName, TargetRel: f.TargetRel}
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
	v.broadcast()
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
	content := normalizeContent(data)
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
func (v *Vault) writeImportedNote(rel, sourcePath string) error {
	data, err := os.ReadFile(sourcePath)
	if err != nil {
		return fmt.Errorf("读取源文件失败")
	}
	content := normalizeContent(data)
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
func (v *Vault) ExportNote(ctx context.Context, id string) (string, error) {
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
		Parent:      mygo.CallerWindow(ctx),
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

// ExportManifest 是 ZIP 包内 manifest.json 的条目（基线 4.7，schemaVersion 1.1）。
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

// ExportVault 打包完整知识库为 ZIP（FR-DATA-03）：notes/ + attachments/ + manifest.json。
// 归档路径为 UTF-8 编码（NFR-PORT-03），时间戳为 ISO-8601（基线 4.4）。
func (v *Vault) ExportVault(ctx context.Context) (string, error) {
	v.mu.Lock()
	if v.root == "" {
		v.mu.Unlock()
		return "", ErrNoWorkspace
	}
	// 冻结快照数据。
	type snapshotNote struct {
		meta  NoteMeta
		body  string
		tags  []ExtractedTag
		links []ExtractedLink
	}
	snapshots := make([]snapshotNote, 0, len(v.notes))
	for _, n := range v.notes {
		snapshots = append(snapshots, snapshotNote{meta: *n.meta, body: n.body, tags: n.tags, links: n.links})
	}
	atts, _ := os.ReadDir(v.attachmentsDir())
	attNames := []string{}
	for _, a := range atts {
		if !a.IsDir() {
			attNames = append(attNames, a.Name())
		}
	}
	v.mu.Unlock()

	sort.Slice(snapshots, func(i, j int) bool { return snapshots[i].meta.RelativePath < snapshots[j].meta.RelativePath })
	sort.Strings(attNames)

	manifest := ExportManifest{
		Generator:        "JustMD Windows v0.1.0",
		ExportTimestamp:  time.Now().Format("2006-01-02T15:04:05Z07:00"),
		SchemaVersion:    "1.1",
		TotalNotes:       len(snapshots),
		TotalAttachments: len(attNames),
		NotesIndex:       []ManifestEntry{},
	}

	path, err := mygo.Dialog.Save(mygo.SaveDialogOptions{
		Parent:      mygo.CallerWindow(ctx),
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
	w := zip.NewWriter(out)

	writeZip := func(name string, data []byte) error {
		f, err := w.Create(name)
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
			return "", ErrSaveFailed
		}
		tags := []string{}
		for _, t := range sn.tags {
			tags = append(tags, t.Name)
		}
		links := []string{}
		for _, l := range sn.links {
			links = append(links, l.Target)
		}
		if tags == nil {
			tags = []string{}
		}
		if links == nil {
			links = []string{}
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
	for _, name := range attNames {
		data, err := os.ReadFile(filepath.Join(v.attachmentsDir(), name))
		if err != nil {
			continue
		}
		if err := writeZip("attachments/"+name, data); err != nil {
			return "", ErrSaveFailed
		}
	}
	mb, _ := json.MarshalIndent(manifest, "", "  ")
	if err := writeZip("manifest.json", mb); err != nil {
		return "", ErrSaveFailed
	}
	if err := w.Close(); err != nil {
		return "", ErrSaveFailed
	}
	return path, nil
}

// ImportDroppedFiles 处理拖放的文件：.md 导入为笔记（同名自动重命名），
// 图片等复制进 attachments/（NFR-SEC-03 只允许工作区内引用）。
func (v *Vault) ImportDroppedFiles(ctx context.Context, paths []string) ([]AttachmentResult, error) {
	v.mu.Lock()
	defer v.mu.Unlock()
	out := []AttachmentResult{}
	for _, p := range paths {
		ext := strings.ToLower(filepath.Ext(p))
		base := filepath.Base(p)
		res := AttachmentResult{FileName: base}
		if ext == ".md" || ext == ".markdown" {
			rel := base
			title, _ := sanitizeTitle(strings.TrimSuffix(base, ext))
			rel = title + ".md"
			f := ImportFile{SourcePath: p, FileName: base, TargetRel: rel}
			var res ImportResult
			v.importAsNew(f, &res, true)
			res.Status = "imported"
			res.TargetRel = rel
			res.FileName = base
		} else {
			if err := os.MkdirAll(v.attachmentsDir(), 0o755); err == nil {
				data, err := os.ReadFile(p)
				if err != nil {
					res.Status = "failed"
					res.Reason = "无法读取文件"
				} else {
					name := fmt.Sprintf("%s_%s", time.Now().Format("20060102_150405"), sanitizeAttachmentName(base))
					if err := atomicWriteFile(filepath.Join(v.attachmentsDir(), name), data); err != nil {
						res.Status = "failed"
						res.Reason = "复制失败"
					} else {
						res.Status = "copied"
						res.RelPath = dirAttachments + "/" + name
					}
				}
			}
		}
		out = append(out, res)
	}
	v.broadcast()
	return out, nil
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
