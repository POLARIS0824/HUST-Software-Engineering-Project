# JustMD 技术验证记录

2026-10-28 更新。本文记录两轮研究原型的结果，不替代正式产品验收。当前已选择纯 Kotlin Compose 源码编辑与独立阅读，理由见 [技术选型](technical-selection.md)。需求目标和待实现设计分别见 [需求分析](requirements-analysis.md) 与 [软件设计](software-design.md)。

## 1. 状态与证据范围

| 状态 | 含义 |
|---|---|
| 已决策 | 用户已确认的产品或技术选择，不表示已经实现 |
| 原型已验证 | 在记录的构建、设备与样例范围内观察到结果 |
| 部分验证 | 仅有最小探针或部分操作通过，仍有缺口 |
| 待实现或待验收 | 没有对应产品实现或运行证据 |
| 历史方案 | 已退出当前选型，只保留对照证据 |

第一轮原型位于 `prototypes/justmd-research/`，包含界面方向、Vditor、CodeMirror 对照、Room 查询和文件操作探针。第二轮位于 `prototypes/justmd-editor-comparison/`，对比 CodeMirror 与纯 Compose 编辑。两个目录都是可丢弃的研究代码。

实际环境为 Windows、JDK 21、AGP 9.1.1、Gradle 9.3.1、Compose 插件 2.3.20，compileSdk 与 targetSdk 为 37，minSdk 为 26。运行设备为 Pixel_10 AVD、API 37，未覆盖低版本系统或真机。第二轮环境详情见 [device-environment.json](../prototypes/justmd-editor-comparison/evidence/device-environment.json)。

## 2. 当前产品决策

| 编号 | 决策 | 状态 |
|---|---|---|
| VER-PROD-01 | 首发为原生 Android，编辑与阅读均使用 Compose | 已决策 |
| VER-PROD-02 | 主要用户为高校学生，次要用户为知识工作者 | 已决策 |
| VER-PROD-03 | Markdown 文件是正文事实源，JSON 保存稳定 ID 与非派生元数据，Room 只保存派生索引 | 已决策，完整链路待实现 |
| VER-PROD-04 | 本版不含账号、云同步或多人协作 | 已决策 |
| VER-PROD-05 | 本版不含第三方插件和内置 AI | 已决策 |

原型提供本次进程内的编辑会话，没有正式保存、元数据恢复或完整索引集成。不能将可运行原型称为已完成产品。

## 3. 编辑方案与实际结果

| 编号 | 当前结论 | 状态与证据 |
|---|---|---|
| VER-EDIT-01 | 编辑显示源码，阅读独立排版，不要求即时渲染 | 已决策，替代原 IR 要求 |
| VER-EDIT-02 | 不自研原生即时渲染，不建立源码与 DOM 映射 | 已决策，不表示 Compose 源码编辑不可行 |
| VER-EDIT-03 | 使用 BasicTextField、TextFieldState、undoState 和原生 Compose Markdown 阅读器 | 原型构建、源码探针及基础 UI 操作已验证 |
| VER-EDIT-04 | Vditor 与 CodeMirror 不进入正式方案 | 历史对照结果见下文 |

原生与 CodeMirror 编辑端口各通过 8 组源码探针，每组 11 个布尔检查均为 true。样例包含引用链接、未知语法、HTML、中文、emoji、组合字符、GFM、换行和约 5 万字符长文。检查覆盖加载、反向 UTF-16 选区、加粗、撤销重做、阅读快照、换篇返回和追加文本。CRLF 与 CR 在入口规范化为 LF，不宣称逐字节保留换行格式。

实际 UI 通过点击 EditText、ADB 键盘事件输入 `UI42`、点击原生工具栏以及阅读切换验证文本与历史。它不是拼音候选输入测试。原生 UI 的选区在进入阅读时由 `[116,118]` 折叠为 `[118,118]`，因此选区保留尚未通过。

证据集中在以下文件。

