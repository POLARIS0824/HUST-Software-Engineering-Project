package main

import (
	"archive/zip"
	"bytes"
	"encoding/json"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"testing"
	"time"
	"unicode/utf8"

	"github.com/egoist/mygo"
)

// 编译期确认"导入文件夹"入口的签名：app.go 的 importFolderFlow 依赖它，
// 且必须是独立入口（文件对话框无法同时选文件与目录）。
var _ func(*Vault, *mygo.Window) (ImportPlan, error) = (*Vault).PrepareImportFolder

// reloadVaultForTest 模拟"关闭应用后重新启动"：只保留工作区根目录，重新装载。
func reloadVaultForTest(t *testing.T, root string) *Vault {
	t.Helper()
	nv := &Vault{
		root:    root,
		notes:   map[string]*indexedNote{},
		trashed: map[string]*NoteMeta{},
		byTitle: map[string][]string{},
	}
	if err := nv.loadWorkspace(); err != nil {
		t.Fatalf("loadWorkspace: %v", err)
	}
	return nv
}

func findNoteByPath(v *Vault, rel string) *indexedNote {
	for _, n := range v.notes {
		if n.meta.RelativePath == rel {
			return n
		}
	}
	return nil
}

func findNoteByTitle(v *Vault, title string) *indexedNote {
	for _, n := range v.notes {
		if n.title == title {
			return n
		}
	}
	return nil
}

// ---- 缺陷 1：GBK/BOM/CRLF 与无效文本的唯一解码入口（基线 4.1）-------------------

func TestDecodeImportedTextVariants(t *testing.T) {
	gbk := []byte{0xd6, 0xd0, 0xce, 0xc4, 0xb1, 0xca, 0xbc, 0xc7} // "中文笔记"
	out, err := decodeImportedText(gbk)
	if err != nil {
		t.Fatalf("GBK 文本应可解码: %v", err)
	}
	if string(out) != "中文笔记" || !utf8.Valid(out) {
		t.Fatalf("GBK 应转成 UTF-8 中文: %q", string(out))
	}

	bomCrlf := append([]byte{0xEF, 0xBB, 0xBF}, []byte("第一行\r\n第二行\r第三行")...)
	out, err = decodeImportedText(bomCrlf)
	if err != nil {
		t.Fatalf("BOM+CRLF 应可解码: %v", err)
	}
	if string(out) != "第一行\n第二行\n第三行" {
		t.Fatalf("BOM 未剥离或换行未归一: %q", string(out))
	}

	invalid := map[string][]byte{
		"NUL 字节":  []byte("abc\x00def"),
		"替换字符":    []byte("abc\uFFFDefg"),
		"PNG 二进制": {0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00},
		"随机二进制":   {0xff, 0xfe, 0x00, 0x81},
	}
	for name, data := range invalid {
		if _, err := decodeImportedText(data); err == nil {
			t.Errorf("%s 应判为无效文本，却通过了解码", name)
		}
	}
}

func TestGBKImportDecodedToUTF8EndToEnd(t *testing.T) {
	v := newTestVault(t)
	ext := t.TempDir()
	src := filepath.Join(ext, "老笔记.md")
	// "中文笔记\n#标签" 的 GBK 字节。
	body := []byte{0xd6, 0xd0, 0xce, 0xc4, 0xb1, 0xca, 0xbc, 0xc7, '\n', '#', 0xb1, 0xea, 0xc7, 0xa9}
	if err := os.WriteFile(src, body, 0o644); err != nil {
		t.Fatal(err)
	}

	plan := ImportPlan{Files: []ImportFile{{SourcePath: src, FileName: "老笔记.md", TargetRel: "老笔记.md", Status: "ok"}}}
	results, err := v.ApplyImport(plan, nil)
	if err != nil {
		t.Fatal(err)
	}
	if len(results) != 1 || results[0].Status != "imported" {
		t.Fatalf("GBK 文件应成功导入: %+v", results)
	}

	abs := filepath.Join(v.notesDir(), filepath.FromSlash(results[0].TargetRel))
	data, err := os.ReadFile(abs)
	if err != nil {
		t.Fatal(err)
	}
	if !utf8.Valid(data) || string(data) != "中文笔记\n#标签" {
		t.Fatalf("落盘内容应是转码后的 UTF-8 中文: %q", string(data))
	}
	doc, err := v.OpenNote(results[0].NoteID)
	if err != nil {
		t.Fatal(err)
	}
	if doc.Markdown != "中文笔记\n#标签" {
		t.Fatalf("应用内正文错误: %q", doc.Markdown)
	}
	if len(doc.Tags) != 1 || doc.Tags[0].Name != "标签" {
		t.Fatalf("转码后应能提取中文标签: %+v", doc.Tags)
	}
	// 导入后元数据哈希必须对得上磁盘，首次保存不能被误判为外部修改。
	if _, err := v.SaveContent(doc.ID, doc.Markdown+"\n补充一句"); err != nil {
		t.Fatalf("导入后首次保存不应报外部修改: %v", err)
	}
}

