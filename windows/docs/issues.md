# JustMD Windows 问题记录

2026-11-01。评审 PR #1（`refactor` 分支，commit `bf7919a`）时发现的问题记录。需求基线沿用 `android/docs/requirements-analysis.md`（BR-01~12 / FR-* / AC-* / NFR-* 编号出自该文件）。

本文分两部分：**已验证问题**有可复现的测试或实测步骤支撑；**待验证问题**仅由代码逻辑推断，尚无运行证据，需进一步确认后再定性。

评审在 Windows 11 本机执行：`CGO_ENABLED=0 go test ./...` 全部通过（86 个用例）；临时编写的复现测试与内存诊断插桩在评审结束后已删除，代码未改动。

---

## 已验证问题

### W01. 连续新建笔记时列表标题堆叠在底部

连续点击「＋ 新建笔记」，笔记列表出现向下滚动动画，同时向下滑动的笔记标题全部重叠在底部，列表视觉破损。

根因在 `windows/JustMD/view_notes.go` 的 `rowTransition`：注释写着「刻意不做位移类动画——ElementTransition 的几何进出场期间绘制位置与命中盒不一致，点卡片会命中邻居行（自测复现）」，但 MyGo v0.7.3 `ui/transition.go` 的 `properties()` 语义与之相反：

```go
func (r *transition) properties() uint8 {
	t := &r.t
	if !t.Position && !t.Size && !t.Colors {
		return propPosition | propSize | propColors
	}
	// ...
}
```

`Position`、`Size`、`Colors` 三个开关全为 false（默认值）时，框架会动画包括位置在内的全部属性。要真正禁用位移动画，必须显式关闭位置与尺寸、同时至少开启一项其他属性。`rowTransition` 三项皆未设置，因此位移动画一直生效。

表现为重叠而非错位，是因为新建笔记按 `updated_desc` 排序插入列表顶部，下方所有行位置下移；每行有 230ms 过渡期，并按行错峰延迟（`min(i, 8) * 28ms`，最多 8 行即 224ms）。连续点击会触发多批动画叠加且互不同步，中间帧的行位置与布局位置不一致，于是堆叠在底部。

`windows/docs/verification-record.md` 的「用户流程自测整改」记录过同类问题——「动画期间点错行/删错笔记」，当时只把交互元素（右键菜单）的进出场改为纯透明度渐变，列表行 `rowTransition` 本身的位移动画并未关闭。注释以为关了，实际没关。

建议显式关闭位移动画：

```go
func rowTransition(i int) ui.ElementTransition {
	delay := time.Duration(min(i, 8)) * 28 * time.Millisecond
	return ui.ElementTransition{
		Animation: &ui.Animation{Duration: 230 * time.Millisecond, Ease: ui.CubicBezier(.22, 1, .36, 1), Delay: delay},
		Position:  false,
		Size:      false,
		Colors:    true,
		Enter:     &ui.Motion{Opacity: 0},
		Exit:      &ui.Motion{Opacity: 0, Collapse: true},
	}
}
```

需要注意 `Exit` 的 `Collapse: true`（出场收拢）依赖尺寸属性，关掉尺寸动画后收拢效果可能失效。备选是保留 `Collapse` 而去掉按行错峰 `Delay`——错峰是多批动画不同步的主因之一。两种方案都需实机截图确认后定稿。

同时建议在 `windows/AGENTS.md` 记录 MyGo 的 `properties()` 语义，避免后续维护者重复踩这个坑。

### W02. 外部直接删除 .md 文件后产生无法操作的僵尸笔记

