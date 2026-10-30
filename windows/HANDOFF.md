# JustMD Windows 版交接文档

> 2026-10-30。写给接手的 AI/开发者。读完本文 + `windows/` 下三个文档即可继续开发，不需要翻聊天记录。

## 1. 项目背景（30 秒版）

小组课程项目 **JustMD**：本地优先（Local-first）的 Markdown 笔记与知识管理工具。仓库 `POLARIS0824/HUST-Software-Engineering-Project`（公开），Android 版在 `android/`，**需求基线 = `android/docs/requirements-analysis.md`**（BR-01~12、FR-*、AC-*、NFR-* 编号都出自它）。

我负责 Windows 版，位于 `windows/JustMD/`。经过两轮迭代：

1. 第一版：MyGo Web 前端（Vue3 + WebView2）——已被完整删除。
2. **当前版：MyGo 原生 UI（`package ui`，GPU 绘制，无 WebView）+ Onevra 组件库**。这是队友康耀燃在群里明确指定的（"mygo 有原生的 ui，不要用 webview"），组件库是用户指定的 onevra。

## 2. 协作规则（必须遵守）

- **不要直接 push main**。用户 GitHub 账号 `YunHai121` 对上游**没有写权限**（试过，403）。流程：commit 到本地分支 `feat/windows` → `git push fork feat/windows`（fork remote 已配置，指向 `YunHai121/HUST-Software-Engineering-Project`）→ PR 自动更新。已有 PR：**https://github.com/POLARIS0824/HUST-Software-Engineering-Project/pull/1**（含 2 个 commit，等队友合并）。
- 根目录 `AGENTS.md` 和 `windows/AGENTS.md` 是仓库约定：中文沟通、Markdown 强调标记与中文之间留空格、改动限定在 `windows/` 平台目录、UI 改动附实际截图、不虚报验证结果。
- Git Bash 环境；提交信息用中文、一行主题。

## 3. 技术栈与运行

```
模块:        justmd (Go 1.27.3)
框架:        github.com/egoist/mygo v0.7.3  (原生 UI 模式)
组件库:      github.com/oneo-me/onevra/source v0.2026.16
其他依赖:    golang.org/x/text (GBK 导入转码)
mygo CLI:    已写入 go.mod 的 tool 指令，用 `go tool mygo ...` 调用
配置:        mygo.json（name/identifier/out=build；原生模式无需前端构建链）
```

```bash
cd windows/JustMD
go run .                      # 开发运行
go tool mygo build            # 打包（build/ 下出 exe + 安装器）
CGO_ENABLED=0 go test ./...   # 全部测试（必须无 cgo）
go vet . && go tool mygo vet .
```

环境（本机已装好）：Go 1.27.3（scoop，GOTOOLCHAIN=auto）、WebView2 运行时（原生模式其实用不到）、gh 已登录 YunHai121。

**已知坑**：`mygo dev` 在此机器会被杀毒软件锁住新编译的 exe 导致 rename 失败——原生模式直接 `go run .` 即可，没有这个问题。

## 4. 代码地图（windows/JustMD/，全部 Go）

### 领域层（不含任何 UI，纯逻辑，可独立测试）

| 文件 | 内容 |
|---|---|
| `vault.go` | 核心服务 `Vault`：工作区装载/切换、笔记 CRUD、重命名级联（改文件名 + 级联改写他人笔记中的 `[[旧标题]]`）、回收站（进/还原/彻底删除/30天清理）、反链查询、`RenderReaderSource`（把双链改写成 `justmd-note://{id}` / `justmd-open://{标题}` 链接供阅读页渲染）。互斥锁保护，**可从后台 goroutine 调用** |
| `parser.go` | 标签提取（排除标题/代码块、多级、大小写折叠）、双链提取（别名/行号/rune 区间）、字数统计、纯文本摘要。**关键设计：`foldString` 用 strings.Map 做大小写折叠，保证 rune 一一对应，所以折叠串的下标能直接定位回原文** |
| `index.go` | 内存派生索引 `indexedNote` + 双链解析（同目录同名优先 → RESOLVED，无候选 → UNRESOLVED，否则 AMBIGUOUS） |
| `search.go` | 全文检索：多关键词 AND、标题/文件名/正文、连续子串匹配、高亮区间（rune 基准）、相关度排序 |
| `meta.go` | 开放 JSON 元数据（NoteMeta schemaVersion 1）+ pending 崩溃恢复日志 |
| `atomic.go` | 原子写文件（临时文件+fsync+rename）、内容规范化（去 BOM、CRLF→LF）、sha256、UUIDv4、`safeRelPath` 路径穿越防御、冲突后缀 ` (n)` |
| `sanitize.go` | 文件名清洗（非法字符→`_`、Windows 保留名、250 字节截断） |
| `portability.go` | 导入（GBK 转码、同名冲突三选一、目录递归）、ZIP 导出（notes/+attachments/+manifest.json 1.1）、索引自愈重建、pending 对账 |
| `errors.go` | 领域错误（中文，业务化措辞） |

### UI 层（MyGo 原生 + Onevra）

