// JustMD Windows —— 基于 MyGo 原生 UI（package ui，无 WebView）与
// Onevra 组件库的本地优先 Markdown 笔记与知识管理工具。
//
// 开发：go run .        打包：go tool mygo build
package main

import (
	"log"

	"github.com/egoist/mygo"
	"github.com/oneo-me/onevra/source/shells/sidebar"
)

func main() {
	app := mygo.App
	// 先设置应用名：决定用户数据目录（配置文件位置）。
	app.SetName("JustMD")

	vault, err := OpenVault()
	if err != nil {
		log.Fatal(err)
	}
	// 附件协议已无 WebView 依赖：本地图片直接经 markdown.LoadImage 读取，
	// 这里只做崩溃恢复与工作区装载。

	a := newApp(vault)

	app.WhenReady(func() {
		app.SetMenu(mygo.NewMenu([]*mygo.MenuItem{
			{Role: mygo.RoleAppMenu},
			{Label: "文件", Submenu: []*mygo.MenuItem{
				{Label: "新建笔记", Accelerator: "CmdOrCtrl+N", Click: func(*mygo.MenuItem, *mygo.Window) {
					a.onUIThread(func() { a.createNote() })
				}},
				{Label: "导入 Markdown…", Accelerator: "CmdOrCtrl+I", Click: func(*mygo.MenuItem, *mygo.Window) {
					go a.importFlow()
				}},
				{Label: "导出完整知识库…", Accelerator: "CmdOrCtrl+Shift+E", Click: func(*mygo.MenuItem, *mygo.Window) {
					// 导出取一致性快照：先保存全部草稿（评审 #3）。
					if a.flushAllOrToast() {
						go a.exportVaultFlow()
					}
				}},
				{Label: "重建索引", Accelerator: "CmdOrCtrl+Shift+R", Click: func(*mygo.MenuItem, *mygo.Window) {
					// 重建会重置全部编辑器：先保存草稿（评审 #3）。
					if a.flushAllOrToast() {
						go a.rebuildFlow()
					}
				}},
				mygo.Separator(),
				{Role: mygo.RoleClose},
			}},
			{Role: mygo.RoleEditMenu},
			{Role: mygo.RoleViewMenu},
			{Role: mygo.RoleWindowMenu},
		}))

		// 窗口尺寸按主屏工作区收缩：写死的 1360×860 在高缩放小屏上会比
		// 屏幕还大，内容被挤出可视区（外框 DIP，见 Onevra ShellOptions）。
		width, height, minW, minH := 1360, 860, 980, 600
		if wa := mygo.Screen.PrimaryDisplay().WorkArea; wa.Width > 0 && wa.Height > 0 {
			width = min(width, wa.Width-48)
			height = min(height, wa.Height-48)
			minW = min(minW, width)
			minH = min(minH, height)
		}

		shell := sidebar.NewShell(sidebar.ShellOptions{
			Title:     "JustMD",
			Width:     width,
			Height:    height,
			MinWidth:  minW,
			MinHeight: minH,
			StateKey:  "main",
			Left:      a.leftView,
			Content:   a.contentView,
		})
		a.attach(shell)
		// 拖放导入（评审 #6）：.md 入库、图片等进 attachments/。
		shell.Window().OnFileDrop(func(e *mygo.FileDropEvent) {
			if len(e.Paths) == 0 {
				return
			}
			paths := append([]string(nil), e.Paths...)
			go a.dropFilesFlow(paths)
		})
		a.onUIThread(a.bootstrap)
	})
	// Windows 惯例：关闭全部窗口即退出。
	app.OnWindowAllClosed(func() { app.Quit() })
	if err := app.Run(); err != nil {
		log.Fatal(err)
	}
}