func TestImportOverwriteDecodesGBK(t *testing.T) {
	v := newTestVault(t)
	existing := mustCreate(t, v, "覆盖目标")
	mustSave(t, v, existing.ID, "旧内容")

	ext := t.TempDir()
	src := filepath.Join(ext, "覆盖目标.md")
	if err := os.WriteFile(src, []byte{0xd0, 0xc2, 0xc4, 0xda, 0xc8, 0xdd}, 0o644); err != nil { // "新内容"
		t.Fatal(err)
	}
	plan := ImportPlan{Files: []ImportFile{{SourcePath: src, FileName: "覆盖目标.md", TargetRel: "覆盖目标.md", Status: "conflict"}}}
	results, err := v.ApplyImport(plan, map[string]string{src: "overwrite"})
	if err != nil {
		t.Fatal(err)
	}
	if results[0].Status != "overwritten" {
		t.Fatalf("覆盖导入失败: %+v", results[0])
	}
	if body := v.readBody(v.notes[existing.ID].meta); body != "新内容" {
		t.Fatalf("覆盖导入也必须走 GBK 解码: %q", body)
	}
}

func TestImportRejectsBinaryMarkdown(t *testing.T) {
	v := newTestVault(t)
	ext := t.TempDir()
	src := filepath.Join(ext, "伪装笔记.md")
	if err := os.WriteFile(src, []byte{0x89, 0x50, 0x4e, 0x47, 0x00, 0x01}, 0o644); err != nil {
		t.Fatal(err)
	}
	plan := ImportPlan{Files: []ImportFile{{SourcePath: src, FileName: "伪装笔记.md", TargetRel: "伪装笔记.md", Status: "ok"}}}
	results, err := v.ApplyImport(plan, nil)
	if err != nil {
		t.Fatal(err)
	}
	if results[0].Status != "failed" || !strings.Contains(results[0].Reason, "无法识别") {
		t.Fatalf("二进制 .md 应被拒绝并给出业务化原因: %+v", results[0])
	}
	if _, err := os.Stat(filepath.Join(v.notesDir(), "伪装笔记.md")); !os.IsNotExist(err) {
		t.Fatal("无效文本不应落盘")
	}
}

// ---- 缺陷 2：文件夹导入的计划构建（PrepareImportFolder 复用同一逻辑）-------------

func TestBuildImportPlanRecursesFolder(t *testing.T) {
	v := newTestVault(t)
	existing := mustCreate(t, v, "既有")
	mustSave(t, v, existing.ID, "旧内容")

	root := t.TempDir()
	if err := os.MkdirAll(filepath.Join(root, "子目录"), 0o755); err != nil {
		t.Fatal(err)
	}
	writeFile(t, filepath.Join(root, "顶层.md"), "顶层内容")
	writeFile(t, filepath.Join(root, "既有.md"), "导入版本")
	writeFile(t, filepath.Join(root, "子目录", "笔记.markdown"), "子目录内容")
	writeFile(t, filepath.Join(root, "子目录", "坏文件.md"), string([]byte{0xff, 0xfe, 0x00}))
	writeFile(t, filepath.Join(root, "忽略.txt"), "不是 Markdown")

	plan := v.buildImportPlan([]string{root})
	if len(plan.Files) != 4 {
		t.Fatalf("应收集 4 个 Markdown（含 1 个无效），实际 %d: %+v", len(plan.Files), plan.Files)
	}
	byName := map[string]ImportFile{}
	for _, f := range plan.Files {
		byName[f.FileName] = f
	}
	if got := byName["顶层.md"]; got.Status != "ok" || got.TargetRel != "顶层.md" {
		t.Errorf("顶层文件计划错误: %+v", got)
	}
	if got := byName["笔记.markdown"]; got.Status != "ok" || got.TargetRel != "子目录/笔记.md" {
		t.Errorf("子目录层级应保留且扩展名统一为 .md: %+v", got)
	}
	if got := byName["既有.md"]; got.Status != "conflict" {
		t.Errorf("同名既有笔记应标冲突: %+v", got)
	}
	if got := byName["坏文件.md"]; got.Status != "invalid" || !strings.Contains(got.Reason, "无法识别") {
		t.Errorf("无效编码应标 invalid: %+v", got)
	}
	if _, ok := byName["忽略.txt"]; ok {
		t.Error("非 Markdown 文件不应进入导入计划")
	}
}

