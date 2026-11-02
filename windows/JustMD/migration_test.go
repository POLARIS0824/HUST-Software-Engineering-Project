package main

import (
	"os"
	"path/filepath"
	"testing"
)

// writeFile 写一个测试文件（目录不存在就建）。
func writeMigrateFile(t *testing.T, path, content string) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
}

// TestMigrateLegacyUserData 品牌更名后，旧目录（JustMD）的配置与窗口状态
// 应复制到新目录（Looma），工作区指针与窗口位置都不丢。
func TestMigrateLegacyUserData(t *testing.T) {
	root := t.TempDir()
	newDir := filepath.Join(root, "Looma")
	legacyDir := filepath.Join(root, legacyAppName)
	writeMigrateFile(t, filepath.Join(legacyDir, legacyConfigFile), `{"workspace":"D:\\笔记"}`)
	writeMigrateFile(t, filepath.Join(legacyDir, windowStateFile), `{"main":{"x":10,"y":20}}`)

	migrated, err := migrateLegacyUserData(newDir)
	if err != nil || !migrated {
		t.Fatalf("应迁移旧数据: migrated=%v err=%v", migrated, err)
	}
	for _, name := range []string{appConfigFile, windowStateFile} {
		data, err := os.ReadFile(filepath.Join(newDir, name))
		if err != nil {
			t.Fatalf("%s 应被复制: %v", name, err)
		}
		if len(data) == 0 {
			t.Fatalf("%s 内容为空", name)
		}
	}
	cfg, _ := os.ReadFile(filepath.Join(newDir, appConfigFile))
	if string(cfg) != `{"workspace":"D:\\笔记"}` {
		t.Fatalf("配置内容应原样复制，实际 %q", cfg)
	}
	// 旧文件保留（用户可回退到旧版本）。
	if _, err := os.Stat(filepath.Join(legacyDir, legacyConfigFile)); err != nil {
		t.Fatalf("旧配置不应被删除: %v", err)
	}
}

// TestMigrateLegacyUserDataKeepsNewData 新目录已有数据时不得覆盖（新版本优先）。
func TestMigrateLegacyUserDataKeepsNewData(t *testing.T) {
	root := t.TempDir()
	newDir := filepath.Join(root, "Looma")
	legacyDir := filepath.Join(root, legacyAppName)
	writeMigrateFile(t, filepath.Join(legacyDir, legacyConfigFile), `{"workspace":"老库"}`)
	writeMigrateFile(t, filepath.Join(newDir, appConfigFile), `{"workspace":"新库"}`)

	migrated, err := migrateLegacyUserData(newDir)
	if err != nil {
		t.Fatalf("迁移不应报错: %v", err)
	}
	if migrated {
		t.Fatal("新配置已存在时不应再迁移配置")
	}
	cfg, _ := os.ReadFile(filepath.Join(newDir, appConfigFile))
	if string(cfg) != `{"workspace":"新库"}` {
		t.Fatalf("新配置不应被旧数据覆盖，实际 %q", cfg)
	}
}

// TestMigrateLegacyUserDataNoLegacy 第一次安装（没有旧目录）时静默跳过。
func TestMigrateLegacyUserDataNoLegacy(t *testing.T) {
	root := t.TempDir()
	migrated, err := migrateLegacyUserData(filepath.Join(root, "Looma"))
	if err != nil {
		t.Fatalf("没有旧目录不应报错: %v", err)
	}
	if migrated {
		t.Fatal("没有旧目录时不应迁移任何文件")
	}
}

// TestLegacyUserDataDir 旧目录与新目录同级、名为 JustMD。
func TestLegacyUserDataDir(t *testing.T) {
	got := legacyUserDataDir(filepath.Join("C:", "Users", "x", "AppData", "Roaming", "Looma"))
	want := filepath.Join("C:", "Users", "x", "AppData", "Roaming", legacyAppName)
	if got != want {
		t.Fatalf("旧目录应为 %q，实际 %q", want, got)
	}
}
