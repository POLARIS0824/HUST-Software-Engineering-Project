# JustMD Windows 版交接文档

> 2026-11-01 更新，取代同路径 2026-10-30 版（其验证状态与代码描述已过时）。
> 读完本文 + 根目录 `AGENTS.md` + `windows/AGENTS.md` 即可继续开发，不需要翻聊天记录。

## 1. 项目背景（30 秒版）

小组课程项目 **JustMD**：本地优先（Local-first）的 Markdown 笔记与知识管理工具。仓库
`POLARIS0824/HUST-Software-Engineering-Project`（公开），Android 版在 `android/`，**需求基线 =
`android/docs/requirements-analysis.md`**（BR-01~12、FR-*、AC-*、NFR-* 编号都出自它）。

Windows 版位于 `windows/JustMD/`：**MyGo 原生 UI**（`package ui`，GPU 绘制，无 WebView）+
Onevra 组件库（小组指定，队友要求"不要用 webview"）。两轮迭代：第一版 Vue3+WebView2 已完整删除。

**PR #1**：https://github.com/POLARIS0824/HUST-Software-Engineering-Project/pull/1
（open，等队友合并；建议 Squash 合并，PR 描述中已注明）。

## 2. 协作规则（必须遵守）

- **不要直接 push main**。用户 GitHub 账号 `YunHai121` 对上游**没有写权限**（403）。流程：
  本地分支 `feat/windows` → `git push fork feat/windows`（fork remote 已配置）→ PR #1 自动更新。
- gh CLI 已登录 YunHai121，**token 已含 `workflow` 权限**（曾为推 `.github/workflows/` 授权过）。
- 改动限定在 `windows/` 平台目录；共享文件（如 `.github/`）变更**必须单独提交并在提交说明中注明**。
- 提交信息用中文、一行主题；UI 改动附实际截图；不虚报验证结果。
- 根 `AGENTS.md` / `windows/AGENTS.md` 为平台开发约定（中文沟通、测试命令等）。

## 3. 技术栈与运行

```
模块:        justmd (Go 1.27.3，go.mod 内 tool 指令)
框架:        github.com/egoist/mygo v0.7.3  (原生 UI 模式)
组件库:      github.com/oneo-me/onevra/source v0.2026.16
其他依赖:    golang.org/x/text (GBK 导入转码)
配置:        mygo.json（name/identifier/out=build；原生模式无前端构建链）
```

```bash
cd windows/JustMD
go run .                      # 开发运行（注意：mygo dev 会被杀毒软件锁 exe）
CGO_ENABLED=0 go test ./...   # 全部测试（必须无 cgo，约 90 个用例）
go vet . && go tool mygo vet .
gofmt -l .                    # 必须无输出
go build -o justmd-test.exe . # 构建可运行程序（本地测试用，勿提交）
```

CI：`.github/workflows/windows-ci.yml`（windows-latest：gofmt/vet/test/mygo vet，
仅监听 `windows/**`；runner 上 `core.autocrlf=false` 防行尾误报）。最近一次 push 全绿。

## 4. 代码地图（windows/JustMD/，全部 Go）

### 领域层（无 UI，可独立测试）

| 文件 | 内容 |
|---|---|
| `vault.go` | 核心服务 `Vault`：装载/CRUD/级联重命名/回收站/反链/`RenderReaderSource`/`ListTags`。互斥锁保护，可后台调用 |
| `parser.go` | 标签提取（多级 `#a/b`、大小写折叠）、双链提取、字数、摘要 |
| `index.go` | 内存索引 + 双链解析；`recomputeLinks`（标题集合变化）与 `resolveNoteLinks`（仅单篇）分开 |
| `search.go` | 全文检索（多关键词 AND、rune 高亮） |
| `meta.go` | 开放 JSON 元数据 + pending 崩溃恢复日志 |
| `atomic.go` | 原子写、规范化、sha256、`safeRelPath`、`uniqueRelPath` |
| `sanitize.go` | 文件名清洗 |
| `portability.go` | 导入（GBK/冲突三选一/路径二次校验）、ZIP 导出、装载与崩溃对账、拖放导入 |
| `errors.go` | 领域错误（中文业务化措辞） |

### UI 层（MyGo 原生 + Onevra）

| 文件 | 内容 |
|---|---|
| `main.go` | 入口：菜单/快捷键、`OnFileDrop` 拖放、`OnClose` 退出前 `flushSaveAll`、`clampWindowToScreen`、`sidebar.NewShell` |
| `app.go` | 全部界面状态 + 动作。**约定：app 方法只跑主线程**；后台流程 `onUIThread` 回写 |
| `view.go` | 侧栏（`leftView` 含**滑动胶囊** `drawNavPill`）、`contentView` 路由、快捷键、overlays |
| `view_notes.go` | 主页面：列表行 `rowTransition`、编辑器/阅读 `paneSwap`、反链面板宽度弹簧、自绘编辑/阅读分段控件 |
| `view_pages.go` | 回收站、设置页、确认弹窗、导入冲突弹窗 |
| `*_test.go` | 领域测试 + `view_test` 回归 + `usersim*` 用户流程模拟 |