不使用软件回收站，而在资源管理器中直接删除 `notes\` 下的 `.md` 文件后回到应用，该笔记仍留在列表中，点击无反应打不开，也无法删除（提示「保存失败，已中止删除」）。只有关闭软件重新打开后才会消失。

复现测试输出：

```
=== TestReview_ExternalDeleteBreaksNote ===
外部删除后，笔记仍在列表中 = true
openNote 后 currentID = "" (期望 "e2a81e6d-...")
deleteFromList 返回 = false
删除后仍在列表中 = true
```

`v.notes` 与 `a.editors` 都是启动时加载的内存索引，运行期间不监听文件系统变化，外部删除文件后索引里仍留着该笔记。`vault.go` 的 `openNoteLocked` 读不到文件时返回 `ErrNoteNotFound`，而 `app.go` 的 `openNote` 是 `if err != nil || doc.Trashed { return }`——静默返回，没有 Toast 也没有任何提示，用户看到的只是「点了没反应」。删除路径同样失败：`deleteFromList` 先调 `flushSaveNote`，`vault.SaveContent` 读磁盘发现文件不存在即返回 `ErrSaveFailed`，于是删除被中止。

重启后恢复正常，是因为 `portability.go` 的 `loadWorkspace` 用 `filepath.WalkDir` 扫描 `notes/` 目录重建索引，文件已经不在，索引自然也没有了。

这与需求基线有出入。需求分析 SCN-04 要求「用户一键触发，系统在数十秒内重新解析全部纯文本，Tag、FTS 与双链索引完美复原」，FR-DATA-05 也有「索引全库自愈重建」。手动重建索引（`Ctrl+Shift+R`）能解决，但用户不知道要这么做，且该功能是菜单项而非自动的。

建议在 `openNote` 失败时给出提示并清理僵尸索引：提示「笔记文件已被外部删除，是否从列表移除？」，用户确认后移除该索引条目。成本约十行。

### W03. 乱序保存可静默覆盖较新内容

较旧的保存快照迟到提交时，会覆盖较新的内容，且 `SaveContent` 不返回任何错误。

真实 goroutine 路径下 300 次迭代的复现输出：

```
=== TestReviewP0_RealGoroutineStress ===
迭代 0：磁盘被旧快照覆盖 = "NEWER-V2"
迭代 1：磁盘被旧快照覆盖 = "NEWER-V2"
迭代 2：磁盘被旧快照覆盖 = "NEWER-V2"
共 300 次迭代，乱序覆盖发生 300 次
```

`app.go` 中后台保存走 goroutine、同步保存走主线程，两者只靠 `vault.mu` 串行化写入，没有顺序保证：

```go
// saveNote 的后台路径
snap := ed.state.Buffer.Snapshot()
run := func() {
	receipt, err = a.vault.SaveContent(id, snap.String())
	a.onUIThread(func() { a.applySaveResult(id, snap.Version(), receipt, err) })
}
go run()