| 文件 | 内容 |
|---|---|
| `main.go` | 入口：OpenVault → 原生菜单（文件：新建/导入/导出/重建 + Ctrl+N/I/Shift+E/Shift+R）→ `sidebar.NewShell` 创建主窗口 |
| `app.go` | **全部界面状态**（路由/列表缓存/当前笔记/编辑器 map/保存状态机/对话框状态）+ 动作方法。**约定：app 的方法只跑在主线程**（视图/`win.Update`/菜单回调）；后台流程（导入导出）用 `onUIThread` 回写 |
| `view.go` | 侧栏（新建按钮、导航、标签树）+ 内容路由 + 快捷键（Ctrl+N/S/E）+ overlays（确认框/导入框/忙碌/Toast） |
| `view_notes.go` | 主页面：搜索框、排序、笔记卡片列表、编辑器区（`ui/editor` + 保存徽章 + 实时统计）、阅读页（`ui/markdown.View` + OnLink + LoadImage）、反链面板 |
| `view_pages.go` | 回收站、设置页、确认弹窗、导入冲突逐项决策弹窗 |
| `view_test.go` | `ui.NewTester` 无窗口渲染测试（页面冒烟、保存链路、跨篇草稿保留）+ `RenderReaderSource`/图片路径测试 |

### 关键设计决策（接手者不要轻易推翻）

1. **编辑器刻意用 `ui/editor` 而非 `ui/richtext`**：richtext 以富文本为事实源，会规范化掉未知语法，破坏 BR-01/BR-06。已写进 `windows/AGENTS.md`。
2. **保存链路**：Buffer.Version 对比 `saved` 水位判断脏 → 2 秒防抖 → `SaveContent` 原子落盘 → 拿到回执才置 `saveSaved`（BR-05）。失败显示"保存失败"可点击重试，绝不假报已保存。
3. **每篇笔记独立 `editor.State`**（`app.editors map[string]*noteEditor`）：换篇保留草稿和撤销历史（FR-EDIT-08 的会话内部分）。
4. **Toast 只能在视图构建期弹**（`c.Toast`），后台流程要提示就写 `a.pendingToast`，下一帧 overlays 里消费。
5. **`Context.Key` 要先取 `kc := c.Key(...)` 再传给容器构造**（`ui.Row(kc)`），不能链在返回的 Element 上——mygo 的 Key 是 Context 方法。
6. 列表/标签/反链等派生索引是**内存态**，重启重建（数千篇毫秒级）。这是对 Android 版 SQLite FTS5 方案的有意简化，理由在 technical-selection.md。

## 5. 验证状态（诚实版）

**已验证**（细节见 `windows/docs/verification-record.md`，该文件为核实状态的唯一权威来源）：
- `CGO_ENABLED=0 go test ./...` 全部用例通过（2026-10-30 评审整改后为 80 个）；核心解析/清洗/检索覆盖率 93~100%。
- `go vet` + `go tool mygo vet` 干净。
- 实机（Win11）手工验证过：工作区选择/持久化、建笔记、标题重命名级联、编辑自动保存落盘、阅读页渲染（标题/加粗/代码块高亮/双链链接）、标签树过滤、导航切换。

**明确没做/没验证**（也是 verification-record.md 里的待办）：
- 阅读页内**检索命中滚动定位**（FR-SEARCH-04 的 native 降级）——`ui/markdown` 暂无锚点滚动 API，我没做。
- 阅读页双链点击的完整闭环（跳转/一键创建已接线但没逐一截图验收）。
- 中文 IME 专项、5000 篇性能压测、真实掉电恢复、Narrator 无障碍、NSIS 安装包分发。
- **拖放导入**：2026-10-30 已接线（`Window.OnFileDrop` → `ImportDroppedFiles`，有单测），实机拖放未截图验收。
- 附件目录 `attachments/` 的管理 UI（拖放入库已有入口；删除/整理附件的管理界面仍无）。

## 6. 建议的下一步（按价值排序）

1. 等 PR #1 被合并；若队友要改动，上游合并后 `git pull origin main` 同步。
2. 补 FR-SEARCH-04：打开笔记后阅读模式定位首个命中（可在 `ui/markdown` 上层自己数块，或提 issue 问上游）。
3. 接拖放导入（`tester.DropFiles` 对应真实 drop；vault 侧逻辑现成）。
4. 打一次 `go tool mygo build` 验证 NSIS 安装包产物。
5. 有余力再做：标签树多级折叠 UI（数据层已支持 level/children）、笔记内图片插入辅助。

## 7. 本机遗留物（可删）

- `E:\Download\软件工程\JustMD-TestWorkspace\` —— 实机测试用的知识库（含 2 篇测试笔记），程序配置还指向它（配置在 `%APPDATA%\JustMD\justmd-config.json`，换工作区即可覆盖）。
- `windows/JustMD/.mygo/` —— Web 版 dev 构建残留，已 gitignore。

## 8. 文档索引

- `windows/README.md` — 功能清单与运行方式
- `windows/AGENTS.md` — 本平台开发约定（给 AI 的操作规则）
- `windows/docs/technical-selection.md` — 为什么原生 UI/Onevra、与 Android 版的差异表、已知边界
- `windows/docs/verification-record.md` — 已验证/未验证的完整清单（含 Web 版历史说明）
- `android/docs/` — 需求基线（改需求先看这里，编号不要乱造）
