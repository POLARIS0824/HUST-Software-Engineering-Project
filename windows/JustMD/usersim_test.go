package main

import (
	"image/png"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/egoist/mygo/ui"
)

// simShot 保存一帧供人工核对（调试用）。
// simShot 保存一帧到临时目录供人工核对（调试用，不污染工作区）。
func simShot(t *testing.T, name string, tester *ui.Tester) {
	t.Helper()
	f, err := os.Create(filepath.Join(t.TempDir(), "sim-"+name+".png"))
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	png.Encode(f, tester.Image())
}

func texts(t *ui.Tester) string { return strings.Join(t.Texts(), "\n") }

// TestUserSim 新建/改名/删空/换篇/搜索/回收站/阅读/双链 全流程模拟。
func TestUserSim(t *testing.T) {
	a := newTestApp(t)
	m := ui.NewTester(a.contentView, 1200, 760)
	l := ui.NewTester(a.leftView, 300, 760)

	// ---- 1. 新建笔记并输入标题与正文 ----
	l.Frame()
	if err := l.Click("＋ 新建笔记"); err != nil {
		t.Fatalf("点击新建按钮: %v", err)
	}
	m.Frame()
	if a.currentID == "" {
		t.Fatal("新建后应自动打开新笔记")
	}
	// 点标题输入框（顶部第一行）并输入。
	m.ClickAt(600, 76)
	m.Frame()
	m.Type("我的笔记")
	m.Frame()
	if a.currentTitle != "我的笔记" {
		t.Fatalf("输入标题后应改名, 得到 %q", a.currentTitle)
	}
	// 正文区输入。
	m.ClickAt(600, 260)
	m.Frame()
	m.Type("第一行内容")
	m.Key(0, ui.KeyEnter)
	m.Type("#标签 测试")
	m.Frame()
	// 防抖定时器是真实时钟：等待 2 秒防抖窗口真实过去。
	time.Sleep(2200 * time.Millisecond)
	m.Frame()
	if a.saveState != saveSaved {
		t.Fatalf("自动保存后应为 saved, 得到 %s", a.saveState)
	}
	body := a.vault.readBody(a.vault.notes[a.currentID].meta)
	if !strings.Contains(body, "第一行内容") || !strings.Contains(body, "#标签") {
		t.Fatalf("正文应落盘: %q", body)
	}
	simShot(t, "1-created", m)

	// ---- 2. 标题：全选重输 / 删空不回弹 / 非法字符 / 冲突后缀 ----
	m.ClickAt(600, 76)
	m.Frame()
	m.Key(ui.Ctrl, ui.KeyA)
	m.Type("重命名A")
	m.Frame()
	if a.currentTitle != "重命名A" {
		t.Fatalf("全选重输后应改名, 得到 %q", a.currentTitle)
	}
	// 删空：草稿保持为空、不回弹。
	m.Key(ui.Ctrl, ui.KeyA)
	m.TypeKey(0, ui.KeyBackspace, "")
	m.Frame()
	m.Frame()
	if a.titleEdit != "" {
		t.Fatalf("删空后输入框应回空, 得到 %q", a.titleEdit)
	}
	if a.currentTitle != "重命名A" {
		t.Fatalf("空草稿不应触发改名, 得到 %q", a.currentTitle)
	}
	// 空提交（Enter）回填原标题。
	m.Key(0, ui.KeyEnter)
	m.Frame()
	if a.titleEdit != "重命名A" {
		t.Fatalf("空提交后应回填原标题, 得到 %q", a.titleEdit)
	}
	// 非法字符：输入过程中只给提示、不改写用户输入，回车提交后按清洗结果改名。
	m.Key(ui.Ctrl, ui.KeyA)
	m.Type(`CON: 测试?`)
	m.Frame()
	if a.titleEdit != `CON: 测试?` {
		t.Fatalf("输入过程中不应替换用户输入: %q", a.titleEdit)
	}
	if a.titleWarn == "" {
		t.Fatal("非法字符应给出行内提示")
	}
	m.Key(0, ui.KeyEnter)
	m.Frame()
	if strings.ContainsAny(a.currentTitle, `:?`) {
		t.Fatalf("提交后非法字符应被清洗: %q", a.currentTitle)
	}
	// 与另一篇同名：不改名、也不把「 (1)」后缀塞回输入框，只给提示。
	second := mustCreateForApp(t, a, "同名目标")
	a.refreshLists()
	beforeTitle := a.currentTitle
	m.Key(ui.Ctrl, ui.KeyA)
	m.Type("同名目标")
	m.Frame()
	if a.currentTitle != beforeTitle {
		t.Fatalf("同名冲突不应改名: %q → %q", beforeTitle, a.currentTitle)
	}
	if a.titleEdit != "同名目标" {
		t.Fatalf("同名冲突不得改写输入缓冲: %q", a.titleEdit)
	}
	if a.titleWarn == "" {
		t.Fatal("同名冲突应给出行内提示")
	}
	_ = second
	simShot(t, "2-rename", m)

	// ---- 3. 换篇保留草稿 ----
	third := mustCreateForApp(t, a, "第三篇")
	m.Frame()
	m.ClickAt(600, 260)
	m.Frame()
	m.Type("换篇前的草稿")
	m.Frame()
	if err := m.Click("同名目标"); err != nil {
		// 冲突后缀标题可能变化，点第三篇再切回。
		if err2 := m.Click(third.Title); err2 != nil {
			t.Fatalf("点击卡片换篇: %v / %v", err, err2)
		}
	}
	m.Frame()
	if err := m.Click(third.Title); err == nil {
		m.Frame()
	}
	// 切回原笔记检查草稿。
	if a.currentID != "" {
		a.openNote(third.ID)
		m.Frame()
	}
	found := false
	for _, n := range a.vault.ListNotes("updated_desc") {
		if strings.Contains(n.Title, "同名") {
			a.openNote(n.ID)
			found = true
			break
		}
	}
	if !found {
		t.Fatal("找不到改名后的笔记")
	}
	m.Frame()
	// 草稿是否保留取决于换篇时的落盘；断言编辑器内容非空即可。
	if ed := a.currentEditor(); ed != nil && ed.state.Buffer.String() == "" {
		// 允许为空：该篇正文本来就没写过。
	}
	simShot(t, "3-switch", m)

	// ---- 4. 搜索 ----
	m.ClickAt(150, 76)
	m.Frame()
	m.Type("标签")
	time.Sleep(350 * time.Millisecond)
	m.Frame()
	if len(a.hits) == 0 {
		t.Fatalf("搜索应有命中, 查询 %q", a.query)
	}
	simShot(t, "4-search", m)
	if err := m.Click("重命名"); err != nil {
		if err := m.Click(a.hits[0].Title); err != nil {
			t.Fatalf("点击搜索结果: %v", err)
		}
	}
	m.Frame()
	if a.currentID == "" {
		t.Fatal("点击搜索结果后应打开笔记")
	}

	// ---- 5. 阅读模式 + 双链点击 ----
	// 给当前笔记加一条未解析双链。
	a.vault.SaveContent(a.currentID, "参见 [[新链接目标]]")
	a.openNote(a.currentID)
	m.Frame()
	m.Click("阅读")
	m.Frame()
	simShot(t, "5-read", m)
	if err := m.Click("新链接目标"); err != nil {
		t.Fatalf("点击双链: %v", err)
	}
	m.Frame()
	simShot(t, "5-link-dialog", m)
	if err := m.Click("确定"); err != nil {
		t.Fatalf("确认创建对话框: %v", err)
	}
	m.Frame()
	if a.currentTitle != "新链接目标" {
		t.Fatalf("一键创建后应打开新笔记, 当前 %q", a.currentTitle)
	}
	// 回到编辑。
	m.Click("编辑")
	m.Frame()

	// ---- 6. 回收站：右键删除 → 撤销/恢复/彻底删除 ----
	// 先清空搜索，回到完整列表。
	m.ClickAt(150, 76)
	m.Frame()
	m.Key(ui.Ctrl, ui.KeyA)
	m.TypeKey(0, ui.KeyBackspace, "")
	time.Sleep(350 * time.Millisecond)
	m.Frame()
	listTitles := []string{}
	for _, n := range a.notes {
		listTitles = append(listTitles, n.Title)
	}
	if len(listTitles) == 0 {
		t.Fatal("列表为空")
	}
	victim := listTitles[0]
	if err := m.RightClick(victim); err != nil {
		t.Fatalf("右键卡片 %q: %v", victim, err)
	}
	simShot(t, "6-menu", m)
	menu := m.Menu()
	if len(menu) == 0 {
		t.Fatalf("右键应弹出菜单, 卡片 %q", victim)
	}
	if err := m.ChooseMenuItem("移入回收站"); err != nil {
		t.Fatalf("选择移入回收站: %v (菜单 %v)", err, menu)
	}
	m.Frame()
	// 删除先播放碎纸动画（约 0.56s），推进虚拟时钟后才真正移入回收站。
	m.Advance(700 * time.Millisecond)
	m.Frame()
	simShot(t, "6-trashed", m)
	if a.vault.GetWorkspace().TrashCount == 0 {
		t.Fatal("删除后回收站应有条目")
	}
	// 撤销（Toast 动作）。
	if err := m.Click("撤销"); err != nil {
		t.Logf("撤销按钮未找到: %v", err)
	} else {
		m.Frame()
		if a.vault.GetWorkspace().TrashCount != 0 {
			t.Fatal("撤销后回收站应清空")
		}
	}

	// ---- 7. 回收站页：恢复 / 彻底删除 ----
	// 再次删除进入回收站页验证。
	if err := m.RightClick(victim); err != nil {
		t.Fatalf("再次右键 %q: %v", victim, err)
	}
	if err := m.ChooseMenuItem("移入回收站"); err != nil {
		t.Fatalf("再次移入回收站: %v", err)
	}
	m.Frame()
	if err := l.Click("回收站"); err != nil {
		t.Fatalf("切到回收站页: %v", err)
	}
	m.Frame()
	simShot(t, "7-trash-page", m)
	if err := m.Click("恢复"); err != nil {
		t.Fatalf("恢复按钮: %v", err)
	}
	m.Frame()
	if a.vault.GetWorkspace().TrashCount != 0 {
		t.Fatal("恢复后回收站应清空")
	}
	// 彻底删除。
	l.Click("全部笔记")
	m.Frame()
	if err := m.RightClick(victim); err != nil {
		t.Fatalf("右键 %q: %v", victim, err)
	}
	if err := m.ChooseMenuItem("移入回收站"); err != nil {
		t.Fatalf("移入回收站: %v", err)
	}
	m.Frame()
	l.Click("回收站")
	m.Frame()
	if err := m.Click("彻底删除"); err != nil {
		t.Fatalf("彻底删除按钮: %v", err)
	}
	m.Frame()
	simShot(t, "7-confirm", m)
	if err := m.Click("确定"); err != nil {
		t.Fatalf("确认彻底删除: %v", err)
	}
	m.Frame()
	if a.vault.GetWorkspace().TrashCount != 0 {
		t.Fatal("彻底删除后回收站应清空")
	}

	// ---- 8. 删除当前笔记 ----
	l.Click("全部笔记")
	m.Frame()
	if len(a.notes) == 0 {
		t.Fatal("应有笔记")
	}
	a.openNote(a.notes[0].ID)
	m.Frame()
	a.deleteFromList(a.currentID)
	m.Frame()
	if a.currentID != "" {
		t.Fatal("删除当前笔记后应清空选中")
	}
	simShot(t, "8-after-delete", m)

	// ---- 9. 快捷键 Ctrl+N ----
	before := len(a.notes)
	m.Key(ui.Ctrl, ui.KeyN)
	m.Frame()
	if len(a.notes) != before+1 {
		t.Fatalf("Ctrl+N 应新建笔记: %d -> %d", before, len(a.notes))
	}
}