// ---- 缺陷 3/4：拖放目录递归 + 导入后重算双链 -------------------------------------

func TestDropImportResolvesLinksImmediately(t *testing.T) {
	v := newTestVault(t)
	b := mustCreate(t, v, "B")
	mustSave(t, v, b.ID, "B 正文")

	ext := t.TempDir()
	src := filepath.Join(ext, "A.md")
	writeFile(t, src, "参考 [[B]] 与 [[C]]。")
	results, err := v.ImportDroppedFiles([]string{src})
	if err != nil {
		t.Fatal(err)
	}
	if results[0].Status != "imported" {
		t.Fatalf("拖入 .md 应导入: %+v", results[0])
	}
	a := findNoteByTitle(v, "A")
	if a == nil {
		t.Fatal("拖入的 A 未入索引")
	}

	var targetB string
	for _, l := range v.GetNoteLinks(a.meta.NoteID) {
		switch l.Target {
		case "B":
			targetB = l.State
		case "C":
			if l.State != string(linkUnresolved) {
				t.Errorf("[[C]] 目标不存在，应为 UNRESOLVED: %+v", l)
			}
		}
	}
	if targetB != string(linkResolved) {
		t.Errorf("拖放导入后 [[B]] 应立即 RESOLVED，实际 %q", targetB)
	}
	if bl := v.GetBacklinks(b.ID); len(bl) != 1 || bl[0].SourceNoteID != a.meta.NoteID {
		t.Errorf("B 的反链应包含 A: %+v", bl)
	}
	reader := v.RenderReaderSource(a.meta.NoteID)
	if !strings.Contains(reader, readerSchemeNote+b.ID) {
		t.Errorf("阅读页应把 [[B]] 渲染为已解析链接: %q", reader)
	}
	if !strings.Contains(reader, readerSchemeOpen+"C") {
		t.Errorf("阅读页应把 [[C]] 渲染为待创建链接: %q", reader)
	}
}

func TestDropDirectoryRecurses(t *testing.T) {
	v := newTestVault(t)
	root := t.TempDir()
	if err := os.MkdirAll(filepath.Join(root, "子"), 0o755); err != nil {
		t.Fatal(err)
	}
	writeFile(t, filepath.Join(root, "甲.md"), "#甲 内容")
	writeFile(t, filepath.Join(root, "乙.md"), "乙 内容")
	writeFile(t, filepath.Join(root, "图.png"), string([]byte{0x89, 'P', 'N', 'G'}))
	writeFile(t, filepath.Join(root, "子", "深.svg"), "<svg/>")

	results, err := v.ImportDroppedFiles([]string{root})
	if err != nil {
		t.Fatal(err)
	}
	notes, copied := 0, 0
	for _, r := range results {
		switch r.Status {
		case "imported", "renamed":
			notes++
		case "copied":
			copied++
		default:
			t.Errorf("目录拖放不应失败: %+v", r)
		}
		if r.RelPath == "" {
			t.Errorf("结果应返回真实路径: %+v", r)
		}
	}
	if notes != 2 || copied != 2 {
		t.Fatalf("目录内应导入 2 篇笔记、复制 2 个附件，实际 %d/%d: %+v", notes, copied, results)
	}
	if len(v.notes) != 2 {
		t.Fatalf("目录内 .md 应入库: %d", len(v.notes))
	}
	atts, _ := os.ReadDir(v.attachmentsDir())
	if len(atts) != 2 {
		t.Fatalf("目录内非 md 文件应复制进 attachments/: %d", len(atts))
	}

	// 空目录也要计入结果（不能静默无反应）。
	empty := t.TempDir()
	res, err := v.ImportDroppedFiles([]string{empty})
	if err != nil {
		t.Fatal(err)
	}
	if len(res) != 1 || res[0].Status != "failed" || res[0].Reason == "" {
		t.Fatalf("空文件夹应返回一条带原因的失败结果: %+v", res)
	}
}

