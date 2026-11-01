package main

import (
	"archive/zip"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// newTestVault 构造一个使用临时目录的 Vault（不启动 MyGo 应用）。
func newTestVault(t *testing.T) *Vault {
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
	return v
}

func mustCreate(t *testing.T, v *Vault, title string) NoteDocument {
	t.Helper()
	doc, err := v.CreateNote(title)
	if err != nil {
		t.Fatalf("CreateNote(%q): %v", title, err)
	}
	return doc
}

func mustSave(t *testing.T, v *Vault, id, body string) SaveReceipt {
	t.Helper()
	receipt, err := v.SaveContent(id, body)
	if err != nil {
		t.Fatalf("SaveContent: %v", err)
	}
	return receipt
}

// ---- AC-NOTE-01：全生命周期与文件落盘 ---------------------------------------------

func TestCreateNoteWritesFileAndMeta(t *testing.T) {
	v := newTestVault(t)
	doc := mustCreate(t, v, "软工需求导论")

	abs := filepath.Join(v.notesDir(), doc.RelativePath)
	if _, err := os.Stat(abs); err != nil {
		t.Fatalf("笔记文件未生成: %v", err)
	}
	meta, err := loadNoteMeta(filepath.Join(v.metaDir(), doc.ID+".json"))
	if err != nil || meta == nil {
		t.Fatalf("元数据未生成: %v", err)
	}
	if meta.State != stateActive || meta.RelativePath != doc.RelativePath {
		t.Fatalf("元数据内容错误: %+v", meta)
	}
	if meta.CreatedAtUtc <= 0 || doc.CreatedAtUtc != meta.CreatedAtUtc {
		t.Fatalf("创建时间戳错误: %d", meta.CreatedAtUtc)
	}
}

func TestCreateNoteSanitizesTitle(t *testing.T) {
	v := newTestVault(t)
	doc, err := v.CreateNote(`CON: 测试?`)
	if err != nil {
		t.Fatal(err)
	}
	if strings.ContainsAny(doc.RelativePath, `:?`) || strings.HasPrefix(filepath.Base(doc.RelativePath), "CON:") {
		t.Fatalf("标题未清洗: %q", doc.RelativePath)
	}
	// Windows 保留名 + 冒号清洗后：CON_ _ 测试.md 形态。
	base := filepath.Base(doc.RelativePath)
	if strings.Contains(base, ":") || strings.Contains(base, "?") {
		t.Fatalf("非法字符残留: %q", base)
	}
}

func TestCreateNoteConflictSuffix(t *testing.T) {
	v := newTestVault(t)
	a := mustCreate(t, v, "笔记")
	b := mustCreate(t, v, "笔记")
	if a.RelativePath == b.RelativePath {
		t.Fatal("同名创建未追加冲突后缀")
	}
	if b.RelativePath != "笔记 (1).md" {
		t.Fatalf("冲突后缀格式错误: %q", b.RelativePath)
	}
}

// ---- FR-EDIT-04/06、BR-04/05：保存、规范化与回执 -----------------------------------

func TestSaveContentNormalizesAndReceipts(t *testing.T) {
	v := newTestVault(t)
	doc := mustCreate(t, v, "保存测试")
	receipt := mustSave(t, v, doc.ID, "\uFEFF第一行\r\n第二行\r第三行")

	if receipt.Revision != doc.Revision+1 {
		t.Fatalf("revision 应自增: %d -> %d", doc.Revision, receipt.Revision)
	}
	data, _ := os.ReadFile(filepath.Join(v.notesDir(), doc.RelativePath))
	if string(data) != "第一行\n第二行\n第三行" {
		t.Fatalf("落盘内容应去 BOM 并统一 LF: %q", data)
	}
	if receipt.ContentSha256 != sha256Hex([]byte("第一行\n第二行\n第三行")) {
		t.Fatal("回执哈希与实际内容不符")
	}
}

func TestSaveContentUnknownNote(t *testing.T) {
	v := newTestVault(t)
	if _, err := v.SaveContent("不存在的 ID", "x"); err != ErrNoteNotFound {
		t.Fatalf("应返回 ErrNoteNotFound: %v", err)
	}
}

// ---- AC-NOTE-03：重命名级联 -----------------------------------------------------

func TestRenameNoteRenamesFile(t *testing.T) {
	v := newTestVault(t)
	doc := mustCreate(t, v, "旧标题")
	mustSave(t, v, doc.ID, "正文内容")
	before := v.notes[doc.ID].meta.UpdatedAtUtc

	renamed, err := v.RenameNote(doc.ID, "新标题")
	if err != nil {
		t.Fatal(err)
	}
	if renamed.RelativePath != "新标题.md" {
		t.Fatalf("文件未重命名: %q", renamed.RelativePath)
	}
	if _, err := os.Stat(filepath.Join(v.notesDir(), "旧标题.md")); !os.IsNotExist(err) {
		t.Fatal("旧文件仍存在")
	}
	data, _ := os.ReadFile(filepath.Join(v.notesDir(), "新标题.md"))
	if string(data) != "正文内容" {
		t.Fatalf("内容丢失: %q", data)
	}
	if renamed.UpdatedAtUtc != before {
		t.Fatal("改名不应顶起更新时间（否则逐字改名时卡片在列表里乱跳）")
	}
}

func TestRenameNoteCascadesResolvedLinks(t *testing.T) {
	v := newTestVault(t)
	target := mustCreate(t, v, "旧标题")
	source := mustCreate(t, v, "来源")
	mustSave(t, v, source.ID, "请参考 [[旧标题|点这里]] 与 [[旧标题]]。")

	if _, err := v.RenameNote(target.ID, "新标题"); err != nil {
		t.Fatal(err)
	}
	updated := v.readBody(v.notes[source.ID].meta)
	if !strings.Contains(updated, "[[新标题|点这里]]") || !strings.Contains(updated, "[[新标题]]") {
		t.Fatalf("级联更新失败: %q", updated)
	}
	if strings.Contains(updated, "旧标题") {
		t.Fatalf("旧引用残留: %q", updated)
	}
}

func TestRenameNoteConflictAutoSuffix(t *testing.T) {
	v := newTestVault(t)
	a := mustCreate(t, v, "甲")
	mustCreate(t, v, "乙")
	// 把甲重命名为乙：应得到 "乙 (1).md" 而非覆盖。
	renamed, err := v.RenameNote(a.ID, "乙")
	if err != nil {
		t.Fatal(err)
	}
	if renamed.RelativePath != "乙 (1).md" {
		t.Fatalf("重命名冲突应自动加后缀: %q", renamed.RelativePath)
	}
	if _, err := os.Stat(filepath.Join(v.notesDir(), "乙.md")); err != nil {
		t.Fatal("原 乙.md 不应被动")
	}
}

// ---- FR-LINK-03/04/06/07：双链解析、反链与歧义 -------------------------------------

func TestLinkResolutionAndBacklinks(t *testing.T) {
	v := newTestVault(t)
	b := mustCreate(t, v, "B")
	a := mustCreate(t, v, "A")
	mustSave(t, v, a.ID, "关于详情请参考 [[B]]。")
	mustSave(t, v, b.ID, "B 的正文")

	links := v.notes[a.ID].links
	if len(links) != 1 || v.notes[a.ID].linkStates[0] != linkResolved {
		t.Fatalf("[[B]] 应解析成功: %+v", v.notes[a.ID].linkStates)
	}
	if v.notes[a.ID].resolvedIDs[0] != b.ID {
		t.Fatal("解析目标 ID 错误")
	}
	backlinks := v.GetBacklinks(b.ID)
	if len(backlinks) != 1 || backlinks[0].SourceNoteID != a.ID {
		t.Fatalf("反链错误: %+v", backlinks)
	}
	if !strings.Contains(backlinks[0].Snippet, "[[B]]") {
		t.Fatalf("反链应带引用上下文: %q", backlinks[0].Snippet)
	}
}

func TestUnresolvedLinkAndCreateFromLink(t *testing.T) {
	v := newTestVault(t)
	a := mustCreate(t, v, "A")
	mustSave(t, v, a.ID, "未来关注 [[量子计算]]")

	if got := v.notes[a.ID].linkStates[0]; got != linkUnresolved {
		t.Fatalf("死链应标记 unresolved: %s", got)
	}
	doc, err := v.CreateNoteFromLink("量子计算")
	if err != nil {
		t.Fatal(err)
	}
	if v.notes[a.ID].linkStates[0] != linkResolved {
		t.Fatal("创建后死链应转正")
	}
	if doc.RelativePath != "量子计算.md" {
		t.Fatalf("创建路径错误: %q", doc.RelativePath)
	}
}

func TestAmbiguousLinkSameDirPriority(t *testing.T) {
	v := newTestVault(t)
	metaDir, _ := v.metaDirs()

	// 手工构造子目录同名笔记 sub/B.md。
	addNote := func(rel, body string) *NoteMeta {
		m := &NoteMeta{SchemaVersion: metaSchemaV1, NoteID: newID(), RelativePath: rel,
			CreatedAtUtc: nowMs(), UpdatedAtUtc: nowMs(), Revision: 1, State: stateActive, MetadataOrigin: originOriginal}
		atomicWriteFile(filepath.Join(v.notesDir(), filepath.FromSlash(rel)), []byte(body))
		saveNoteMeta(metaDir, m)
		v.indexNote(m, body)
		return m
	}
	subB := addNote("sub/B.md", "子目录里的 B")
	rootB := mustCreate(t, v, "B")
	if rootB.RelativePath != "B.md" {
		t.Fatalf("前置条件失败: %q", rootB.RelativePath)
	}
	if titleOfRelPath(subB.RelativePath) != "B" {
		t.Fatal("前置条件失败：sub/B.md 的标题应为 B")
	}

	// A 在根目录引用 [[B]]：两个候选中恰有一个同目录 → 解析为根目录的 B。
	a := mustCreate(t, v, "A")
	mustSave(t, v, a.ID, "引用 [[B]]")
	if got := v.notes[a.ID].linkStates[0]; got != linkResolved {
		t.Fatalf("同目录唯一候选应解析: %s", got)
	}
	if v.notes[a.ID].resolvedIDs[0] != rootB.ID {
		t.Fatalf("应优先解析到同目录候选: %s", v.notes[a.ID].resolvedIDs[0])
	}

	// other/D.md 引用 [[B]]：两个候选都不同目录 → AMBIGUOUS。
	addNote("other/D.md", "引用 [[B]]")
	v.recomputeLinks()
	// other/D.md 自身也包含双链，直接检查其状态。
	var dID string
	for id, n := range v.notes {
		if n.meta.RelativePath == "other/D.md" {
			dID = id
		}
	}
	if dID == "" {
		t.Fatal("other/D.md 未被索引")
	}
	if got := v.notes[dID].linkStates[0]; got != linkAmbiguous {
		t.Fatalf("无同目录候选的歧义链接应标记 AMBIGUOUS: %s", got)
	}
}

func TestResolveLinkTargets(t *testing.T) {
	v := newTestVault(t)
	mustCreate(t, v, "同名")
	// 子目录中的同名笔记（标题取文件名去后缀，均为"同名"）。
	atomicWriteFile(filepath.Join(v.notesDir(), "sub", "同名.md"), []byte("子目录版本"))
	metaDir, _ := v.metaDirs()
	m := &NoteMeta{SchemaVersion: metaSchemaV1, NoteID: newID(), RelativePath: "sub/同名.md",
		CreatedAtUtc: nowMs(), UpdatedAtUtc: nowMs(), Revision: 1, State: stateActive, MetadataOrigin: originOriginal}
	saveNoteMeta(metaDir, m)
	v.indexNote(m, "子目录版本")
	v.recomputeLinks() // indexNote 不再自动全量重算（评审 #8），调用方负责

	targets := v.ResolveLinkTargets("同名")
	if len(targets) != 2 {
		t.Fatalf("应返回 2 个候选: %d", len(targets))
	}
}

// ---- BR-11：回收站流程 ------------------------------------------------------------

func TestTrashRestoreDeleteForever(t *testing.T) {
	v := newTestVault(t)
	doc := mustCreate(t, v, "数据结构复习")
	mustSave(t, v, doc.ID, "内容")

	stats, err := v.TrashNote(doc.ID)
	if err != nil {
		t.Fatal(err)
	}
	if stats.TrashCount != 1 || stats.NoteCount != 0 {
		t.Fatalf("统计错误: %+v", stats)
	}
	if _, err := os.Stat(filepath.Join(v.trashDir(), doc.ID+".md")); err != nil {
		t.Fatal("文件应移入 .trash/")
	}
	if _, err := os.Stat(filepath.Join(v.notesDir(), doc.RelativePath)); !os.IsNotExist(err) {
		t.Fatal("notes/ 下应已移除")
	}

	// 撤销恢复。
	restored, err := v.RestoreFromTrash(doc.ID)
	if err != nil {
		t.Fatal(err)
	}
	if restored.RelativePath != doc.RelativePath {
		t.Fatalf("恢复路径错误: %q", restored.RelativePath)
	}
	if data, _ := os.ReadFile(filepath.Join(v.notesDir(), doc.RelativePath)); string(data) != "内容" {
		t.Fatalf("恢复后内容损坏: %q", data)
	}

	// 再次删除并彻底粉碎。
	if _, err := v.TrashNote(doc.ID); err != nil {
		t.Fatal(err)
	}
	if _, err := v.DeleteForever(doc.ID); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(filepath.Join(v.trashDir(), doc.ID+".md")); !os.IsNotExist(err) {
		t.Fatal("彻底删除后文件应不存在")
	}
	if v.GetWorkspace().TrashCount != 0 {
		t.Fatal("回收站应为空")
	}
}

func TestRestoreToOccupiedPathGetsSuffix(t *testing.T) {
	v := newTestVault(t)
	doc := mustCreate(t, v, "笔记")
	if _, err := v.TrashNote(doc.ID); err != nil {
		t.Fatal(err)
	}
	// 原路径被新笔记占用。
	mustCreate(t, v, "笔记")
	restored, err := v.RestoreFromTrash(doc.ID)
	if err != nil {
		t.Fatal(err)
	}
	if restored.RelativePath != "笔记 (1).md" {
		t.Fatalf("恢复冲突应追加后缀: %q", restored.RelativePath)
	}
}

func TestTrashPurgeExpired(t *testing.T) {
	v := newTestVault(t)
	doc := mustCreate(t, v, "过期笔记")
	if _, err := v.TrashNote(doc.ID); err != nil {
		t.Fatal(err)
	}
	// 把回收时间拨回 31 天前。
	m := v.trashed[doc.ID]
	m.TrashedAtUtc = nowMs() - trashRetentionMs - 1000
	v.purgeExpiredTrash()
	if _, ok := v.trashed[doc.ID]; ok {
		t.Fatal("过期条目应被清除")
	}
	if _, err := os.Stat(filepath.Join(v.trashDir(), doc.ID+".md")); !os.IsNotExist(err) {
		t.Fatal("过期文件应被物理删除")
	}
}

// ---- BR-01/BR-03：事实源与索引自愈 -------------------------------------------------

func TestRebuildIndexRecoversOrphanFile(t *testing.T) {
	v := newTestVault(t)
	doc := mustCreate(t, v, "孤立笔记")
	mustSave(t, v, doc.ID, "#标签 内容 [[其他]]")

	// 模拟元数据损坏：删除 .meta/notes/{id}.json。
	os.Remove(filepath.Join(v.metaDir(), doc.ID+".json"))
	// 清空内存索引再重建（模拟重启）。
	v.notes = map[string]*indexedNote{}
	v.trashed = map[string]*NoteMeta{}

	result, err := v.RebuildIndex()
	if err != nil {
		t.Fatal(err)
	}
	if result.NoteCount != 1 {
		t.Fatalf("重建后应发现 1 篇笔记: %+v", result)
	}
	// 元数据丢失后身份无法找回，重建会生成新的 noteId，按路径找回。
	var n *indexedNote
	for _, item := range v.notes {
		if item.meta.RelativePath == doc.RelativePath {
			n = item
		}
	}
	if n == nil {
		t.Fatal("重建后应恢复索引")
	}
	if n.meta.MetadataOrigin != originRecovered {
		t.Fatalf("重建出的元数据应标记 RECOVERED: %s", n.meta.MetadataOrigin)
	}
	if len(n.tags) != 1 || n.tags[0].Name != "标签" {
		t.Fatalf("标签索引未恢复: %+v", n.tags)
	}
}

func TestRebuildIndexReportsOrphanMeta(t *testing.T) {
	v := newTestVault(t)
	doc := mustCreate(t, v, "将被外部删除")
	// 外部直接删除 .md 文件（保留元数据）。
	os.Remove(filepath.Join(v.notesDir(), doc.RelativePath))
	result, err := v.RebuildIndex()
	if err != nil {
		t.Fatal(err)
	}
	if result.OrphanMeta != 1 {
		t.Fatalf("应报告 1 条孤立元数据: %+v", result)
	}
}

// ---- FR-DATA：导入导出 ------------------------------------------------------------

func TestPrepareImportPlanAndApply(t *testing.T) {
	v := newTestVault(t)
	existing := mustCreate(t, v, "LeetCode")
	mustSave(t, v, existing.ID, "旧内容")

	external := t.TempDir()
	os.WriteFile(filepath.Join(external, "LeetCode.md"), []byte("新内容"), 0o644)
	os.WriteFile(filepath.Join(external, "Algorithm.md"), []byte("算法"), 0o644)
	os.WriteFile(filepath.Join(external, "Corrupted.md"), []byte{0xff, 0xfe, 0x00}, 0o644) // 无效 UTF-8/GBK

	// 直接构造计划（绕过系统对话框），模拟真实 PrepareImport 的产出。
	plan := ImportPlan{Files: []ImportFile{
		{SourcePath: filepath.Join(external, "LeetCode.md"), FileName: "LeetCode.md", TargetRel: "LeetCode.md", Status: "conflict"},
		{SourcePath: filepath.Join(external, "Algorithm.md"), FileName: "Algorithm.md", TargetRel: "Algorithm.md", Status: "ok"},
		{SourcePath: filepath.Join(external, "Corrupted.md"), FileName: "Corrupted.md", TargetRel: "Corrupted.md", Status: "invalid", Reason: "无法识别"},
	}}

	// 覆盖 LeetCode，其余默认导入。
	results, err := v.ApplyImport(plan, map[string]string{
		filepath.Join(external, "LeetCode.md"): "overwrite",
	})
	if err != nil {
		t.Fatal(err)
	}
	if len(results) != 3 {
		t.Fatalf("应有 3 条结果: %d", len(results))
	}
	byName := map[string]ImportResult{}
	for _, r := range results {
		byName[r.FileName] = r
	}
	if byName["LeetCode.md"].Status != "overwritten" {
		t.Fatalf("覆盖导入失败: %+v", byName["LeetCode.md"])
	}
	if body := v.readBody(v.notes[existing.ID].meta); body != "新内容" {
		t.Fatalf("覆盖后内容错误: %q", body)
	}
	if byName["Algorithm.md"].Status != "imported" {
		t.Fatalf("正常导入失败: %+v", byName["Algorithm.md"])
	}
	if byName["Corrupted.md"].Status != "skipped" {
		t.Fatalf("损坏文件应跳过: %+v", byName["Corrupted.md"])
	}
	// 覆盖流程产生 .bak 后应清理。
	if _, err := os.Stat(filepath.Join(v.notesDir(), "LeetCode.md.bak")); !os.IsNotExist(err) {
		t.Fatal("备份文件应在成功后移除")
	}
}

func TestApplyImportRenameOnConflict(t *testing.T) {
	v := newTestVault(t)
	mustCreate(t, v, "既有")
	external := t.TempDir()
	src := filepath.Join(external, "既有.md")
	os.WriteFile(src, []byte("导入版本"), 0o644)
	plan := ImportPlan{Files: []ImportFile{{SourcePath: src, FileName: "既有.md", TargetRel: "既有.md", Status: "conflict"}}}
	results, err := v.ApplyImport(plan, map[string]string{src: "rename"})
	if err != nil {
		t.Fatal(err)
	}
	if results[0].Status != "renamed" || results[0].TargetRel != "既有 (1).md" {
		t.Fatalf("重命名导入结果错误: %+v", results[0])
	}
}

func TestPrepareImportPlanFromDirectory(t *testing.T) {
	// PrepareImport 会打开系统对话框，无法无头测试；
	// 改为验证其内部对导入目标段的清洗逻辑。
	dirSeg, _ := sanitizeTitle("子目录?")
	base, _ := sanitizeTitle("笔记:名")
	if dirSeg != "子目录_" || base != "笔记_名" {
		t.Fatalf("导入目标段清洗错误: %q %q", dirSeg, base)
	}
}

func TestExportVaultZipStructure(t *testing.T) {
	v := newTestVault(t)
	a := mustCreate(t, v, "网络协议")
	mustSave(t, v, a.ID, "#计算机网络 正文，参见 [[其他]]。")
	mustCreate(t, v, "其他")
	if len(v.notes) != 2 {
		t.Fatalf("前置条件失败: %d", len(v.notes))
	}

	// ExportVault 需要系统保存对话框；此处只验证 zip 写入与 UTF-8 文件名结构。

	// 直接写一个 zip 验证 Go 标准库对 UTF-8 文件名的支持。
	zipPath := filepath.Join(t.TempDir(), "backup.zip")
	f, _ := os.Create(zipPath)
	w := zip.NewWriter(f)
	entry, err := w.Create("notes/网络协议.md")
	if err != nil {
		t.Fatal(err)
	}
	entry.Write([]byte("中文文件名内容"))
	w.Close()
	f.Close()

	r, err := zip.OpenReader(zipPath)
	if err != nil {
		t.Fatal(err)
	}
	defer r.Close()
	if len(r.File) != 1 || r.File[0].Name != "notes/网络协议.md" {
		t.Fatalf("zip 中文文件名损坏: %+v", r.File)
	}
}

func TestExportManifestJSONShape(t *testing.T) {
	manifest := ExportManifest{
		Generator: "JustMD Windows v0.1.0", ExportTimestamp: msToISO(nowMs()),
		SchemaVersion: "1.1", TotalNotes: 1, TotalAttachments: 0,
		NotesIndex: []ManifestEntry{{
			NoteID: "x", RelativePath: "notes/a.md", SHA256: sha256Hex([]byte("a")),
			Tags: []string{"t"}, OutboundLinks: []string{"b"},
			CreatedAt: msToISO(0), UpdatedAt: msToISO(0), Revision: 1, MetadataOrigin: originOriginal,
		}},
	}
	data, err := json.Marshal(manifest)
	if err != nil {
		t.Fatal(err)
	}
	var parsed map[string]any
	if err := json.Unmarshal(data, &parsed); err != nil {
		t.Fatal(err)
	}
	if parsed["schemaVersion"] != "1.1" || parsed["totalNotes"] != float64(1) {
		t.Fatalf("manifest 字段不完整: %s", data)
	}
}

// ---- GBK 导入转码（基线 4.1）------------------------------------------------------

func TestGBKImportDecoded(t *testing.T) {
	// 用 GBK 编码器生成"中文笔记"的字节，验证解码路径。
	encoded := []byte{0xd6, 0xd0, 0xce, 0xc4, 0xb1, 0xca, 0xbc, 0xc7} // 中文笔记
	decoded, err := gbkDecode(encoded)
	if err != nil {
		t.Fatalf("GBK 解码失败: %v", err)
	}
	if decoded != "中文笔记" {
		t.Fatalf("GBK 解码结果错误: %q", decoded)
	}
}

// ---- pending 日志恢复协议（设计 6.2）-----------------------------------------------

func TestReconcilePendingCompletesSave(t *testing.T) {
	v := newTestVault(t)
	doc := mustCreate(t, v, "断电恢复")
	receipt := mustSave(t, v, doc.ID, "最终内容")

	// 模拟"正文已落盘、元数据提交前断电"：把元数据回滚到旧版本，
	// 重新写入 pending 记录，然后触发对账。
	metaDir, pendingDir := v.metaDirs()
	old := *v.notes[doc.ID].meta
	old.Revision = receipt.Revision - 1
	old.ContentSha256 = sha256Hex([]byte("中间版本"))
	saveNoteMeta(metaDir, &old)

	op := &PendingOp{
		OperationID: "op-1", NoteID: doc.ID, Kind: "save",
		OldSha: sha256Hex([]byte("中间版本")), NewSha: receipt.ContentSha256,
		ExpectedRevision: receipt.Revision - 1,
	}
	if err := writePending(pendingDir, op); err != nil {
		t.Fatal(err)
	}

	metas := map[string]*NoteMeta{doc.ID: &old}
	v.reconcilePending(pendingDir, metas)

	fresh, err := loadNoteMeta(filepath.Join(metaDir, doc.ID+".json"))
	if err != nil || fresh == nil {
		t.Fatal("元数据读取失败")
	}
	if fresh.Revision != receipt.Revision || fresh.ContentSha256 != receipt.ContentSha256 {
		t.Fatalf("对账未补齐元数据: rev=%d sha=%s", fresh.Revision, fresh.ContentSha256)
	}
	if _, err := os.Stat(filepath.Join(pendingDir, "op-1.json")); !os.IsNotExist(err) {
		t.Fatal("对账后应清理 pending 记录")
	}
}

func TestReconcilePendingDiscardsIncompleteWrite(t *testing.T) {
	v := newTestVault(t)
	doc := mustCreate(t, v, "半写文件")
	receipt := mustSave(t, v, doc.ID, "已保存内容")

	metaDir, pendingDir := v.metaDirs()
	// 正文仍是旧内容（新版本未落盘）。
	op := &PendingOp{
		OperationID: "op-2", NoteID: doc.ID, Kind: "save",
		OldSha: receipt.ContentSha256, NewSha: sha256Hex([]byte("没写完的内容")),
		ExpectedRevision: receipt.Revision,
	}
	writePending(pendingDir, op)
	metas := map[string]*NoteMeta{doc.ID: v.notes[doc.ID].meta}
	v.reconcilePending(pendingDir, metas)

	fresh, _ := loadNoteMeta(filepath.Join(metaDir, doc.ID+".json"))
	if fresh.Revision != receipt.Revision {
		t.Fatalf("未落盘的新版本不得推进元数据: rev=%d", fresh.Revision)
	}
	if body := v.readBody(v.notes[doc.ID].meta); body != "已保存内容" {
		t.Fatalf("旧版本应完好: %q", body)
	}
}

// ---- 标签树（多级嵌套） ---------------------------------------------------------

func TestListTagsMultiLevelTree(t *testing.T) {
	v := newTestVault(t)
	doc := mustCreate(t, v, "标签页")
	mustSave(t, v, doc.ID, "内容A #复习计划/第一章 #复习计划/第二章 #TCP")
	mustSave(t, v, mustCreate(t, v, "二号").ID, "#复习计划/第一章")

	tags := v.ListTags()
	var root *TagNode
	for i := range tags {
		if tags[i].Name == "复习计划" {
			root = &tags[i]
		}
	}
	if root == nil {
		t.Fatalf("多级标签的父节点应作为根出现: %+v", tags)
	}
	if len(root.Children) != 2 {
		t.Fatalf("复习计划应有两个子级: %+v", root.Children)
	}
	if root.Children[0].Name != "第一章" || root.Children[0].Count != 2 {
		t.Fatalf("第一章应计数 2: %+v", root.Children[0])
	}
	if root.Children[1].Name != "第二章" || root.Children[1].Count != 1 {
		t.Fatalf("第二章应计数 1: %+v", root.Children[1])
	}
	var flat *TagNode
	for i := range tags {
		if tags[i].Name == "TCP" {
			flat = &tags[i]
		}
	}
	if flat == nil || flat.Count != 1 || len(flat.Children) != 0 {
		t.Fatalf("单级标签不受影响: %+v", tags)
	}
}

// ---- 排序 ---------------------------------------------------------------------

func TestSortSummaries(t *testing.T) {
	list := []NoteSummary{
		{Title: "b", UpdatedAtUtc: 100, CreatedAtUtc: 300},
		{Title: "A", UpdatedAtUtc: 200, CreatedAtUtc: 100},
		{Title: "c", UpdatedAtUtc: 300, CreatedAtUtc: 200},
	}
	sortSummaries(list, "updated_desc")
	if list[0].Title != "c" {
		t.Fatalf("updated_desc 排序错误: %+v", list)
	}
	sortSummaries(list, "title_asc")
	if list[0].Title != "A" || list[1].Title != "b" {
		t.Fatalf("title_asc 应大小写折叠排序: %+v", list)
	}
	sortSummaries(list, "created_asc")
	if list[0].Title != "A" {
		t.Fatalf("created_asc 排序错误: %+v", list)
	}
}

func TestTitleHelpers(t *testing.T) {
	if got := titleOfRelPath("计算机网络/TCP.md"); got != "TCP" {
		t.Fatalf("titleOfRelPath 错误: %q", got)
	}
	if got := parentDirOfRelPath("计算机网络/TCP.md"); got != "计算机网络" {
		t.Fatalf("parentDirOfRelPath 错误: %q", got)
	}
	if got := parentDirOfRelPath("TCP.md"); got != "" {
		t.Fatalf("根目录 parentDir 应为空串: %q", got)
	}
}

// ---- 评审 #1：trash/restore/delete/rename 崩溃点对账 --------------------------------

func TestReconcilePendingCompletesTrash(t *testing.T) {
	v := newTestVault(t)
	doc := mustCreate(t, v, "中断的删除")
	mustSave(t, v, doc.ID, "内容")
	metaDir, pendingDir := v.metaDirs()

	// 模拟 TrashNote 在文件移入 .trash/ 之后、元数据提交之前断电。
	src := filepath.Join(v.notesDir(), doc.RelativePath)
	dst := filepath.Join(v.trashDir(), doc.ID+".md")
	if err := os.Rename(src, dst); err != nil {
		t.Fatal(err)
	}
	op := &PendingOp{OperationID: "op-t", NoteID: doc.ID, Kind: "trash",
		OldRelPath: doc.RelativePath, ExpectedRevision: v.notes[doc.ID].meta.Revision}
	if err := writePending(pendingDir, op); err != nil {
		t.Fatal(err)
	}

	if err := v.loadWorkspace(); err != nil {
		t.Fatal(err)
	}
	if _, ok := v.notes[doc.ID]; ok {
		t.Fatal("中断的 trash 应补完，不应再出现在活跃列表")
	}
	m := v.trashed[doc.ID]
	if m == nil {
		t.Fatal("中断的 trash 应补完，应出现在回收站（此前会两边都看不到）")
	}
	if m.State != stateTrashed {
		t.Fatalf("状态应为 TRASHED: %s", m.State)
	}
	fresh, _ := loadNoteMeta(filepath.Join(metaDir, doc.ID+".json"))
	if fresh == nil || fresh.State != stateTrashed {
		t.Fatalf("元数据应提交 TRASHED: %+v", fresh)
	}
}

func TestReconcilePendingRollsBackTrash(t *testing.T) {
	v := newTestVault(t)
	doc := mustCreate(t, v, "未推进的删除")
	mustSave(t, v, doc.ID, "内容")
	_, pendingDir := v.metaDirs()
	// pending 已写但 rename 未发生：应保留活跃态。
	op := &PendingOp{OperationID: "op-t2", NoteID: doc.ID, Kind: "trash",
		OldRelPath: doc.RelativePath, ExpectedRevision: v.notes[doc.ID].meta.Revision}
	if err := writePending(pendingDir, op); err != nil {
		t.Fatal(err)
	}
	if err := v.loadWorkspace(); err != nil {
		t.Fatal(err)
	}
	if _, ok := v.notes[doc.ID]; !ok {
		t.Fatal("未推进的 trash 应回退保留活跃态")
	}
	if _, ok := v.trashed[doc.ID]; ok {
		t.Fatal("未推进的 trash 不应进入回收站")
	}
}

func TestReconcilePendingCompletesRestore(t *testing.T) {
	v := newTestVault(t)
	doc := mustCreate(t, v, "中断的还原")
	mustSave(t, v, doc.ID, "内容")
	if _, err := v.TrashNote(doc.ID); err != nil {
		t.Fatal(err)
	}
	_, pendingDir := v.metaDirs()

	// 模拟 RestoreFromTrash 在文件回到 notes/ 之后、元数据提交之前断电。
	rel := doc.RelativePath
	if err := os.Rename(filepath.Join(v.trashDir(), doc.ID+".md"), filepath.Join(v.notesDir(), rel)); err != nil {
		t.Fatal(err)
	}
	op := &PendingOp{OperationID: "op-r", NoteID: doc.ID, Kind: "restore",
		OldRelPath: rel, NewRelPath: rel, ExpectedRevision: v.trashed[doc.ID].Revision}
	if err := writePending(pendingDir, op); err != nil {
		t.Fatal(err)
	}

	if err := v.loadWorkspace(); err != nil {
		t.Fatal(err)
	}
	n := v.notes[doc.ID]
	if n == nil {
		t.Fatal("中断的 restore 应补完并保留原 noteId（此前会另建 RECOVERED 身份）")
	}
	if n.meta.State != stateActive || n.meta.RelativePath != rel {
		t.Fatalf("还原状态错误: %+v", n.meta)
	}
	if _, ok := v.trashed[doc.ID]; ok {
		t.Fatal("补完后不应再出现在回收站")
	}
}

func TestReconcilePendingRollsBackRestore(t *testing.T) {
	v := newTestVault(t)
	doc := mustCreate(t, v, "未推进的还原")
	if _, err := v.TrashNote(doc.ID); err != nil {
		t.Fatal(err)
	}
	_, pendingDir := v.metaDirs()
	op := &PendingOp{OperationID: "op-r2", NoteID: doc.ID, Kind: "restore",
		OldRelPath: doc.RelativePath, NewRelPath: doc.RelativePath, ExpectedRevision: 1}
	if err := writePending(pendingDir, op); err != nil {
		t.Fatal(err)
	}
	if err := v.loadWorkspace(); err != nil {
		t.Fatal(err)
	}
	if _, ok := v.trashed[doc.ID]; !ok {
		t.Fatal("未推进的 restore 应回退保留回收站态")
	}
	if _, ok := v.notes[doc.ID]; ok {
		t.Fatal("未推进的 restore 不应进入活跃列表")
	}
}

func TestReconcilePendingCompletesDelete(t *testing.T) {
	v := newTestVault(t)
	doc := mustCreate(t, v, "中断的粉碎")
	if _, err := v.TrashNote(doc.ID); err != nil {
		t.Fatal(err)
	}
	metaDir, pendingDir := v.metaDirs()
	// 模拟 DeleteForever 删除回收站副本后、元数据删除前断电。
	if err := os.Remove(filepath.Join(v.trashDir(), doc.ID+".md")); err != nil {
		t.Fatal(err)
	}
	op := &PendingOp{OperationID: "op-d", NoteID: doc.ID, Kind: "delete", OldRelPath: doc.RelativePath}
	if err := writePending(pendingDir, op); err != nil {
		t.Fatal(err)
	}

	if err := v.loadWorkspace(); err != nil {
		t.Fatal(err)
	}
	if _, ok := v.trashed[doc.ID]; ok {
		t.Fatal("中断的 delete 应补完，不应再出现在回收站")
	}
	if _, err := os.Stat(filepath.Join(metaDir, doc.ID+".json")); !os.IsNotExist(err) {
		t.Fatal("元数据应被补删")
	}
}

func TestTrashCrashWithoutPendingRecovers(t *testing.T) {
	v := newTestVault(t)
	doc := mustCreate(t, v, "丢失 pending 的删除")
	mustSave(t, v, doc.ID, "内容")
	// 文件已在 .trash/、元数据仍 ACTIVE、pending 已丢：兜底也应进回收站。
	if err := os.Rename(filepath.Join(v.notesDir(), doc.RelativePath), filepath.Join(v.trashDir(), doc.ID+".md")); err != nil {
		t.Fatal(err)
	}
	if err := v.loadWorkspace(); err != nil {
		t.Fatal(err)
	}
	if _, ok := v.notes[doc.ID]; ok {
		t.Fatal("不应再出现在活跃列表")
	}
	if m := v.trashed[doc.ID]; m == nil || m.State != stateTrashed {
		t.Fatalf("兜底路径也应恢复到回收站: %+v", m)
	}
}

// ---- 评审 #5/#7/#9/#4/#6：大小写重命名、外部修改、路径校验、附件、拖放 --------------

func TestRenameCaseOnlyKeepsPath(t *testing.T) {
	v := newTestVault(t)
	doc := mustCreate(t, v, "Note")
	got, err := v.RenameNote(doc.ID, "note")
	if err != nil {
		t.Fatal(err)
	}
	if got.RelativePath != "note.md" {
		t.Fatalf("仅大小写重命名不应追加冲突后缀: %q", got.RelativePath)
	}
	if _, err := os.Stat(filepath.Join(v.notesDir(), "note (1).md")); !os.IsNotExist(err) {
		t.Fatal("不应生成 note (1).md")
	}
}

func TestSaveContentDetectsExternalEdit(t *testing.T) {
	v := newTestVault(t)
	doc := mustCreate(t, v, "外部修改")
	mustSave(t, v, doc.ID, "第一版")

	// 另一个程序改写了磁盘文件。
	abs := filepath.Join(v.notesDir(), doc.RelativePath)
	if err := os.WriteFile(abs, []byte("外部的新内容"), 0o644); err != nil {
		t.Fatal(err)
	}
	if _, err := v.SaveContent(doc.ID, "编辑器里的内容"); err != ErrExternalEdit {
		t.Fatalf("检测到外部修改应返回 ErrExternalEdit: %v", err)
	}
	if body := v.readBody(v.notes[doc.ID].meta); body != "外部的新内容" {
		t.Fatalf("冲突时不得覆盖磁盘内容: %q", body)
	}
	if _, err := v.ForceSaveContent(doc.ID, "编辑器里的内容"); err != nil {
		t.Fatalf("显式强制保存应成功: %v", err)
	}
	if body := v.readBody(v.notes[doc.ID].meta); body != "编辑器里的内容" {
		t.Fatalf("强制保存应覆盖: %q", body)
	}
}

func TestCascadeUpdateKeepsShaInSync(t *testing.T) {
	// 级联改写后元数据哈希必须同步，否则后续保存被误判为外部修改（评审 #7）。
	v := newTestVault(t)
	target := mustCreate(t, v, "目标")
	src := mustCreate(t, v, "来源")
	mustSave(t, v, src.ID, "见 [[目标]]")
	if _, err := v.RenameNote(target.ID, "新目标"); err != nil {
		t.Fatal(err)
	}
	if _, err := v.SaveContent(src.ID, "见 [[新目标]]！"); err != nil {
		t.Fatalf("级联改写后保存不应误报冲突: %v", err)
	}
}

func TestApplyImportRejectsUnsafePlan(t *testing.T) {
	v := newTestVault(t)
	external := t.TempDir()
	src := filepath.Join(external, "x.md")
	if err := os.WriteFile(src, []byte("x"), 0o644); err != nil {
		t.Fatal(err)
	}
	plan := ImportPlan{Files: []ImportFile{
		{SourcePath: src, FileName: "x.md", TargetRel: "../逃逸.md", Status: "ok"},
		{SourcePath: filepath.Join(external, "missing.md"), FileName: "missing.md", TargetRel: "missing.md", Status: "ok"},
	}}
	results, err := v.ApplyImport(plan, nil)
	if err != nil {
		t.Fatal(err)
	}
	for _, r := range results {
		if r.Status != "failed" {
			t.Fatalf("非法计划条目应在执行阶段被拒绝: %+v", r)
		}
	}
	if _, err := os.Stat(filepath.Join(v.root, "逃逸.md")); !os.IsNotExist(err) {
		t.Fatal("目标路径穿越未被拦截")
	}
}

func TestImportDroppedFilesAndAttachmentLoad(t *testing.T) {
	v := newTestVault(t)
	ext := t.TempDir()
	mdPath := filepath.Join(ext, "拖入笔记.md")
	if err := os.WriteFile(mdPath, []byte("# 内容"), 0o644); err != nil {
		t.Fatal(err)
	}
	pngPath := filepath.Join(ext, "图片.png")
	if err := os.WriteFile(pngPath, []byte{0x89, 'P', 'N', 'G'}, 0o644); err != nil {
		t.Fatal(err)
	}

	results, err := v.ImportDroppedFiles([]string{mdPath, pngPath})
	if err != nil {
		t.Fatal(err)
	}
	if len(results) != 2 {
		t.Fatalf("应返回 2 条结果: %d", len(results))
	}
	for _, r := range results {
		if r.Status == "" || r.Status == "failed" {
			t.Fatalf("拖放结果状态不应为空或失败（此前因变量遮蔽为空）: %+v", r)
		}
		if r.RelPath == "" {
			t.Fatalf("拖放结果 RelPath 不应为空: %+v", r)
		}
	}

	// .md 成为笔记；png 的引用路径可被 NoteAssetPath 解析（评审 #4）。
	var noteID string
	for id, n := range v.notes {
		if n.meta.RelativePath == results[0].RelPath {
			noteID = id
		}
	}
	if noteID == "" {
		t.Fatalf("拖入的 .md 应入库: %+v", results[0])
	}
	abs, err := v.NoteAssetPath(noteID, results[1].RelPath)
	if err != nil {
		t.Fatalf("拖入的附件应可加载（此前只在 notes/ 下查找）: %v", err)
	}
	if _, err := os.Stat(abs); err != nil {
		t.Fatalf("附件路径应指向真实文件: %v", err)
	}
}
