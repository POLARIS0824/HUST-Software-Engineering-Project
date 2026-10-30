# Looma 原型研究结果

2026-10-28。本文基于 `docs/software-design.md` 的现行修正记录，不把需求文档的早期技术假设当作已实现事实。

## 建议保留原生界面和混合搜索，重新选择编辑器基础

Compose、Room 3 和 bundled SQLite 的组合已在 Android 模拟器上完成构建与查询。Markdown 文件作为正文事实源、数据库作为可重建索引的方向可以继续。

严格要求原文保真时，建议下一轮采用 CodeMirror 6 的源文本模型，按需增加隐藏标记和渲染装饰。Vditor 3.9.9 能运行 IR，但本次发现参考式链接的标题丢失，以及禁用 DOM storage 时的启动错误。不能直接把它当作满足全部编辑器需求的成品。

这是可丢弃的研究代码。界面数据保存在内存会话中，原生文件探针只操作独立临时文件。没有实现产品级自动保存、恢复协议、SAF 导入导出或完整 WikiLink 编辑。

## 先看三种界面

打开 [交互式截图比较页](compare.html)，可切换列表和编辑页，也可点击查看原图。截图来自同一个 Pixel 10 模拟器、同一组 6 篇中文笔记。列表在顶部，编辑页都打开 TCP 拥塞控制。系统时间、网络图标和电量来自模拟器，未修图。

| 方向 | 具体设计选择 | 适用场景 | 取舍 |
| --- | --- | --- | --- |
| A 深色工作台 | 深色表面、目录图标、较紧凑行距、正文标签 | 技术资料和知识库 | 正文与元数据密度较高 |
| B 纸张笔记 | 暖白纸面、琥珀色操作、弱化列表图标、显示目录与时间 | 随手记和长时间阅读 | 首页对知识连接的提示较少 |
| C 灵感手册 | M3 Expressive 主题、强调标题、分组表面、较大的置顶行 | Android 原生应用和快速记录 | 首屏能看到的笔记更少 |

我的产品判断是优先 C 的列表，也可以组合 B 的阅读配色。这是设计建议，由用户选择，不是实验能证明的优劣。

列表首要任务是找回笔记，其次是新建，标签与链接放在辅助导航。编辑页首要任务是连续写作，正文占据主要区域。色彩来源分别为 Material 默认深色、官方 Material 色彩工具生成的琥珀色、Expressive 默认浅色。纸张方案的种子色是自选设计决定，角色色由 `generate-palette.mjs` 生成。

M3 组件使用 `MaterialExpressiveTheme`、Expressive motion、`ShortNavigationBar` 和带标签的记录入口。Google Sans Flex 用于原生字级，中文回退到系统字体。编辑区是可运行的 Vditor IR 样式实验，六张风格截图不代表已完成 CodeMirror 的完整 IR。

