package main

// 本文件是「分类（notes/ 下的真实目录）」的领域测试（task-3）：
//   - 分类树的层级、空目录、直接数/总数与排序；
//   - 新建/删除分类的清洗、幂等与路径安全；
//   - 笔记移动：纯搬文件（标题/正文/标签/双链不变）、移回根、同名冲突、
//     幂等移动、NoteSummary.Category 同步；
//   - 崩溃对账：pending kind=move 在「文件已搬走、元数据未提交」后可补完，
//     且不会产生重复笔记/新身份。
//
// 复用 vault_test.go 的 newTestVault / mustCreate / mustSave。

import (
	"encoding/json"
	"os"
	"path/filepath"
	goruntime "runtime"
	"strings"
	"testing"
)

// categoryFind 在分类树里按 Path 递归查找节点。
func categoryFind(nodes []CategoryNode, path string) (CategoryNode, bool) {
	for _, node := range nodes {
		if node.Path == path {
			return node, true
		}
		if found, ok := categoryFind(node.Children, path); ok {
			return found, true
		}
	}
	return CategoryNode{}, false
}

// categoryPendingOps 读取 pending 目录下剩余的操作记录。
func categoryPendingOps(t *testing.T, v *Vault) []PendingOp {
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

// categoryFileExists 判断路径是否存在（测试断言用）。
func categoryFileExists(t *testing.T, path string) bool {
	t.Helper()
	if _, err := os.Stat(path); err == nil {
		return true
	} else if os.IsNotExist(err) {
		return false
	} else {
		t.Fatalf("判断路径是否存在: %v", err)
		return false
	}
}

// ---- 分类树 ---------------------------------------------------------------

func TestListCategoriesTreeEmptyDirsAndCounts(t *testing.T) {
	v := newTestVault(t)
	mustCreate(t, v, "根目录笔记") // 未分类，用于 UnfiledNoteCount

	direct := mustCreate(t, v, "分类直接笔记")
	one := mustCreate(t, v, "第一章笔记")
	beta := mustCreate(t, v, "Beta笔记")

	for _, cat := range []string{"alpha", "alpha/one", "alpha/two", "beta", "beta/one", "empty"} {
		if _, err := v.CreateCategory(cat); err != nil {
			t.Fatalf("CreateCategory(%q): %v", cat, err)
		}
	}
	if _, err := v.MoveNoteToCategory(direct.ID, "alpha"); err != nil {
		t.Fatal(err)
	}
	if _, err := v.MoveNoteToCategory(one.ID, "alpha/one"); err != nil {
		t.Fatal(err)
	}
	if _, err := v.MoveNoteToCategory(beta.ID, "beta/one"); err != nil {
		t.Fatal(err)
	}

	categories := v.ListCategories()
	if len(categories) != 3 {
		t.Fatalf("应有 3 个根分类: %+v", categories)
	}
	wantRoots := []string{"alpha", "beta", "empty"}
	for i, want := range wantRoots {
		if categories[i].Path != want {
			t.Fatalf("根分类应按 Path 排序: 第 %d 个是 %q，期望 %q（%+v）", i, categories[i].Path, want, categories)
		}
	}
	// 排序是严格递增的（相邻 Path 不能相等）。
	for i := 1; i < len(categories); i++ {
		if categories[i-1].Path >= categories[i].Path {
			t.Fatalf("分类未按 Path 升序: %+v", categories)
		}
	}

	alpha, ok := categoryFind(categories, "alpha")
	if !ok {
		t.Fatalf("缺少 alpha 分类: %+v", categories)
	}
	if alpha.Count != 1 || alpha.Total != 2 {
		t.Fatalf("alpha 直接数应为 1、总数应为 2: %+v", alpha)
	}
	if len(alpha.Children) != 2 || alpha.Children[0].Path != "alpha/one" || alpha.Children[1].Path != "alpha/two" {
		t.Fatalf("alpha 子分类应按 Path 排序（含空目录 alpha/two）: %+v", alpha.Children)
	}

	oneNode, ok := categoryFind(categories, "alpha/one")
	if !ok || oneNode.Count != 1 || oneNode.Total != 1 {
		t.Fatalf("alpha/one 计数错误: %+v", oneNode)
	}
	twoNode, ok := categoryFind(categories, "alpha/two")
	if !ok {
		t.Fatal("空分类 alpha/two 应出现在树里")
	}
	if twoNode.Count != 0 || twoNode.Total != 0 || twoNode.Children == nil {
		t.Fatalf("空分类计数应为 0 且 Children 非 nil: %+v", twoNode)
	}
	// Children 必须序列化为 []（界面不需要判 null）。
	data, err := json.Marshal(twoNode)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(data), `"children":[]`) {
		t.Fatalf("空分类的 children 应序列化为 []: %s", data)
	}

	betaNode, ok := categoryFind(categories, "beta")
	if !ok || betaNode.Count != 0 || betaNode.Total != 1 {
		t.Fatalf("beta 直接数应为 0、总数应为 1: %+v", betaNode)
	}
	emptyNode, ok := categoryFind(categories, "empty")
	if !ok || emptyNode.Count != 0 || emptyNode.Total != 0 || emptyNode.Children == nil {
		t.Fatalf("空的根分类应保留在树里: %+v", emptyNode)
	}

	// 未分类 = notes/ 根目录下的活跃笔记。
	if got := v.UnfiledNoteCount(); got != 1 {
		t.Fatalf("UnfiledNoteCount 应为 1: %d", got)
	}

	// 列表摘要带分类（界面据此分组/过滤）。
	var alphaSummary *NoteSummary
	for _, item := range v.ListNotes("title_asc") {
		summary := item
		if summary.ID == direct.ID {
			alphaSummary = &summary
		}
	}
	if alphaSummary == nil || alphaSummary.Category != "alpha" {
		t.Fatalf("NoteSummary.Category 应为 alpha: %+v", alphaSummary)
	}
	for _, item := range v.ListNotes("title_asc") {
		if item.ID == one.ID && item.Category != "alpha/one" {
			t.Fatalf("多级分类路径错误: %+v", item)
		}
	}
}

