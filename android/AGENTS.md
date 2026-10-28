# JustMD 开发约定

- 先读 [技术选型](docs/technical-selection.md)。按 [需求分析](docs/requirements-analysis.md) 和 [软件设计](docs/software-design.md) 开发，用 [核实记录](docs/verification-record.md) 区分目标与已验证结果。
- 当前只有研究原型。正式工程与 `prototypes/` 分开维护，保留原型和证据。
- 采用纯 Kotlin Compose 源码编辑与独立阅读。沿用方案 B，优先 Material Expressive，支持系统 Dynamic Color。不引入 Web 编辑器或即时渲染。
- Markdown 文件是正文事实源，Room 是派生索引。阅读器不回写源码。只有当前版本实际提交成功才显示已保存。
- 使用 `.agents/skills/` 中相关技能。Android 界面参考 `android-design`，设备操作遵守 `android-cli` 的交互与截图要求。

## 构建与验证

- 原型需要 JDK 21 和 Android SDK 37。使用环境变量配置工具路径，不提交 `local.properties`。
- 原生原型构建命令如下。Windows 使用对应的 `gradlew.bat`。

```bash
bash prototypes/justmd-editor-comparison/android/gradlew -p prototypes/justmd-editor-comparison/android :app:assembleNativeDebug
```

- 正式工程建立后，补充其构建与测试命令。原型通过不代替正式应用验收。
- 修改后运行相关检查，说明执行结果与未覆盖项。UI 改动提供实际截图，不把 ASCII 输入、源码探针或构建成功当作中文 IME、性能或可靠保存验收。

## 协作

- 中文沟通，Markdown 强调标记与相邻中文之间留空格。本机 Windows 优先 Nushell，云端使用可用的 shell。
- 未明确要求执行时，只提供 Git 提交方案，不暂存、提交或推送。
- 需要用户操作时，结尾用 **What I need from you** 列出具体动作。否则写 `Nothing needed from you right now`。
