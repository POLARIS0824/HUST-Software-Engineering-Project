## 开发约定

- 除非明确说明，否则不要写文档
- 如果遇到决策问题，都优先和 Obsidian 对齐

## 技术选型

- **平台**：Android Native，Kotlin，minSdk 26，targetSdk 37
- **UI**：Jetpack Compose + Material 3 Expressive，支持 Dynamic Color，设计参考 Google Keep
- **架构**：MVVM + Repository，按 Feature 组织代码，UI / Data 分离
- **状态管理**：ViewModel + StateFlow，单向数据流（UDF）
- **依赖注入**：暂时使用手动注入和 ViewModel Factory
- **文件存储**：Local-first，Markdown 文件作为唯一内容数据源
- **文件访问**：MANAGE_EXTERNAL_STORAGE，使用 java.io.File
- **配置持久化**：Preferences DataStore，保存 Vault 元数据
- **导航**：计划使用 Navigation 3
- **数据库**：计划使用 Room 3 存储索引与缓存，不存储 Markdown 正文
- **构建**：Gradle Kotlin DSL + Version Catalog