- [原生源码探针](../prototypes/justmd-editor-comparison/evidence/native-editor-probes.json) 与 [CodeMirror 源码探针](../prototypes/justmd-editor-comparison/evidence/cm-editor-probes.json)。
- [UI 操作快照与检查结果](../prototypes/justmd-editor-comparison/evidence/ui-journeys.json)。
- [原生选区问题 E03](../prototypes/justmd-editor-comparison/issues.md#e03-native-ui-selection-collapses-when-entering-reading)。
- [APK 检查](../prototypes/justmd-editor-comparison/evidence/apk-inspection.json)。原生 APK 没有 editor JS、CodeMirrorPort 或 AndroidX WebKit 类描述符。

第一轮 Vditor 3.9.9 在 8 个未编辑样例中保留了 7 个，引用链接定义丢失标题。禁用 DOM storage 时还出现启动错误。第二轮 CodeMirror 基础操作通过，但需要 WebView、JS 桥接与 CSS 适配，绘制问题仅在指定模拟器上修复。这些结果支持当前原生源码方案，不构成两种编辑器的完整能力或性能排名。

## 4. 界面与动态主题

用户选定方案 B，组件优先使用 Material Expressive。原型实际使用 MaterialExpressiveTheme、连接式 ToggleButton、HorizontalFloatingToolbar 和 Extended FAB。Markdown 阅读库为 `multiplatform-markdown-renderer-m3:0.39.2`，实际 Foundation 解析版本为 1.12.0。

API 37 模拟器的系统色板由蓝色切为绿色后，两条路线都随系统更新。绿色浅色 primary 为 `#326A4D`，深色为 `#A4D1B4`。系统设置和夜间模式在实验后已恢复，恢复后的默认蓝色色值略有变化，未宣称资源逐色还原。

证据见 [主题记录](../prototypes/justmd-editor-comparison/research-report.md#dynamic-color-与截图) 与 [截图比较页](../prototypes/justmd-editor-comparison/comparison.html)。Android 12 以下的静态回退、TalkBack、大字号、横屏和大屏尚未验收。

## 5. 存储与搜索

| 编号 | 当前结论 | 状态 |
|---|---|---|
| VER-DATA-01 | 正文存为 Markdown，数据库只作索引 | 已决策，产品保存尚未实现 |
| VER-DATA-02 | 默认应用专属目录，SAF 导入导出。卸载清除专属目录，独立备份需导出 | 设计已确定，SAF 产品流程待实现 |
| VER-DATA-03 | 同目录替换和目录 fsync 的一次正常路径成功 | 部分验证，不证明掉电恢复或文件与 JSON 的联合提交 |
| VER-SEARCH-01 | 标题、文件名与完整源码均可搜索，普通搜索使用连续文本匹配 | 已决策，正式搜索链路待实现 |
| VER-SEARCH-02 | Room 3.0.3 与 sqlite-bundled 2.7.1 可以构建、建立 FTS5 表并查询 | 最小集成已验证，实际 SQLite 为 3.50.1 |
| VER-SEARCH-03 | trigram 用于至少 3 个 Unicode 码点的候选，中文短词走转义 LIKE，最终核对原文 | 小样本语义已验证，5,000 篇性能待测 |

第一轮数据库探针只有 5 条小文档，执行 12 个固定查询。中文单字、两字、多字及字面 `%`、`_`、反斜杠和引号的 LIKE 结果与独立 Kotlin 匹配结果一致。直接 MATCH 不能把一字或两字查询当作 trigram 命中。这里没有计时成绩。

原型 UI 搜索使用固定内存样例，未接入 Room，第二轮也没有将新编辑内容接入全库搜索。原始 SQL、参数与文件回读见 [第一轮 native-probes.json](../prototypes/justmd-research/evidence/native-probes.json)，解释与边界见 [第一轮原型报告](../prototypes/justmd-research/research-report.md#技术验证矩阵)。原 Room 2.6.x 原生提供 FTS5 的说法已撤回。

## 6. 标签、链接与内容安全

| 编号 | 当前结论 | 状态 |
|---|---|---|
| VER-LINK-01 | 支持 `[[目标]]` 与别名，目标状态为 resolved、unresolved 或 ambiguous | 需求和设计，完整解析与原生阅读扩展待实现 |
| VER-LINK-02 | Backlinks 来自可重建索引，不通过修改正文补全引用 | 设计已确定，端到端链路待实现 |
| VER-WEB-01 | 原 WebViewAssetLoader 与 JS 沙箱配置退出正式方案 | 历史方案，旧编号仅保留追踪 |
| VER-WEB-02 | 外链改由原生阅读回调校验 http、https 后交给系统浏览器 | 设计约束，链接回调与恶意样例待验收 |

阅读器不得执行脚本，附件不得越界读取或自动下载远程内容。这些要求对应 NFR-SEC-02~04。原型保留 WikiLink 字面文本，不能把列表导航视为正文链接已实现，也不能把没有 JS 依赖视为完整安全审计。

## 7. 源码参考项目

本轮只审阅固定版本源码，没有构建或运行这些参考应用。

| 项目 | 已检查的实现 |
|---|---|
| MarkReader | Compose 源码编辑。编辑预览有 Markwon 与 TextView，通用阅读仍有 WebView 路径 |
| Markleaf | Compose 源码编辑，将 CommonMark AST 适配成 Compose 预览组件 |
| Kori | state-based BasicTextField 与 Expressive 动态主题，Android Markdown 阅读仍使用 WebView |

仓库地址、固定提交与文件位置见 [源码核查记录](../prototypes/justmd-editor-comparison/research/native-projects.md)。旧文档中的其他竞品性能、价格和行为断言不作为当前选型证据。

## 8. 仍需执行的验证

保留原 Spike 编号，内容按当前原生方案更新。

| 编号 | 内容 | 当前状态与完成条件 |
|---|---|---|
| Spike 1 | 原生编辑、阅读、撤销与状态交接 | 基础探针与 UI 已通过。修复选区折叠，补测 100 步撤销、连续输入分组、长选区和粘贴 |
| Spike 2 | 真机中文 IME | 待验收。验证候选词、组合输入、工具栏、换篇、长按选择和剪贴板，不用 ADB ASCII 代替 |
| Spike 3 | 源码保真与阅读兼容 | 8 组源码探针通过。扩充语料，逐项验收阅读语法、附件和失败回退，数学公式单列为低优先级 |
| Spike 4 | 原生 WikiLink 与 Backlinks | 待实现。验证阅读回调、同名消解、缺失目标、重命名与源位置映射 |
| Spike 5 | Room 搜索与规模测试 | 最小查询通过。接入真实工作区后，与原文匹配器对照并测 5,000 篇性能 |
| Spike 6 | 保存、故障与恢复 | 正常文件操作局部通过。实现版本回执、元数据协议和退出等待，注入乱序、满盘、重建和进程终止故障 |
| Spike 7 | 原生内容与附件安全 | 待验收。覆盖恶意 Markdown、路径穿越、协议限制、无浏览器和离线运行 |

## 9. 性能仍是验收目标

以下目标在同级真机的发布构建上测量。每类至少 20 次，报告中位数与 P95，并记录设备、系统、语料和缓存状态。当前没有满足这些目标的测量记录。

| 编号 | 目标 |
|---|---|
| VER-PERF-01 | 冷启动交互就绪 P95 不超过 2 秒，中位数不超过 1.5 秒 |
| VER-PERF-02 | 5,000 篇搜索首屏 P95 不超过 500 毫秒，中位数不超过 300 毫秒，短词单列 |
| VER-PERF-03 | 普通笔记源码与输入连接就绪 P95 不超过 500 毫秒，中位数不超过 350 毫秒 |
| VER-PERF-04 | 至少 5 万字符的连续中文输入延迟 P95 不超过 50 毫秒，帧率至少 55 FPS，30 分钟无 ANR |
| VER-PERF-05 | 保存主线程占用 P95 不超过 50 毫秒，峰值不超过 100 毫秒 |
| VER-PERF-06 | 5,000 篇完整索引重建不超过 60 秒 |

第二轮独立证据审查见 [evidence-review.md](../prototypes/justmd-editor-comparison/evidence/evidence-review.md)。审查确认的是有边界的原型记录，选区、设备覆盖与产品验收缺口仍然存在。
