package main

import (
	"bytes"
	"image/png"
	"testing"
)

// TestAppIconEmbedded 应用图标必须是可解码的方形 PNG：
// 它既用于打包时嵌入 exe 资源，也用于运行期设置窗口图标，
// 文件丢失/损坏会让 `go:embed` 编译失败或图标变成空白。
func TestAppIconEmbedded(t *testing.T) {
	if len(appIconPNG) == 0 {
		t.Fatal("resources/icon.png 未嵌入（go:embed 结果为空）")
	}
	img, err := png.Decode(bytes.NewReader(appIconPNG))
	if err != nil {
		t.Fatalf("图标不是可解码的 PNG: %v", err)
	}
	b := img.Bounds()
	if b.Dx() != b.Dy() {
		t.Fatalf("图标应为方形，得到 %dx%d", b.Dx(), b.Dy())
	}
	if b.Dx() < 256 {
		t.Fatalf("图标边长应 ≥256（Windows 需要 256px 一档），得到 %d", b.Dx())
	}
	// 无窗口时调用不应 panic（窗口未就绪/测试环境）。
	applyWindowIcon(nil)
}
