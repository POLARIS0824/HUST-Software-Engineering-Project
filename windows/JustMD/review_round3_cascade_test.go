package main

import (
	"path/filepath"
	"strings"
	"testing"

	"github.com/egoist/mygo/ui"
)

// 本文件覆盖 PR #1 第三轮评审的评审 #2（改名崩溃窗口：pending 不含
// 受影响笔记信息，中）。
//
// 改名的落盘顺序是「写 pending → 级联改写其它笔记 → 改名本篇」。
// 若在「级联已成功、本篇尚未改名」之间崩溃，重启对账只看得到一条
// rename pending，却不知道其它笔记的正文已被改成新标题，结果是留下
// 指向不存在笔记的 [[新标题]] 与凭空消失的反链。

// TestR2_RenamePendingRecordsCascade 改名的 pending 必须记录被级联改写的
// 笔记及其改写前正文，否则崩溃后无法回滚（评审 #2）。
//
// 验证方式：拦下**元数据**写入，让改名在"级联已成功、本篇改名未提交"
// 之后失败。正常路径会 clearPending 清掉记录，因此这里改为直接检查
// 崩溃路径的磁盘状态（见下一个用例）。
func TestR2_RenamePendingRecordsCascade(t *testing.T) {
	v := newTestVault(t)
	target := mustCreate(t, v, "旧标题")
	src := mustCreate(t, v, "来源")
	mustSave(t, v, src.ID, "参见 [[旧标题]]。")

	// 让元数据提交失败：改名在级联之后失败。
	renameFixMakeMetaUnwritable(t, v, target.ID)

	if _, _, err := v.RenameNoteReported(target.ID, "新标题"); err == nil {
		t.Fatal("元数据提交失败时重命名必须返回错误")
	}

	// 回滚后来源正文必须恢复
	body := v.readBody(v.notes[src.ID].meta)
	if body != "参见 [[旧标题]]。" {
		t.Fatalf("回滚后来源正文应恢复原样，得到 %q", body)
	}
}

// TestR2_ReconcileRollsBackCascadeAfterCrash 模拟"级联已成功、本篇尚未改名"
// 时崩溃的磁盘状态，重启对账必须把级联改写精确回滚（评审 #2）。
//
// 崩溃后的磁盘状态：
//   - 本篇仍是旧标题（旧标题.md 存在），新标题.md 不存在；
//   - 来源笔记的正文已被改成 [[新标题]]；
//   - pending 里有一条 rename 记录，带 Cascade 字段记着改写前的正文。
func TestR2_ReconcileRollsBackCascadeAfterCrash(t *testing.T) {
	v := newTestVault(t)
	target := mustCreate(t, v, "旧标题")
	src := mustCreate(t, v, "来源")
	mustSave(t, v, src.ID, "参见 [[旧标题]]。")

	metaDir, pendingDir := v.metaDirs()
	oldBody := "参见 [[旧标题]]。"
	newBody := "参见 [[新标题]]。"

	// 构造崩溃后的磁盘状态：来源正文已被级联改写，本篇还没改名。
	srcAbs := filepath.Join(v.notesDir(), filepath.FromSlash(src.RelativePath))
	if err := atomicWriteFile(srcAbs, []byte(newBody)); err != nil {
		t.Fatal(err)
	}
	// 来源元数据已同步成改写后的哈希（级联成功时会这么做）
	srcMeta := v.notes[src.ID].meta
	srcMeta.ContentSha256 = sha256Hex([]byte(newBody))
	srcMeta.Revision++
	srcMeta.UpdatedAtUtc = nowMs()
	if err := saveNoteMeta(metaDir, srcMeta); err != nil {
		t.Fatal(err)
	}
	v.reindexOne(v.notes[src.ID], newBody)

	// pending 记录：改名意图 + 被级联改写笔记的改写前正文
	op := &PendingOp{
		OperationID: newID(), NoteID: target.ID, Kind: "rename",
		OldRelPath: target.RelativePath, NewRelPath: "新标题.md",
		NewSha: v.notes[target.ID].meta.ContentSha256, ExpectedRevision: v.notes[target.ID].meta.Revision,
		Cascade: []PendingCascadeNote{{
			NoteID:          src.ID,
			RelPath:         src.RelativePath,
			OldBody:         oldBody,
			NewBody:         newBody,
			OldRevision:     srcMeta.Revision - 1,
			OldUpdatedAtUtc: srcMeta.UpdatedAtUtc,
		}},
	}
	if err := writePending(pendingDir, op); err != nil {
		t.Fatal(err)
	}

	// 重启对账
	if err := v.loadWorkspace(); err != nil {
		t.Fatalf("loadWorkspace: %v", err)
	}

	// 来源正文必须回到旧标题
	body := v.readBody(v.notes[src.ID].meta)
	if body != oldBody {
		t.Fatalf("崩溃后应回滚级联改写，得到 %q", body)
	}
	// 链接必须仍解析到目标（旧标题）
	links := v.GetNoteLinks(src.ID)
	if len(links) != 1 || links[0].State != string(linkResolved) || links[0].ResolvedNoteID != target.ID {
		t.Fatalf("回滚后链接应仍解析到目标，得到 %+v", links)
	}
	// 反链必须还在
	backlinks := v.GetBacklinks(target.ID)
	if len(backlinks) != 1 || backlinks[0].SourceNoteID != src.ID {
		t.Fatalf("回滚后反链应恢复，得到 %+v", backlinks)
	}
	// 本篇不得被改名（改名没完成）
	if v.notes[target.ID].meta.RelativePath != target.RelativePath {
		t.Fatalf("本篇不应被改名，得到 %q", v.notes[target.ID].meta.RelativePath)
	}
}

