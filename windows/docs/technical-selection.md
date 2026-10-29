# JustMD Windows 技术选型

2026-10-30 修订。Windows 版采用 [MyGo](https://mygo.egoist.dev/) 的**原生 UI**（`package ui`，GPU 直接绘制）与 [Onevra](https://github.com/oneo-me/onevra) 组件库，**不使用 WebView**（小组决定，替代本文件此前记录的 Web 前端方案）。需求基线沿用 `android/docs/requirements-analysis.md`，本文说明平台差异与对应的设计调整。

## 选型

- **MyGo 原生 UI**：界面以 Go 的 view 函数逐帧构建（`ui.View`），窗口即开即显、内存占用低；无 WebView 生命周期、无 JavaScript 桥接、无前端构建链。与 Android 版"不通过 WebView 执行 HTML 或 JavaScript"（NFR-SEC-02）的方向完全一致——Android 靠"不用 WebView"保证，Windows 原生 UI 同样从根上消除该面。
- **Onevra 组件库**（`github.com/oneo-me/onevra/source`）：生态内基于 MyGo 的主题化组件库（Button、TextBox、Sidebar、Badge、Dialog、Toasts、Tree、Lucide 图标与 `shells/sidebar` 窗口壳），统一视觉与交互语义；MyGo 自带组件未覆盖的部分才直接使用 `ui` 原语。
- **源码编辑器**：`ui/editor`（Zed 风格代码编辑器，软换行、撤销历史、按文件名推断语法高亮、任意大小文件性能）。**刻意不用 `ui/richtext`**：富文本编辑器以富文本为事实源、Markdown 仅是导出格式，会把未知语法规范化掉，违背 BR-01（Markdown 是唯一事实源）与 BR-06（未知语法原样保留）。
- **阅读页**：`ui/markdown.View` 直接渲染 Markdown 快照，`OnLink` 处理双链与外链点击，`LoadImage` 按需加载工作区内本地图片（自定义实现：相对笔记目录解析 + 路径穿越校验，NFR-SEC-03）；远程图片不加载。

## 与 Android 版的平台差异

| 差异点 | Android 版 | Windows 版 | 依据 |
|---|---|---|---|
| 渲染 | 纯 Compose 原生渲染 | MyGo 原生 GPU 绘制 + Onevra 组件，无 WebView | 小组决定；与 NFR-SEC-02 同向 |
| 编辑 | Compose `BasicTextField` | `ui/editor` 源码编辑器（软换行、按文件名语法高亮） | FR-EDIT-01 源码编辑语义一致 |
| 阅读 | `multiplatform-markdown-renderer-m3` | `ui/markdown.View`（CommonMark + 扩展，OnLink/LoadImage） | FR-EDIT-02 |
| 派生索引 | Room + SQLite FTS5 trigram | 内存派生索引（折叠正文 + 折叠标题/文件名），启动全量扫描、保存增量更新，提供一键重建 | BR-02 派生性不变；实现形态属于平台自由度。数千篇规模下内存检索为毫秒级，远低于 NFR-PERF-02 的 500 ms 目标 |
| 工作区访问 | 应用专属外部目录 + SAF | 用户自选文件夹（原生对话框），默认 `文档/JustMDWorkspace`，配置持久化在用户数据目录 | 桌面端无沙箱限制，直接使用用户可见目录更符合"数据随拷随用" |
| 编辑器会话 | 换篇保留会话撤销历史 | 每篇笔记持有独立 `editor.State`（含 Buffer 与撤销栈），跨篇切换不销毁 | FR-EDIT-08；磁盘文件仍是持久层事实源 |
| 撤销删除反馈 | 5 秒 Snackbar | `c.ToastAction("已移入回收站", "撤销", …)` | BR-11 |
| 快捷键 | 无（触屏） | Ctrl+N 新建、Ctrl+S 强制保存、Ctrl+E 编辑/阅读切换（`c.Shortcut`） | 角色 2（外接键盘用户）诉求 |

其余业务规则（BR-01~BR-12）、数据格式（4.1~4.7）、功能需求与验收标准均按 Android 版基线执行；实现差异仅限上表。

## 技术组合

| 部分 | 选择 | 说明 |
|---|---|---|
| 框架 | MyGo v0.7.3（原生 UI 模板） | `go run .` 开发；`go tool mygo build` 打包（exe + 安装包） |
| 组件库 | Onevra v0.2026.16 | `shells/sidebar` 窗口壳 + `components` 主题化控件 + Lucide 图标 |
| 后端 | Go 1.27 标准库为主 | `golang.org/x/text` 用于 GBK 导入转码；`archive/zip` 导出 |
| 测试 | `ui.NewTester` 无窗口渲染 | 视图冒烟测试与领域单测同为普通 `go test` |

## 已知边界

- 索引为进程内存态，重启后重建（数千篇以内秒级）；未实现 Android 版设计中的 SQLite 落盘索引缓存。
- 阅读页的搜索命中定位（FR-SEARCH-04 的"滚动至首发位置"）暂未实现：`ui/markdown` 暂无锚点滚动 API，检索结果高亮摘要（FR-SEARCH-05 已实现）承担判别作用。
- Onevra 处于 pre-1.0（日期版本号），其 README 自述在 MyGo 提供官方等价物时可能切换到上游；锁定版本 + 仅使用稳定组件面（Button/TextBox/Sidebar/Badge/Dialog/Toasts/Tree/Icon）控制该风险。
- 导入对话框使用系统原生多选，目录导入保留层级；ZIP 备份包的逆向恢复（FR-DATA-07，低优先级）未实现，可用手动解压后重新选择工作区替代。
