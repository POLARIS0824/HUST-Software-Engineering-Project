# JustMD Windows 技术选型

2026-10-29。小组确定 Windows 版采用 [MyGo](https://mygo.egoist.dev/)（Go 后端 + 系统 WebView 前端的桌面框架），需求基线沿用 `android/docs/requirements-analysis.md`。本文说明平台差异与对应的设计调整，不重复 Android 版已定的业务规则。

## 为什么是 MyGo

- 队友指定试用 MyGo。框架活跃（v0.7.3，纯 Go、无 cgo），提供类型化 IPC：后端导出方法经 `mygo generate` 生成 TypeScript 客户端，前端调用即得到带文档注释的类型签名，与小组"接口即文档"的习惯一致。
- Windows 端渲染使用系统自带 WebView2，无需打包浏览器；打包产物为单个 exe 与 NSIS 安装包。
- 业务逻辑全部在 Go 侧实现（文件 I/O、解析器、索引、导入导出），前端只做展示与交互，与 Android 版"业务逻辑不依赖 DOM"的可维护性要求（NFR-MAINT-01）方向一致。

## 与 Android 版的平台差异

| 差异点 | Android 版 | Windows 版 | 依据 |
|---|---|---|---|
| 渲染与阅读 | 纯 Compose 原生渲染，不使用 WebView | 整个界面运行在 WebView2 中，但渲染 Markdown 时经 DOMPurify 消毒，剔除脚本、iframe 与事件属性，不执行正文中的任何 JavaScript | NFR-SEC-02 的等价强度：Android 版靠"不用 WebView"保证，Windows 版靠"消毒 + 不执行"保证 |
| 派生索引 | Room + SQLite FTS5 trigram | 内存派生索引（折叠正文 + 大小写折叠标题/文件名），启动全量扫描、保存增量更新，提供一键重建 | BR-02 派生性不变；实现形态属于平台自由度。数千篇规模下内存检索为毫秒级，远低于 NFR-PERF-02 的 500 ms 目标 |
| 工作区访问 | 应用专属外部目录 + SAF | 用户自选文件夹（原生对话框），默认 `文档/JustMDWorkspace`，配置持久化在用户数据目录 | 桌面端无沙箱限制，直接使用用户可见目录更符合"数据随拷随用" |
| 编辑器 | Compose `BasicTextField` | 原生 `<textarea>` 源码编辑（IME、撤销重做由 WebView2 原生支持） | FR-EDIT-01 源码编辑语义一致；不引入第三方编辑器组件 |
| 快捷键 | 无（触屏） | Ctrl+N 新建、Ctrl+F 检索、Ctrl+S 强制保存、Ctrl+E 编辑/阅读切换 | 角色 2（外接键盘用户）诉求 |
| 拖放导入 | 无 | 拖放 `.md` 入库、图片复制到 `attachments/` | 桌面端自然交互增强 |

其余业务规则（BR-01~BR-12）、数据格式（4.1~4.7）、功能需求与验收标准均按 Android 版基线执行；实现差异仅限上表。

## 技术组合

| 部分 | 选择 | 说明 |
|---|---|---|
| 框架 | MyGo v0.7.3 | `mygo dev` 开发热重载；`mygo build` 打包并嵌入前端 |
| 后端 | Go 1.27 | 标准库为主；`golang.org/x/text` 用于 GBK 导入转码；`archive/zip` 导出 |
| 前端 | Vue 3 + Vite + TypeScript（strict） | 组件化列表/编辑器/弹层；`marked` + `dompurify` 渲染与消毒 Markdown |
| 主题 | CSS `prefers-color-scheme` 浅/深双主题 | 对应 FR-UI-05 的桌面等价实现 |
| 原生能力 | MyGo Dialog（打开/保存）、Menu（文件菜单与快捷键）、Protocol（`attach://` 本地图片协议）、自定义标题栏 | 图片协议处理器内做路径穿越校验 |

## 已知边界

- 索引为进程内存态，重启后重建（数千篇以内秒级）；未实现 Android 版设计中的 SQLite 落盘索引缓存。
- 导入对话框使用系统原生多选，目录导入保留层级；ZIP 备份包的逆向恢复（FR-DATA-07，低优先级）未实现，可用手动解压后重新选择工作区替代。
- `mygo dev` 在部分环境（疑似杀毒实时扫描）下替换 exe 偶发失败，重试即可；`mygo build` 产物不受影响。