// ---- 缺陷 5：同名附件不得静默覆盖 ------------------------------------------------

func TestDroppedAttachmentNamesAreUnique(t *testing.T) {
	v := newTestVault(t)
	d1, d2 := t.TempDir(), t.TempDir()
	p1 := filepath.Join(d1, "shot.png")
	p2 := filepath.Join(d2, "shot.png")
	writeFile(t, p1, "IMAGE-ONE")
	writeFile(t, p2, "IMAGE-TWO")
	// 清洗后会折叠成同一个名字（空格与下划线都映射为下划线）。
	d3, d4 := t.TempDir(), t.TempDir()
	p3 := filepath.Join(d3, "a b.png")
	p4 := filepath.Join(d4, "a_b.png")
	writeFile(t, p3, "SPACE-NAME")
	writeFile(t, p4, "UNDERSCORE-NAME")

	results, err := v.ImportDroppedFiles([]string{p1, p2, p3, p4})
	if err != nil {
		t.Fatal(err)
	}
	if len(results) != 4 {
		t.Fatalf("应返回 4 条结果: %d", len(results))
	}
	seen := map[string]bool{}
	for _, r := range results {
		if r.Status != "copied" || r.RelPath == "" {
			t.Fatalf("附件应复制成功: %+v", r)
		}
		if seen[r.RelPath] {
			t.Fatalf("附件引用路径重复（会互相覆盖）: %q", r.RelPath)
		}
		seen[r.RelPath] = true
	}
	atts, _ := os.ReadDir(v.attachmentsDir())
	if len(atts) != 4 {
		t.Fatalf("4 个不同附件应落成 4 个文件，实际 %d", len(atts))
	}
	contents := map[string]bool{}
	for rel := range seen {
		data, err := os.ReadFile(filepath.Join(v.root, filepath.FromSlash(rel)))
		if err != nil {
			t.Fatalf("附件路径不可读: %s: %v", rel, err)
		}
		contents[string(data)] = true
	}
	for _, want := range []string{"IMAGE-ONE", "IMAGE-TWO", "SPACE-NAME", "UNDERSCORE-NAME"} {
		if !contents[want] {
			t.Errorf("附件内容 %q 被同名文件覆盖丢失；实际=%v", want, contents)
		}
	}
}

// ---- 缺陷 6：装载时校准哈希 + 同路径多身份确定性 --------------------------------

func TestLoadWorkspaceCalibratesExternalEditSha(t *testing.T) {
	v := newTestVault(t)
	doc := mustCreate(t, v, "同步笔记")
	mustSave(t, v, doc.ID, "app 里写的内容")
	revBefore := v.notes[doc.ID].meta.Revision

	abs := filepath.Join(v.notesDir(), doc.RelativePath)
	if err := os.WriteFile(abs, []byte("外部改过的内容"), 0o644); err != nil {
		t.Fatal(err)
	}

	nv := reloadVaultForTest(t, v.root)
	loaded, err := nv.OpenNote(doc.ID)
	if err != nil {
		t.Fatal(err)
	}
	if loaded.Markdown != "外部改过的内容" {
		t.Fatalf("重启后应载入磁盘内容: %q", loaded.Markdown)
	}
	meta := nv.notes[doc.ID].meta
	if meta.ContentSha256 != sha256Hex([]byte("外部改过的内容")) {
		t.Fatalf("装载后元数据哈希应校准为磁盘实际哈希: %s", meta.ContentSha256)
	}
	if meta.Revision != revBefore {
		t.Fatalf("校准哈希不应改变 Revision: %d -> %d", revBefore, meta.Revision)
	}
	// 校准必须持久化，否则下一次启动又会误报外部修改。
	onDisk, err := loadNoteMeta(filepath.Join(nv.metaDir(), doc.ID+".json"))
	if err != nil || onDisk == nil {
		t.Fatalf("读取元数据失败: %v", err)
	}
	if onDisk.ContentSha256 != meta.ContentSha256 {
		t.Fatalf("校准后的哈希未写回元数据文件: %s", onDisk.ContentSha256)
	}
	// 用户在应用已载入的正文上继续编辑并保存，不得再被判外部修改。
	if _, err := nv.SaveContent(doc.ID, loaded.Markdown+" 用户补充"); err != nil {
		t.Fatalf("在已载入内容上保存被拒: %v", err)
	}
}