Obsidian 和 Apple Notes 是用户指定的设计方向。本次没有复制它们的界面。Mobbin 返回套餐限制，没有使用其图片作为设计证据。公开产品参考见 [Obsidian Mobile](https://obsidian.md/mobile)。

## 技术验证矩阵

| 文档假设或需求 | 本次证据 | 判断 |
| --- | --- | --- |
| Compose 原生界面加局部 WebView | APK 构建成功，三方案切换、搜索、笔记打开、系统返回均实际操作 | 最小集成可行 |
| Room 3.0.3 加 sqlite-bundled 2.7.1 | KSP 生成数据库，真实连接返回 SQLite 3.50.1，生成 FTS5 trigram 表 | 最小集成可行 |
| 中文一字、两字和多字子串搜索 | 12 个固定查询的转义 LIKE 结果均与独立 Kotlin 字符串扫描相符。三字“数据库”可用 MATCH，一字“中”、两字“中文”的直接 MATCH 不返回匹配 | 现行混合查询方向正确 |
| 百分号、下划线、反斜杠和引号 | 探针包含字面字符及干扰样例，记录 SQL、绑定参数、预期 ID 和实际 ID | 小样本字面语义可行 |
| 关闭 DOM storage 的 Vditor | `cache.enable=false` 时仍发生 `localStorage.getItem` 空引用。日志与固定 bundle 代码相符 | 文档配置不能直接采用 |
| 开启 DOM storage 的 Vditor IR | 本地资源运行，8 个语法样例均完成探针。7 个未编辑样例完全一致，参考式链接样例丢失标题 | 不满足严格原文保真门槛 |
| CodeMirror 源文档对照 | DOM storage 关闭，8 个样例的载入、追加编辑、撤销均保留原文 | 源文本模型更适合作为下一轮基础 |
| 撤销 100 步、按词打包 | 固定 Vditor bundle 存在 `stackSize=50`。探针使用固定版本内部撤销入口，并非稳定公开 API | 不能按配置宣称满足 |
| 现有文件原子替换 | 同目录 `Os.rename` 和 `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)` 都回读成功，目录 `fsync` 返回成功 | 仅证明本模拟器上的一次正常路径 |
| 文件、JSON 元数据和数据库共同恢复 | 未实现 pending 操作记录或崩溃恢复协议 | 尚未验证 |
| 完整 WikiLink 与源位置映射 | 能看反向引用面板；正文 `[[...]]` 保持原样，没有扩展成可点击 IR 链接 | 尚未验证编辑器扩展 |
| 大规模性能、中文 IME、TalkBack | 未运行 5,000 篇、长文持续输入、真实中文候选、屏幕朗读 | 尚未验收 |

UI 搜索使用同一份内存 fixture 的字符串扫描。Room 查询是独立技术探针，尚未把数据库连接成产品的数据链路。CodeMirror 只做了标题和加粗的装饰草图，不包含完整 IR、表格、公式交互或 WikiLink 映射。

## 原文丢失有具体反例

Vditor 输入中包含以下引用定义。

```markdown
[说明][ref]

[ref]: https://example.com "标题"
```

未进行编辑就调用 `getValue()`，输出已经变为以下内容。

```markdown
[说明][ref]

[ref]: https://example.com
```

追加编辑后，参考定义还会被移动到新增段落之后。完整输入、未编辑输出、编辑后输出和撤销后输出保存在 [Vditor 原始结果](evidence/vditor-probes.json)。这个反例足以否定该版本的无损承诺，不说明全部未知语法都会丢失。本次自定义块和 HTML 属性样例保持原样。

[CodeMirror 原始结果](evidence/codemirror-probes.json) 对同一批源字符串进行文档载入、末尾追加、撤销。它的优势来自直接保存源文本。这个探针没有证明移动端 IR 的操作体验已经完成，也没有证明中文输入法兼容性。

## 搜索先保持正确，再决定是否需要更多加速

保留文档的“长串 trigram 候选、短串转义 LIKE、最后核对原文”的方向。`%` 和 `_` 应当按普通字符处理。SQLite 官方说明，trigram 对不足三个 Unicode 字符的 MATCH 不产生结果，带 ESCAPE 的 LIKE 不能使用 trigram 优化。因此中文短词能查到，与短词受到倒排索引加速，是不同的结论。见 [SQLite FTS5](https://sqlite.org/fts5.html)。

本次探针只有 5 条小文档，没有计时，没有资格推出 5,000 篇检索时延。建议正式阶段先做 Kotlin 扫描的独立正确性基准，再用同一语料测短词和长词。没有证据需要立即引入 JNI 搜索引擎、中文分词服务或语义检索。

## 存储方案仍需要恢复协议

普通正常路径的替换和目录同步成功，不能推出任意进程中断或断电时安全。文件、开放 JSON 元数据和 SQLite 之间没有跨介质事务。文档的操作记录、哈希与版本号需要单独实现和注入故障验证。

本次探针使用应用专属外部存储中的独立 UUID 目录。它没有触及用户知识库。该目录会随应用卸载被清除。应用专属目录不能作为独立备份，SAF 导出的用户副本仍然需要保留。见 [Android 应用专属存储](https://developer.android.com/training/data-storage/app-specific)。

`ATOMIC_MOVE` 对既有目标的替换由具体实现决定，本设备通过的结果不能推广到所有机型或 SAF provider。见 [Files.move](https://developer.android.com/reference/java/nio/file/Files)。

## Android 集成需要保留的发现

- 本地 Vditor 资源必须保留其 `dist/` 目录结构，语言包和 Lute 会按该相对路径请求。
- 原型将 DOM storage 开关作为显式实验条件。Vditor 笔记缓存始终关闭。CodeMirror 对照不需要 DOM storage。
- WebView 只加载 appassets 的 assets 路径，没有 INTERNET 权限，禁用 file 和 content 访问，禁止混合内容。消息桥使用来源限定的 `WebMessageListener`，并检查主框架与消息大小。
- Vditor 图标通过同源 XHR 加载，所以 CSP 的 `connect-src` 允许 self。其动态内联图标脚本仍会被严格 script CSP 拦截。原生工具栏不依赖这些图标，这一部分尚未完成适配。
- 恶意图片样例的远程图片和内联事件被 CSP 拦截，JS 执行标志没有置位。这只是一个样例，不能当作完整安全审计。
- WebView 的初始 CSS 视口高度曾为零。最终适配使用原生测量高度，并在尺寸变化时更新。深色状态栏还需要在窗口获得焦点后设置图标明暗。

本地消息桥和资源隔离的依据见 [Android 本地内容指南](https://developer.android.com/develop/ui/views/layout/webapps/load-local-content) 与 [原生桥风险说明](https://developer.android.com/privacy-and-security/risks/insecure-webview-native-bridges)。

控制台日志是多轮运行的累积记录，包含早期 CSP 配置错误，不能单独证明最终策略的全部行为。代码中的最终策略和带原始字符串的探针结果需要一起读取。

独立 gpt-6-sol 证据审查发现编辑页 H1/H2 装饰被裁切，最终已去除装饰并重拍三张编辑页。补充驱动检查复现了双链切换后 WebView 空白的问题，按笔记 ID 重建视图后，同一步骤已显示正确的握手正文。六张主要风格截图都已在该最终构建上重新采集。列表的悬浮记录按钮可能遮住当前视口中较低位置的部分行，列表可继续滚动。正式设计应继续调整这一处布局。

新建、ASCII 文本输入、返回列表再打开的会话保留已实际观察。这个结果不能推出后台保存或真实中文候选输入可靠。驱动记录见 [UI observations](evidence/ui-observations.md)，未解决问题集中在 [issues.md](issues.md)。

## 固定构建组合与证据

| 项目 | 实际组合 |
| --- | --- |
| CLI | Android CLI 1.1.16717390 |
| AGP、Gradle | 9.1.1、9.3.1 |
| 主机 JDK、字节码目标 | OpenJDK 21.0.2、Java 17 |
| Kotlin Compose 与序列化插件 | 2.3.20，Android 使用 AGP 内置 Kotlin |
| KSP | 2.3.12 |
| Compose BOM、Material3 | 2026.03.01、1.5.0-beta02 |
| Room、bundled SQLite | 3.0.3、2.7.1 |
| compileSdk、targetSdk、minSdk | 37.0、37、26 |
| Webkit、Vditor | 1.10.0、3.9.9 |
| CodeMirror state、view、markdown、commands | 6.5.2、6.38.1、6.3.4、6.8.1 |
| 实际设备 | 现有 Pixel_10 AVD，x86_64，Android API 37，WebView 156.0.8078.47 |

依赖来源见 [Room 3 发布说明](https://developer.android.com/jetpack/androidx/releases/room3)、[SQLite 发布说明](https://developer.android.com/jetpack/androidx/releases/sqlite)、[Material3 发布说明](https://developer.android.com/jetpack/androidx/releases/compose-material3)。这些版本之外的组合没有推断为兼容。

[汇总 JSON](evidence/summary.json) 链接到探针结果和最终 APK 哈希。[原生探针](evidence/native-probes.json) 记录 DDL、参数与文件回读。[固定资源清单](assets-lock.json) 记录 Vditor npm integrity 和 JS 哈希。最终构建输出在 [build.log](evidence/build.log)。

## 下一轮应验证什么

先根据用户选择收敛 UI。技术上优先构建 CodeMirror 的完整源位置映射和组合输入原型，再验证真实中文输入、光标、粘贴、撤销和键盘避让。若团队更重视现成 IR 而接受 Markdown 规范化，可继续 Vditor，但必须显式更改保真约束或修复固定版本，不能继续承诺无损。

保存状态机与恢复协议应先于大规模功能开发。随后用真实 5,000 篇语料验证搜索、重建、内存和长文输入。当前版本没有经过 API 26、横屏、200% 字号、TalkBack、真实断电或设备实机验收。