// ---- 新建分类 -------------------------------------------------------------

func TestCreateCategorySanitizesAndIsIdempotent(t *testing.T) {
	v := newTestVault(t)

	got, err := v.CreateCategory(" 学习:计划 / 第一章 ")
	if err != nil {
		t.Fatalf("CreateCategory: %v", err)
	}
	if got != "学习_计划/第一章" {
		t.Fatalf("分类名应逐级清洗并去空格: %q", got)
	}
	if !categoryFileExists(t, filepath.Join(v.notesDir(), filepath.FromSlash(got))) {
		t.Fatal("分类目录未创建")
	}

	// 幂等：重复创建同一路径视为成功，路径规范一致。
	again, err := v.CreateCategory("学习_计划/第一章")
	if err != nil || again != got {
		t.Fatalf("重复创建应幂等: path=%q err=%v", again, err)
	}

	// 非法输入一律拒绝，且不产生任何新目录。
	badPaths := []string{"", "   ", "..", "../逃逸", "a//b", "a/", "/绝对"}
	for _, bad := range badPaths {
		if _, err := v.CreateCategory(bad); err != ErrPathOutside {
			t.Fatalf("CreateCategory(%q) 应返回 ErrPathOutside: %v", bad, err)
		}
	}
	// 绝对路径（含盘符的 t.TempDir 路径）同样拒绝。
	if _, err := v.CreateCategory(filepath.Join(t.TempDir(), "cat")); err != ErrPathOutside {
		t.Fatalf("绝对路径应返回 ErrPathOutside: %v", err)
	}
	if cats := v.ListCategories(); len(cats) != 1 || cats[0].Path != "学习_计划" {
		t.Fatalf("非法输入不应创建目录: %+v", cats)
	}
}

// ---- 笔记移动 -------------------------------------------------------------

