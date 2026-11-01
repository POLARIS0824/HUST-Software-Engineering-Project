# JustMD Windows 核实记录

2026-11-01 修订（原生 UI 重写后；同日按 PR #1 评审意见整改，并完成用户反馈的缺陷修复轮）。按 `android/AGENTS.md` 的要求区分"目标"与"已验证结果"：以下仅记录实际执行过的验证，未列出的 Android 版验收项不视为已通过。本文件的 Web 前端验证记录随原生重写作废，见文末历史说明。

## 已验证

### 构建与工具链

- `go vet .`、`go tool mygo vet .`、`gofmt` 全部通过。
- `CGO_ENABLED=0 go test ./...` 全部通过（80 个用例，无 cgo，符合 MyGo 原生测试要求）。
- CI 工作流（`.github/workflows/windows-ci.yml`）已在 GitHub Actions 实跑通过（windows-latest：gofmt / go vet / go test / mygo vet 全绿）。

### 后端单元测试（领域层，与 UI 无关）

- 标签提取：标题/围栏代码/行内代码排除、边界判定（前导字符）、大小写折叠、多级标签层级（对应 AC-TAG-01/02）。
- 双链提取：别名、行号、rune 区间恰好覆盖 `[[...]]`、嵌套括号与保留符排除、代码区排除。
- 文件名清洗：非法字符映射、首尾空格句点剥离、Windows 保留名拦截、250 字节截断不切断多字节字符、冲突后缀 ` (n)`。
- 内容规范化：UTF-8 BOM 剥离、CRLF/CR → LF；原子写入覆盖既有文件且无临时文件残留。
- 路径安全：`..` 穿越、绝对路径、前导 `/` 全部拒绝（NFR-SEC-03）；导入执行阶段对 `TargetRel`/`SourcePath` 二次校验，穿越目标与缺失来源均拒绝。
- 全文检索：中文连续匹配、英文大小写不敏感、多关键词 AND、标题/文件名命中优先、高亮区间恰好覆盖关键词（含中文 rune 偏移，对应 AC-SEARCH-01/02）。
- 笔记生命周期：创建落盘 + 元数据、重命名级联文件与引用改写、回收站进/出/彻底删除/30 天清理、恢复到被占用路径自动加后缀（对应 AC-NOTE-01/02/03）。
- 仅大小写重命名（`Note` → `note`）直接落在目标文件名，不再被误判冲突追加 ` (1)`。
- 索引自愈：删除元数据后从 Markdown 重建并标记 RECOVERED、孤立元数据统计（对应 AC-DATA-03）。
- 导入：同名冲突三选一（重命名/覆盖备份回滚/跳过）、损坏文件跳过不中断（对应 AC-DATA-02）。
- 崩溃恢复：pending 日志按哈希幂等补完元数据；未完整落盘的写入不推进版本（BR-04）。
- 崩溃恢复（PR #1 评审补充）：trash/restore/delete/rename 在"文件已动、元数据未提交"断点均可幂等补完或回退，重启后笔记不会从活跃列表与回收站同时消失；pending 丢失时回收站副本也有兜底恢复。
- 外部修改冲突检测：磁盘内容与上次回执哈希不一致时保存中止并返回 `ErrExternalEdit`，不静默覆盖；`ForceSaveContent` 供显式确认后覆盖；标题级联改写同步元数据哈希，不产生误报。
- 拖放导入（`ImportDroppedFiles`）：.md 入库、图片进 `attachments/`，返回结果的 `Status`/`RelPath` 完整；附件引用路径可被 `NoteAssetPath` 解析加载。
- **阅读源改写**：`RenderReaderSource` 把已解析双链改写为指向 noteID 的链接、未解析链接指向编码标题，代码区内不处理，且不回写正文（BR-06）。

核心模块行覆盖（`go test -cover`）：标签解析 93.8%、双链解析 97.6%、文件名清洗 100%、检索 97.1%、文本统计 100%，满足 NFR-MAINT-02 的 ≥ 90% 要求。

### 原生视图测试（`ui.NewTester` 无窗口渲染）

- 三页视图（笔记/回收站/设置）渲染不 panic，关键文本（标题、保存状态、导航项）可见。
- 保存链路：Buffer 编辑 → `saveNow` 规范化 LF 并原子落盘，回执推进、`MarkSaved` 生效（BR-04/05）。
- 会话草稿：切走再切回，未保存草稿与撤销历史保留（FR-EDIT-08）。
- 删除保护（PR #1 评审补充）：删除当前笔记前草稿先落盘（内容随文件进入回收站）；换篇时上一篇草稿先落盘。

