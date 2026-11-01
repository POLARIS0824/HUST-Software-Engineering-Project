package main

// 本文件是重命名级联与保存 pending 日志的回归测试（task-1 / 评审修复）：
//   - 写 pending 失败时不得留下被改坏的 [[旧标题]]（审计缺陷 1）；
//   - 来源笔记被外部修改时不得被内存旧正文静默覆盖（审计缺陷 2）；
//   - 级联成功后必须通过 RenameReport 报告受影响的笔记 ID，
//     且元数据哈希与磁盘同步、链接仍解析（审计缺陷 3、评审 #7）；
//   - saveContent 失败路径的 pending 清理/保留策略。
// 注意：现有的 newTestVault / mustCreate / mustSave 复用 vault_test.go 中的定义。

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// renameFixContainsID 判断 ID 是否出现在报告中（顺序不应影响断言）。
func renameFixContainsID(ids []string, want string) bool {
	for _, id := range ids {
		if id == want {
			return true
		}
	}
	return false
}

// renameFixBlockPendingDir 把 .meta/pending 目录换成同名文件，令 writePending 必然失败。
func renameFixBlockPendingDir(t *testing.T, v *Vault) {
	t.Helper()
	pendingDir := filepath.Join(v.root, filepath.FromSlash(dirMetaPending))
	if err := os.RemoveAll(pendingDir); err != nil {
		t.Fatalf("移除 pending 目录: %v", err)
	}
	if err := os.WriteFile(pendingDir, []byte("blocked"), 0o644); err != nil {
		t.Fatalf("用文件占位 pending 目录: %v", err)
	}
}

// renameFixReadPending 读取 pending 目录下剩余的操作记录。
func renameFixReadPending(t *testing.T, v *Vault) []PendingOp {
	t.Helper()
	_, pendingDir := v.metaDirs()
	entries, err := os.ReadDir(pendingDir)
	if err != nil {
		t.Fatalf("读取 pending 目录: %v", err)
	}
	ops := []PendingOp{}
	for _, e := range entries {
		if e.IsDir() || !strings.HasSuffix(e.Name(), ".json") {
			continue
		}
		data, err := os.ReadFile(filepath.Join(pendingDir, e.Name()))
		if err != nil {
			t.Fatalf("读取 pending 记录: %v", err)
		}
		var op PendingOp
		if err := json.Unmarshal(data, &op); err != nil {
			t.Fatalf("解析 pending 记录: %v", err)
		}
		ops = append(ops, op)
	}
	return ops
}

// renameFixMakeMetaUnwritable 用同名目录占住 .meta/notes/{id}.json，
// 让 saveNoteMeta 的原子替换在 Windows 上必然失败（不能覆盖目录）。
func renameFixMakeMetaUnwritable(t *testing.T, v *Vault, noteID string) {
	t.Helper()
	path := filepath.Join(v.metaDir(), noteID+".json")
	if err := os.Remove(path); err != nil {
		t.Fatalf("移除元数据文件: %v", err)
	}
	if err := os.Mkdir(path, 0o755); err != nil {
		t.Fatalf("用目录占位元数据文件: %v", err)
	}
}

// ---- a) 写 pending 失败：必须提前返回，其它笔记正文与链接状态不受影响 ----------

func TestVaultRenamePendingFailureRollsBackLinks(t *testing.T) {
	v := newTestVault(t)
	target := mustCreate(t, v, "旧标题")
	mustSave(t, v, target.ID, "目标正文")
	src := mustCreate(t, v, "来源")
	mustSave(t, v, src.ID, "参见 [[旧标题]]。")

	renameFixBlockPendingDir(t, v)

	_, report, err := v.RenameNoteReported(target.ID, "新标题")
	if err == nil {
		t.Fatal("pending 写失败时重命名必须返回错误")
	}
	if len(report.Affected) != 0 || len(report.Skipped) != 0 {
		t.Fatalf("失败时不应报告级联结果: %+v", report)
	}
	// 旧实现会先把其它笔记的 [[旧标题]] 改成新标题再失败 → 链接 UNRESOLVED。
	if body := v.readBody(v.notes[src.ID].meta); body != "参见 [[旧标题]]。" {
		t.Fatalf("pending 失败后来源正文被改写: %q", body)
	}
	links := v.GetNoteLinks(src.ID)
	if len(links) != 1 || links[0].State != string(linkResolved) || links[0].ResolvedNoteID != target.ID {
		t.Fatalf("pending 失败后链接状态被破坏: %+v", links)
	}
	doc, err := v.OpenNote(target.ID)
	if err != nil {
		t.Fatal(err)
	}
	if doc.Title != "旧标题" || doc.RelativePath != "旧标题.md" {
		t.Fatalf("pending 失败后目标不应被改名: title=%q rel=%q", doc.Title, doc.RelativePath)
	}
	if _, err := os.Stat(filepath.Join(v.notesDir(), "新标题.md")); !os.IsNotExist(err) {
		t.Fatal("pending 失败后不应出现新文件名的笔记")
	}
}

