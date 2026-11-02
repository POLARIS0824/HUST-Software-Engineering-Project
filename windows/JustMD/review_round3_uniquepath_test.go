package main

import (
	"os"
	"path/filepath"
	"testing"
	"time"
)

// 本文件覆盖 PR #1 第三轮评审的评审 #3（uniqueRelPath 死循环，中）。
//
// 旧实现只认 os.ErrNotExist，权限不足、共享冲突、网络盘断开等其它错误
// 会让 for 循环永远重试同一个错误条件——循环永不退出，直接卡死整个应用
// （Create/Rename/Restore/import 都持着 v.mu 调用它，一卡就是全应用卡死）。

// TestR3_UniqueRelPathDoesNotLoopOnStatError 非 ENOENT 的 Lstat 错误
// 不得导致无限重试（评审 #3）。
//
// 复现方式：标题里带 Windows 非法文件名字符（"<"）。此时 Lstat 返回
// "The filename, directory name, or volume label syntax is incorrect."——
// 这不是 os.ErrNotExist（实测确认）。旧实现只认 ErrNotExist，
// 于是 for 循环永远重试同一个错误条件，**直接卡死应用**。
func TestR3_UniqueRelPathDoesNotLoopOnStatError(t *testing.T) {
	dir := t.TempDir()
	// 前置确认：该路径的 Lstat 错误确实不是 ErrNotExist
	probe := filepath.Join(dir, "bad<name>.md")
	if _, err := os.Lstat(probe); err == nil {
		t.Skip("本平台上该路径可正常 Lstat，跳过")
	} else if os.IsNotExist(err) {
		t.Skipf("本平台把该错误映射成 NotExist（%v），无法构造非 ENOENT 场景", err)
	}

	done := make(chan string, 1)
	go func() {
		done <- uniqueRelPath(dir, "bad<name>")
	}()
	select {
	case got := <-done:
		if got == "" {
			t.Fatal("应返回候选名")
		}
	case <-time.After(2 * time.Second):
		t.Fatal("uniqueRelPath 在 Lstat 出错时死循环了（评审 #3）")
	}
}

// TestR3_UniqueRelPathWithUnreadableDir 不存在的目录也必须立刻返回，
// 不得无限重试。
func TestR3_UniqueRelPathWithUnreadableDir(t *testing.T) {
	missing := filepath.Join(t.TempDir(), "不存在的目录", "更深一层")
	done := make(chan string, 1)
	go func() {
		done <- uniqueRelPath(missing, "笔记")
	}()
	select {
	case got := <-done:
		if got != "笔记.md" {
			t.Fatalf("目录不存在时应直接返回原名，得到 %q", got)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("uniqueRelPath 在目录不存在时死循环了（评审 #3）")
	}
}