func TestLoadWorkspaceDuplicateActiveMetaDeterministic(t *testing.T) {
	v := newTestVault(t)
	doc := mustCreate(t, v, "重复身份")
	mustSave(t, v, doc.ID, "正式正文")

	// 同一路径再塞一条 ACTIVE 元数据：Revision 更大但磁盘哈希对不上，
	// 代表"外部拷贝/损坏残留的旧身份"。旧实现按 map 遍历随机覆盖。
	metaDir, _ := v.metaDirs()
	orig, err := loadNoteMeta(filepath.Join(metaDir, doc.ID+".json"))
	if err != nil || orig == nil {
		t.Fatalf("读取原始元数据失败: %v", err)
	}
	dup := *orig
	dup.NoteID = "00000000-0000-4000-8000-000000000001"
	dup.Revision = orig.Revision + 5
	dup.ContentSha256 = sha256Hex([]byte("别的内容"))
	if err := saveNoteMeta(metaDir, &dup); err != nil {
		t.Fatal(err)
	}

	ids := map[string]bool{}
	for i := 0; i < 8; i++ {
		nv := reloadVaultForTest(t, v.root)
		n := nv.notes[doc.ID]
		if n == nil {
			t.Fatalf("第 %d 次重启：磁盘哈希命中的元数据应被选中，实际选中了别的身份", i+1)
		}
		ids[n.meta.NoteID] = true
	}
	if len(ids) != 1 {
		t.Fatalf("同一路径在重启间身份不稳定: %v", ids)
	}
	// 未被认领的孤儿元数据保留在磁盘上，但不会再套用到新文件。
	if _, err := os.Stat(filepath.Join(metaDir, dup.NoteID+".json")); err != nil {
		t.Fatalf("孤儿元数据不应被删除: %v", err)
	}
	writeFile(t, filepath.Join(v.notesDir(), "新文件.md"), "全新内容")
	nv := reloadVaultForTest(t, v.root)
	fresh := findNoteByPath(nv, "新文件.md")
	if fresh == nil {
		t.Fatal("新文件应被恢复为笔记")
	}
	if fresh.meta.NoteID == dup.NoteID || fresh.meta.MetadataOrigin != originRecovered {
		t.Fatalf("孤儿元数据和不应套用到新文件: %+v", *fresh.meta)
	}
}

func TestLoadWorkspaceOrphanMetaDoesNotStealIdentity(t *testing.T) {
	v := newTestVault(t)
	a := mustCreate(t, v, "A")
	mustSave(t, v, a.ID, "v1 内容")

	// 用户在资源管理器里把 A.md 改名成 B.md，随后又从备份拷回一个不同的 A.md。
	if err := os.Rename(filepath.Join(v.notesDir(), "A.md"), filepath.Join(v.notesDir(), "B.md")); err != nil {
		t.Fatal(err)
	}
	writeFile(t, filepath.Join(v.notesDir(), "A.md"), "从备份恢复的另一份内容")

	nv := reloadVaultForTest(t, v.root)
	restored := findNoteByPath(nv, "A.md")
	if restored == nil {
		t.Fatal("A.md 应被载入")
	}
	if restored.meta.ContentSha256 != sha256Hex([]byte("从备份恢复的另一份内容")) {
		t.Fatalf("A.md 的哈希应按磁盘内容校准: %s", restored.meta.ContentSha256)
	}
	if _, err := nv.SaveContent(restored.meta.NoteID, "用户此时正常编辑并保存"); err != nil {
		t.Fatalf("应用已载入磁盘内容，保存却被判为外部修改: %v", err)
	}
	// B.md 没有对应元数据：补建 RECOVERED 身份，而不是抢 A 的旧身份。
	b := findNoteByPath(nv, "B.md")
	if b == nil || b.meta.NoteID != a.ID && b.meta.MetadataOrigin != originRecovered {
		t.Fatalf("B.md 应补建 RECOVERED 元数据: %+v", b)
	}
}

// ---- 缺陷 7/8：回收站孤儿文件可见、可恢复；重建报告清理数量 -----------------------

