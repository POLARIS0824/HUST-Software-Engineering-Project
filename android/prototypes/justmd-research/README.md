# JustMD disposable Android prototype

打开 `compare.html` 比较真实截图。研究结论见 [research-report.md](research-report.md)。本目录可以整体丢弃。编辑内容只保留在当前进程，不能用于真实笔记。

## 运行界面

在本目录使用 Nushell。

```nu
cd android
./gradlew.bat :app:assembleDebug
android run --apks=app/build/outputs/apk/debug/app-debug.apk --activity=edu.hust.justmd.prototype.MainActivity --device=emulator-5554
```

顶部调节图标可切换 A、B、C。底部可打开标签和链接，编辑页的连接图标显示引用当前笔记的样例。`记一笔` 创建会话草稿。

现有截图和 APK 已在当前机器上构建完成。SDK 路径写在本地 `android/local.properties`。换机器后修改该路径。脚本不会设置全局代理。当前环境的 Java 下载通过 Windows 系统代理执行，官方 Gradle 分发地址已改用 `downloads.gradle.org` 并锁定校验值。

## 重新准备资源

```nu
npm ci
node generate-palette.mjs
powershell -NoProfile -File prepare-assets.ps1
./node_modules/.bin/esbuild.cmd codemirror-probe.mjs --bundle --format=iife --outfile=android/app/src/main/assets/editor/codemirror.bundle.js
```

Vditor 资源已打包。`prepare-assets.ps1` 核对 npm 的 SHA512 integrity 并写入 `assets-lock.json`。Kotlin 载入时核对 Vditor JS 哈希。CodeMirror 依赖锁在 `package-lock.json`。图标来源为 Google Material Symbols Rounded，字体为 Google Sans Flex。

## 复跑 Android 探针

```nu
adb -s emulator-5554 shell am force-stop edu.hust.justmd.prototype
adb -s emulator-5554 shell am start -n edu.hust.justmd.prototype/.MainActivity --ez probe true
```

等待应用写出 `files/native-probes.json` 和 `files/editor-probes.json` 后获取结果。读取失败时先检查实际应用状态，不能把旧文件当作本轮证据。

```nu
adb -s emulator-5554 exec-out run-as edu.hust.justmd.prototype cat files/native-probes.json | save --force evidence/native-probes.json
adb -s emulator-5554 exec-out run-as edu.hust.justmd.prototype cat files/editor-probes.json | save --force evidence/vditor-probes.json
adb -s emulator-5554 shell am force-stop edu.hust.justmd.prototype
adb -s emulator-5554 shell am start -n edu.hust.justmd.prototype/.MainActivity --ez probe true --es engine codemirror --ez strictDom true
```

等待 CodeMirror 探针写出结果，再获取。

```nu
adb -s emulator-5554 exec-out run-as edu.hust.justmd.prototype cat files/editor-probes.json | save --force evidence/codemirror-probes.json
node summarize.mjs
```

`--ez strictDom true` 可复现 Vditor 的 DOM storage 禁用失败。`--es engine codemirror` 选择源文本对照。`--ei style 0`、`1`、`2` 分别选择三种风格，`--es note tcp` 直接打开样例笔记。
