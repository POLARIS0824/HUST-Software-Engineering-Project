package main

import (
	"os"
	"path/filepath"
)

// 品牌更名（JustMD → Looma）的用户数据兼容。
//
// 应用名决定用户数据目录（mygo.App.Path(PathUserData)）：改名后新目录是
// %APPDATA%\Looma，旧版本的数据仍留在 %APPDATA%\JustMD。这里只在新目录里
// 还没有对应文件时，把旧目录的**配置**（工作区指针）与**窗口状态**复制过来，
// 让升级后的用户不用重新选择知识库、窗口位置也不丢。
//
// 只复制、不删除旧文件：用户可能回退到旧版本，也可能两个版本并存对比。
// 迁移失败不影响启动（最坏情况是重新选一次工作区）。
const (
	// appConfigFile 是本应用在工作区之外保存的配置文件名。
	appConfigFile = "looma-config.json"
	// legacyConfigFile 是改名前的配置文件名（旧版应用目录里）。
	legacyConfigFile = "justmd-config.json"
	// windowStateFile 是窗口位置/尺寸状态，两代版本同名（由 Shell 的 StateKey 写入）。
	windowStateFile = "window-state.json"
	// legacyAppName 是改名前的应用名（旧版用户数据目录名）。
	legacyAppName = "JustMD"
)

// legacyUserDataDir 返回旧版应用的用户数据目录：与新目录同级、名为 JustMD。
func legacyUserDataDir(newDir string) string {
	return filepath.Join(filepath.Dir(newDir), legacyAppName)
}

// migrateLegacyUserData 从旧版用户数据目录迁移配置与窗口状态。
// 返回是否复制了任何文件。目标文件已存在时一律不覆盖（新版本数据优先）。
func migrateLegacyUserData(newDir string) (bool, error) {
	legacy := legacyUserDataDir(newDir)
	if legacy == newDir {
		return false, nil // 防呆：同名目录（理论上不会发生）
	}
	if _, err := os.Stat(legacy); err != nil {
		return false, nil // 旧版本没装过 / 已清理：没什么可迁移
	}
	if err := os.MkdirAll(newDir, 0o755); err != nil {
		return false, err
	}

	pairs := [][2]string{
		{filepath.Join(legacy, legacyConfigFile), filepath.Join(newDir, appConfigFile)},
		{filepath.Join(legacy, windowStateFile), filepath.Join(newDir, windowStateFile)},
	}
	migrated := false
	for _, p := range pairs {
		src, dst := p[0], p[1]
		if _, err := os.Stat(dst); err == nil {
			continue // 已有新数据：不覆盖
		}
		data, err := os.ReadFile(src)
		if err != nil {
			continue // 旧文件缺失/不可读：跳过这一项
		}
		if err := os.WriteFile(dst, data, 0o644); err != nil {
			return migrated, err
		}
		migrated = true
	}
	return migrated, nil
}