func TestTrashFileWithoutMetaVisibleAndRestorable(t *testing.T) {
	v := newTestVault(t)
	doc := mustCreate(t, v, "孤儿回收站")
	if _, err := v.TrashNote(doc.ID); err != nil {
		t.Fatal(err)
	}
	metaDir, _ := v.metaDirs()
	if err := os.Remove(filepath.Join(metaDir, doc.ID+".json")); err != nil {
		t.Fatal(err)
	}

	nv := reloadVaultForTest(t, v.root)
	list := nv.ListTrash()
	if len(list) != 1 || list[0].ID != doc.ID {
		t.Fatalf("回收站文件缺元数据时应补建并可见: %+v", list)
	}
	got, err := nv.RestoreFromTrash(doc.ID)
	if err != nil {
		t.Fatalf("补建元数据后应可恢复: %v", err)
	}
	if got.ID != doc.ID || got.Trashed {
		t.Fatalf("恢复结果错误: %+v", got)
	}
	if len(nv.ListTrash()) != 0 {
		t.Fatal("恢复后不应再出现在回收站列表")
	}
	if _, err := os.Stat(filepath.Join(nv.notesDir(), filepath.FromSlash(got.RelativePath))); err != nil {
		t.Fatalf("恢复后正文应在 notes/ 下: %v", err)
	}
}

func TestTrashFileWithoutMetaDeletable(t *testing.T) {
	v := newTestVault(t)
	doc := mustCreate(t, v, "孤儿可删除")
	if _, err := v.TrashNote(doc.ID); err != nil {
		t.Fatal(err)
	}
	metaDir, _ := v.metaDirs()
	if err := os.Remove(filepath.Join(metaDir, doc.ID+".json")); err != nil {
		t.Fatal(err)
	}
	nv := reloadVaultForTest(t, v.root)
	if _, err := nv.DeleteForever(doc.ID); err != nil {
		t.Fatalf("补建元数据后应可彻底删除: %v", err)
	}
	if _, err := os.Stat(filepath.Join(nv.trashDir(), doc.ID+".md")); !os.IsNotExist(err) {
		t.Fatal("彻底删除后回收站文件应消失")
	}
	if _, err := os.Stat(filepath.Join(metaDir, doc.ID+".json")); !os.IsNotExist(err) {
		t.Fatal("彻底删除后元数据应消失")
	}
}

func TestRebuildIndexReportsPurgedTrash(t *testing.T) {
	v := newTestVault(t)
	doc := mustCreate(t, v, "过期条目")
	if _, err := v.TrashNote(doc.ID); err != nil {
		t.Fatal(err)
	}
	// 把过期时间写回磁盘（模拟真实重启后的清理场景）。
	m := v.trashed[doc.ID]
	m.TrashedAtUtc = nowMs() - trashRetentionMs - 1000
	metaDir, _ := v.metaDirs()
	if err := saveNoteMeta(metaDir, m); err != nil {
		t.Fatal(err)
	}
	res, err := v.RebuildIndex()
	if err != nil {
		t.Fatal(err)
	}
	if _, ok := v.trashed[doc.ID]; ok {
		t.Fatal("过期回收站条目应被清理")
	}
	if res.PurgedTrash != 1 {
		t.Fatalf("本次清理了 1 条，PurgedTrash 应为 1，实际 %+v", res)
	}
	if _, err := os.Stat(filepath.Join(v.trashDir(), doc.ID+".md")); !os.IsNotExist(err) {
		t.Fatal("过期条目应被物理删除")
	}
}

// ---- 缺陷 10：导出包基线字段、附件递归与真实写包逻辑 -----------------------------