## 5. 本阶段已完成的工作（2026-10-30 ~ 11-01）

1. **PR #1 评审 10 项整改**（队友 POLARIS0824）：trash/restore/delete/rename 崩溃对账、
   删前保存、操作前 `flushSaveAll`、附件路径解析、大小写重命名、拖放导入接入、
   外部修改冲突检测（`ErrExternalEdit` + `ForceSaveContent`）、UI 线程性能（单篇链接解析/异步保存/检索防抖/阅读快照缓存）、导入路径二次校验、彻底删除错误处理。详见 `windows/docs/verification-record.md`。
2. **界面效果整改**（实机截图 `docs/ui-effect-2026-10-31.png`）：浮动标题栏让位
   （`sidebar.HeaderHeight`）、侧栏计数徽章、卡片时间换行、窗口尺寸按工作区收缩。
3. **交互动画**（分帧证据 `docs/ui-animation-2026-11-01.png`）：侧栏滑动胶囊、反链面板
   宽度收展、编辑/阅读与页面进出场、列表行错峰淡入淡出、卡片 hover 渐变。
   **注意：交互元素的进出场刻意只用透明度**——几何类 `ElementTransition` 在动画期间
   绘制位置与命中盒不一致，会点错行（自测复现，见第 6 节）。
4. **用户流程自测修复**（`usersim*_test.go` 三轮模拟）：
   - 多级标签树整体丢失（`ListTags` 重写为指针树）
   - 动画期间右键删错笔记（几何进出场改纯淡入淡出）
   - 新建笔记标题框接字（空草稿 + 占位符 + 自动聚焦）
   - 删除失败仍弹"已移入回收站"、撤销后回到原笔记
   - 关窗丢草稿（`OnClose`/`OnWindowAllClosed` 前 `flushSaveAll`）
   - 窗口恢复到屏幕外（`clampWindowToScreen`）
   - 标题框"最后一个字符删不掉"（`app.titleEdit` 持久缓冲）
   - 改名顶起更新时间导致卡片乱跳（改名不再改 `UpdatedAtUtc`）
5. **上游缺陷绕行**：Onevra `components.Tabs` 的 `slideBehind` 在 paint-only 帧持有过期
   `Element`（`ui: element from pass N used in pass N+1`），动画一开就崩——已替换为**自绘
   分段控件**（`modeSwitch`），勿改回。

## 6. 关键设计决策（接手者不要轻易推翻）

1. **标题框绑定 `app.titleEdit` 持久缓冲**：不能绑定到每帧重建的局部变量——删空后改名被
   `ErrTitleEmpty` 拒绝，绑定值与输入缓冲不一致会触发 mygo `textinput` 的值回同步
   （`applyString`），旧字符弹回（"最后一个字符删不掉"）。空值是合法草稿：不改名、不回弹；
   Enter 提交时回填原标题；换篇时 `openNote` 重置缓冲。
2. **进出场动画只做透明度**（列表行/页面/面板内容）：几何类位移在动画期间命中盒与绘制位置
   不一致，会命中邻居行（`TestContextMenuHitsAnimatedRow` 回归）。布局级动画（`AnimateTo`
   宽度驱动布局、胶囊 Draw 绘制）不受影响。
3. **自绘分段控件**替代 `components.Tabs`：见第 5 节第 4 条。用 `ui.ButtonBase` + `Draw` +
   `AnimateTo` 滑块；`Valid()` 守卫后取 `LayoutBounds`（同 pass 元素可取，跨 pass 需 `ui.Handle`，
   但 `Handle.Bind` 同一 pass 不允许换绑——点击引发中途重建会 panic，勿用在选中项会变的场景）。
4. **反链面板宽度是弹簧动画值**（`AnimateTo` 0↔280），编辑区 `Grow(1)` 每帧精确互补；
   内容在宽度 ≥150 后构建 + `fadeSwap` 过渡。不要改回 `ElementTransition` 的 Collapse 方案
   （入场不补间宽度，会与编辑区重叠）。
5. **`ListTags` 指针树 + 末尾物化**：值拷贝建树会丢子孙更新，且"仅无斜杠路径作根"漏掉
   多级标签父节点（整棵树消失）。
6. **改名不顶起 `UpdatedAtUtc`**：否则逐字改名时卡片按更新时间排序不停跳动。正文更新才算。
7. **删除/重建/导出/换库前 `flushSaveAll`**，失败即中止并提示（`flushAllOrToast`）。
   `deleteFromList` 先保存草稿再 `TrashNote`，返回 bool；失败不弹撤销 Toast。
8. **`saveNote` 异步落盘**（窗口模式 `go` + `onUIThread` 回执；无窗口测试走同步分支，
   同一落盘函数）。防抖定时器是**真实时钟** `time.AfterFunc`（见第 7 节测试陷阱）。
