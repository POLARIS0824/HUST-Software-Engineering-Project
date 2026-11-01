# JustMD Windows

JustMD 的 Windows 桌面客户端，基于 [MyGo](https://mygo.egoist.dev/) 的**原生 UI**（`package ui`，GPU 直接绘制，无 WebView、无 HTML/JavaScript）与 [Onevra](https://github.com/oneo-me/onevra) 组件库实现。与 Android 版共享同一需求基线：本地优先（Local-first）、Markdown 文件是正文唯一事实源、标签与全文检索、`[[双链]]` 与反向引用。

## V1 功能

- 创建、编辑、整理和删除笔记，2 秒自动保存（节流：变脏后每 2 秒落盘一次，原子落盘），保存状态徽章只在真实落盘后显示"已保存"；换篇保留会话内草稿与撤销历史。
- 源码编辑（原生代码编辑器，软换行）与独立 Markdown 阅读页一次点击切换；阅读器只读快照、不回写正文，未知语法原样保留。
- 从正文提取 `#标签`（排除标题与代码块，大小写折叠，支持多级标签），侧栏标签树按标签过滤。
- 按文件名、标题和正文全文检索，支持中文连续文本匹配与英文大小写不敏感子串，结果以富文本高亮关键词并附上下文摘要。
- `[[笔记名称]]` 与 `[[笔记名称|别名]]` 双链：阅读页渲染为可点击链接，未解析链接一键创建，同目录同名优先，歧义时确认。
- 反向链接面板展示引用当前笔记的来源与段落片段。
- 删除进入回收站：列表行按"碎纸机"分片下落动画消失，Toast 撤销，保留 30 天自动清理；支持单篇还原、彻底删除与清空。系统开启"减少动态效果"时不做动画。
- 导入 `.md` 文件或整个文件夹（GBK 自动转 UTF-8，同名冲突逐一询问：自动重命名 / 覆盖 / 跳过）；也支持直接拖入 `.md` 与图片文件（图片自动进入 `attachments/`）。
- 导出单篇 Markdown，或一键导出完整知识库 ZIP（`notes/` + `attachments/` + `manifest.json` 校验清单）。
- 索引全库自愈重建：标签、双链与检索索引全部可从 Markdown 事实源重建。
- 阅读页本地图片直接渲染（路径校验不越出工作区），远程图片不加载。

## 运行

开发与打包（需要 Go 1.27+ 与 WebView2 无关——原生 UI 无任何前端工具链依赖）：

```bash
cd windows/JustMD
go run .                 # 开发运行
go tool mygo build       # 打包（build/ 下生成 exe 与安装包）
```

测试：

```bash
CGO_ENABLED=0 go test ./...   # 领域 + 原生视图（ui.Tester 无窗口渲染）测试
go vet . && go tool mygo vet .
```

## 数据存储

工作区默认在 `文档/JustMDWorkspace`（首次启动时选择，可随时更换），目录结构与 Android 版设计一致：

```text
JustMDWorkspace/
├── notes/          # Markdown 正文事实源（UTF-8 无 BOM，LF 换行）
├── attachments/    # 本地图片与附件
├── .meta/notes/    # 每篇笔记的开放 JSON 元数据（UUID、时间戳、版本）
├── .meta/pending/  # 崩溃恢复操作日志
├── .trash/         # 回收站
└── .tmp/           # 原子写入临时文件
```

应用配置（工作区路径）保存在系统用户数据目录的 `justmd-config.json`。

## 文档

- [技术选型](docs/technical-selection.md)：原生 UI + Onevra 的选型理由与平台适配差异。
- [核实记录](docs/verification-record.md)：已验证结果与待验证边界。

需求基线、软件设计与验收标准以 [Android 版文档](../android/docs/) 为准，Windows 版按同一基线做平台适配。