func TestMoveNoteToCategoryPreservesNoteAndLinks(t *testing.T) {
	v := newTestVault(t)
	target := mustCreate(t, v, "目标")
	mustSave(t, v, target.ID, "目标正文")
	src := mustCreate(t, v, "来源")
	const body = "#标签 参见 [[目标]] 与 [[不存在]]。"
	mustSave(t, v, src.ID, body)
	before := *v.notes[src.ID].meta

	doc, err := v.MoveNoteToCategory(src.ID, "学习/第一章")
	if err != nil {
		t.Fatalf("MoveNoteToCategory: %v", err)
	}
	if doc.RelativePath != "学习/第一章/来源.md" {
		t.Fatalf("移动后路径错误: %q", doc.RelativePath)
	}
	// 纯搬文件：标题、正文、标签、链接一个字都不变。
	if doc.Title != "来源" || doc.Markdown != body {
		t.Fatalf("移动不应改标题/正文: title=%q markdown=%q", doc.Title, doc.Markdown)
	}
	if len(doc.Tags) != 1 || doc.Tags[0].Name != "标签" {
		t.Fatalf("标签应保持不变: %+v", doc.Tags)
	}
	if len(doc.Links) != 2 {
		t.Fatalf("双链数量应保持不变: %+v", doc.Links)
	}
	if doc.Links[0].State != string(linkResolved) || doc.Links[0].ResolvedNoteID != target.ID {
		t.Fatalf("移动后 [[目标]] 应仍解析: %+v", doc.Links[0])
	}
	if doc.Links[1].State != string(linkUnresolved) {
		t.Fatalf("[[不存在]] 仍应是未解析状态: %+v", doc.Links[1])
	}
	if backlinks := v.GetBacklinks(target.ID); len(backlinks) != 1 || backlinks[0].SourceNoteID != src.ID {
		t.Fatalf("移动不应丢反链: %+v", backlinks)
	}
	// 搬家不是编辑：修订号/更新时间不变。
	if doc.Revision != before.Revision || doc.UpdatedAtUtc != before.UpdatedAtUtc {
		t.Fatalf("移动不应推进修订号/更新时间: %+v -> rev=%d at=%d", before, doc.Revision, doc.UpdatedAtUtc)
	}
	// 磁盘：新路径有文件、旧路径没有；元数据路径同步。
	if !categoryFileExists(t, filepath.Join(v.notesDir(), "学习", "第一章", "来源.md")) {
		t.Fatal("新路径下应有正文文件")
	}
	if categoryFileExists(t, filepath.Join(v.notesDir(), "来源.md")) {
		t.Fatal("旧路径不应残留正文文件")
	}
	fresh, err := loadNoteMeta(filepath.Join(v.metaDir(), src.ID+".json"))
	if err != nil || fresh == nil || fresh.RelativePath != "学习/第一章/来源.md" {
		t.Fatalf("元数据路径未提交: %+v err=%v", fresh, err)
	}
	if got := v.NoteDir(src.ID); got != "学习/第一章" {
		t.Fatalf("NoteDir 应为新分类: %q", got)
	}
	for _, item := range v.ListNotes("title_asc") {
		if item.ID == src.ID && item.Category != "学习/第一章" {
			t.Fatalf("NoteSummary.Category 错误: %+v", item)
		}
	}
	if ops := categoryPendingOps(t, v); len(ops) != 0 {
		t.Fatalf("移动成功后不应残留 pending: %+v", ops)
	}

	// 移回根目录（未分类）。
	back, err := v.MoveNoteToCategory(src.ID, "")
	if err != nil {
		t.Fatalf("移回根目录: %v", err)
	}
	if back.RelativePath != "来源.md" || v.NoteDir(src.ID) != "" {
		t.Fatalf("移回根目录失败: %q category=%q", back.RelativePath, v.NoteDir(src.ID))
	}
	if links := v.GetNoteLinks(src.ID); len(links) != 2 || links[0].State != string(linkResolved) {
		t.Fatalf("移回根后链接应仍解析: %+v", links)
	}
	if got := v.UnfiledNoteCount(); got != 2 { // 目标 + 来源
		t.Fatalf("移回根后未分类数应为 2: %d", got)
	}
}

func TestMoveNoteToCategorySanitizesCategoryName(t *testing.T) {
	v := newTestVault(t)
	doc := mustCreate(t, v, "笔记")
	moved, err := v.MoveNoteToCategory(doc.ID, "工作:项目")
	if err != nil {
		t.Fatal(err)
	}
	if moved.RelativePath != "工作_项目/笔记.md" {
		t.Fatalf("分类名应清洗非法字符: %q", moved.RelativePath)
	}
	if cats := v.ListCategories(); len(cats) != 1 || cats[0].Path != "工作_项目" {
		t.Fatalf("分类应出现在树里: %+v", cats)
	}
}