// TestR2_ReconcileDoesNotOverwriteUserEdits 回滚时不得覆盖用户在崩溃后
// 又写入的内容（评审 #2）。
func TestR2_ReconcileDoesNotOverwriteUserEdits(t *testing.T) {
	v := newTestVault(t)
	target := mustCreate(t, v, "旧标题")
	src := mustCreate(t, v, "来源")
	mustSave(t, v, src.ID, "参见 [[旧标题]]。")

	metaDir, pendingDir := v.metaDirs()
	oldBody := "参见 [[旧标题]]。"
	newBody := "参见 [[新标题]]。"
	// 用户在崩溃后又写了一整段
	userBody := "参见 [[新标题]]。\n\n用户后来新写的一整段重要内容。"

	srcAbs := filepath.Join(v.notesDir(), filepath.FromSlash(src.RelativePath))
	atomicWriteFile(srcAbs, []byte(userBody))
	srcMeta := v.notes[src.ID].meta
	srcMeta.ContentSha256 = sha256Hex([]byte(userBody))
	srcMeta.Revision++
	if err := saveNoteMeta(metaDir, srcMeta); err != nil {
		t.Fatal(err)
	}

	op := &PendingOp{
		OperationID: newID(), NoteID: target.ID, Kind: "rename",
		OldRelPath: target.RelativePath, NewRelPath: "新标题.md",
		Cascade: []PendingCascadeNote{{
			NoteID:  src.ID,
			RelPath: src.RelativePath,
			OldBody: oldBody,
			NewBody: newBody,
		}},
	}
	if err := writePending(pendingDir, op); err != nil {
		t.Fatal(err)
	}

	if err := v.loadWorkspace(); err != nil {
		t.Fatalf("loadWorkspace: %v", err)
	}

	body := v.readBody(v.notes[src.ID].meta)
	if body != userBody {
		t.Fatalf("用户的新内容不得被回滚覆盖，得到 %q", body)
	}
}

// TestR2_RenameSuccessLeavesNoCascadeInPending 正常改名成功后，
// pending 必须被清掉（不留陈旧的级联记录）。
func TestR2_RenameSuccessLeavesNoCascadeInPending(t *testing.T) {
	v := newTestVault(t)
	target := mustCreate(t, v, "旧标题")
	src := mustCreate(t, v, "来源")
	mustSave(t, v, src.ID, "参见 [[旧标题]]。")

	if _, report, err := v.RenameNoteReported(target.ID, "新标题"); err != nil {
		t.Fatalf("改名失败: %v", err)
	} else if len(report.Affected) != 1 {
		t.Fatalf("应级联改写 1 篇，得到 %+v", report)
	}

	ops := renameFixReadPending(t, v)
	if len(ops) != 0 {
		t.Fatalf("改名成功后不应残留 pending: %+v", ops)
	}
	// 级联正文正确
	body := v.readBody(v.notes[src.ID].meta)
	if !strings.Contains(body, "[[新标题]]") {
		t.Fatalf("级联正文未更新: %q", body)
	}
}

// TestR2_UIViewStillRenders 确认本轮改动后界面仍能正常渲染。
func TestR2_UIViewStillRenders(t *testing.T) {
	a := newTestApp(t)
	doc := mustCreateForApp(t, a, "渲染")
	mustSaveForApp(t, a, doc.ID, "正文 #标签")
	a.openNote(doc.ID)
	a.refreshLists()

	m := ui.NewTester(a.contentView, 1200, 760)
	m.Frame()
	txt := strings.Join(m.Texts(), "\n")
	if !strings.Contains(txt, "渲染") {
		t.Fatalf("界面应渲染笔记:\n%s", txt)
	}
}
