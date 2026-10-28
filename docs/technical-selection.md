# JustMD 技术选型

2026-10-28。根据两轮原型结果，确定采用纯 Kotlin Compose，编辑 Markdown 源码，使用独立的原生阅读页。即时渲染退出本版范围。

## 选择原生源码编辑

编辑器直接修改源文本，阅读器只接收快照，不将排版结果写回正文。这条路径能保留未知语法，也省去了 WebView 生命周期、JavaScript 桥接和源码与 DOM 的位置映射。

原型使用 `BasicTextField`、`TextFieldState` 和 `undoState`，已经跑通输入、加粗、撤销重做、阅读切换和换篇返回。接受独立阅读模式后，没有必要为编辑器引入另一套 Web 技术栈。

## 技术组合

| 部分 | 选择 | 当前边界 |
|---|---|---|
| 页面与编辑 | Kotlin、Jetpack Compose、`BasicTextField(TextFieldState)` | 原型可运行，正式保存尚未接入 |
| 阅读 | `multiplatform-markdown-renderer-m3:0.39.2` | 原生 Compose 渲染，完整语法与附件尚未验收 |
| 视觉 | 方案 B、Material 3 Expressive、Dynamic Color | Android 12 起跟随系统色板，旧系统使用静态回退 |
| 正文存储 | UTF-8 Markdown 文件，开放 JSON 元数据 | 保留既有设计，提交与恢复协议待实现 |
| 派生索引 | Room 3.0.3、`sqlite-bundled:2.7.1`、FTS5 | 最小建表与查询已验证，尚未接入编辑链路 |
| 并发与外部文件 | Kotlin Coroutines、Flow、SAF | 用于后台读写和导入导出，产品流程待实现 |

这些版本是原型实测组合，不代表最新版本。完整工具链见 [第二轮原型报告](../prototypes/justmd-editor-comparison/research-report.md#构建环境与边界)。

## 原型支持这个决定

原生编辑端口通过 8 组源码探针，覆盖未知语法、引用链接、Unicode、换行规范化和长文。实际 UI 操作验证了输入、格式化、撤销与阅读切换。系统色板由蓝切绿后，浅色和深色主题均随之更新。原生 APK 不含 CodeMirror JS 资源或 AndroidX WebKit 类描述符。

CodeMirror 也完成了这些基础操作，并提供语法高亮与编辑扩展，但需要维护 WebView、桥接协议和 CSS。Vditor 3.9.9 则在第一轮出现引用链接标题丢失，不满足本项目的源文本保真要求。因此两者均不进入当前正式方案。原型及历史证据保留供对照。

## 正式实现仍需补齐

原生选区在收起键盘进入阅读时会折叠，必须修复。中文输入法候选、100 步撤销、进程恢复、可靠保存、搜索性能、TalkBack 和完整 Markdown 阅读能力仍待验证。数学公式预览保留为低优先级扩展，不沿用 KaTeX 依赖。

当前已有可运行的研究原型，尚无完整产品。约 5 万字符的源码探针通过，不代表长文输入性能达标。具体验证范围见 [核实记录](verification-record.md)，正式目标与设计分别见 [需求分析](requirements-analysis.md) 和 [软件设计](software-design.md)。
