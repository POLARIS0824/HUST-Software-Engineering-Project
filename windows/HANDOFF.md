# JustMD Windows 版交接文档

> 2026-11-01 晚更新（在当晚"缺陷修复轮"之后），取代同路径早前版本。
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
CGO_ENABLED=0 go test ./...   # 全部测试（必须无 cgo，128 个用例，约 11 秒）
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
| `vault.go` | 核心服务 `Vault`：装载/CRUD/级联重命名（`RenameNoteReported` 返回受影响/跳过报告）/回收站/反链/`RenderReaderSource`/`ListTags`。互斥锁保护，可后台调用 |
| `parser.go` | 标签提取（多级 `#a/b`、大小写折叠、围栏/缩进代码块排除）、双链提取、字数、摘要 |
| `index.go` | 内存索引 + 双链解析；`recomputeLinks`（标题集合变化）与 `resolveNoteLinks`（仅单篇）分开 |
| `search.go` | 全文检索（多关键词 AND、rune 高亮） |
| `meta.go` | 开放 JSON 元数据 + pending 崩溃恢复日志 |
| `atomic.go` | 原子写、规范化、sha256、`safeRelPath`、`uniqueRelPath` |
| `sanitize.go` | 文件名清洗 |
| `portability.go` | 导入（`decodeImportedText` 唯一解码入口 / 文件夹导入 / 冲突三选一 / 路径二次校验）、`writeVaultZip` ZIP 导出、装载与崩溃对账（哈希校准、元数据择优）、拖放导入（目录递归、附件查重） |
| `errors.go` | 领域错误（中文业务化措辞） |

### UI 层（MyGo 原生 + Onevra）

| 文件 | 内容 |
|---|---|
| `main.go` | 入口：菜单/快捷键、`OnFileDrop` 拖放、`OnClose`（保存失败拦一次，`app.onCloseRequest`）、`clampWindowToScreen`、`sidebar.NewShell` |
| `app.go` | 全部界面状态 + 动作。**约定：app 方法只跑主线程**；后台流程 `onUIThread` 回写。含标题草稿策略（`applyTitleDraft`）、保存状态机（失败不自动重试）、编辑器缓存重载（`reloadEditors`）、导入摘要 |
| `view.go` | 侧栏（`leftView` 含**滑动胶囊** `drawNavPill`）、`contentView` 路由、快捷键、overlays |
| `view_notes.go` | 主页面：列表行 `rowTransition`、编辑器/阅读 `paneSwap`、反链面板宽度弹簧、自绘编辑/阅读分段控件 |
| `view_pages.go` | 回收站、设置页、确认弹窗、导入冲突弹窗 |
| `shred.go` | 删除笔记的"碎纸机"动画：`shredAnim`（进度/时长）+ `shredEffect`（`ui.Effect`，把行渲染成位图后按竖条切片下落/旋转/淡出） |
| `*_test.go` | 领域测试 + `view_test` 回归 + `usersim*` 用户流程模拟 |

## 5. 本阶段已完成的工作（2026-10-30 ~ 11-01）

1. **PR #1 评审 10 项整改**（队友 POLARIS0824）：trash/restore/delete/rename 崩溃对账、
   删前保存、操作前 `flushSaveAll`、附件路径解析、大小写重命名、拖放导入接入、
   外部修改冲突检测（`ErrExternalEdit` + `ForceSaveContent`）、UI 线程性能（单篇链接解析/异步保存/检索防抖/阅读快照缓存）、导入路径二次校验、彻底删除错误处理。详见 `windows/docs/verification-record.md`。
2. **界面效果整改**（实机截图 `docs/ui-effect-2026-10-31.png`）：浮动标题栏让位
   （`sidebar.HeaderHeight`）、侧栏计数徽章、卡片时间换行、窗口尺寸按工作区收缩。