// ---- b) 来源笔记被外部修改：保留磁盘原文，并计入 Skipped ----------------------

func TestVaultRenameSkipsExternallyModifiedSource(t *testing.T) {
	v := newTestVault(t)
	target := mustCreate(t, v, "目标")
	mustSave(t, v, target.ID, "目标正文")
	src := mustCreate(t, v, "来源")
	mustSave(t, v, src.ID, "参见 [[目标]]。")

	// 应用运行期间，外部程序改写了来源笔记；内存索引仍是旧正文。
	srcAbs := filepath.Join(v.notesDir(), v.notes[src.ID].meta.RelativePath)
	externallyEdited := "参见 [[目标]]。\n\n外部补充的一整段重要内容。"
	if err := os.WriteFile(srcAbs, []byte(externallyEdited), 0o644); err != nil {
		t.Fatal(err)
	}

	doc, report, err := v.RenameNoteReported(target.ID, "目标改名")
	if err != nil {
		t.Fatal(err)
	}
	if doc.RelativePath != "目标改名.md" {
		t.Fatalf("重命名本身应成功: %q", doc.RelativePath)
	}
	// 旧实现用内存旧正文整篇回写，把用户新加的这一段静默覆盖掉。
	data, err := os.ReadFile(srcAbs)
	if err != nil {
		t.Fatal(err)
	}
	if string(data) != externallyEdited {
		t.Fatalf("外部修改被静默覆盖: %q", data)
	}
	if !renameFixContainsID(report.Skipped, src.ID) {
		t.Fatalf("外部修改过的来源应计入 Skipped: %+v", report)
	}
	if renameFixContainsID(report.Affected, src.ID) {
		t.Fatalf("跳过的来源不应出现在 Affected: %+v", report)
	}
}

// ---- c) 正常级联：Affected 含来源 ID，正文更新、链接仍 RESOLVED、哈希同步 -------

func TestVaultRenameReportedCascadeReport(t *testing.T) {
	v := newTestVault(t)
	target := mustCreate(t, v, "旧标题")
	mustSave(t, v, target.ID, "目标正文")
	src := mustCreate(t, v, "来源")
	mustSave(t, v, src.ID, "见 [[旧标题]] 与 [[旧标题|别名]]。")
	srcRevBefore := v.notes[src.ID].meta.Revision

	doc, report, err := v.RenameNoteReported(target.ID, "新标题")
	if err != nil {
		t.Fatal(err)
	}
	if doc.RelativePath != "新标题.md" {
		t.Fatalf("重命名失败: %q", doc.RelativePath)
	}
	if !renameFixContainsID(report.Affected, src.ID) {
		t.Fatalf("Affected 应包含来源笔记: %+v", report)
	}
	if len(report.Skipped) != 0 {
		t.Fatalf("正常级联不应有 Skipped: %+v", report)
	}

	body := v.readBody(v.notes[src.ID].meta)
	if !strings.Contains(body, "[[新标题]]") || !strings.Contains(body, "[[新标题|别名]]") {
		t.Fatalf("级联正文未更新: %q", body)
	}
	if strings.Contains(body, "旧标题") {
		t.Fatalf("旧引用残留: %q", body)
	}

	links := v.GetNoteLinks(src.ID)
	if len(links) != 2 {
		t.Fatalf("来源应有 2 条出链: %+v", links)
	}
	for _, l := range links {
		if l.State != string(linkResolved) || l.ResolvedNoteID != target.ID {
			t.Fatalf("级联后链接应仍解析到目标: %+v", links)
		}
	}
	// GetBacklinks 按链接条目返回：来源笔记里两条出链都应仍指向目标。
	backlinks := v.GetBacklinks(target.ID)
	if len(backlinks) != 2 {
		t.Fatalf("级联后两条出链的反链都应仍在: %+v", backlinks)
	}
	for _, item := range backlinks {
		if item.SourceNoteID != src.ID || !strings.Contains(item.Snippet, "[[新标题]]") {
			t.Fatalf("反链内容错误: %+v", item)
		}
	}

	// 元数据必须与磁盘同步，否则下一次保存会被误判为外部修改（评审 #7）。
	srcAbs := filepath.Join(v.notesDir(), v.notes[src.ID].meta.RelativePath)
	disk, err := os.ReadFile(srcAbs)
	if err != nil {
		t.Fatal(err)
	}
	if got := sha256Hex(normalizeContent(disk)); got != v.notes[src.ID].meta.ContentSha256 {
		t.Fatalf("级联后 meta 哈希与磁盘不一致: %s != %s", got, v.notes[src.ID].meta.ContentSha256)
	}
	if got := v.notes[src.ID].meta.Revision; got != srcRevBefore+1 {
		t.Fatalf("级联改写应自增修订号: %d -> %d", srcRevBefore, got)
	}
	if _, err := v.SaveContent(src.ID, "见 [[新标题]]，再补一句。"); err != nil {
		t.Fatalf("级联后继续保存不应误报外部修改: %v", err)
	}
}