func TestWriteVaultZipBaselineManifestAndNestedAttachments(t *testing.T) {
	v := newTestVault(t)
	note := mustCreate(t, v, "网络协议")
	networkBody := "#计算机网络 正文，参见 [[其他]]。"
	mustSave(t, v, note.ID, networkBody)
	other := mustCreate(t, v, "其他")
	mustSave(t, v, other.ID, "被引用的正文")

	attDir := v.attachmentsDir()
	writeFile(t, filepath.Join(attDir, "img_20260928_001.png"), "PNG-TOP")
	if err := os.MkdirAll(filepath.Join(attDir, "sub"), 0o755); err != nil {
		t.Fatal(err)
	}
	writeFile(t, filepath.Join(attDir, "sub", "architecture_diag.svg"), "<svg/>")

	var buf bytes.Buffer
	if err := v.writeVaultZip(&buf); err != nil {
		t.Fatalf("writeVaultZip: %v", err)
	}
	r, err := zip.NewReader(bytes.NewReader(buf.Bytes()), int64(buf.Len()))
	if err != nil {
		t.Fatalf("生成的 ZIP 不可读: %v", err)
	}
	names := map[string]bool{}
	for _, f := range r.File {
		names[f.Name] = true
	}
	for _, want := range []string{
		"notes/网络协议.md",
		"notes/其他.md",
		"attachments/img_20260928_001.png",
		"attachments/sub/architecture_diag.svg", // 子目录附件不能丢
		"manifest.json",
	} {
		if !names[want] {
			t.Errorf("ZIP 缺少条目 %q，实际=%v", want, sortedKeys(names))
		}
	}

	var manifest ExportManifest
	if err := json.Unmarshal(entryBytes(t, r, "manifest.json"), &manifest); err != nil {
		t.Fatalf("manifest.json 解析失败: %v", err)
	}
	if manifest.Generator != "JustMD v1.1" {
		t.Errorf("generator 应为 \"JustMD v1.1\"（基线 4.7），实际 %q", manifest.Generator)
	}
	if manifest.SchemaVersion != "1.0" {
		t.Errorf("schemaVersion 应为 \"1.0\"（基线 4.7），实际 %q", manifest.SchemaVersion)
	}
	if manifest.TotalNotes != 2 || len(manifest.NotesIndex) != 2 {
		t.Errorf("笔记统计错误: %+v", manifest)
	}
	if manifest.TotalAttachments != 2 {
		t.Errorf("附件统计应含子目录附件（2 个），实际 %d", manifest.TotalAttachments)
	}
	if _, err := time.Parse("2006-01-02T15:04:05Z07:00", manifest.ExportTimestamp); err != nil {
		t.Errorf("exportTimestamp 不是 ISO-8601: %q: %v", manifest.ExportTimestamp, err)
	}

	var entry *ManifestEntry
	for i := range manifest.NotesIndex {
		if manifest.NotesIndex[i].RelativePath == "notes/网络协议.md" {
			entry = &manifest.NotesIndex[i]
		}
	}
	if entry == nil {
		t.Fatal("manifest 缺少笔记索引项")
	}
	if entry.SHA256 != sha256Hex([]byte(networkBody)) {
		t.Errorf("manifest 校验哈希与正文不一致: %s", entry.SHA256)
	}
	if !containsString(entry.Tags, "计算机网络") {
		t.Errorf("manifest 应包含标签: %+v", entry.Tags)
	}
	if !containsString(entry.OutboundLinks, "其他") {
		t.Errorf("manifest 应包含出链: %+v", entry.OutboundLinks)
	}
	if entry.MetadataOrigin != originOriginal {
		t.Errorf("manifest 缺少正确的 metadataOrigin: %+v", entry)
	}

	// 包内正文与磁盘一致（UTF-8 原样）。
	if body := string(entryBytes(t, r, "notes/网络协议.md")); body != networkBody {
		t.Errorf("ZIP 内笔记正文错误: %q", body)
	}
	if data := string(entryBytes(t, r, "attachments/sub/architecture_diag.svg")); data != "<svg/>" {
		t.Errorf("子目录附件内容错误: %q", data)
	}
}

// ---- 小工具 ---------------------------------------------------------------------

func writeFile(t *testing.T, path, content string) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
}

func entryBytes(t *testing.T, r *zip.Reader, name string) []byte {
	t.Helper()
	for _, f := range r.File {
		if f.Name != name {
			continue
		}
		rc, err := f.Open()
		if err != nil {
			t.Fatalf("打开 ZIP 条目 %q: %v", name, err)
		}
		defer rc.Close()
		data, err := io.ReadAll(rc)
		if err != nil {
			t.Fatalf("读取 ZIP 条目 %q: %v", name, err)
		}
		return data
	}
	t.Fatalf("ZIP 缺少条目 %q", name)
	return nil
}

func sortedKeys(m map[string]bool) []string {
	out := make([]string, 0, len(m))
	for k := range m {
		out = append(out, k)
	}
	sort.Strings(out)
	return out
}

func containsString(list []string, want string) bool {
	for _, s := range list {
		if s == want {
			return true
		}
	}
	return false
}