### 运行时手工验证（Windows 11 实机，原生 GPU 渲染）

以下均通过实际操作截图确认：

- 窗口与 Onevra 侧栏壳渲染：新建按钮、导航（全部笔记/回收站/设置与数据，带计数徽章）、标签树（#TCP、#复习计划、#计算机网络，点击过滤）。
- 工作区配置持久化：重启后直接进入上次的知识库。
- 笔记列表：卡片（标题/摘要/时间/#标签徽章）、排序下拉。
- 编辑器：标题修改、绿色"● 已保存"徽章、带行号的源码编辑器、底部实时统计（字符/汉字/阅读时长）。
- 阅读页：H2 标题、加粗、代码块（语言标签 + Copy 按钮 + 语法高亮）；`[[双链]]` 与 `[[目标|别名]]` 渲染为可点击链接（改写自 `RenderReaderSource`）。
- 编辑/阅读 Tabs 切换（FR-EDIT-01）。
- 原生菜单（文件：新建/导入/导出/重建索引）与 Ctrl+N/Ctrl+S/Ctrl+E 快捷键已接线。

### 界面效果整改（2026-10-31，实机截图 `docs/ui-effect-2026-10-31.png`）

针对实机截图发现的布局缺陷逐项修复并重新截图确认：

- **浮动标题栏重叠**：Shell 的窗口按钮（最小化/最大化/关闭）此前压在"编辑/阅读" Tabs 上造成乱码；现按 Onevra 约定给侧栏与内容区顶部让出 `sidebar.HeaderHeight`，按钮与内容不再重叠。
- **侧栏计数消失**：`SidebarItem` 默认占满行宽，把计数挤出可视区；改用 `SidebarItemWithSlots` 的 trailing 槽，全部笔记/回收站/标签计数恢复可见。
- **笔记卡片时间换行**：时间与标签徽章同排时时间被挤成竖排；改为时间独立一行、徽章一行。
- **窗口尺寸溢出**：写死的 1360×860 在高缩放小屏（逻辑桌面 1138×711）上比屏幕还大；初始尺寸与最小尺寸按 `mygo.Screen` 工作区收缩。
- 同步修复后 `ui.Tester` 无窗口渲染与实机截图一致，主界面/侧栏无残影与错位。

### 交互动画（2026-11-01，分帧证据 `docs/ui-animation-2026-11-01.png`）

参照 Neverland 演示（同为 MyGo 原生 UI）实现的动效，全部经 `ui.Tester` 虚拟时钟分步截帧确认中间态：

- **侧栏选中胶囊**：在选中项之间弹簧形变（滑动 + 变宽，`AnimateTo` 四通道 + `Jump` 首帧落位），分帧可见胶囊飞行中的拉长形变。
- **反链面板收展**：面板宽度本身是弹簧动画值，编辑区 `Grow(1)` 每帧精确互补，任何中间帧无重叠；内容在面板足够宽后淡入。
- **编辑/阅读、换篇、页面切换**：`ElementTransition` 进出场（淡入上移/淡出），分帧可见交叉过渡。
- **列表行进出场**：`Enter/Exit` + `Position` + 按行 `Delay` 错峰；排序重排平滑滑动；卡片悬停底色渐变（`AnimateColor`）。
- **自绘编辑/阅读分段控件**：滑块与胶囊同款弹簧。替换掉 Onevra `components.Tabs`——其实现（`slideBehind`）在 paint-only 帧持有过期 `Element`，动画一开就触发 `ui: element from pass N used in pass N+1` 崩溃（这也是此前"阅读"乱码的根源）。回归测试 `TestAnimationTransitionsSmoke` 覆盖上述路径。
- 尊重系统"减弱动态效果"偏好：框架在 ReduceMotion 下自动改为淡入淡出。

### 删除笔记的碎纸机动画（2026-11-01，分帧证据 `docs/ui-delete-shred-2026-11-01.png`）

需求方要求"删除笔记时像把组件切成几段、模拟物理下落"的效果（参考 MyGo 把原生组件切片/碎纸机的演示）。实现完全基于 MyGo 自带能力，没有截屏、没有额外浮层：

