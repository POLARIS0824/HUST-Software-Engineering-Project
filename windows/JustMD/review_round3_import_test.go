package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// 本文件覆盖 PR #1 第三轮评审中的低优先级遗留项（导入侧）：
//   README.MD 被导入成 README.MD.md（扩展名去后缀大小写不敏感）
//   空标题生成 notes/.md

// TestL1_ImportUppercaseExtensionKeepsSingleExtension 大写扩展名的
// Markdown 文件导入后不得变成 "README.MD.md"。
func TestL1_ImportUppercaseExtensionKeepsSingleExtension(t *testing.T) {
	v := newTestVault(t)
	ext := t.TempDir()
	src := filepath.Join(ext, "README.MD")
	if err := os.WriteFile(src, []byte("# 说明\n"), 0o644); err != nil {
		t.Fatal(err)
	}

	plan := v.buildImportPlan([]string{src})
	if len(plan.Files) != 1 {
		t.Fatalf("应生成 1 条导入计划，得到 %d 条", len(plan.Files))
	}
	if got := plan.Files[0].TargetRel; got != "README.md" {
		t.Fatalf("目标路径应去掉大写扩展名，得到 %q", got)
	}

	results, err := v.ApplyImport(plan, nil)
	if err != nil {
		t.Fatalf("导入失败: %v", err)
	}
	if len(results) != 1 || results[0].Status != "imported" {
		t.Fatalf("导入结果异常: %+v", results)
	}
	// notes/ 下只应有一个 README.md，不得再有 README.MD.md
	entries, _ := os.ReadDir(v.notesDir())
	names := []string{}
	for _, e := range entries {
		names = append(names, e.Name())
	}
	for _, n := range names {
		if n == "README.MD.md" {
			t.Fatalf("不应生成 README.MD.md，实际目录内容: %v", names)
		}
	}
	if len(names) != 1 || names[0] != "README.md" {
		t.Fatalf("导入后目录应只有 README.md，得到 %v", names)
	}
}

// TestL1_DropUppercaseExtensionKeepsSingleExtension 拖入大写扩展名的
// Markdown 文件同样不得产生双扩展名。
func TestL1_DropUppercaseExtensionKeepsSingleExtension(t *testing.T) {
	v := newTestVault(t)
	ext := t.TempDir()
	src := filepath.Join(ext, "NOTES.MARKDOWN")
	if err := os.WriteFile(src, []byte("内容"), 0o644); err != nil {
		t.Fatal(err)
	}

	results, err := v.ImportDroppedFiles([]string{src})
	if err != nil {
		t.Fatalf("拖放导入失败: %v", err)
	}
	if len(results) != 1 {
		t.Fatalf("应返回 1 条结果，得到 %d 条", len(results))
	}
	if got := results[0].RelPath; got != "NOTES.md" {
		t.Fatalf("目标路径应去掉大写扩展名，得到 %q", got)
	}
	entries, _ := os.ReadDir(v.notesDir())
	for _, e := range entries {
		if strings.Contains(e.Name(), ".md.md") || strings.HasSuffix(e.Name(), ".MARKDOWN.md") {
			t.Fatalf("不应生成双扩展名文件: %s", e.Name())
		}
	}
}

// TestL2_ImportEmptyTitleDoesNotCreateDotMd 源文件名为 ".md" 时，
// 导入不得生成 notes/.md 这种没有名字的文件。
func TestL2_ImportEmptyTitleDoesNotCreateDotMd(t *testing.T) {
	v := newTestVault(t)
	ext := t.TempDir()
	src := filepath.Join(ext, ".md")
	if err := os.WriteFile(src, []byte("内容"), 0o644); err != nil {
		t.Fatal(err)
	}

	plan := v.buildImportPlan([]string{src})
	if len(plan.Files) != 1 {
		t.Fatalf("应生成 1 条导入计划，得到 %d 条", len(plan.Files))
	}
	if got := plan.Files[0].TargetRel; strings.HasPrefix(got, ".md") {
		t.Fatalf("目标路径不得为空名文件，得到 %q", got)
	}

	if _, err := v.ApplyImport(plan, nil); err != nil {
		t.Fatalf("导入失败: %v", err)
	}
	entries, _ := os.ReadDir(v.notesDir())
	for _, e := range entries {
		if e.Name() == ".md" {
			t.Fatal("不应生成 notes/.md")
		}
	}
}

// TestL2_DropEmptyTitleDoesNotCreateDotMd 拖入 ".md" 同样不得生成空名笔记。
func TestL2_DropEmptyTitleDoesNotCreateDotMd(t *testing.T) {
	v := newTestVault(t)
	ext := t.TempDir()
	src := filepath.Join(ext, ".md")
	if err := os.WriteFile(src, []byte("内容"), 0o644); err != nil {
		t.Fatal(err)
	}

	results, err := v.ImportDroppedFiles([]string{src})
	if err != nil {
		t.Fatalf("拖放导入失败: %v", err)
	}
	if len(results) != 1 {
		t.Fatalf("应返回 1 条结果，得到 %d 条", len(results))
	}
	entries, _ := os.ReadDir(v.notesDir())
	for _, e := range entries {
		if e.Name() == ".md" {
			t.Fatal("不应生成 notes/.md")
		}
	}
}
