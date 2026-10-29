# JustMD Windows 开发约定

- 项目位于 `windows/JustMD/`，是 MyGo **原生 UI** 应用（`package ui` + Onevra 组件库，无 WebView、无前端构建链）。
- 先读 [README](README.md) 与 [技术选型](docs/technical-selection.md)；需求基线沿用 `android/docs/`，平台差异见技术选型。
- 修改后运行 `CGO_ENABLED=0 go test ./...`、`go vet .` 与 `go tool mygo vet .`。UI 改动提供实际截图；原生视图改动优先补 `ui.Tester` 无窗口测试（`view_test.go`）。
- 界面组件优先用 Onevra（`components`、`shells/sidebar`、`icons/lucide`）；Onevra 未覆盖的用 MyGo `ui` 原语（编辑器用 `ui/editor`，阅读渲染用 `ui/markdown`）。不要为 MyGo 已有官方等价物引入额外封装。
- 业务规则（解析器、存储、检索、导入导出）只写在领域文件（`parser.go`、`vault.go` 等），视图层（`view*.go`、`app.go`）不直接操作文件系统；`vault` 方法可从后台 goroutine 调用，界面状态只在主线程（视图 / `win.Update` / 菜单回调）修改。
- 正文编辑器是 `ui/editor` 的**源码**编辑器：不要换成 `ui/richtext`（富文本为事实源会破坏 BR-01/BR-06）。
- 提交遵循根目录 `AGENTS.md`：改动限定在本平台目录，提交走 PR。