- **原理**：`Element.Effect` 把这一行（含子树）渲染成一张位图交给自定义 `shredEffect`；`Painter.ImageRegion` 按像素把位图切成 9 条竖片；`Painter.Transform` 给每片叠加位移（重力下落 + 外飘 + 摆动）与绕自身中心的旋转，`ImageOptions.Tint` 做末段淡出；`Painter.Clip` 裁在行盒子内，碎片沉到卡片底边即消失，不会盖住下面的行。
- **时序**：每片错峰 26ms（从右往左依次被"咬"下去），总时长 560ms；删除动作在动画播完后执行，所以被切的位图就是这一行自己的真实渲染结果（标题/摘要/标签/底色都原样），不需要捕获或重绘。动画期间行不响应点击/右键。
- **不改变删除语义**：撤销 Toast（5 秒以上）、删除前保存草稿、失败提示都保持原样；动画期间笔记仍在库里，进程被杀只是"没删成"，不会出现"界面说删了、磁盘还在"。
- **兜底路径**：系统开启"减少动态效果"（`Preferences.ReduceMotion`）时直接删除不做动画；动画中途切到回收站/设置页、被删笔记因标签过滤不在列表、或进入搜索态时，立即结算删除。
- **回归测试**（`shred_test.go`）：动画期间不删且可打开 → 推进虚拟时钟后进回收站并带"撤销"、撤销可恢复；ReduceMotion 立即删除；切页立即结算；碎纸中段的渲染像素与静止画面不同（`ui.Tester.Image()` 逐像素比较）。同时更新了两条既有删除用例（`TestUserSim`、`TestContextMenuHitsAnimatedRow`）以等待动画结束。

### 标题框删不干净（2026-11-01，实机复现修复）

- **现象**：标题输入框删到只剩最后一个字符就删不掉，旧字符反复回弹。
- **根因**：标题框绑定到每帧从 `currentTitle` 重建的局部变量；删空后改名被 `ErrTitleEmpty` 拒绝，绑定值与输入缓冲不一致，触发 mygo `textinput` 的值回同步（`applyString`），把旧字符冲回输入框。
- **修复**：编辑缓冲持久化到 `app.titleEdit`，空值保留为草稿（不改名、不回弹），失焦/换篇/空提交时回填原标题。回归测试 `TestTitleBoxDeletesToEmptyWithoutBounce`（真实点击 + 退格删空 + 多帧观察）。

### 用户流程自测整改（2026-11-01，`usersim*_test.go` 三轮全流程模拟）

模拟用户真实操作（新建/改名/删空/换篇/搜索/回收站撤销/阅读双链/标签过滤/撤销重做/拖放/IME/排序/批量清空等）发现并修复：

- **多级标签树整体丢失**：`extractTags` 能提取 `#复习计划/第一章`，但 `ListTags` 建树只把不含 `/` 的路径当根，多级标签的父节点被 `roots[:0]` 清掉 → 树返回空。重写为指针树 + 末尾物化（值拷贝还会丢子孙更新），回归 `TestListTagsMultiLevelTree`。
- **动画期间点错行/删错笔记**：`ElementTransition` 的几何进出场（位移、重排滑动）期间绘制位置与命中盒不一致，右键会命中邻居行。交互元素的进出场改为纯透明度渐变（面板宽度、胶囊等布局级动画不受影响），回归 `TestContextMenuHitsAnimatedRow`。
- **新建笔记标题框接字**：新笔记标题「未命名笔记」后直接键入会拼在后面；现在标题框以空草稿 + 占位符呈现，键入即命名（`focusTitle` 自动聚焦）。
- **删除失败仍弹「已移入回收站」**：`deleteFromList` 失败时 Toast 不再误导；撤销后回到刚才正在编辑的笔记。
- **关闭窗口丢草稿**：2 秒防抖未到就关窗会丢编辑；`OnClose`/`OnWindowAllClosed` 前同步 `flushSaveAll`。
- **窗口恢复到屏幕外**：`StateKey` 记忆的位置/尺寸在换显示器后可能越界；启动时按工作区收缩尺寸，几乎不可见时居中（`clampWindowToScreen`）。
- 测试器备注：`Tester.Find` 的标签矩形在滚动列表重排后会过期（官方文档明示），涉及位置的交互断言需先稳定列表；防抖定时器是真实时钟，虚拟 `Advance` 驱动不了。