func TestMoveNoteToCategoryIdempotent(t *testing.T) {
	v := newTestVault(t)
	doc := mustCreate(t, v, "Study")
	if _, err := v.MoveNoteToCategory(doc.ID, "StudyCat"); err != nil {
		t.Fatal(err)
	}
	before := *v.notes[doc.ID].meta

	// 同一分类重复移动：幂等返回当前文档，不搬动、不写 pending。
	again, err := v.MoveNoteToCategory(doc.ID, "StudyCat")
	if err != nil {
		t.Fatalf("重复移动应幂等: %v", err)
	}
	if again.RelativePath != "StudyCat/Study.md" {
		t.Fatalf("重复移动不应改变路径: %q", again.RelativePath)
	}

	// 仅大小写差异也视为同一路径（Windows 文件系统不区分大小写）。
	caseOnly, err := v.MoveNoteToCategory(doc.ID, "studycat")
	if err != nil {
		t.Fatalf("仅大小写差异应幂等: %v", err)
	}
	if caseOnly.RelativePath != "StudyCat/Study.md" {
		t.Fatalf("仅大小写差异不应搬动文件: %q", caseOnly.RelativePath)
	}
	entries, err := os.ReadDir(v.notesDir())
	if err != nil {
		t.Fatal(err)
	}
	if len(entries) != 1 || entries[0].Name() != "StudyCat" {
		t.Fatalf("不应产生第二个分类目录: %+v", entries)
	}
	if v.notes[doc.ID].meta.Revision != before.Revision || v.notes[doc.ID].meta.UpdatedAtUtc != before.UpdatedAtUtc {
		t.Fatal("幂等移动不应改元数据")
	}
	if ops := categoryPendingOps(t, v); len(ops) != 0 {
		t.Fatalf("幂等移动不应残留 pending: %+v", ops)
	}

	// 已在根目录时再移回根目录：同样幂等。
	rootDoc := mustCreate(t, v, "根笔记")
	rootAgain, err := v.MoveNoteToCategory(rootDoc.ID, "")
	if err != nil || rootAgain.RelativePath != "根笔记.md" {
		t.Fatalf("根目录幂等移动失败: path=%q err=%v", rootAgain.RelativePath, err)
	}
}

func TestMoveNoteToCategoryConflictDoesNotRename(t *testing.T) {
	v := newTestVault(t)
	first := mustCreate(t, v, "同名")
	if _, err := v.MoveNoteToCategory(first.ID, "归档"); err != nil {
		t.Fatal(err)
	}
	// 根目录已腾出"同名.md"，再建一篇同名笔记。
	second := mustCreate(t, v, "同名")
	if second.RelativePath != "同名.md" {
		t.Fatalf("前置条件失败: %q", second.RelativePath)
	}

	_, err := v.MoveNoteToCategory(second.ID, "归档")
	if err != ErrCategoryConflict {
		t.Fatalf("目标分类已有同名笔记时应返回 ErrCategoryConflict: %v", err)
	}
	// 文件必须原地不动，且不得静默改名为"同名 (1).md"（改名会破坏双链）。
	if !categoryFileExists(t, filepath.Join(v.notesDir(), "同名.md")) {
		t.Fatal("冲突时源文件不应被移动")
	}
	if categoryFileExists(t, filepath.Join(v.notesDir(), "归档", "同名 (1).md")) {
		t.Fatal("冲突时不得静默追加序号改名")
	}
	if got := v.NoteDir(second.ID); got != "" {
		t.Fatalf("冲突时索引不应变化: %q", got)
	}
	if ops := categoryPendingOps(t, v); len(ops) != 0 {
		t.Fatalf("冲突时不应写 pending: %+v", ops)
	}
}

