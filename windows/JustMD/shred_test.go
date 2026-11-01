package main

import (
	"strings"
	"testing"
	"time"

	"github.com/egoist/mygo/ui"
)

// TestDeletePlaysShredBeforeTrashing 删除笔记先播放碎纸动画：
// 动画期间笔记仍在（还没进回收站），播完才真正移入回收站并给出撤销 Toast。
func TestDeletePlaysShredBeforeTrashing(t *testing.T) {
	a := newTestApp(t)
	doc := mustCreateForApp(t, a, "待碎纸笔记")
	mustSaveForApp(t, a, doc.ID, "会被切成条的内容\n\n第二行")
	a.refreshLists()

	m := ui.NewTester(a.contentView, 1200, 760)
	m.Frame()
	if err := m.RightClick("待碎纸笔记"); err != nil {
		t.Fatalf("右键卡片: %v", err)
	}
	if err := m.ChooseMenuItem("移入回收站"); err != nil {
		t.Fatalf("选择移入回收站: %v", err)
	}
	m.Frame()

	// 动画开始，但笔记还没被删除（这一行本身就是动画素材）。
	if a.shred == nil {
		t.Fatal("删除应进入碎纸动画")
	}
	if got := a.vault.GetWorkspace().TrashCount; got != 0 {
		t.Fatalf("动画期间不应已移入回收站，回收站条目 %d", got)
	}
	if doc2, err := a.vault.OpenNote(doc.ID); err != nil || doc2.Trashed {
		t.Fatalf("动画期间笔记应仍可打开且未回收: err=%v trashed=%v", err, doc2.Trashed)
	}

	// 推进虚拟时钟走完动画：真正删除，并弹出带"撤销"的 Toast。
	m.Advance(shredDuration + 60*time.Millisecond)
	m.Frame()
	if a.shred != nil {
		t.Fatal("动画结束后应结算删除")
	}
	if got := a.vault.GetWorkspace().TrashCount; got != 1 {
		t.Fatalf("动画结束后应移入回收站，回收站条目 %d", got)
	}
	txt := texts(m)
	if !containsAll(txt, "移入回收站", "撤销") {
		t.Fatalf("删除后应给出撤销 Toast，实际文本:\n%s", txt)
	}

	// 撤销应把笔记还回来（动画没有改变撤销语义）。
	if err := m.Click("撤销"); err != nil {
		t.Fatalf("点击撤销: %v", err)
	}
	m.Frame()
	if got := a.vault.GetWorkspace().TrashCount; got != 0 {
		t.Fatalf("撤销后回收站应清空，仍 %d 条", got)
	}
	if n := len(a.vault.ListNotes("updated_desc")); n != 1 {
		t.Fatalf("撤销后应恢复 1 篇笔记，实际 %d", n)
	}
}

// TestDeleteInstantWhenReduceMotion 系统要求"减少动态效果"时不做碎纸动画，
// 直接移入回收站（无障碍）。
func TestDeleteInstantWhenReduceMotion(t *testing.T) {
	a := newTestApp(t)
	doc := mustCreateForApp(t, a, "无动画删除")
	a.refreshLists()

	m := ui.NewTester(a.contentView, 1200, 760)
	m.SetPreferences(ui.Preferences{ReduceMotion: true, TextScale: 1})
	m.Frame()
	if err := m.RightClick("无动画删除"); err != nil {
		t.Fatalf("右键卡片: %v", err)
	}
	if err := m.ChooseMenuItem("移入回收站"); err != nil {
		t.Fatalf("选择移入回收站: %v", err)
	}
	m.Frame()
	if a.shred != nil {
		t.Fatal("减少动态效果时不应播放碎纸动画")
	}
	if got := a.vault.GetWorkspace().TrashCount; got != 1 {
		t.Fatalf("应立即移入回收站，回收站条目 %d", got)
	}
	if doc2, err := a.vault.OpenNote(doc.ID); err != nil || !doc2.Trashed {
		t.Fatalf("笔记应已回收: err=%v trashed=%v", err, doc2.Trashed)
	}
}

// TestShredFinalizesWhenLeavingNotesView 动画期间切到回收站/设置页：
// 立刻结算删除，不能留下"半个删除"。
func TestShredFinalizesWhenLeavingNotesView(t *testing.T) {
	a := newTestApp(t)
	doc := mustCreateForApp(t, a, "切页删除")
	a.refreshLists()

	m := ui.NewTester(a.contentView, 1200, 760)
	m.Frame()
	if err := m.RightClick("切页删除"); err != nil {
		t.Fatalf("右键卡片: %v", err)
	}
	if err := m.ChooseMenuItem("移入回收站"); err != nil {
		t.Fatalf("选择移入回收站: %v", err)
	}
	m.Frame()
	if a.shred == nil {
		t.Fatal("删除应进入碎纸动画")
	}

	a.view = viewTrash // 动画还没播完就切页
	m.Frame()
	if a.shred != nil {
		t.Fatal("切到回收站页后应立即结算动画")
	}
	if got := a.vault.GetWorkspace().TrashCount; got != 1 {
		t.Fatalf("切页后应完成删除，回收站条目 %d", got)
	}
	if _, ok := a.vault.trashed[doc.ID]; !ok {
		t.Fatal("被删笔记应在回收站里")
	}
}

// TestShredEffectCutsContentIntoStrips shredEffect 把内容切成竖条并让它们
// 随时间下移：同一笔记在动画中和静止时渲染出的像素必须不同，且不 panic。
func TestShredEffectCutsContentIntoStrips(t *testing.T) {
	// 静止版本（无 Effect）。
	plain := ui.NewTester(func(c *ui.Context) {
		ui.Row(c).Fill().Children(func() {
			ui.Box(c).Width(120).Height(60).Background(ui.RGB(200, 80, 80))
		})
	}, 200, 100)
	plain.Frame()
	base := plain.Image()

	// 动画中段版本：碎片下落、旋转。
	sliced := ui.NewTester(func(c *ui.Context) {
		ui.Row(c).Fill().Children(func() {
			ui.Box(c).Width(120).Height(60).Background(ui.RGB(200, 80, 80)).
				Effect(&shredEffect{t: 0.55, strips: 5})
		})
	}, 200, 100)
	sliced.Frame()
	cut := sliced.Image()

	if base.Bounds() != cut.Bounds() {
		t.Fatalf("尺寸应一致: %v vs %v", base.Bounds(), cut.Bounds())
	}
	diff := 0
	for y := 0; y < base.Bounds().Dy(); y++ {
		for x := 0; x < base.Bounds().Dx(); x++ {
			r1, g1, b1, a1 := base.At(x, y).RGBA()
			r2, g2, b2, a2 := cut.At(x, y).RGBA()
			if r1 != r2 || g1 != g2 || b1 != b2 || a1 != a2 {
				diff++
			}
		}
	}
	if diff == 0 {
		t.Fatal("碎纸动画中段的像素应与静止状态不同（分片已下落）")
	}
}

// containsAll 判断 s 是否包含全部子串。
func containsAll(s string, subs ...string) bool {
	for _, sub := range subs {
		if !strings.Contains(s, sub) {
			return false
		}
	}
	return true
}