### 缺陷修复轮（2026-11-01 晚，用户反馈"还是有些问题"的定位与修复）

先做三路只读审计（领域层存储/导入导出、界面层 MyGo 语义、需求符合性），每条结论都用 `ui.Tester` 或领域测试复现后再修。本轮新增 37 个回归用例：`CGO_ENABLED=0 go test ./...` 共 **124 个用例全绿**，`gofmt`/`go vet`/`go tool mygo vet` 无输出，总语句覆盖 75.8%。

**数据安全与一致性**

- **GBK 导入根本没转码**：`PrepareImport` 解码结果只赋给局部变量后丢弃，落盘路径重新读原始字节 → GBK 文件以乱码入库却报"已导入"。改为唯一解码入口 `decodeImportedText`（BOM/CRLF 归一、GBK→UTF-8、拒绝含 NUL/U+FFFD 的二进制），导入计划校验、新建、覆盖、拖放四条链路共用；损坏文件按 AC-DATA-02 报"已跳过"。回归 `TestGBKImportDecodedToUTF8EndToEnd`、`TestImportOverwriteDecodesGBK`、`TestImportRejectsBinaryMarkdown`。
- **重启后首次保存误报"笔记已被其他程序修改"**：`loadWorkspace` 只索引正文、不校准 `ContentSha256`，应用关闭期间被外部改过的笔记一保存就冲突（用户只能强制覆盖）。装载时以磁盘为事实源校准哈希（`Revision` 不变）；同一相对路径的多条 ACTIVE 元数据改为确定性择优（sha 命中 → Revision → NoteID），消除 noteID 在重启间跳变；`.trash/` 里缺元数据的孤儿 `.md` 按 mtime 补建 RECOVERED 元数据，不再永久不可见、不可恢复。回归 `TestLoadWorkspaceCalibratesExternalEditSha`、`TestLoadWorkspaceDuplicateActiveMetaDeterministic`、`TestTrashFileWithoutMetaVisibleAndRestorable`。
  - **实机验证**：保存后关闭应用 → 外部工具追加一行 → 重启 → 在编辑器继续输入 → 等待自动保存。磁盘上外部追加内容与新输入同时保留，保存徽章为"已保存"（无冲突提示）。截图 `docs/ui-fix-2026-11-01.png`（第 1 行黑体为外部追加内容，第 2 行为重启后的新输入，右上角"● 已保存"）。
- **改名级联会改坏数据**：级联改写发生在写 pending 之前，且用内存旧正文整篇回写磁盘 → 改名失败会留下 `[[新标题]]` 断链与消失的反链；其它笔记在会话中被外部改过时，用户的新内容被静默覆盖。改为"先写 pending → 逐篇读盘校验哈希才改写（否则跳过并计入报告）→ 改名 → 提交元数据"，失败时按快照回滚已改正文与元数据；新增 `RenameReport{Affected,Skipped}` + `RenameNoteReported`（`RenameNote` 保持原签名委托）。回归 `TestVaultRenamePendingFailureRollsBackLinks`、`TestVaultRenameSkipsExternallyModifiedSource`、`TestVaultRenameReportedCascadeReport`、`TestVaultRenameMetaFailureRollsBackCascade`。
- **级联改名 / 覆盖导入后编辑器缓存不失效**：已在会话里打开过的笔记仍显示旧正文，用户再输入一个字，2 秒防抖就把整篇旧正文写回磁盘，静默覆盖级联结果或刚导入的内容（`SaveContent` 的外部修改检测此时比的是新哈希，不会报警）。改名按报告重载受影响的干净编辑器缓存，覆盖导入按 `ImportResult.NoteID` 重载。回归 `TestRenameCascadeRefreshesEditors`。
- **拖放导入后双链不解析**：`ImportDroppedFiles` 末尾没有 `recomputeLinks` → 导入笔记的 `[[双链]]` 全为未解析，点击提示"尚未创建"并生成重复笔记。已补齐；同时支持拖入文件夹（递归 `.md` 入库、其余进 `attachments/`，空目录也计入结果）。回归 `TestDropImportResolvesLinksImmediately`、`TestDropDirectoryRecurses`。
- **同名附件静默覆盖**：附件名只带秒级时间戳，名称清洗还会把不同名折叠成同名 → 一批拖入的两个 `shot.png` 只剩一张。改为落盘前查重（`_2/_3…`、清洗后为空时用 sha8 兜底）并返回真实 `RelPath`。回归 `TestDroppedAttachmentNamesAreUnique`。
- **异步保存可能把旧内容盖回磁盘**：同步 flush 与在飞的异步保存没有次序保证（窗口模式才有此分支）。`flushSaveNote` 先等该篇写完再落盘，`applySaveResult` 忽略过期回执。

