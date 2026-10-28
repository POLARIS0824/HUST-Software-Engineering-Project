# JustMD

JustMD 是笔记和知识管理工具
Markdown & Local First

## V1 目标

- 创建、编辑、整理和删除笔记，支持自动保存、撤销删除和回收站。
- 使用纯 Kotlin Compose 编辑 Markdown 源码，切换到独立的原生阅读页。保留未知语法，不把排版结果写回正文。
- 从正文提取 `#标签`，按文件名、标题和正文搜索，支持中文连续文本匹配。
- 使用 `[[笔记名称]]` 关联笔记，查看反向链接，并允许链接指向尚未创建的笔记。
- 导入和导出 Markdown，导出完整知识库为 ZIP，并在索引损坏时重建索引。

笔记正文以本地 `.md` 文件为准。稳定 ID、创建时间和回收站状态等信息使用开放的 JSON 元数据保存。搜索、标签和链接索引属于可重建的派生数据。核心功能无需账号或网络。

## V1 范围之外

V1 不包含即时渲染、云同步、账号、多人协作、第三方插件、内置 AI、全局知识图谱，以及桌面或 iOS 客户端。

## 项目状态

当前已有两轮可运行的 Android 研究原型，正式应用尚未实现。已选择方案 B、Material Expressive、Dynamic Color 和纯 Compose 源码编辑。基础编辑、主题与独立数据探针已有证据，可靠保存、中文输入法、搜索与双链集成及性能仍待完成。

研究代码见 [第二轮原型](prototypes/justmd-editor-comparison/README.md)，它只保留本次进程内的编辑会话。

## 文档

- [技术选型](docs/technical-selection.md)：根据原型确定原生方案，说明理由与边界。

- [需求分析](docs/requirements-analysis.md)：用户需求、功能范围和验收标准。
- [软件设计](docs/software-design.md)：设计方案及对需求基线的修正。
- [核实记录](docs/verification-record.md)：决策依据和待验证事项。
- [AI 协作记录](docs/ai-collaboration-log.md)：需求分析中的协作过程与人工决策。