9. **窗口尺寸**按 `mygo.Screen.PrimaryDisplay().WorkArea` 收缩（默认 1360×860 在 225% 缩放的
   2560×1600 屏上会溢出），启动后 `clampWindowToScreen` 校正越界恢复位置。
10. **侧栏胶囊**：同 pass 捕获选中项 `ui.Element` + `Draw` 里 `Valid()` 守卫（过期帧跳过一次
    定位）+ `Jump` 首帧落位。不要用 `ui.Handle.Bind`（见决策 3 的换绑 panic）。

## 7. 测试与调试工具链

- **`ui.Tester` 无窗口测试**是主验证手段（`view_test.go`、`usersim*_test.go`）。
  关键 API：`Frame()`、`Advance(d)`、`Click/ClickAt/RightClick/ChooseMenuItem`、
  `Type/TypeKey/Compose`（Compose=显示组合，Type=提交）、`Find/Texts`、`DropFiles`、`Image()`。
- **Tester 陷阱**（务必知道，否则测试结论会错）：
  - **`Find` 的标签矩形在滚动列表重排后会过期**（官方文档明示：可能返回没显示过的元素）。
    涉及位置的断言先稳定列表（Advance+Frame）或直接调状态方法。
  - **防抖/搜索定时器是真实时钟**，虚拟 `Advance` 驱动不了——需 `time.Sleep` 真实等待。
  - **进出场动画在虚拟时钟下会冻结在起始姿态**（Enter 起始 Opacity=0 的帧永远不可见）。
    截图/断言前先 `Advance` 走完动画。退场残影同理。
  - 阶段性状态污染：一个 Tester 内点击会改共享 `app` 状态，跨场景测试要显式复位。
- **实机截图**：`SetWindowPos(HWND_TOPMOST, SWP_NOMOVE|SWP_NOSIZE)` → `CopyFromScreen` → 恢复。
  注意：`PrintWindow`/`GetDC` 对 GPU 绘制窗口是黑的；非 DPI 感知进程的坐标会错乱
  （先 `SetProcessDPIAware`）。
- **ffmpeg**：本机在 `E:\Download\软件工程\tools\ffmpeg.exe`（自下载的静态构建，未入仓库），
  可用于视频抽帧、拼对比图。
- 环境：Go 1.27.3（scoop）、gh 已登录 YunHai121（含 workflow scope）。

## 8. 验证状态（诚实版）

**已验证**（细节见 `windows/docs/verification-record.md`）：
- `gofmt`/`go vet`/`go tool mygo vet`/`CGO_ENABLED=0 go test ./...` 全绿（90 用例）；
  CI（windows-latest）全绿。
- 领域层：解析/检索/导入导出/崩溃对账/标签树/冲突检测等单测覆盖。
- 用户流程模拟（三轮）：新建/改名/删空/换篇/搜索/回收站撤销与彻底删除/阅读双链一键创建/
  标签过滤/撤销重做/拖放导入/IME 组合/排序/批量清空。
- 实机截图：主界面/阅读页/设置页/标签树/动画分帧（`docs/*.png`）。

**明确没做/没验证**（也是下一步）：
- 真实中文 IME 专项、5000 篇性能压测、真实掉电恢复、Narrator 无障碍、NSIS 分发。
- 拖放导入**实机**验收（vault 有单测，`Window.OnFileDrop` 已接线，未做从资源管理器拖入的截图）。
- 阅读页内检索命中滚动定位（FR-SEARCH-04 的 native 降级）。
- 异步保存/异步检索在**窗口模式**的长时实机验证（无窗口测试走同步分支）。
- 用户反馈过"还是有些问题"但未给细节——交接后若用户再报，优先发截图定位；
  已知测试工作区（`%APPDATA%\JustMD` 指向 `E:\Download\软件工程\JustMD-TestWorkspace`）
  里有用户测试留下的垃圾笔记（"("、"未"、"未命名笔记 (2)" 等），可提醒清理。

## 9. 本机遗留物

- `windows/JustMD/justmd-test.exe` — 本地构建产物（**未跟踪、勿提交**；进程可能还在运行，
  `Stop-Process -Name justmd-test` 后重建）。
- `E:\Download\软件工程\tools\` — ffmpeg 静态构建（~110MB，可删）。
- `E:\Download\软件工程\*.png` — 历次调试截图（可删）。
- `windows/JustMD/.mygo/` — 已 gitignore 的历史残留。

## 10. 文档索引

- `windows/README.md` — 功能清单与运行方式
- `windows/AGENTS.md` — 本平台开发约定
- `windows/docs/technical-selection.md` — 为什么原生 UI/Onevra、平台差异
- `windows/docs/verification-record.md` — **已验证/未验证的唯一权威来源**
- `android/docs/` — 需求基线（改需求先看这里，编号不要乱造）