**界面与提示**

- **同名/非法标题污染输入框**：旧实现逐字改名，撞名时把 ` (1)` 后缀回填进输入框（继续输入得到「abcdef (1)」），非法字符被中途替换、光标跳走。改为：合法且不冲突才立即改名；撞名或含非法字符时保留用户原稿并给行内提示，回车后才按清洗结果提交（空标题仍是合法草稿）。
- **保存失败无限重试 + 永久 Toast**：`saveError` 仍每 2 秒重试，同一条"已被其他程序修改"Toast 因 ID 相同被反复重置而永久驻留。改为失败后停止自动重试、只提示一次，由用户点"保存失败"徽章显式重试（FR-EDIT-06）；原子写失败清理 pending，元数据写失败保留 pending 供重启对账。
- **关窗丢稿无提示**：`OnClose` 忽略 `flushSaveAll` 失败。现在失败时拦一次关窗并提示"草稿保存失败"，用户再次关窗放行（避免磁盘持续故障时关不掉窗口）。
- **回收站恢复失败仍报成功 / 删除失败无提示**：恢复失败仍弹"已恢复笔记"，右键删除失败点了像没点，撤销失败静默无效。改为按领域错误给业务化提示，成功才刷新与提示。
- **标签过滤残留与越界匹配**：删掉最后一篇带该标签的笔记后过滤条件仍在，列表谎称"还没有笔记"、侧栏无选中项；前缀匹配把 `#ab/c` 算成 `#a` 的命中。改为层级边界匹配（`a` 命中 `a`、`a/b`，不命中 `ab`）+ 过滤失效自动清除。
- **阅读页过期快照点出 UUID 垃圾笔记**：快照里的已解析双链在目标被删除/回收后仍指向旧 noteID，点击后把 noteID 当标题提示"尚未创建"，确认后建出一篇 UUID 标题的笔记。改为提示"目标笔记已删除或已移入回收站"，并在笔记集合变化后重算阅读快照。
- **导入结果只报成功数**：损坏/跳过的文件静默消失（AC-DATA-02 要求告知）。结束提示改为"成功 N 篇，跳过 M 个，失败 K 个（文件名…）"。
- **"导入整个文件夹"实际不可达**：文件对话框没有开目录选择（`FOS_PICKFOLDERS`），递归分支是死代码。新增 `PrepareImportFolder`（`Directory:true`）+ 设置页独立入口「导入文件夹…」。
- **弹窗打开时全局快捷键仍生效**：确认框/导入冲突框上按 Ctrl+N 会在弹窗背后新建笔记，关闭弹窗后用户面对的是另一篇。现在弹窗打开时不响应全局快捷键。

**其他**

- **标签误判**：缩进代码块、引用块内围栏、四反引号围栏里的 `#include` 被当成正文标签。`forEachProseSegment` 按 CommonMark 规则重写（引用前缀剥离、围栏按实际长度+字符配对、缩进代码块跳过），双链 rune 下标仍精确映射原文。
- **导出包对齐基线 4.7**：`generator` 改为 `JustMD v1.1`、`schemaVersion` 改为 `1.0`；附件递归收集（`attachments/sub/x.png` 不再静默丢出备份包）；把写包体抽成 `writeVaultZip(io.Writer)` 并在单测里真实执行 manifest 组装/哈希/子目录附件（此前 `ExportVault` 本体从未被执行过）。
- `RebuildIndex.PurgedTrash` 不再恒为 0（按前后回收站条目数差统计）。

### PR #1 第二轮评审整改（2026-11-01 晚，评审 W01~W14 / N1~N5）

针对最新推送 `91a72007` 的二次评审，逐条复现后再修。本轮新增 19 个回归用例
（`windows/JustMD/review_followup_test.go`）：`CGO_ENABLED=0 go test ./...` 共
**148 个用例全绿**，`gofmt`/`go vet`/`go tool mygo vet` 无输出。

**界面与动画**

