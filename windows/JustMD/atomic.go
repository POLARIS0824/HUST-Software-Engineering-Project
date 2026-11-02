package main

import (
	"bytes"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"strings"
)

// atomicWriteFile 以"先写临时文件并落盘、再原子替换"的方式写入目标路径（BR-04）。
// 临时文件与目标同目录（保证同一卷，rename 才是原子的），Windows 上 os.Rename
// 底层使用 MOVEFILE_REPLACE_EXISTING，可直接覆盖既有文件。
func atomicWriteFile(path string, data []byte) error {
	dir := filepath.Dir(path)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return err
	}
	tmp, err := os.CreateTemp(dir, ".justmd-*.tmp")
	if err != nil {
		return err
	}
	tmpName := tmp.Name()
	defer func() {
		// 无论成功失败，清理残留临时文件；成功时文件已被改名走。
		os.Remove(tmpName)
	}()

	if _, err := tmp.Write(data); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Sync(); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	if err := os.Rename(tmpName, path); err != nil {
		return err
	}
	syncDir(dir)
	return nil
}

// syncDir 尽力刷新目录项，让新文件/改名在断电后可见。
//
// Windows 不支持对目录调用 fsync（会返回 "incorrect function"），
// 因此在 Windows 上直接跳过——目录项的持久化由 NTFS 自身保证，
// 显式 fsync 只会产生无意义的错误。
func syncDir(dir string) {
	if runtime.GOOS == "windows" {
		return
	}
	if d, err := os.Open(dir); err == nil {
		_ = d.Sync()
		_ = d.Close()
	}
}

// normalizeContent 统一换行为 LF 并剥离 UTF-8 BOM（基线 4.1）。
func normalizeContent(data []byte) []byte {
	data = bytes.TrimPrefix(data, []byte{0xEF, 0xBB, 0xBF})
	if bytes.IndexByte(data, '\r') >= 0 {
		data = bytes.ReplaceAll(data, []byte("\r\n"), []byte("\n"))
		data = bytes.ReplaceAll(data, []byte("\r"), []byte("\n"))
	}
	return data
}

// sha256Hex 计算 64 位小写十六进制正文哈希。
func sha256Hex(data []byte) string {
	sum := sha256.Sum256(data)
	return hex.EncodeToString(sum[:])
}

// newID 生成 UUID v4。
func newID() string {
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		panic(fmt.Sprintf("生成 UUID 失败: %v", err))
	}
	b[6] = (b[6] & 0x0f) | 0x40
	b[8] = (b[8] & 0x3f) | 0x80
	h := hex.EncodeToString(b[:])
	return strings.Join([]string{h[0:8], h[8:12], h[12:16], h[16:20], h[20:32]}, "-")
}

// safeRelPath 校验用户/外部提供的相对路径：
// 拒绝绝对路径与 ".." 越界（基线 4.3 路径穿越防御），统一正斜杠返回。
func safeRelPath(root, rel string) (string, error) {
	rel = strings.ReplaceAll(rel, "\\", "/")
	if rel == "" {
		return "", ErrPathOutside
	}
	// 绝对路径（含以 / 开头的盘符根路径）直接拒绝，不做静默钳制。
	if strings.HasPrefix(rel, "/") || filepath.IsAbs(filepath.FromSlash(rel)) {
		return "", ErrPathOutside
	}
	rel = strings.TrimPrefix(rel, "/")
	clean := filepath.Clean(filepath.FromSlash(rel))
	abs := filepath.Join(root, clean)
	relRoot, err := filepath.Abs(root)
	if err != nil {
		return "", err
	}
	absAbs, err := filepath.Abs(abs)
	if err != nil {
		return "", err
	}
	if absAbs != relRoot && !strings.HasPrefix(absAbs, relRoot+string(filepath.Separator)) {
		return "", ErrPathOutside
	}
	return filepath.ToSlash(clean), nil
}

// uniqueRelPath 在 notes/ 下为目标标题生成不冲突的相对路径：
// 冲突时追加自增序号（基线 4.3：严禁静默覆盖）。
//
// Lstat 出错时的处理（评审 #3）：旧实现只认 os.ErrNotExist，
// 其它错误（权限不足、共享冲突、网络盘断开）会让 for 循环**无限重试**——
// 同一个错误条件永远不消失，循环永不退出，直接卡死整个应用
// （Create/Rename/Restore/import 都持着 v.mu 调用它，一卡就是全应用卡死）。
//
// 现在区分三种情况：
//   - os.IsNotExist：目标不存在，这个名字可用；
//   - 其它错误：状态未知，不能确定这个名字是否被占用，也不能无限重试。
//     此时返回一个带序号后缀的候选名并停止重试——最坏情况是名字冲突
//     （后续写盘会报错，由调用方如实告知用户），远好过把应用卡死；
//   - 无错误：确实被占用，换下一个候选名。
func uniqueRelPath(notesDir, title string) string {
	base := filepath.FromSlash(title + ".md")
	_, err := os.Lstat(filepath.Join(notesDir, base))
	if err == nil {
		// 已存在：走冲突后缀分支。
	} else if os.IsNotExist(err) {
		return filepath.ToSlash(base)
	}
	for i := 1; ; i++ {
		candidate := fmt.Sprintf("%s (%d).md", title, i)
		_, err := os.Lstat(filepath.Join(notesDir, filepath.FromSlash(candidate)))
		if os.IsNotExist(err) {
			return filepath.ToSlash(candidate)
		}
		if err != nil {
			// 状态未知（权限/共享冲突/网络盘断开）：不再重试。
			// 返回当前候选交给写盘报错，避免无限循环卡死应用（评审 #3）。
			return filepath.ToSlash(candidate)
		}
	}
}