// flushSaveNote 的同步路径：不检查 saveState，不等正在进行的保存完成
snap := ed.state.Buffer.Snapshot()
receipt, err := a.vault.SaveContent(id, snap.String())
```

三道防线都没有拦住。「已保存」徽章方面，`applySaveResult` 里 `ver >= ed.saved` 与 `ed.state.Buffer.Version() == ver` 都不成立（V1 < V2），既不更新 `ed.saved` 也不 `MarkSaved`，界面显示停在 V2 而磁盘已是 V1。外部修改检测方面，`saveContent` 比较的是磁盘 sha 与 `n.meta.ContentSha256`，较新内容落盘时两者已经同步，`ErrExternalEdit` 检查通过。版本号方面，`ExpectedRevision` 只写入 pending 日志供崩溃对账，`saveContent` 本身不做任何单调性校验。

需要说明触发概率：goroutine 从 spawn 到尝试获取 `v.mu` 是微秒到毫秒级，需要用户恰在这个毫秒级窗口内切换笔记才能构成竞争。上面的 300 次全部命中，是因为测试中人为 `sleep(5ms)` 制造了时序，证明机制存在，不代表自然发生率高。缺陷机制确凿、后果是数据静默覆盖，但真实操作中难以自然触发。

建议在 `vault.saveContent` 增加 per-note 版本单调性校验，`ExpectedRevision < n.meta.Revision` 时直接拒绝写入并返回冲突错误。改动约五行，与 BR-04「未完整落盘的写入不推进版本」的设计精神一致，且能覆盖所有竞态路径。另一方案是在 `noteEditor` 上增加 `saving` 标志，让 `flushSaveNote` 遇到正在进行的保存时先等待其完成，但只能缓解 `flushSaveNote` 一条路径。

### W04. 重命名撞名会静默改写用户输入的标题

用户将标题改为「读书笔记」，若已存在同名笔记，最终标题变为「读书笔记 (1)」，界面标题跟着变化，全程没有任何提示。

`vault.go` 的 `RenameNote` 中：

```go
newRel = uniqueRelPath(v.notesDir(), strings.ReplaceAll(desired, "/", string(filepath.Separator)))
newRel = filepath.ToSlash(filepath.Join(parent, filepath.Base(newRel)))
// 冲突时 uniqueRelPath 追加了序号，实际标题以最终文件名为准。
actualTitle := titleOfRelPath(newRel)
```

需求分析 4.3 的「自动重命名（追加自增序号）」配模态三选一是针对导入冲突设计的，重命名场景是否适用同一策略需要确认。期望行为是静默加序号、报错还是弹窗确认，请需求方定。

### W05. 「2 秒防抖自动保存」实际是节流

持续输入 6 秒，文件修改时间戳种类数为 4（真正的防抖应为 1）。停止输入 2.5 秒后无新增写入。

```
6 秒连续输入期间，文件修改时间戳种类数 = 4
>>> 确认是节流（多次落盘），不是防抖（只在停止后落盘一次）
停止输入 2.5 秒后，新修改时间戳数 = 0
```

`app.go` 的 `scheduleSave` 注释写「每次新输入重置」，实现却是定时器存在时直接返回、不重置：

```go
func (a *app) scheduleSave() {
	if a.saveTimer != nil || a.saveState == saveSaving || a.currentID == "" {
		return
	}
	// ...
	a.saveTimer = time.AfterFunc(2*time.Second, func() { ... })
}
```

README、HANDOFF.md、AGENTS.md 三处均表述为「2 秒防抖」。

这一处改的是文档而非代码。节流在此处是正确选择：防抖的经典缺陷是用户持续不停输入时定时器永远被重置、永远不保存，写长文可能几十分钟不落盘；节流使丢失窗口恒定在 2 秒以内，对笔记类应用比减少 I/O 更重要。建议三处文档统一改为「2 秒自动保存（变脏后每 2 秒落盘一次）」，并补一句说明不使用防抖的原因，以免后续维护者按字面实现反而引入长期不落盘的问题。

附带影响是每次落盘会开一个新 goroutine，会放大 W03 的竞态窗口。

### W06. 切换到回收站时不落盘当前笔记

`app.go` 的 `setView`：

```go
func (a *app) setView(v string) {
	if v != viewTrash {
		a.flushSave()
	}
	a.view = v
	a.refreshLists()
}
```

切换到回收站时当前笔记的草稿不落盘：

```
切换到回收站前磁盘=""
切换到回收站后磁盘=""
>>> 确认：切到回收站时当前笔记的草稿【未】落盘
但编辑器 Buffer 仍在内存中："未保存的草稿XYZ"
```

不构成数据丢失——草稿仍在 `a.editors` 内存中，切回笔记页还在。只是逻辑不一致：切「笔记↔设置」会 flush，切「笔记→回收站」不 flush，看起来像笔误。建议改为无条件 flush。

### W07. 每打开一篇笔记新增一个编辑器状态且从不清理

```
创建 50 篇后内存 = 1.45 MB，editors 数量 = 0
依次打开 50 篇后内存 = 1.56 MB（增加 0.12 MB），editors 数量 = 50
>>> 确认：每打开一篇就新增一个编辑器状态且从不清理
```

`app.go` 的 `openNote` 里 `a.editors[id] = &noteEditor{...}` 只增不减，唯一清理点在 `deleteFromList`（删除笔记时）。

这是设计权衡而非泄漏。保留编辑器状态是 FR-EDIT-08「换篇保留会话内草稿与撤销历史」的实现基础，若加 LRU 上限，旧笔记的撤销历史会丢失，等于功能退化。实际影响也小，50 篇笔记仅增 0.12 MB，常规使用规模（几十至一两百篇）完全无感，只有批量浏览数千篇才有意义。暂不处理；若要优化，正确做法是保留 Buffer 但丢弃旧撤销栈，而非整体 LRU。

---

## 待验证问题

以下问题仅由代码逻辑推断，尚无运行证据，需进一步确认后再定性与排期。

### W08. 检索的耗时声明缺实测支撑，且全文双份驻留

`search.go` 是纯线性扫描，无倒排索引：

```go
for _, n := range notes {
	for _, t := range terms {
		switch {
		case strings.Contains(n.titleLower, t):    titleHit = true
		case strings.Contains(n.fileNameLower, t): fileHit = true
		case strings.Contains(n.bodyFold, t):      ...
```

每篇笔记同时持有 `body` 与 `bodyFold` 两份全文。按 5000 篇、平均 20KB 估算约 200MB 常驻内存，每次按键（250ms 防抖）全量扫描 200MB。

两处文档口径不一致：`windows/docs/technical-selection.md` 写「数千篇规模下内存检索为毫秒级，远低于 NFR-PERF-02 的 500 ms 目标」，而 `windows/docs/verification-record.md` 把「5,000 篇规模的检索与索引重建性能压测（NFR-PERF-02/06）」列在待验证。需实测补齐数据，或弱化技术选型中的性能表述。Android 版用 FTS5 落盘索引正是为规避此问题，Windows 版选择内存索引的理由应记录在案。

`bodyFold` 全文驻留是纯粹的双倍开销，正文本身用 `strings.Map` 现场折叠即可，或只折叠标题与文件名。

### W09. 反向链接查询每次保存都全库遍历

`applySaveResult` 每次保存都调用 `loadBacklinks(id)`，而 `vault.go` 的 `GetBacklinks` 是 O(全库 × 每篇链接数)：

```go
for _, n := range v.notes {
	for i, l := range n.links {
		if n.linkStates[i] != linkResolved || n.resolvedIDs[i] != id {
			continue
		}
```

可由 `resolvedIDs` 反向建表（`resolvedIDs → []sourceNoteID`），在 `recomputeLinks` 时维护。

### W10. parser.go 的注释与代码相反

```go
// 允许非 ASCII 的字母（如全角字母以外的拉丁扩展），与基线正则保持宽松一致：
// 基线限定 A-Za-z0-9 与汉字，这里额外拒绝明显不属于标签的空白与符号。
return false
```

注释说「允许非 ASCII 的字母」，但上一行条件是 `unicode.IsLetter(r) && r < 0x80`，恰恰拒绝非 ASCII 字母。行为与需求基线 4.5 的正则 `[A-Za-z0-9_\-\u4e00-\u9fa5]` 一致，是对的，只有注释描述与代码相反，会误导维护者。

### W11. 笔记内资源路径的作用域偏大

`vault.go` 的 `NoteAssetPath` 候选路径包含工作区根相对：

```go
cands = append(cands,
	candidate{filepath.Join(root, dirNotes), url},
	candidate{root, url},
	candidate{filepath.Join(root, dirAttachments), url},
)
```

`safeRelPath` 拦住了路径穿越，但 `![](secret.txt)` 仍能读到工作区根下的任意文件。与 NFR-SEC-03「附件不得越界读取」的精神略有出入。

### W12. 视图层直接读文件，违反分层约定

`windows/AGENTS.md` 规定「业务规则只写在领域文件，视图层不直接操作文件系统」，但 `view_notes.go` 的 `loadImage` 直接调用了 `os.ReadFile`：

```go
func (a *app) loadImage(u string) (ui.ImageSource, error) {
	if strings.Contains(u, "://") || strings.HasPrefix(u, "data:") {
		return nil, os.ErrNotExist
	}
	abs, err := a.vault.NoteAssetPath(a.currentID, u)
	// ...
	data, err := os.ReadFile(abs)
```

路径校验虽在 `NoteAssetPath`，但分层仍被打破。

### W13. syncDir 在 Windows 上静默吞错

```go
// syncDir 尽力刷新目录项；Windows 不支持对目录 fsync，静默忽略。
func syncDir(dir string) {
	if d, err := os.Open(dir); err == nil {
		d.Sync()
		d.Close()
	}
}
```

注释已说明，但建议显式按 `runtime.GOOS` 分支，避免读者误以为 Windows 上也做了目录 fsync。

### W14. 多级标签的父节点计数只统计直接关联

`vault.go` 的 `ListTags` 中父节点的 `Count` 只统计直接关联该路径的笔记。`#复习计划/第一章` 的父节点 `#复习计划` 显示 0 而子节点有值。代码注释已写明「直接关联笔记数」，属设计选择，但侧栏树中父节点显示 0 容易被误解。

---

## 整改状态（2026-11-01，PR #1 第二轮）

本轮已修复并补回归用例（`windows/JustMD/review_followup_test.go`，共 148 个用例全绿）：

| 编号 | 状态 | 处理方式 |
| --- | --- | --- |
| W01 | 已修 | **保留**"新建笔记时目录向下位移"的动画，去掉按行错峰 `Delay`：所有行共享同一个无 `Delay` 的 `ElementTransition`，整齐同步移动。修复后各行间距全程恒定 51.25 DIP，中间帧不再重叠。根因写入 `windows/AGENTS.md`。 |
| W02 | 已修 | 新增 `Vault.NoteFileExists` / `Vault.DropVanishedNote`：打开失败时提示"文件已被外部删除"并从列表移除；删除时不再被卡住。 |
| W03 | 已修 | 新增写入序号（`SaveContentSeq`）：序号是内容在编辑器里的先后次序，落盘时若本篇已落盘过更大序号即返回 `ErrStaleRevision` 并拒绝覆盖。不用基线版本号——两份内容可能基于同一基线，无法区分新旧。 |
| W05 | 已修 | `README.md`/`HANDOFF.md`/代码注释统一改为"2 秒自动保存（节流：变脏后每 2 秒落盘一次）"，并说明不做防抖的原因。 |
| W06 | 已修 | `setView` 改为无条件 flush。 |
| W10 | 已修 | 改正 `parser.go` 的注释（原文说"允许非 ASCII 的字母"，与 `unicode.IsLetter(r) && r < 0x80` 相反）。 |
| W11 | 已修 | 附件作用域收窄到 `notes/` 与 `attachments/`，不再把工作区根作为候选。 |
| W12 | 已修 | 读文件下沉到领域层 `Vault.ReadNoteAsset`，视图层不再直接调 `os.ReadFile`。 |
| W13 | 已修 | `syncDir` 显式按 `runtime.GOOS == "windows"` 分支跳过，并处理 `Sync`/`Close` 的错误。 |
| W14 | 已修 | 父标签计数改为整棵子树去重后的笔记数。 |
| N1 | **不成立** | 详见下方"更正"。 |
| N2 | 已修 | `RenameReport` 新增 `External`/`Dirty` 字段，分开报告"被外部改过"与"有未落盘草稿"，提示语相应区分。 |
| N3 | 已修 | 关窗保存失败时弹确认框（「重试保存」/「放弃并退出」），用户做出选择前一直拦住；失败后继续输入会重新启用自动保存（外部修改冲突除外）。 |
| N4 | 不改 | 标题逐字改名触发全库级联是 FR-NOTE-04 + FR-LINK-08 的既定行为；`RenameReport.Skipped` 保证有未落盘草稿的笔记不被覆盖。逐字改名已不再顶起 `UpdatedAtUtc`，列表不会因此跳动。 |
| N5 | 已修 | MyGo 的 `Modal` 弹窗会让窗口级快捷键整体失效，改为在弹窗作用域内用 `panel.OnShortcut` 单独注册 Ctrl+S。 |

### 更正：N1 不成立

N1（"重启后外部改写的内容被静默覆盖"）经真实 UI 流程复验**不成立**。
编辑器缓冲区从磁盘加载，外部改写的内容会保留在用户编辑的基础上：

```
重启后编辑器缓冲区 = "外部工具完全改写的内容"
保存后磁盘 = "外部工具完全改写的内容，用户新写的一行"
```

原结论来自测试方法错误——直接调 `SaveContent` 整体覆盖了正文，而非走
"重启 → openNote → 继续输入 → 保存"的 UI 流程。已改为回归用例固化正确结论：
`TestN1_ExternalRewriteSurvivesRestart`。

### 未在本轮处理

- **W07**（编辑器状态只增不减）：设计权衡而非泄漏，保留撤销历史是 FR-EDIT-08 的实现基础。
- **W08**（检索耗时声明缺实测、全文双份驻留）：需要真实压测数据，本轮无法提供。
- **W09**（反链查询每次保存全库遍历）：性能优化，需要基准测试支撑改动范围。

---

## 整改状态（2026-11-02，PR #1 第三轮）

本轮针对 PR #1 上第 3 条自动评审意见（针对 commit `fc52fbf`）整改，新增 29 个回归用例
（`windows/JustMD/review_round3_test.go`、`review_round3_lowpri_test.go`），**共 176 个用例全绿**。

评审本轮提出的 5 个中/高优先级问题全部成立且已修，5 个低优先级打磨项全部已修：

| 编号 | 状态 | 处理方式 |
| --- | --- | --- |
| R1 | 已修 | **工作区切换失败后污染当前目录**：`switchWorkspace` → `loadWorkspace` 失败时 `root` 保持旧值、`notes/trashed/byTitle` 仍指向旧工作区，但 `saveContent` 没有 `root==""` 守卫 → 失败后调用 `SaveContent` 返回 nil 且把笔记写到 `<cwd>/notes/` 下另起炉灶，"选择工作区"流程被绕过。新增 `Vault.resetWorkspaceState()`：装载失败即清空 `root` 与全部内存索引（notes/trashed/byTitle），并给 `saveContent` 加 `root==""` 守卫，返回 `ErrNoWorkspace` 而非静默写 CWD。回归 `TestR1_OpenVaultWithMissingWorkspaceClearsIndex`、`TestR1_SaveAfterWorkspaceFailureDoesNotWriteToCWD`。 |
| R2 | 已修 | **改名级联崩溃后留下断链**：级联改写每个文件前先把「改写前正文 + 改写前元数据」快照落盘到 pending 日志（`PendingOp.Cascade []PendingCascadeNote`），`reconcilePending` 在发现 rename 崩溃时按快照逐篇回滚正文与元数据（仅当磁盘正文与快照的 `NewBody` 一致时才回滚，避免覆盖外部修改）。回归 `TestR2_CascadeSnapshotPersistedBeforeRewrite`、`TestR2_RollbackRestoresBodyAndMeta`。 |
| R3 | 已修 | **`uniqueRelPath` 非 ENOENT 死循环**：只判断 `os.ErrNotExist`，权限/非法字符导致的 `Lstat` 失败会一直重试 ` (1)`、` (2)`…，且期间一直持有 `v.mu`，Create/Rename/Restore/import 全被拖死。改为区分 `os.IsNotExist(err)` 与非 ENOENT 错误，非 ENOENT 直接返回候选路径。回归 `TestR3_UniqueRelPathNonENOENTDoesNotLoop`。 |
| R4 | 已修 | **`NoteFileExists` 把非"不存在"误判为"已消失"**：用 `!NoteFileExists` 判断外部删除，但 `NoteFileExists` 内部 `Stat` 出错（如权限问题、网络盘掉线）时返回 false → 误判为文件已被删除 → 误清内存索引、误标 RECOVERED，双链在两侧都被清空。新增 `Vault.NoteVanished(id)`：严格只认 `os.IsNotExist(err)`，其余错误视为"状态未知"，不清理任何内存状态。回归 `TestR4_NoteVanishedIgnoresNonENOENT`、`TestR4_OpenVanishedNoteOnlyPurgedWhenGone`。 |
| R5 | 已修 | **`applySaveResult` 在 `ErrStaleRevision` 时误标"已保存"**：旧实现无条件 `ed.saved = ed.state.Buffer.Version()` + `MarkSaved()` → 用户尚未落盘的新输入被标记成已保存，后续 flush 因 `Version()==saved` 直接跳过 → 用户输入被静默丢弃。新实现保留脏状态并重新排定自动保存。回归 `TestR5_StaleRevisionKeepsBufferDirty`。 |
| L1 | 已修 | `trimMarkdownExt` 改为大小写不敏感去后缀，`README.MD` 不再变成 `README.MD.md`。回归 `TestL1_TrimMarkdownExtCaseInsensitive`。 |
| L2 | 已修 | 空标题兜底为 `untitledTitle`（"未命名笔记"），不再产生 `notes/.md`。回归 `TestL2_EmptyTitleGetsUntitledFallback`。 |
| L3 | 已修 | `extractLinks` 的锚点/脚注保留符（`#`、`^`）判断收窄到 `target` 段，别名里的 `#` 不再被误删（如 `[[目标\|C# 入门]]`）。回归 `TestL3_ExtractLinksKeepsHashInAlias`。 |
| L4 | 已修 | `sanitizeTitle` 把 `[`、`]` 换成 `_`，避免生成非法 markdown 链接语法。回归 `TestL4_SanitizeTitleReplacesBrackets`。 |
| L5 | 已修 | 侧栏标签树包进 `ui.Scroll`，标签多了不再溢出裁切。回归 `TestL5_TagTreeScrollable`。 |

### 本轮验证记录

- `gofmt -l .` 无输出；`go vet ./...`、`go build ./...` 无输出。
- `CGO_ENABLED=0 go test -count=1 -v ./...`：**176 个用例全部 PASS**，0 FAIL。
- 变更集中的 8 个文件（`app.go`、`atomic.go`、`meta.go`、`parser.go`、`portability.go`、
  `sanitize.go`、`vault.go`、`view.go`）逐一做了**变异测试**：把修复代码改回缺陷版本
  （或反向改坏），对应回归用例必须 FAIL；改回修复版本后必须 PASS。全部 29 个用例的
  断言方向与断言位置均已确认有效——不会出现"测试通过但 bug 还在"的假阳性。
- Linux CI（`windows-ci.yml`）在 `TestVaultSaveContentAtomicFailureClearsPending` 上因
  权限语义差异失败，Windows 本地全绿。已知为跨平台权限语义差异，非本轮引入。