- **W01 新建笔记时目录行重叠**：列表行按序号加递增 `Delay` 做错峰淡入，各行补间起点错开，
  上方行先走、下方行还没跟上，中间帧两行叠在一起（实测相邻行重叠约 7 DIP）。
  保留"目录向下位移"的动画，但去掉按行错峰：所有行共享同一个无 `Delay` 的
  `ElementTransition`，整齐同步移动。修复后各行间距全程恒定 51.25 DIP。
  根因写在 `windows/AGENTS.md`：`properties()` 在 `Position`/`Size`/`Colors` 都未显式声明时
  会补间全部属性（位置随之补间），但各行起点必须一致，否则中间帧重叠。
  回归 `TestW01_ShiftedRowsDoNotOverlap`、`TestW01_RowTransitionHasNoPerRowDelay`。
- **N5 弹窗打开时 Ctrl+S 失效**：MyGo 的 `Modal` 弹窗会让窗口级快捷键整体失效
  （`c.Shortcut` 在弹窗外一律返回 false）。改为在弹窗**作用域内**重新注册 Ctrl+S，
  用户在"草稿保存失败"的确认框上可直接用快捷键重试保存。
  回归 `TestN5_CtrlSWorksWhileDialogOpen`。

**数据安全与一致性**

- **W03 保存乱序静默覆盖**：异步保存与同步 flush 之间没有次序保证，迟到的旧快照会
  静默覆盖已落盘的新内容。新增写入序号（`SaveContentSeq`/`ForceSaveContentSeq`）：
  序号是"这份内容在编辑器里的先后次序"，落盘时若本篇已落盘过更大序号即返回
  `ErrStaleRevision` 并拒绝覆盖。不用"基线版本号"判定——两份内容可能基于同一基线，
  无法区分新旧；按序号则"旧快照后落盘"被准确拦下、"新快照后落盘"正常生效。
  回归 `TestW03_StaleSnapshotDoesNotOverwriteNewerSave` 等 4 例。
- **N3 关窗丢草稿**：旧实现只拦一次关窗，用户再点一次关闭就放行 → 草稿直接丢失。
  改为失败时弹出确认框（「重试保存」/「放弃并退出」），在用户做出选择之前一律拦住。
  回归 `TestN3_CloseWithFailedSaveKeepsBlocking`、`TestN3_CloseSucceedsWhenSaveWorks`。
- **W02 外部删除 .md 产生僵尸笔记**：内存索引不监听文件系统，外部删掉正文后
  列表里还在、点不开、也删不掉。新增 `NoteFileExists`/`DropVanishedNote`：
  打开失败时提示"文件已被外部删除"并从列表移除；删除时不再被卡住。
  回归 `TestW02_OpenVanishedNoteGivesFeedback` 等 4 例。
- **N3 保存失败后不再自动保存**：旧实现把 `saveError` 当终态，用户再敲一个字也不保存。
  改为记录失败时的缓冲区版本号，用户在失败后**又继续输入**时重新启用自动保存；
  但外部修改冲突仍不自动重试（须用户显式确认强制覆盖，FR-EDIT-06）。
  回归 `TestSaveErrorStopsAutoRetry`、`TestSaveErrorExternalConflictNotAutoRetried`。
- **N2 级联跳过的原因被误报**：`RenameReport` 只有 `Skipped`，一律提示"已被其它程序修改"，
  但其中一部分只是"编辑器里有未落盘草稿"。新增 `External`/`Dirty` 两个字段分开报告，
  提示语相应区分。回归 `TestN2_RenameReportDistinguishesExternalAndDirty`。
- **W06 切到回收站不落盘**：`setView` 对回收站跳过 flush，草稿仍在内存但逻辑不一致。
  改为无条件 flush。回归 `TestW06_SetViewFlushesDraft`。

**更正：N1 不成立**

N1（"重启后外部改写的内容被静默覆盖"）经真实 UI 流程复验**不成立**：
编辑器缓冲区从磁盘加载，外部改写的内容会保留在用户编辑的基础上。

```
重启后编辑器缓冲区 = "外部工具完全改写的内容"
保存后磁盘 = "外部工具完全改写的内容，用户新写的一行"
```

原结论来自测试方法错误——直接调 `SaveContent` 整体覆盖了正文，而非走
"重启 → openNote → 继续输入 → 保存"的 UI 流程。已改为回归用例固化正确结论：
`TestN1_ExternalRewriteSurvivesRestart`。

