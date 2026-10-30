# Looma 编辑器选型 prototype

沿用已选的方案 B 布局，接入 Android Dynamic Color，外壳优先使用 Material Expressive。这里是可丢弃的技术实验，不是正式应用。

- `native`：Compose `BasicTextField(TextFieldState)` 源码编辑 + Compose Markdown 阅读。
- `cm`：Compose 外壳 + AndroidView(WebView) 承载 CodeMirror 6 源码编辑 + 相同的 Compose 阅读。

两个 flavor 可以同时安装。各自保留本次进程内的笔记、选区与撤销历史；切换引擎不迁移历史。没有正式保存、SAF、Room 或进程死亡恢复。

结论与证据见 [research-report.md](research-report.md)，可视比较见 [comparison.html](comparison.html)。

## 构建和运行

Windows / Nushell，在本目录运行：

```nu
npm ci
./node_modules/.bin/esbuild.cmd cm-editor.mjs --bundle --format=iife --outfile=android/app/src/cm/assets/editor/cm.bundle.js
../looma-research/.cache/gradle-9.3.1/bin/gradle.bat -p android :app:assembleNativeDebug :app:assembleCmDebug --max-workers=2
android install --apks=android/app/build/outputs/apk/native/debug/app-native-debug.apk --device=emulator-5554
android install --apks=android/app/build/outputs/apk/cm/debug/app-cm-debug.apk --device=emulator-5554
adb -s emulator-5554 shell am start -W -n edu.hust.looma.comparison.native/edu.hust.looma.comparison.MainActivity --es note tcp
```

CodeMirror 的包名后缀是 `.cm`。菜单中的“运行源码检查”会操作实际编辑端口；也可用启动参数 `--ez probe true`。结果写入各 app 的 `files/editor-probes.json`，读取方式：

```nu
adb -s emulator-5554 shell run-as edu.hust.looma.comparison.native cat files/editor-probes.json | save -f evidence/native-editor-probes.json
```

证据中的源码探针、真实 UI 输入和阅读截图分开记录，避免将程序化插入误称为输入法验证。
