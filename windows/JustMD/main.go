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
					go a.exportVaultFlow()
				}},
				{Label: "重建索引", Accelerator: "CmdOrCtrl+Shift+R", Click: func(*mygo.MenuItem, *mygo.Window) {
					go a.rebuildFlow()
				}},
				mygo.Separator(),
				{Role: mygo.RoleClose},
			}},
			{Role: mygo.RoleEditMenu},
			{Role: mygo.RoleViewMenu},
			{Role: mygo.RoleWindowMenu},
		}))

		shell := sidebar.NewShell(sidebar.ShellOptions{
			Title:     "JustMD",
			Width:     1360,
			Height:    860,
			MinWidth:  980,
			MinHeight: 600,
			StateKey:  "main",
			Left:      a.leftView,
			Content:   a.contentView,
		})
		a.attach(shell)
		a.onUIThread(a.bootstrap)
	})
	// Windows 惯例：关闭全部窗口即退出。
	app.OnWindowAllClosed(func() { app.Quit() })
	if err := app.Run(); err != nil {
		log.Fatal(err)
	}
}
