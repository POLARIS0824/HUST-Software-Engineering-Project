package main

import (
	"time"

	"github.com/egoist/mygo/ui"
	"github.com/oneo-me/onevra/source/components"
	"github.com/oneo-me/onevra/source/icons/lucide"
	"github.com/oneo-me/onevra/source/shells/sidebar"
)

// 侧栏选中胶囊的动画曲线：形变用弹簧（切换时保持速度、可打断），
// 透明度用短淡入；参考 Onevra 浮动按钮组的共享高亮实现。
var (
	navPillMorph = ui.Animation{Spring: ui.SpringOptions{Duration: 420 * time.Millisecond, Bounce: 0.12}}
	navPillFade  = ui.Animation{Duration: 160 * time.Millisecond}
)

// pageEnter 是页面/内容切换的进出场：原地淡入淡出。
// 不做位移类几何动画：进出场期间绘制位置与命中盒不一致（自测复现），
// 大面积面板上偏移不明显，但语义上保持"所见即所点"。
var pageEnter = ui.ElementTransition{
	Animation: &ui.Animation{Duration: 180 * time.Millisecond, Ease: ui.CubicBezier(.22, 1, .36, 1)},
	Enter:     &ui.Motion{Opacity: 0},
	Exit:      &ui.Motion{Opacity: 0},
}

// leftView 构建 Shell 的侧栏：导航、标签树与新建按钮（FR-UI-03：1 步新建）。
// 顶部让出 sidebar.HeaderHeight：Shell 的浮动标题栏（侧栏开关与窗口按钮）
// 悬浮在内容上方，内容必须在其下方布局。
func (a *app) leftView(c *ui.Context) {
	t := c.Theme()
	var sel ui.Element // 本帧选中项；同 pass 的 Draw 在布局后读取它的矩形
	ui.Column(c).Fill().Gap(t.Space(1)).Padding(sidebar.HeaderHeight(c), t.Space(2), t.Space(2), t.Space(2)).Background(t.Surface).Children(func() {
		if components.Button(c, "＋ 新建笔记", components.ButtonPrimary).Clicked() {
			a.createNote()
		}
		body := ui.Column(c).Key("nav-body").FillWidth().Gap(t.Space(1))
		body.Draw(func(p *ui.Painter, r ui.Rect) {
			drawNavPill(c, body, p, r, sel)
		})
		body.Children(func() {
			item := navItem(c, "全部笔记", lucide.FileText(), sidebarCount(len(a.notes)))
			if item.Clicked() {
				a.tagFilter = ""
				a.setView(viewNotes)
			}
			if a.navSelection() == "notes" {
				sel = item
			}
			item = navItem(c, "回收站", lucide.Trash(), sidebarCount(len(a.trash)))
			if item.Clicked() {
				a.tagFilter = ""
				a.setView(viewTrash)
			}
			if a.navSelection() == "trash" {
				sel = item
			}
			item = navItem(c, "设置与数据", lucide.Settings(), nil)
			if item.Clicked() {
				a.tagFilter = ""
				a.setView(viewSettings)
			}
			if a.navSelection() == "settings" {
				sel = item
			}

			if len(a.tags) > 0 {
				components.SidebarSection(c, "标签", func() {
					// 标签树放进可滚动区域（评审遗留项）：
					// 标签多时（几十个以上）侧栏被撑开，标签树被挤出
					// 视口底部，用户滚动主内容区也看不到它。
					// 固定最大高度 + 滚动，保证导航项始终可见。
					ui.Scroll(c).Grow(1).FillWidth().MinWidth(0).Children(func() {
						a.tagTree(c, &sel)
					})
				})
			}
		})
	})
}

// navSelection 返回当前选中的侧栏项：有标签过滤时选标签，否则选导航页。
func (a *app) navSelection() string {
	if a.tagFilter != "" {
		return "tag:" + a.tagFilter
	}
	return a.view
}

// navItem 构建一个侧栏项；选中态由滑动胶囊呈现（传 selected=false 避免双重底色）。
func navItem(c *ui.Context, label string, svg *ui.SVG, count func(*ui.Context, float32)) ui.Element {
	return components.SidebarItemWithSlots(c, label, false, sidebarIcon(svg), count)
}

// sidebarIcon 是 SidebarItemWithSlots 的标准图标槽。
func sidebarIcon(svg *ui.SVG) func(c *ui.Context, size float32) {
	return func(c *ui.Context, size float32) {
		components.Icon(c, svg).Size(size, size).TextColor(c.Theme().Accent)
	}
}

// sidebarCount 是 SidebarItemWithSlots 的计数槽；0 不显示以免噪音。
func sidebarCount(n int) func(c *ui.Context, size float32) {
	return func(c *ui.Context, size float32) {
		if n == 0 {
			return
		}
		components.Text(c, itoa(n)).FontSize(11).TextColor(c.Theme().TextMuted)
	}
}

