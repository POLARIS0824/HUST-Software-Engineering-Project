package main

import (
	_ "embed"

	"github.com/egoist/mygo"
)

// appIconPNG 是应用图标，与打包时嵌入 exe 资源的 resources/icon.png 是同一份文件
// （MyGo 的默认图标约定：resources/icon.png，方形 PNG，构建时生成多尺寸 .ico）。
//
// 运行期再设置一次窗口图标，是为了覆盖没有 exe 资源的场景：
// `go run .` / `mygo dev` 的窗口与任务栏此前只能显示系统默认图标。
// 打包后与 exe 内嵌图标一致，重复设置没有副作用。
//
//go:embed resources/icon.png
var appIconPNG []byte

// applyWindowIcon 把应用图标设置到窗口；失败只影响图标，不影响启动。
func applyWindowIcon(w *mygo.Window) {
	if w == nil || len(appIconPNG) == 0 {
		return
	}
	_ = w.SetIcon(appIconPNG)
}
