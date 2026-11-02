package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"unicode/utf8"
)

// ---- 文件名清洗（基线 4.2/4.3、BR-09）-----------------------------------------

func TestSanitizeTitleIllegalChars(t *testing.T) {
	got, changed := sanitizeTitle(`a/b\c:d*e?f"g<h>i|j`)
	want := "a_b_c_d_e_f_g_h_i_j"
	if got != want || !changed {
		t.Fatalf("非法字符应映射为下划线: got %q want %q", got, want)
	}
}

func TestSanitizeTitleTrimsSpacesAndDots(t *testing.T) {
	got, changed := sanitizeTitle("  .标题. ")
	if got != "标题" || !changed {
		t.Fatalf("首尾空格与句点应剥离: %q", got)
	}
}

func TestSanitizeTitleReservedNames(t *testing.T) {
	for _, reserved := range []string{"CON", "PRN", "AUX", "NUL", "COM1", "LPT9"} {
		got, changed := sanitizeTitle(reserved)
		if !changed || got != reserved+"_" {
			t.Errorf("保留名 %s 应追加下划线，得到 %q", reserved, got)
		}
		// 小写同样拦截（Windows 不区分大小写）。
		got2, _ := sanitizeTitle(strings.ToLower(reserved))
		if strings.EqualFold(got2, reserved) {
			t.Errorf("小写保留名 %s 未被拦截", reserved)
		}
	}
}

func TestSanitizeTitleByteLimit(t *testing.T) {
	// 100 个汉字 = 300 字节，超过 250 字节上限。
	long := strings.Repeat("汉", 100)
	got, changed := sanitizeTitle(long)
	if !changed {
		t.Fatal("超长标题应发生截断")
	}
	if len(got) > maxTitleBytes {
		t.Fatalf("截断后仍超限: %d 字节", len(got))
	}
	if !utf8.ValidString(got) {
		t.Fatal("截断不得切断多字节字符")
	}
}

func TestTruncateRunesMultibyte(t *testing.T) {
	s := strings.Repeat("编", 50) // 150 字节
	got := truncateRunes(s, 10)
	if len(got) != 9 { // 10 字节只能容纳 3 个 3 字节汉字 = 9 字节
		t.Fatalf("按字节截断应取整字符: got %d 字节", len(got))
	}
}

// ---- 唯一路径与冲突后缀（基线 4.3：严禁静默覆盖）------------------------------

func TestUniqueRelPathSuffixes(t *testing.T) {
	dir := t.TempDir()
	if got := uniqueRelPath(dir, "笔记"); got != "笔记.md" {
		t.Fatalf("首次应直接返回原名: %q", got)
	}
	os.WriteFile(filepath.Join(dir, "笔记.md"), nil, 0o644)
	if got := uniqueRelPath(dir, "笔记"); got != "笔记 (1).md" {
		t.Fatalf("冲突应追加 (1): %q", got)
	}
	os.WriteFile(filepath.Join(dir, "笔记 (1).md"), nil, 0o644)
	if got := uniqueRelPath(dir, "笔记"); got != "笔记 (2).md" {
		t.Fatalf("冲突应追加 (2): %q", got)
	}
}

// ---- 内容规范化与原子写入（BR-04、基线 4.1）-----------------------------------

func TestNormalizeContent(t *testing.T) {
	cases := [][2]string{
		{"\uFEFFBOM 内容\r\n第二行\r第三行", "BOM 内容\n第二行\n第三行"}, // BOM + CRLF + CR
		{"plain\nlines", "plain\nlines"},
		{"", ""},
	}
	for _, c := range cases {
		got := string(normalizeContent([]byte(c[0])))
		if got != c[1] {
			t.Errorf("normalizeContent(%q) = %q, want %q", c[0], got, c[1])
		}
	}
}

func TestAtomicWriteFileReplacesExisting(t *testing.T) {
	path := filepath.Join(t.TempDir(), "note.md")
	if err := os.WriteFile(path, []byte("旧内容"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := atomicWriteFile(path, []byte("新内容")); err != nil {
		t.Fatalf("原子覆盖写入失败: %v", err)
	}
	data, err := os.ReadFile(path)
	if err != nil || string(data) != "新内容" {
		t.Fatalf("覆盖后内容错误: %q err=%v", data, err)
	}
	// 临时文件不应残留。
	entries, _ := os.ReadDir(filepath.Dir(path))
	for _, e := range entries {
		if strings.HasPrefix(e.Name(), ".justmd-") {
			t.Errorf("临时文件残留: %s", e.Name())
		}
	}
}

func TestAtomicWriteFileCreatesNestedDirs(t *testing.T) {
	root := t.TempDir()
	path := filepath.Join(root, "a", "b", "c.md")
	if err := atomicWriteFile(path, []byte("x")); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(path); err != nil {
		t.Fatal("嵌套目录未创建")
	}
}

// ---- 哈希与 UUID ------------------------------------------------------------

func TestSha256Hex(t *testing.T) {
	if got := sha256Hex(nil); got != "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855" {
		t.Fatalf("空串 SHA-256 不正确: %s", got)
	}
}

func TestNewIDIsUUIDv4(t *testing.T) {
	seen := map[string]bool{}
	for i := 0; i < 100; i++ {
		id := newID()
		if len(id) != 36 || id[14] != '4' {
			t.Fatalf("不是合法 UUID v4: %s", id)
		}
		if seen[id] {
			t.Fatalf("UUID 重复: %s", id)
		}
		seen[id] = true
	}
}

// ---- 路径穿越防御（基线 4.3、NFR-SEC-03）---------------------------------------

func TestSafeRelPath(t *testing.T) {
	root := t.TempDir()
	ok, err := safeRelPath(root, "子目录/笔记.md")
	if err != nil || ok != filepath.ToSlash(filepath.Clean("子目录/笔记.md")) {
		t.Fatalf("合法相对路径被拒绝: %q %v", ok, err)
	}
	for _, bad := range []string{"../逃逸.md", "..\\逃逸.md", "/etc/passwd", "a/../../b.md", ""} {
		if _, err := safeRelPath(root, bad); err != ErrPathOutside {
			t.Errorf("穿越路径 %q 应被拒绝，得到 %v", bad, err)
		}
	}
	// 子目录内的 .. 回到根目录是允许的边界。
	if _, err := safeRelPath(root, "子/../笔记.md"); err != nil {
		t.Errorf("规范化后仍在根内的路径不应被拒绝: %v", err)
	}
}