// drawNavPill 绘制选中胶囊：在选中项的矩形间弹簧形变（滑动 + 变宽），
// 首次出现就地落位淡入，不从旧位置飞来。
// sel 为同 pass 构建的选中项；paint-only 帧里元素可能已过期，先 Valid 守卫
// （过期帧跳过一次定位，胶囊由弹簧通道继续走）。
func drawNavPill(c *ui.Context, holder ui.Element, p *ui.Painter, r ui.Rect, sel ui.Element) {
	t := c.Theme()
	has := sel.Valid()
	show := float32(0)
	if has {
		show = 1
	}
	opacity := holder.AnimateTo("nav-pill-opacity", show, navPillFade)
	if !has {
		return
	}
	b := sel.LayoutBounds()
	to := ui.Rect{X: b.X - r.X, Y: b.Y - r.Y, W: b.W, H: b.H}
	if opacity < 0.15 {
		holder.Jump("nav-pill-x", to.X)
		holder.Jump("nav-pill-y", to.Y)
		holder.Jump("nav-pill-w", to.W)
		holder.Jump("nav-pill-h", to.H)
	}
	pill := ui.Rect{
		X: holder.AnimateTo("nav-pill-x", to.X, navPillMorph),
		Y: holder.AnimateTo("nav-pill-y", to.Y, navPillMorph),
		W: holder.AnimateTo("nav-pill-w", to.W, navPillMorph),
		H: holder.AnimateTo("nav-pill-h", to.H, navPillMorph),
	}
	p.Fill(ui.Rect{X: r.X + pill.X, Y: r.Y + pill.Y, W: pill.W, H: pill.H}, t.Accent.Alpha(.13*opacity), t.Radius)
}

// tagTree 渲染多级标签树（FR-TAG-06），点击过滤笔记列表。
func (a *app) tagTree(c *ui.Context, sel *ui.Element) {
	var render func(nodes []TagNode, depth int)
	render = func(nodes []TagNode, depth int) {
		for _, node := range nodes {
			node := node
			kc := c.Key("tag:" + node.Path)
			ui.Row(kc).AlignItems(ui.Center).
				Padding(0, 0, 0, float32(depth)*14).Children(func() {
				label := node.Name
				if label == "" {
					label = node.Path
				}
				item := components.SidebarItemWithSlots(c, label, false,
					sidebarIcon(lucide.Hash()), sidebarCount(node.Count))
				if item.Clicked() {
					a.view = viewNotes
					if a.tagFilter == node.Path {
						a.tagFilter = ""
					} else {
						a.tagFilter = node.Path
					}
					a.refreshLists()
				}
				if a.navSelection() == "tag:"+node.Path {
					*sel = item
				}
			})
			if len(node.Children) > 0 {
				render(node.Children, depth+1)
			}
		}
	}
	render(a.tags, 0)
}

// contentView 是 Shell 主内容区：按 a.view 路由到页面。
func (a *app) contentView(c *ui.Context) {
	a.handleShortcuts(c)
	// 删除动画只在笔记页有素材；切到回收站/设置时立刻结算，不留半个删除。
	if a.shred != nil && a.view != viewNotes {
		a.finishShred(c)
	}
	switch a.view {
	case viewTrash:
		a.trashView(c)
	case viewSettings:
		a.settingsView(c)
	default:
		a.notesView(c)
	}
	a.overlays(c)
}

// handleShortcuts 处理全局快捷键（角色 2：外接键盘诉求）。
// 确认弹窗/导入冲突弹窗打开时不响应：旧实现里在弹窗上按 Ctrl+N 会在
// 弹窗背后新建笔记，关闭弹窗后用户面对的是另一篇，状态容易错乱。
//
// Ctrl+S 的例外（评审 N5）：保存是无害且期望中的操作，尤其在"草稿保存失败，
// 请确认是否退出"的弹窗上正是用户想重试保存的快捷键。但 MyGo 的 Modal 弹窗
// 会让窗口级快捷键整体失效（c.Shortcut 在弹窗外一律返回 false），
// 因此 Ctrl+S 是在弹窗**内部**单独注册的，见 confirmDialog。
func (a *app) handleShortcuts(c *ui.Context) {
	if a.confirm.open || a.importPlan != nil || a.importDialogOpen {
		return
	}
	if c.Shortcut(ui.Ctrl, ui.KeyN) {
		a.createNote()
	}
	if c.Shortcut(ui.Ctrl, ui.KeyS) {
		a.flushSave()
	}
	if c.Shortcut(ui.Ctrl, ui.KeyE) {
		if a.mode == "edit" {
			a.enterReadMode() // 生成阅读快照前先落盘（与分段控件一致）
		} else {
			a.mode = "edit"
		}
	}
}

// overlays 构建悬浮层：确认弹窗、导入冲突、忙碌提示、Toast。
func (a *app) overlays(c *ui.Context) {
	a.confirmDialog(c)
	a.importDialog(c)
	if a.busy != "" {
		// 忙碌状态作为内容顶部的一行提示（避免遮挡交互）。
		th := c.Theme()
		ui.Row(c).FillWidth().Gap(th.Space(2)).Padding(th.Space(2), th.Space(4)).AlignItems(ui.Center).Children(func() {
			components.Spinner(c, components.SpinnerRing)
			components.Text(c, a.busy).TextColor(th.TextMuted)
		})
	}
	if a.pendingToast != "" {
		c.Toast(a.pendingToast)
		a.pendingToast = ""
	}
}
