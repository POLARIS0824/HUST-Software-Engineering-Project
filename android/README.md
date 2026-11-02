# Looma Android

Looma 是本地优先（Local-first）的 Android Markdown 笔记应用。应用直接读写本地文件系统中的 Markdown 文件，与 Obsidian 知识库格式兼容。

## 核心特性

- **本地存储优先**：正文以 UTF-8 编码存储为 `.md` 文件，不入数据库。
- **索引缓存**：Room 3 仅缓存文件元数据、标签与双向链接关系。磁盘文件变更时，通过文件修改时间和大小自动增量重建。
- **文件与目录管理**：支持多级文件夹、路径导航、新建、重命名、移动和批量操作。
- **双向链接与反向引用**：支持 `[[笔记名称]]` 与 `[[笔记名称|别名]]` 语法。重命名笔记时自动更新引用链接。详情页包含反向链接面板。
- **标签与全文检索**：从正文提取 `#标签`。检索支持按标题、正文和标签匹配，包含关键词高亮与上下文摘要。
- **双模式编辑**：提供源码编辑与富文本阅读视图。编辑模式配备格式化工具栏与自动防抖落盘。
- **回收站**：删除的笔记移入 `.trash/` 目录，支持单篇还原、彻底删除与清空。
- **Material 3 UI**：遵循 Material 3 Expressive 设计规范。支持动态取色（Dynamic Color）、多套色彩预设以及应用内语言切换（中文、英文）。

## 架构与技术栈

- **平台与语言**：Android Native，Kotlin 2.4，minSdk 26，targetSdk 37，compileSdk 37。
- **界面**：Jetpack Compose，Material 3，Navigation 3。
- **架构模式**：MVVM 搭配 Repository，通过 ViewModel 与 StateFlow 维护单向数据流（UDF）。
- **文件访问**：使用 `MANAGE_EXTERNAL_STORAGE` 权限通过 `java.io.File` 读写任意本地知识库目录。
- **本地持久化**：Preferences DataStore 存储知识库路径与应用设置，Room 3 维护元数据索引。

## 目录结构

```text
android/
├── app/src/main/
│   ├── java/com/example/looma/
│   │   ├── data/          # 知识库、笔记存储、Room 索引与设置仓库
│   │   ├── domain/        # Markdown 解析、WikiLinks 双链图与数据模型
│   │   ├── feature/       # Home、Note、Trash、Settings 与 Vault 页面
│   │   ├── navigation/    # Navigation 3 路由定义与抽屉导航
│   │   └── ui/            # Compose 主题、图标与 Markdown 渲染组件
│   └── res/               # 矢量图标、多语言字符串与主题配置
└── gradle/                # Gradle 包装器与依赖版本目录（libs.versions.toml）
```

## 构建与运行

### 环境要求

- JDK 21 或更高版本
- Android SDK（Platform 37，Build Tools 35 或更新版本）

### 常用命令

所有 Gradle 命令均在 `android` 目录下执行。

若要运行全部单元测试，执行：

```bash
./gradlew test
```

若要在 Windows PowerShell 中运行单元测试，执行：

```powershell
.\gradlew.bat test
```

若要编译 Debug 版本的 APK 安装包，执行：

```bash
./gradlew assembleDebug
```

编译生成的 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。

若要将应用安装到已连接的设备或模拟器，执行：

```bash
./gradlew installDebug
```

若要执行代码检查与校验，执行：

```bash
./gradlew check
```
