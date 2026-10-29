# JustMD Windows 开发约定

- 项目位于 `windows/JustMD/`，是 MyGo 应用：Go 后端 + Vite/TypeScript 前端（WebView2 渲染）。
- 先读 [README](README.md) 与 [技术选型](docs/technical-selection.md)；需求基线沿用 `android/docs/`，平台差异见技术选型。
- 修改 Go 代码后运行 `go test ./...` 与 `go vet ./...`；修改前端后运行 `bun run typecheck`。UI 改动提供实际截图。
- 生成或修改绑定后运行 `bun run generate`（`mygo generate`），不要手改 `src/mygo.ts`。
- 后端导出方法上的文档注释会出现在生成的 TypeScript 客户端里，保持中文注释。
- 提交遵循根目录 `AGENTS.md`：改动限定在本平台目录，提交走 PR。
