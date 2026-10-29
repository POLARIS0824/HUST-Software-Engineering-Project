// JustMD Windows —— 基于 MyGo 的本地优先 Markdown 笔记与知识管理工具。
//
// 启动方式（开发）：
//
//	bun run dev    # Vite 开发服务器 + 自动重建 Go 后端
//	bun run build  # 打包应用（build/ 下生成 exe）
package main

import (
	"log"
	"mime"
	"net/http"
	"os"
	"path/filepath"
	"strings"

	"github.com/egoist/mygo"
)

func main() {
	app := mygo.App
	// 先设置应用名：决定用户数据目录（配置文件位置）。
	app.SetName("JustMD")

	vault, err := OpenVault()
	if err != nil {
		log.Fatal(err)
	}
	mygo.Bind(vault)

	// 附件协议：阅读页中的本地图片经 attach://localhost/... 从工作区读取，
	// 处理器内部做路径穿越校验（NFR-SEC-03）。
	registerAttachmentProtocol(vault)

	app.WhenReady(func() {
		app.SetMenu(mygo.NewMenu([]*mygo.MenuItem{
			{Role: mygo.RoleAppMenu},
			{Label: "文件", Submenu: []*mygo.MenuItem{
				{Label: "新建笔记", Accelerator: "CmdOrCtrl+N", Click: func(*mygo.MenuItem, *mygo.Window) {
					MenuAction.Broadcast("new-note")
				}},
				{Label: "导入 Markdown…", Accelerator: "CmdOrCtrl+I", Click: func(*mygo.MenuItem, *mygo.Window) {
					MenuAction.Broadcast("import")
				}},
				{Label: "导出完整知识库…", Accelerator: "CmdOrCtrl+Shift+E", Click: func(*mygo.MenuItem, *mygo.Window) {
					MenuAction.Broadcast("export-vault")
				}},
				{Label: "重建索引", Accelerator: "CmdOrCtrl+Shift+R", Click: func(*mygo.MenuItem, *mygo.Window) {
					MenuAction.Broadcast("rebuild-index")
				}},
				mygo.Separator(),
				{Role: mygo.RoleClose},
			}},
			{Role: mygo.RoleEditMenu},
			{Role: mygo.RoleViewMenu},
			{Role: mygo.RoleWindowMenu},
		}))
		newWindow()
	})
	// Windows 惯例：关闭全部窗口即退出。
	app.OnWindowAllClosed(func() { app.Quit() })
	if err := app.Run(); err != nil {
		log.Fatal(err)
	}
}

func newWindow() {
	mygo.NewWindow(mygo.WindowOptions{
		Title:           "JustMD",
		Width:           1360,
		Height:          860,
		MinWidth:        980,
		MinHeight:       600,
		TitleBarStyle:   mygo.TitleBarHidden,
		TitleBarHeight:  48,
		BackgroundColor: "light-dark(#f6f7f9, #0f1115)", // 与前端 --bg 保持一致
		StateKey:        "main",                         // 记住上次窗口位置与大小
		URL:             "/",                            // devUrl（开发）或内嵌 frontendDist（打包）
	})
}

// registerAttachmentProtocol 注册 attach:// 协议：
//
//	attach://localhost/note/{noteID}/{相对笔记目录的路径}
//	attach://localhost/att/{attachments/ 内的文件名}
//
// 全部路径在解析后校验仍位于工作区根目录内（NFR-SEC-03）。
func registerAttachmentProtocol(vault *Vault) {
	mygo.Protocol.Handle("attach", http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		parts := strings.SplitN(strings.TrimPrefix(r.URL.Path, "/"), "/", 3)
		var abs string
		switch {
		case len(parts) == 3 && parts[0] == "note":
			rel, err := safeRelPath(filepath.Join(vault.notesDir(), parts[1]), parts[2])
			if err != nil {
				http.Error(w, "forbidden", http.StatusForbidden)
				return
			}
			abs = filepath.Join(vault.notesDir(), filepath.FromSlash(rel))
		case len(parts) == 2 && parts[0] == "att":
			rel, err := safeRelPath(vault.attachmentsDir(), parts[1])
			if err != nil {
				http.Error(w, "forbidden", http.StatusForbidden)
				return
			}
			abs = filepath.Join(vault.attachmentsDir(), filepath.FromSlash(rel))
		default:
			http.Error(w, "not found", http.StatusNotFound)
			return
		}
		f, err := os.Open(abs)
		if err != nil {
			http.Error(w, "not found", http.StatusNotFound)
			return
		}
		defer f.Close()
		st, err := f.Stat()
		if err != nil || st.IsDir() {
			http.Error(w, "not found", http.StatusNotFound)
			return
		}
		w.Header().Set("Content-Type", mime.TypeByExtension(strings.ToLower(filepath.Ext(abs))))
		http.ServeContent(w, r, filepath.Base(abs), st.ModTime(), f)
	}))
}