3. **交互动画**（分帧证据 `docs/ui-animation-2026-11-01.png`）：侧栏滑动胶囊、反链面板
   宽度收展、编辑/阅读与页面进出场、列表行淡入淡出（**已去掉按行错峰**，见第 6 节决策 18）、
   卡片 hover 渐变。
   **注意：交互元素的进出场刻意只用透明度**——几何类 `ElementTransition` 在动画期间
   绘制位置与命中盒不一致，会点错行（自测复现，见第 6 节）。
   但列表**重排**时的位移（新建笔记后下方行下移）是位置补间带来的，不是刻意做的几何进出场。
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
6. **缺陷修复轮**（2026-11-01 晚，回应用户"还是有些问题"的反馈）：三路只读审计
   （领域存储/导入导出、界面 MyGo 语义、需求符合性）→ 逐条用 `ui.Tester` 或领域测试复现
   → 修复 → 实机复验。新增 37 个回归用例（共 124 个）。修掉的主要问题：
   GBK 导入乱码（解码结果被丢弃，落盘重读原始字节）、重启后首次保存误报"已被其他程序修改"
   （装载不校准 `ContentSha256`）、改名级联在写 pending 前改盘且用内存旧正文回写（失败留断链、
   外部修改被覆盖）、级联/覆盖导入后编辑器缓存不失效（再输入一字就把旧正文写回磁盘）、
   拖放导入不重算链接（双链全未解析、点击建出重复笔记）、同名附件静默覆盖、
   保存失败每 2 秒无限重试、关窗落盘失败无提示、回收站恢复失败仍报成功、
   标签过滤残留与越界匹配、阅读页过期快照点出 UUID 垃圾笔记、"导入整个文件夹"不可达、
   导入结果不报跳过/失败、标签围栏误判、导出包 manifest 与附件递归。
   细节与证据见 `windows/docs/verification-record.md` 的"缺陷修复轮"一节。
7. **删除笔记的碎纸机动画**（2026-11-01，分帧证据 `docs/ui-delete-shred-2026-11-01.png`）：
   删除时把列表行按竖条切片、错峰下落旋转淡出，播完才真正移入回收站。用 MyGo 的
   `Element.Effect` + `Painter.ImageRegion/Transform/Clip` 实现，不截屏、不加浮层；
   系统"减少动态效果"时直接删除。代码在 `shred.go`，决策见第 6 节第 17 条。
8. **PR #1 第二轮评审整改**（2026-11-01 晚，评审 W01~W14 / N1~N5）：新增 19 个回归用例
   （共 148 个）。修掉的主要问题：列表行按行错峰导致新建笔记时相邻行重叠（保留位移动画、
   去掉错峰）、保存乱序静默覆盖新内容（写入序号 + `ErrStaleRevision`）、外部删 `.md`
   产生点不开也删不掉的僵尸笔记、关窗保存失败只拦一次导致丢稿（改为弹确认框由用户选择）、
   保存失败后再输入也不自动保存、级联跳过的原因一律误报"已被其它程序修改"、
   切到回收站不落盘、弹窗打开时 Ctrl+S 失效（MyGo 的 Modal 会禁掉窗口级快捷键，
   改为在弹窗作用域内单独注册）、附件作用域过大（能读到 `.meta/` 里的元数据）、
   视图层直接读文件（读取下沉到 `Vault.ReadNoteAsset`）、多级标签父节点计数只算直接关联。
   另**更正 N1 不成立**：重启后外部改写的内容不会丢（编辑器缓冲区从磁盘加载），
   原结论来自测试方法错误。细节见 `windows/docs/verification-record.md` 的
   "PR #1 第二轮评审整改"一节。
9. **PR #1 第三轮评审整改**（2026-11-02，评审针对 `fc52fbf` 的 5 个中/高优先级 + 5 个低优先级）：
   新增 29 个回归用例（共 176 个）。修掉的主要问题：工作区装载失败后 `root` 未清空且
   `saveContent` 无守卫 → 把笔记写到 `<cwd>/notes/` 下绕过"选择工作区"流程；
   改名级联崩溃后留下断链（现改为先把改写前正文/元数据快照落盘到 pending，
   崩溃时按快照回滚）；`uniqueRelPath` 非 ENOENT 错误死循环并持有 `v.mu`；
   `NoteFileExists` 把权限/掉线等非"不存在"错误误判为"文件已消失"而误清索引；
   `applySaveResult` 在 `ErrStaleRevision` 时误标"已保存"导致用户未落盘的输入被丢弃。
   低优先级：`README.MD` 后缀、空标题兜底"未命名笔记"、别名里的 `#` 被误删、
   标题里的 `[ ]` 生成非法 markdown、标签树无滚动。
   细节见 `windows/docs/verification-record.md` 的"缺陷修复轮（2026-11-02）"一节。

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
   同一落盘函数）。自动保存定时器是**真实时钟** `time.AfterFunc`（见第 7 节测试陷阱）。
