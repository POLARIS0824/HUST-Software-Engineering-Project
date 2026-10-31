package main

import (
	"github.com/egoist/mygo/ui"
	"github.com/oneo-me/onevra/source/components"
	"github.com/oneo-me/onevra/source/icons/lucide"
	"github.com/oneo-me/onevra/source/shells/sidebar"
)

// leftView 构建 Shell 的侧栏：导航、标签树与新建按钮（FR-UI-03：1 步新建）。
// 顶部让出 sidebar.HeaderHeight：Shell 的浮动标题栏（侧栏开关与窗口按钮）
// 悬浮在内容上方，内容必须在其下方布局。
func (a *app) leftView(c *ui.Context) {
	t := c.Theme()
	ui.Column(c).Fill().Gap(t.Space(1)).Padding(sidebar.HeaderHeight(c), t.Space(2), t.Space(2), t.Space(2)).Children(func() {
		if components.Button(c, "＋ 新建笔记", components.ButtonPrimary).Clicked() {
			a.createNote()
		}
		if item := components.SidebarItemWithSlots(c, "全部笔记", a.view == viewNotes,
			sidebarIcon(lucide.FileText()),
			sidebarCount(len(a.notes))); item.Clicked() {
			a.setView(viewNotes)
		}
		if item := components.SidebarItemWithSlots(c, "回收站", a.view == viewTrash,
			sidebarIcon(lucide.Trash()),
			sidebarCount(len(a.trash))); item.Clicked() {
			a.setView(viewTrash)
		}
		if components.SidebarItem(c, "设置与数据", lucide.Settings(), a.view == viewSettings).Clicked() {
			a.setView(viewSettings)
		}

		if len(a.tags) > 0 {
			components.SidebarSection(c, "标签", func() {
				a.tagTree(c)
			})
		}
	}).Background(t.Surface)
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

// tagTree 渲染多级标签树（FR-TAG-06），点击过滤笔记列表。
func (a *app) tagTree(c *ui.Context) {
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
				item := components.SidebarItemWithSlots(c, label, a.view == viewNotes && a.tagFilter == node.Path,
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
func (a *app) handleShortcuts(c *ui.Context) {
	if c.Shortcut(ui.Ctrl, ui.KeyN) {
		a.createNote()
	}
	if c.Shortcut(ui.Ctrl, ui.KeyS) {
		a.flushSave()
	}
	if c.Shortcut(ui.Ctrl, ui.KeyE) {
		if a.mode == "edit" {
			a.enterReadMode()
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
