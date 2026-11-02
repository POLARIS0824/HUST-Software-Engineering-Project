package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// 本文件覆盖 PR #1 第三轮评审的评审 #5（applySaveResult 的 ErrStaleRevision
// 分支误标已保存，中）。
//
// 旧实现在 ErrStaleRevision 分支无条件执行
// ed.saved = ed.state.Buffer.Version() + MarkSaved()，
// 于是编辑器里尚未落盘的新输入被误标为"已保存"，随后的 flush 看到
// Version()==saved 就直接跳过——用户的新内容永远写不进磁盘，
// 界面上却一直显示"已保存"。

// TestR5_StaleReceiptDoesNotMarkUnsavedContentAsSaved 迟到的旧保存回执
// 不得把编辑器里尚未落盘的新输入误标为"已保存"（评审 #5）。
func TestR5_StaleReceiptDoesNotMarkUnsavedContentAsSaved(t *testing.T) {
	a := newTestApp(t)
	doc := mustCreateForApp(t, a, "乱序回执")
	a.refreshLists()
	a.openNote(doc.ID)

	ed := a.editors[doc.ID]
	// 已有一次成功落盘，ed.saved = 1
	ed.state.Buffer.Set("第一版")
	a.saveNow()
	if a.saveState != saveSaved {
		t.Fatalf("首次保存应成功，得到 %s", a.saveState)
	}
	savedVer := ed.saved

	// 用户又输入了新的内容（未落盘）
	ed.state.Buffer.Set("第二版：用户新写的内容")
	dirtyVer := ed.state.Buffer.Version()
	if dirtyVer == savedVer {
		t.Fatal("测试前提不成立：缓冲区版本应已变化")
	}

	// 一份**迟到的旧保存**回执返回（模拟异步保存与同步 flush 乱序）：
	// 领域层拦下了它，返回 ErrStaleRevision。
	a.applySaveResult(doc.ID, savedVer, SaveReceipt{}, ErrStaleRevision)

	// 关键断言：缓冲区不得被标记为已保存
	if ed.saved == dirtyVer {
		t.Fatalf("未落盘的缓冲区不得被标记为已保存（评审 #5）：ed.saved=%d", ed.saved)
	}
	// Modified() 为 true 表示内容与"已保存文本"仍有差异，即未被 MarkSaved。
	if !ed.state.Buffer.Modified() {
		t.Fatal("未落盘的内容不得被 MarkSaved（评审 #5）")
	}

	// 随后的 flush 必须真的把新内容写进磁盘
	if !a.flushSaveNote(doc.ID) {
		t.Fatalf("flush 应成功，提示 %q", a.pendingToast)
	}
	abs := filepath.Join(a.vault.notesDir(), filepath.FromSlash(doc.RelativePath))
	data, _ := os.ReadFile(abs)
	if !strings.Contains(string(data), "用户新写的内容") {
		t.Fatalf("新内容应落盘，磁盘为 %q", string(data))
	}
}

// TestR5_StaleReceiptDoesNotBlockLaterSave 迟到的旧回执不得把界面
// 卡在"保存失败"状态，后续保存仍应正常进行（评审 #5）。
func TestR5_StaleReceiptDoesNotBlockLaterSave(t *testing.T) {
	a := newTestApp(t)
	doc := mustCreateForApp(t, a, "旧回执")
	a.refreshLists()
	a.openNote(doc.ID)

	ed := a.editors[doc.ID]
	ed.state.Buffer.Set("内容")
	a.saveNow()
	savedVer := ed.saved

	ed.state.Buffer.Set("更新的内容")
	a.applySaveResult(doc.ID, savedVer, SaveReceipt{}, ErrStaleRevision)

	if a.saveState == saveError {
		t.Fatal("迟到的旧回执不是失败，不应进入保存失败状态（评审 #5）")
	}
	// 状态应回到待保存，并被自动保存安排上
	if a.saveState != saveDirty {
		t.Fatalf("状态应回到 dirty，得到 %s", a.saveState)
	}

	// 手动触发一次保存，确认能正常落盘
	a.saveNow()
	abs := filepath.Join(a.vault.notesDir(), filepath.FromSlash(doc.RelativePath))
	data, _ := os.ReadFile(abs)
	if !strings.Contains(string(data), "更新的内容") {
		t.Fatalf("更新内容应落盘，磁盘为 %q", string(data))
	}
}

// TestR5_StaleReceiptWithCleanBufferIsSaved 缓冲区与磁盘一致时，
// 迟到的旧回执应把状态恢复为"已保存"。
func TestR5_StaleReceiptWithCleanBufferIsSaved(t *testing.T) {
	a := newTestApp(t)
	doc := mustCreateForApp(t, a, "一致")
	a.refreshLists()
	a.openNote(doc.ID)

	ed := a.editors[doc.ID]
	ed.state.Buffer.Set("内容")
	a.saveNow()
	savedVer := ed.saved

	// 缓冲区没有新输入：状态应为已保存
	a.applySaveResult(doc.ID, savedVer, SaveReceipt{}, ErrStaleRevision)
	if a.saveState != saveSaved {
		t.Fatalf("缓冲区无新输入时应为已保存，得到 %s", a.saveState)
	}
}