func TestMoveNoteToCategoryCaseInsensitiveConflict(t *testing.T) {
	if goruntime.GOOS != "windows" {
		t.Skip("大小写不敏感是 Windows 文件系统行为")
	}
	v := newTestVault(t)
	upper := mustCreate(t, v, "Report")
	if _, err := v.MoveNoteToCategory(upper.ID, "Archive"); err != nil {
		t.Fatal(err)
	}
	lower := mustCreate(t, v, "report")
	// 目标目录名与文件名都只差大小写：Windows 上就是同一个目录/文件。
	if _, err := v.MoveNoteToCategory(lower.ID, "archive"); err != ErrCategoryConflict {
		t.Fatalf("大小写不敏感的同名冲突应被拦截: %v", err)
	}
	if !categoryFileExists(t, filepath.Join(v.notesDir(), "report.md")) {
		t.Fatal("冲突时源文件不应被移动")
	}
}

func TestMoveNoteToCategoryRejectsInvalidPath(t *testing.T) {
	v := newTestVault(t)
	doc := mustCreate(t, v, "安全笔记")

	badPaths := []string{"../逃逸", "..", "a//b", "a/", "/绝对"}
	// 绝对路径（t.TempDir 是绝对路径）也必须拒绝。
	badPaths = append(badPaths, filepath.Join(t.TempDir(), "cat"))
	for _, bad := range badPaths {
		if _, err := v.MoveNoteToCategory(doc.ID, bad); err != ErrTitleInvalid {
			t.Fatalf("MoveNoteToCategory(%q) 应返回 ErrTitleInvalid: %v", bad, err)
		}
	}
	// 非法路径不应搬动文件。
	if !categoryFileExists(t, filepath.Join(v.notesDir(), "安全笔记.md")) {
		t.Fatal("非法路径不应移动文件")
	}
	if got := v.NoteDir(doc.ID); got != "" {
		t.Fatalf("非法路径不应改变索引: %q", got)
	}
	if cats := v.ListCategories(); len(cats) != 0 {
		t.Fatalf("非法路径不应创建分类: %+v", cats)
	}
	if ops := categoryPendingOps(t, v); len(ops) != 0 {
		t.Fatalf("非法路径不应写 pending: %+v", ops)
	}

	if _, err := v.MoveNoteToCategory("不存在的ID", "某分类"); err != ErrNoteNotFound {
		t.Fatalf("未知笔记应返回 ErrNoteNotFound: %v", err)
	}
}

// 移动会改变 parentDir，而同名双链的"同目录优先"消歧依赖它：
// 换目录后原本靠同目录唯一的解析可能变成 AMBIGUOUS，反向亦然。
// 因此 MoveNoteToCategory 必须重算全部链接解析（不能只重建本篇索引）。
func TestMoveNoteToCategoryRecomputesSameDirAmbiguity(t *testing.T) {
	v := newTestVault(t)
	metaDir, _ := v.metaDirs()

	// 手工造一篇 sub/重复.md，与根目录的「重复」构成同名候选。
	subBody := "子目录里的重复"
	if err := atomicWriteFile(filepath.Join(v.notesDir(), "sub", "重复.md"), []byte(subBody)); err != nil {
		t.Fatal(err)
	}
	subMeta := &NoteMeta{
		SchemaVersion: metaSchemaV1, NoteID: newID(), RelativePath: "sub/重复.md",
		CreatedAtUtc: nowMs(), UpdatedAtUtc: nowMs(), Revision: 1,
		ContentSha256: sha256Hex([]byte(subBody)), State: stateActive, MetadataOrigin: originOriginal,
	}
	if err := saveNoteMeta(metaDir, subMeta); err != nil {
		t.Fatal(err)
	}
	v.indexNote(subMeta, subBody)
	rootDup := mustCreate(t, v, "重复")

	// 根目录里的引用者：两个同名候选里恰有一个同目录 → RESOLVED 到根目录那篇。
	referrer := mustCreate(t, v, "引用者")
	mustSave(t, v, referrer.ID, "见 [[重复]]。")
	if got := v.notes[referrer.ID].linkStates[0]; got != linkResolved || v.notes[referrer.ID].resolvedIDs[0] != rootDup.ID {
		t.Fatalf("前置条件失败：同目录候选应解析到根目录的重复: %v", got)
	}

	// 把根目录的「重复」移到别的分类：引用者不再有同目录候选 → AMBIGUOUS。
	if _, err := v.MoveNoteToCategory(rootDup.ID, "另一处"); err != nil {
		t.Fatal(err)
	}
	if got := v.notes[referrer.ID].linkStates[0]; got != linkAmbiguous {
		t.Fatalf("换目录后同目录候选消失，双链应变为 AMBIGUOUS: %v", got)
	}
	if backlinks := v.GetBacklinks(rootDup.ID); len(backlinks) != 0 {
		t.Fatalf("歧义后不应再有已解析反链: %+v", backlinks)
	}

	// 移回根目录：重新拿到同目录优先 → 恢复 RESOLVED。
	if _, err := v.MoveNoteToCategory(rootDup.ID, ""); err != nil {
		t.Fatal(err)
	}
	if got := v.notes[referrer.ID].linkStates[0]; got != linkResolved || v.notes[referrer.ID].resolvedIDs[0] != rootDup.ID {
		t.Fatalf("移回根目录后应恢复同目录解析: %v", got)
	}
}