9. **窗口尺寸**按 `mygo.Screen.PrimaryDisplay().WorkArea` 收缩（默认 1360×860 在 225% 缩放的
   2560×1600 屏上会溢出），启动后 `clampWindowToScreen` 校正越界恢复位置。
10. **侧栏胶囊**：同 pass 捕获选中项 `ui.Element` + `Draw` 里 `Valid()` 守卫（过期帧跳过一次
    定位）+ `Jump` 首帧落位。不要用 `ui.Handle.Bind`（见决策 3 的换绑 panic）。
11. **标题草稿策略**（缺陷修复轮）：合法且不冲突才逐字改名；撞名或含文件名非法字符时
    **保留用户原稿**、只显示行内提示（`app.titleWarn`），回车后才按清洗结果提交。
    不要改回"逐字改名 + 把 ` (1)` 后缀回填输入框"——旧实现会把输入 `abcdef` 变成
    `abcdef (1)`、把 `a:b` 中途替换成 `a_b` 并让光标跳走（`TestTitleConflictKeepsDraftAndWarns`）。
12. **保存失败不自动重试已失败的那一次**：`saveState == saveError` 时 `scheduleSave` 不排定时器；
    用户点"保存失败"徽章显式重试（`saveNowForce`）。不要恢复"每 2 秒重试"——
    同 ID Toast 会被反复重置而永久驻留，还会无限读盘（`TestSaveErrorStopsAutoRetry`）。
    但用户在失败后**又继续输入**时必须重新启用自动保存（`saveErrorVersion` 比对缓冲区版本号）；
    外部修改冲突例外：须用户显式确认强制覆盖，不自动重试（FR-EDIT-06）。
    关窗落盘失败时弹确认框（「重试保存」/「放弃并退出」），用户做出选择前一直拦住，
    不要改回"只拦一次、第二次放行"——那会直接丢稿（`TestN3_CloseWithFailedSaveKeepsBlocking`）。
13. **装载以磁盘为准**：`loadWorkspace` 发现磁盘哈希 ≠ `meta.ContentSha256` 时校准哈希
    （`Revision` 不变），否则关闭期间被外部改过的笔记重启后一保存就误报外部修改。
    同一相对路径多条 ACTIVE 元数据用 `pickActiveMeta` 确定性择优（sha 命中 → Revision →
    NoteID），不要改回 `map` 直接覆盖（noteID 会在重启间跳变）。孤儿 ACTIVE 元数据保留但
    不得绑定到其它路径；`.trash/` 缺元数据的 `.md` 补建 RECOVERED 元数据。
14. **改名级联的顺序与报告**：先 `writePending` → 逐篇**读盘并校验哈希**才改写（磁盘与内存
    不一致计入 `RenameReport.Skipped` 跳过）→ `os.Rename` → 提交 meta；失败按快照回滚。
    `RenameReport.Affected` 必须被界面消费：这些笔记的编辑器缓存要重载
    （`app.reloadEditors`），否则用户再输入一个字就把旧正文写回磁盘、覆盖级联结果。
15. **导入的唯一落盘入口是 `decodeImportedText`**（BOM/CRLF 归一、GBK→UTF-8、拒绝二进制）；
    拖放导入末尾补 `recomputeLinks()`；附件命名必须查重（`uniqueAttachmentName`）。
    文件夹导入走 `PrepareImportFolder`（`Directory:true`），与文件导入共用 `buildImportPlan`。
16. **异步保存与同步 flush 的次序**：`flushSaveNote` 先等该篇的 `writing` 通道关闭再落盘，
    `applySaveResult` 忽略过期回执（`ver < ed.saved`）。否则在飞的旧快照可能晚于 flush 落盘。
17. **删除动画"先播完再删"**：碎纸动画期间笔记仍在库里，动画结束才 `deleteFromList`，
    这样被切的位图就是这一行自己的真实渲染（`Element.Effect` 提供），不需要截屏/浮层定位；
    中途被杀只是"没删成"，不会出现界面说删了、磁盘还在。不要改成"先删再动画"：
    行没了就没有素材，只能重绘一份假的行或做绝对定位浮层，复杂且容易错位。
    `shredEffect` 必须 `Painter.Clip` 在行盒子内（否则碎片会被后续行压住成残影）；
    碎纸中的行要不透明底色（`t.Surface`），否则分片与下方文字叠成噪点。
    ReduceMotion 下走 `deleteNoteNow` 直接删除；切页/搜索态/不可见时立即结算。