**其余**

- **W11 附件作用域过大**：`NoteAssetPath` 的候选路径含工作区根，
  `![](secret.txt)` 能读到根下任意文件（含 `.meta/` 里的元数据 JSON）。
  作用域收窄到 `notes/` 与 `attachments/`。回归 `TestW11_AssetPathScopedToNotesAndAttachments`。
- **W12 视图层直接读文件**：`loadImage` 在视图层调 `os.ReadFile`，违反
  `windows/AGENTS.md` 的分层约定。读取下沉到领域层 `Vault.ReadNoteAsset`。
  回归 `TestW12_ReadNoteAssetDelegatesToDomain`。
- **W14 多级标签父节点计数只统计直接关联**：`#复习计划/第一章` 的父节点
  `#复习计划` 显示 0。改为整棵子树去重后的笔记数（同一篇笔记在同一子树里只算一次）。
  回归 `TestW14_ParentTagCountIncludesSubtree`、`TestW14_ParentTagCountDedupsSameNote`。
- **W10 parser.go 注释与代码相反**：注释说"允许非 ASCII 的字母"，
  但上一行条件 `unicode.IsLetter(r) && r < 0x80` 恰恰拒绝非 ASCII 字母。改正注释。
- **W13 syncDir 在 Windows 上静默吞错**：改为显式 `runtime.GOOS == "windows"` 分支跳过
  （Windows 不支持对目录 fsync），并处理 `d.Sync()`/`d.Close()` 的错误。
- **W05「2 秒防抖」表述错误**：实际是节流（变脏后每 2 秒落盘一次），不是防抖。
  `README.md`/`HANDOFF.md`/代码注释统一改为"2 秒自动保存（节流）"，并说明
  不做防抖的原因（防抖在持续输入时永不落盘，丢失窗口不可控）。


## 待验证（对应 Android 版基线，Windows 版尚未覆盖）

- 中文 IME 组合输入的系统性验证（`ui/editor` 原生 IME，未做专门用例；常规中文输入实测正常，本轮实机确认 IME 组合输入进入标题框）。
- 5,000 篇规模的检索与索引重建性能压测（NFR-PERF-02/06）。
- 真实掉电/进程强杀下的 pending 恢复（仅有测试级模拟）。
- 窗口模式下异步保存/异步检索/防抖的长时实机验证（本轮实机确认自动保存、关闭前落盘与外部修改合并正常；未做长时压测）。
- 拖放导入的实机验证（`Window.OnFileDrop` 已接线，拖放 `.md`、目录递归、附件查重均有单测；未做从资源管理器拖入的截图验收）。
- 系统对话框路径的实机验收：导入文件/导入文件夹/覆盖导入/导出 ZIP（计划构建与写包体已有单测，原生对话框本身无法无头测试）。
- 阅读页双链点击的完整交互截图验收（跳转/歧义候选/一键创建已实现并接线；本轮修掉了过期快照点出 UUID 垃圾笔记的问题）。
- 键盘无障碍（Narrator 播报）与高对比度检查。
- NSIS 安装包的分发验证（`go tool mygo build` 完整打包路径）。
- 阅读页内检索命中滚动定位（FR-SEARCH-04 的 native 降级，见技术选型已知边界；检索命中行号已具备，未接编辑器定位）。

## 历史

2026-10-29 的首版实现采用 MyGo Web 前端（Vue 3 + WebView2），已完成等价功能验证；同日按小组决定重写为原生 UI（无 WebView），领域层（解析器、存储、检索、导入导出，含全部单测）完整保留，前端验证记录以本文件为准。

2026-10-30 按 PR #1 评审意见整改（数据安全与崩溃对账、删除前保存、外部修改冲突检测、附件路径解析、拖放导入接入、性能与路径校验等 10 项），新增 13 个测试用例并更新本记录。

2026-11-01 完成交互动画、用户流程自测整改与缺陷修复轮：三路只读审计后修复 GBK 导入乱码、重启后外部修改误报冲突、改名级联改坏数据与编辑器缓存失效、拖放导入双链不解析、附件同名覆盖、保存失败无限重试、关窗丢稿、回收站恢复失败误报成功、标签过滤残留、阅读页过期快照、导入文件夹不可达等缺陷，新增 37 个回归用例（共 124 个），并实机验证了"外部修改后重启继续编辑不误报冲突"这条数据安全路径。