// ---- d) 后续步骤（保存 meta）失败：回滚已级联正文，不得留下破损链接 -------------

func TestVaultRenameMetaFailureRollsBackCascade(t *testing.T) {
	v := newTestVault(t)
	target := mustCreate(t, v, "旧标题")
	mustSave(t, v, target.ID, "目标正文")
	src := mustCreate(t, v, "来源")
	mustSave(t, v, src.ID, "参见 [[旧标题]]。")
	srcMetaBefore := *v.notes[src.ID].meta

	// 让目标笔记的元数据提交失败：os.Rename 之后、saveNoteMeta 时报错。
	renameFixMakeMetaUnwritable(t, v, target.ID)

	if _, _, err := v.RenameNoteReported(target.ID, "新标题"); err == nil {
		t.Fatal("元数据提交失败时重命名必须返回错误")
	}

	// 来源笔记的正文与元数据都要回到原样，链接仍解析到目标。
	if body := v.readBody(v.notes[src.ID].meta); body != "参见 [[旧标题]]。" {
		t.Fatalf("回滚后来源正文应保持原样: %q", body)
	}
	if got := *v.notes[src.ID].meta; got.Revision != srcMetaBefore.Revision || got.ContentSha256 != srcMetaBefore.ContentSha256 {
		t.Fatalf("回滚后来源元数据应恢复: %+v != %+v", got, srcMetaBefore)
	}
	links := v.GetNoteLinks(src.ID)
	if len(links) != 1 || links[0].State != string(linkResolved) || links[0].ResolvedNoteID != target.ID {
		t.Fatalf("回滚后链接应仍解析到目标: %+v", links)
	}
	// 目标文件必须改回旧名，不能留下"元数据仍是旧路径、文件已是新名"的错位。
	if _, err := os.Stat(filepath.Join(v.notesDir(), "旧标题.md")); err != nil {
		t.Fatalf("回滚后目标文件应回到旧路径: %v", err)
	}
	if _, err := os.Stat(filepath.Join(v.notesDir(), "新标题.md")); !os.IsNotExist(err) {
		t.Fatal("回滚后不应残留新路径文件")
	}
	if got := v.notes[target.ID].meta.RelativePath; got != "旧标题.md" {
		t.Fatalf("回滚后内存元数据路径应恢复: %q", got)
	}
	// pending 不应残留：这次重命名在文件系统层面已经整体回滚。
	if ops := renameFixReadPending(t, v); len(ops) != 0 {
		t.Fatalf("回滚后不应残留 pending 记录: %+v", ops)
	}
}

// ---- e) saveContent：正文写失败要清 pending；元数据写失败要留 pending ----------

func TestVaultSaveContentAtomicFailureClearsPending(t *testing.T) {
	v := newTestVault(t)
	doc := mustCreate(t, v, "只读正文")
	mustSave(t, v, doc.ID, "第一版")

	abs := filepath.Join(v.notesDir(), doc.RelativePath)
	// 只读文件：原子替换在 Windows 上会失败（不能覆盖只读目标）。
	if err := os.Chmod(abs, 0o444); err != nil {
		t.Fatal(err)
	}
	defer os.Chmod(abs, 0o666)

	if _, err := v.SaveContent(doc.ID, "第二版"); err == nil {
		t.Fatal("正文不可写时应返回错误")
	}
	// 正文一个字节都没变，pending 意图必须被清理，不能污染恢复日志。
	if ops := renameFixReadPending(t, v); len(ops) != 0 {
		t.Fatalf("正文写失败后不应残留 pending: %+v", ops)
	}
	if data, _ := os.ReadFile(abs); string(data) != "第一版" {
		t.Fatalf("正文写失败后磁盘内容应保持原样: %q", data)
	}
}

func TestVaultSaveContentMetaFailureKeepsPending(t *testing.T) {
	v := newTestVault(t)
	doc := mustCreate(t, v, "元数据不可写")
	mustSave(t, v, doc.ID, "第一版")

	// .meta/notes/{id}.json 变成目录 → 正文已落盘后 saveNoteMeta 必然失败。
	renameFixMakeMetaUnwritable(t, v, doc.ID)

	if _, err := v.SaveContent(doc.ID, "第二版"); err == nil {
		t.Fatal("元数据不可写时应返回错误")
	}
	// 正文已落盘、元数据未提交：必须保留 pending 供下次启动对账补完（设计 6.2）。
	ops := renameFixReadPending(t, v)
	if len(ops) != 1 || ops[0].Kind != "save" || ops[0].NoteID != doc.ID {
		t.Fatalf("元数据写失败后应保留 save pending: %+v", ops)
	}
	abs := filepath.Join(v.notesDir(), doc.RelativePath)
	if data, _ := os.ReadFile(abs); string(data) != "第二版" {
		t.Fatalf("正文应已落盘: %q", data)
	}
	if v.notes[doc.ID].meta.ContentSha256 != sha256Hex([]byte("第二版")) {
		t.Fatal("内存元数据应按新正文推进（对账按 NewSha 幂等补完）")
	}
}