18. **列表行共享同一个无 Delay 的 Transition**（评审 W01）：`rowTransition()` 不带行号，
    所有行用同一条动画曲线。要保留"新建笔记后下方行整体下移"的动画，就**不能**给各行加
    递增 `Delay`——起点错开会让中间帧两行重叠（实测约 7 DIP）。
    `properties()` 在 `Position`/`Size`/`Colors` 都未显式声明时补间全部属性，位置随之补间，
    这正是下移动画的来源（`TestW01_ShiftedRowsDoNotOverlap`）。
19. **保存乱序保护用"写入序号"不用"基线版本号"**（评审 W03）：`SaveContentSeq` 携带
    编辑器里的内容先后序号，落盘时若本篇已落盘过更大序号即返回 `ErrStaleRevision`。
    不用基线版本号是因为两份内容可能基于同一基线，无法区分新旧。
    序号在 `noteEditor.saveSeq` 上分配，每次发起落盘 +1。
20. **弹窗内的快捷键要单独注册**（评审 N5）：MyGo 的 `Modal` 弹窗会让窗口级快捷键整体失效
    （`c.Shortcut` 在弹窗外一律返回 false）。Ctrl+S 在 `confirmDialog` 的 `panel` 上
    用 `panel.OnShortcut` 重新注册；Ctrl+N/Ctrl+E 仍由 `handleShortcuts` 在弹窗时禁用。
21. **附件作用域限于 `notes/` 与 `attachments/`**（评审 W11）：不要把工作区根加回候选路径，
    否则 `![](secret.txt)` 能读到根下任意文件（含 `.meta/` 里的元数据）。
    引用路径可以是工作区相对（`attachments/x.png`）或相对 `attachments/` 本身（`x.png`），两种都认。
22. **读文件在领域层**（评审 W12）：`Vault.ReadNoteAsset` 负责解析路径并读字节，
    视图层（`view_notes.go`）不直接调 `os.ReadFile`（见本目录 `AGENTS.md` 的分层约定）。
23. **多级标签父节点计数是整棵子树去重后的笔记数**（评审 W14）：
    `#复习计划/第一章` 的父节点 `#复习计划` 不再显示 0。同一篇笔记在同一子树里只算一次。

## 7. 测试与调试工具链

- **`ui.Tester` 无窗口测试**是主验证手段（`view_test.go`、`usersim*_test.go`）。
  关键 API：`Frame()`、`Advance(d)`、`Click/ClickAt/RightClick/ChooseMenuItem`、
  `Type/TypeKey/Compose`（Compose=显示组合，Type=提交）、`Find/Texts`、`DropFiles`、`Image()`。
- **Tester 陷阱**（务必知道，否则测试结论会错）：
  - **`Find` 的标签矩形在滚动列表重排后会过期**（官方文档明示：可能返回没显示过的元素）。
    涉及位置的断言先稳定列表（Advance+Frame）或直接调状态方法。
  - **自动保存/搜索定时器是真实时钟**，虚拟 `Advance` 驱动不了——需 `time.Sleep` 真实等待。
  - **进出场动画在虚拟时钟下会冻结在起始姿态**（Enter 起始 Opacity=0 的帧永远不可见）。
    截图/断言前先 `Advance` 走完动画。退场残影同理。
  - 阶段性状态污染：一个 Tester 内点击会改共享 `app` 状态，跨场景测试要显式复位。
  - **装载会校准磁盘哈希**（决策 13）：测试里"模拟外部修改"要改磁盘后**重新装载**才算
    外部修改；只改磁盘不改元数据时，下一次 `loadWorkspace` 会把哈希校准为磁盘内容。
    级联相关断言用 `RenameNoteReported` 的报告，不要猜受影响集合。
- **实机截图**：`SetWindowPos(HWND_TOPMOST, SWP_NOMOVE|SWP_NOSIZE)` → `CopyFromScreen` → 恢复。
  注意：`PrintWindow`/`GetDC` 对 GPU 绘制窗口是黑的；非 DPI 感知进程的坐标会错乱
  （先 `SetProcessDPIAware`）。
- **ffmpeg**：本机在 `E:\Download\软件工程\tools\ffmpeg.exe`（自下载的静态构建，未入仓库），
  可用于视频抽帧、拼对比图。
