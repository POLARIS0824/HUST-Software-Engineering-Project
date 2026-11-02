package main

import (
	"os"
	"path/filepath"
	"testing"
)

// 本文件覆盖 PR #1 第三轮评审的评审 #1（切换知识库失败后写入进程 CWD，高）。
//
// 旧实现：switchWorkspace / OpenVault 在装载失败时只把 root 置空，
// 内存索引却还留着上一座知识库的笔记；saveContent 只判 v.notes[id] 存在与否，
// 于是 filepath.Join("", "notes", ...) 落到进程当前工作目录，凭空写出
// notes/ 与 .meta/，"选择工作区"流程被绕过。

// TestR1_SaveAfterWorkspaceFailureDoesNotWriteToCWD 工作区装载失败后，
// 保存必须被拒绝，绝不能在进程当前工作目录（评审 #1）。
func TestR1_SaveAfterWorkspaceFailureDoesNotWriteToCWD(t *testing.T) {
	// 一个"旧知识库"，装载正常。
	oldRoot := t.TempDir()
	v := &Vault{
		root:    oldRoot,
		notes:   map[string]*indexedNote{},
		trashed: map[string]*NoteMeta{},
		byTitle: map[string][]string{},
	}
	if err := v.loadWorkspace(); err != nil {
		t.Fatalf("loadWorkspace: %v", err)
	}
	doc, err := v.CreateNote("旧库的笔记")
	if err != nil {
		t.Fatalf("CreateNote: %v", err)
	}

	// 切换到一个装载必定失败的目录：把 root 指向一个**文件**，
	// MkdirAll(root/notes) 会失败。
	blocked := filepath.Join(t.TempDir(), "not-a-dir")
	if err := os.WriteFile(blocked, []byte("x"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := v.switchWorkspace(blocked); err == nil {
		t.Fatal("切换到不可用目录应返回错误")
	}

	// 失败后不得残留旧库的索引
	if len(v.notes) != 0 {
		t.Fatalf("切换失败后索引应清空，得到 %d 条", len(v.notes))
	}
	if v.GetWorkspace().Ready {
		t.Fatal("切换失败后工作区不应处于就绪状态")
	}

	// 保存必须被拒绝
	if _, err := v.SaveContent(doc.ID, "不该落盘的内容"); err == nil {
		t.Fatal("没有工作区时保存必须返回错误")
	}

	// 关键断言：CWD 下不得凭空出现 notes/ 或 .meta/
	cwd, err := os.Getwd()
	if err != nil {
		t.Fatal(err)
	}
	for _, name := range []string{dirNotes, dirMeta, dirTrash, dirAttachments} {
		if _, err := os.Stat(filepath.Join(cwd, name)); err == nil {
			t.Errorf("CWD %s 下不应出现 %s/", cwd, name)
		}
	}
}

// TestR1_SaveWithoutWorkspaceReturnsNoWorkspace 没有工作区时，
// 保存返回 ErrNoWorkspace 而不是 ErrNoteNotFound。
func TestR1_SaveWithoutWorkspaceReturnsNoWorkspace(t *testing.T) {
	v := &Vault{notes: map[string]*indexedNote{}, trashed: map[string]*NoteMeta{}, byTitle: map[string][]string{}}
	// 伪造一条索引记录（模拟旧实现"root 置空但索引还在"的状态）
	v.notes["ghost"] = &indexedNote{meta: &NoteMeta{NoteID: "ghost", RelativePath: "幽灵.md"}}

	_, err := v.SaveContent("ghost", "内容")
	if err != ErrNoWorkspace {
		t.Fatalf("没有工作区时应返回 ErrNoWorkspace，得到 %v", err)
	}
}

// TestR1_LoadWorkspaceWithoutRootIsRejected root 为空时装载必须被拒绝，
// 否则会在 CWD 下凭空造出一整套知识库目录（评审 #1）。
func TestR1_LoadWorkspaceWithoutRootIsRejected(t *testing.T) {
	v := &Vault{notes: map[string]*indexedNote{}, trashed: map[string]*NoteMeta{}, byTitle: map[string][]string{}}
	if err := v.loadWorkspace(); err != ErrNoWorkspace {
		t.Fatalf("root 为空时 loadWorkspace 应返回 ErrNoWorkspace，得到 %v", err)
	}
	cwd, _ := os.Getwd()
	for _, name := range []string{dirNotes, dirMeta, dirTrash} {
		if _, err := os.Stat(filepath.Join(cwd, name)); err == nil {
			t.Errorf("CWD %s 下不应出现 %s/", cwd, name)
		}
	}
}

// TestR1_OpenVaultWithMissingWorkspaceClearsIndex 工作区装载失败时，
// 索引必须清空（评审 #1）。
//
// 复现方式：把 root 指向一个**普通文件**——MkdirAll(root/notes) 会失败
// （"不是目录"），装载因此失败。
func TestR1_OpenVaultWithMissingWorkspaceClearsIndex(t *testing.T) {
	root := t.TempDir()
	blocked := filepath.Join(root, "not-a-dir")
	if err := os.WriteFile(blocked, []byte("x"), 0o644); err != nil {
		t.Fatal(err)
	}
	v := &Vault{
		root:    blocked,
		notes:   map[string]*indexedNote{},
		trashed: map[string]*NoteMeta{},
		byTitle: map[string][]string{},
	}
	// 索引里先放一条旧记录
	v.notes["stale"] = &indexedNote{meta: &NoteMeta{NoteID: "stale", RelativePath: "旧.md"}}

	if err := v.loadWorkspace(); err == nil {
		t.Fatal("工作区不可用时装载应失败")
	}
	v.resetWorkspaceState()

	if len(v.notes) != 0 {
		t.Fatalf("装载失败后索引应清空，得到 %d 条", len(v.notes))
	}
	if v.root != "" {
		t.Fatalf("装载失败后 root 应为空，得到 %q", v.root)
	}
}

// TestR1_ExistingBehaviorStillWorks 确认本轮改动没有破坏正常的工作区
// 切换与保存流程。
func TestR1_ExistingBehaviorStillWorks(t *testing.T) {
	v := newTestVault(t)
	doc := mustCreate(t, v, "正常笔记")
	if _, err := v.SaveContent(doc.ID, "正常内容"); err != nil {
		t.Fatalf("保存失败: %v", err)
	}
	body := v.readBody(v.notes[doc.ID].meta)
	if body != "正常内容" {
		t.Fatalf("保存内容不对: %q", body)
	}
}