// ---- 崩溃对账：pending kind=move ------------------------------------------

func TestReconcilePendingCompletesMoveAfterCrash(t *testing.T) {
	v := newTestVault(t)
	doc := mustCreate(t, v, "崩溃中的移动")
	mustSave(t, v, doc.ID, "正文")
	metaDir, pendingDir := v.metaDirs()

	// 手工模拟「文件已搬走、元数据未提交」：pending 已写、os.Rename 已发生。
	oldRel := v.notes[doc.ID].meta.RelativePath
	newRel := "归档/崩溃中的移动.md"
	if err := os.MkdirAll(filepath.Join(v.notesDir(), "归档"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.Rename(filepath.Join(v.notesDir(), oldRel), filepath.Join(v.notesDir(), filepath.FromSlash(newRel))); err != nil {
		t.Fatal(err)
	}
	op := &PendingOp{
		OperationID: "op-move-crash", NoteID: doc.ID, Kind: "move",
		OldRelPath: oldRel, NewRelPath: newRel, ExpectedRevision: v.notes[doc.ID].meta.Revision,
	}
	if err := writePending(pendingDir, op); err != nil {
		t.Fatal(err)
	}
	// 元数据仍是旧路径（崩溃时尚未提交）。
	stale, err := loadNoteMeta(filepath.Join(metaDir, doc.ID+".json"))
	if err != nil || stale == nil || stale.RelativePath != oldRel {
		t.Fatalf("前置条件失败: %+v err=%v", stale, err)
	}

	if err := v.loadWorkspace(); err != nil {
		t.Fatalf("loadWorkspace: %v", err)
	}
	n := v.notes[doc.ID]
	if n == nil {
		t.Fatal("对账后笔记应保持原 noteID（不应另建 RECOVERED 身份）")
	}
	if n.meta.RelativePath != newRel {
		t.Fatalf("对账应把元数据补到新路径: %q", n.meta.RelativePath)
	}
	if len(v.notes) != 1 {
		t.Fatalf("移动后不应产生重复笔记: %d", len(v.notes))
	}
	if !categoryFileExists(t, filepath.Join(v.notesDir(), filepath.FromSlash(newRel))) ||
		categoryFileExists(t, filepath.Join(v.notesDir(), oldRel)) {
		t.Fatal("对账后正文应只在新路径")
	}
	if got := v.NoteDir(doc.ID); got != "归档" {
		t.Fatalf("对账后 NoteDir 应为新分类: %q", got)
	}
	fresh, err := loadNoteMeta(filepath.Join(metaDir, doc.ID+".json"))
	if err != nil || fresh == nil || fresh.RelativePath != newRel {
		t.Fatalf("对账后磁盘元数据未更新: %+v err=%v", fresh, err)
	}
	if ops := categoryPendingOps(t, v); len(ops) != 0 {
		t.Fatalf("对账完成后应清理 pending: %+v", ops)
	}
}

func TestReconcilePendingMoveNotAppliedKeepsOldPath(t *testing.T) {
	v := newTestVault(t)
	doc := mustCreate(t, v, "未推进的移动")
	mustSave(t, v, doc.ID, "正文")
	_, pendingDir := v.metaDirs()
	oldRel := v.notes[doc.ID].meta.RelativePath

	// 只写了 pending，文件其实没搬走：对账必须保留旧路径（不能凭空改元数据）。
	op := &PendingOp{
		OperationID: "op-move-stale", NoteID: doc.ID, Kind: "move",
		OldRelPath: oldRel, NewRelPath: "归档/未推进的移动.md",
		ExpectedRevision: v.notes[doc.ID].meta.Revision,
	}
	if err := writePending(pendingDir, op); err != nil {
		t.Fatal(err)
	}
	if err := v.loadWorkspace(); err != nil {
		t.Fatal(err)
	}
	n := v.notes[doc.ID]
	if n == nil {
		t.Fatal("未推进的移动后笔记应仍是活跃状态")
	}
	if n.meta.RelativePath != oldRel {
		t.Fatalf("文件未搬走时元数据不应改路径: %q", n.meta.RelativePath)
	}
	if categoryFileExists(t, filepath.Join(v.notesDir(), "归档", "未推进的移动.md")) {
		t.Fatal("不应凭空在新路径生成文件")
	}
	if ops := categoryPendingOps(t, v); len(ops) != 0 {
		t.Fatalf("对账应清理过期的 pending: %+v", ops)
	}
}

// ---- 删除分类 -------------------------------------------------------------

func TestDeleteCategory(t *testing.T) {
	v := newTestVault(t)

	// 空分类可删；重复删除幂等。
	if _, err := v.CreateCategory("空分类"); err != nil {
		t.Fatal(err)
	}
	if err := v.DeleteCategory("空分类"); err != nil {
		t.Fatalf("删除空分类: %v", err)
	}
	if categoryFileExists(t, filepath.Join(v.notesDir(), "空分类")) {
		t.Fatal("分类目录应被删除")
	}
	if err := v.DeleteCategory("空分类"); err != nil {
		t.Fatalf("重复删除应幂等: %v", err)
	}

	// 有子分类 → ErrCategoryNotEmpty；先删子再删父。
	if _, err := v.CreateCategory("父/子"); err != nil {
		t.Fatal(err)
	}
	if err := v.DeleteCategory("父"); err != ErrCategoryNotEmpty {
		t.Fatalf("含子分类应返回 ErrCategoryNotEmpty: %v", err)
	}
	if err := v.DeleteCategory("父/子"); err != nil {
		t.Fatalf("删除空的子分类: %v", err)
	}
	if err := v.DeleteCategory("父"); err != nil {
		t.Fatalf("子分类删除后父分类应可删: %v", err)
	}

	// 有笔记 → ErrCategoryNotEmpty，且目录与笔记都保留。
	doc := mustCreate(t, v, "占用笔记")
	if _, err := v.MoveNoteToCategory(doc.ID, "含笔记"); err != nil {
		t.Fatal(err)
	}
	if err := v.DeleteCategory("含笔记"); err != ErrCategoryNotEmpty {
		t.Fatalf("含笔记应返回 ErrCategoryNotEmpty: %v", err)
	}
	if !categoryFileExists(t, filepath.Join(v.notesDir(), "含笔记", "占用笔记.md")) {
		t.Fatal("拒绝删除时不得动笔记文件")
	}

	// 非 Markdown 文件也算非空（绝不递归删用户文件）。
	if _, err := v.CreateCategory("有附件"); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(v.notesDir(), "有附件", "x.png"), []byte("x"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := v.DeleteCategory("有附件"); err != ErrCategoryNotEmpty {
		t.Fatalf("含其它文件应返回 ErrCategoryNotEmpty: %v", err)
	}

	// 路径安全与幂等：不存在返回 nil；非法路径拒绝。
	if err := v.DeleteCategory("不存在/子"); err != nil {
		t.Fatalf("不存在的分类应幂等返回 nil: %v", err)
	}
	for _, bad := range []string{"", "../逃逸", "/绝对", ".."} {
		if err := v.DeleteCategory(bad); err != ErrPathOutside {
			t.Fatalf("DeleteCategory(%q) 应返回 ErrPathOutside: %v", bad, err)
		}
	}
}