- 环境：Go 1.27.3（scoop）、gh 已登录 YunHai121（含 workflow scope）。

## 8. 验证状态（诚实版）

**已验证**（细节见 `windows/docs/verification-record.md`）：
- `gofmt`/`go vet`/`go tool mygo vet`/`CGO_ENABLED=0 go test ./...` 全绿（176 用例，
  总语句覆盖 75.8%）；上一轮 CI（windows-latest）全绿，本轮改动需 push 后再看 CI 结果。
- **本轮（2026-11-02）PR #1 第三轮整改的 8 个变更文件逐一做了变异测试**：把修复代码改回缺陷版本，
  对应回归用例必须 FAIL；改回后必须 PASS——确认 29 个新用例不会出现"测试通过但 bug 还在"的假阳性。
- 领域层：解析/检索/导入导出（含 GBK 端到端、ZIP 包体真实执行）/崩溃对账/标签树/
  冲突检测/装载自愈（外部修改校准、重复元数据择优、孤儿回收站条目）等单测覆盖。
- 用户流程模拟（三轮）+ 缺陷修复轮回归：新建/改名/删空/换篇/搜索/回收站撤销与彻底删除/
  阅读双链一键创建/标签过滤/撤销重做/拖放导入（含目录与附件查重）/IME 组合/排序/批量清空/
  标题冲突与非法字符/保存失败状态机/级联后编辑器缓存重载/导入摘要。
- 实机截图：主界面/阅读页/设置页/标签树/动画分帧（`docs/*.png`）。
- **实机数据安全路径**：外部工具改正文 → 重启应用 → 继续输入并等自动保存 → 外部内容与新
  输入同时保留、徽章"已保存"（验证决策 13 的校准与保存链路）。
- **删除碎纸机动画**：`ui.Tester` 分帧（`docs/ui-delete-shred-2026-11-01.png`）确认切片、
  错峰下落、淡出；用例覆盖"动画期间不删→播完入回收站→撤销恢复"、ReduceMotion 直接删除、
  切页立即结算、碎纸中段像素与静止画面不同。窗口模式的观感仍需人工确认（无头渲染与 GPU 绘制
  的采样略有差异）。

**明确没做/没验证**（也是下一步）：
- 真实中文 IME 专项（常规中文输入与 IME 组合输入标题实测正常，未做系统性用例）、
  5000 篇性能压测、真实掉电恢复、Narrator 无障碍、NSIS 分发。
- 拖放导入**实机**验收（vault 有单测，`Window.OnFileDrop` 已接线，未做从资源管理器拖入的截图）。
- 原生系统对话框路径（导入文件/导入文件夹/覆盖导入/导出 ZIP）：计划构建与写包体已单测，
  对话框本身无法无头测试，未做逐项实机截图。
- 阅读页内检索命中滚动定位（FR-SEARCH-04 的 native 降级）。
- 异步保存/异步检索在**窗口模式**的长时实机验证（本轮已验证自动保存与外部修改合并，
  未做长时压测）。
- 用户反馈"还是有些问题"已在缺陷修复轮逐条定位（见第 5 节第 6 条）；若再报，优先要截图。
  已知测试工作区（`%APPDATA%\JustMD` 指向 `E:\Download\软件工程\JustMD-TestWorkspace`）
  里有用户测试留下的垃圾笔记（"("、"未"、"未命名笔记 (2)" 等），可提醒清理。

## 9. 本机遗留物

- `windows/JustMD/justmd-test.exe` — 本地构建产物（**未跟踪、勿提交**；进程可能还在运行，
  `Stop-Process -Name justmd-test` 后重建）。缺陷修复轮用它做过实机复验，结束后已停进程。
- `E:\Download\软件工程\tools\` — ffmpeg 静态构建（~110MB，可删）。
- `E:\Download\软件工程\*.png` — 历次调试截图（可删）。
- `windows/JustMD/.mygo/` — 已 gitignore 的历史残留。

## 10. 文档索引

- `windows/README.md` — 功能清单与运行方式
- `windows/AGENTS.md` — 本平台开发约定
- `windows/docs/technical-selection.md` — 为什么原生 UI/Onevra、平台差异
- `windows/docs/verification-record.md` — **已验证/未验证的唯一权威来源**
- `android/docs/` — 需求基线（改需求先看这里，编号不要乱造）
