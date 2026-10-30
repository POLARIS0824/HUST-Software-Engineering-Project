package main

import (
	"github.com/egoist/mygo/ui"
	"github.com/oneo-me/onevra/source/components"
	"github.com/oneo-me/onevra/source/icons/lucide"
)

// leftView 构建 Shell 的侧栏：导航、标签树与新建按钮（FR-UI-03：1 步新建）。
func (a *app) leftView(c *ui.Context) {
	t := c.Theme()
	ui.Column(c).Fill().Gap(t.Space(1)).Padding(t.Space(2), t.Space(2), t.Space(2), t.Space(2)).Children(func() {
		if components.Button(c, "＋ 新建笔记", components.ButtonPrimary).Clicked() {
			a.createNote()
		}
		ui.Row(c).Gap(t.Space(1)).AlignItems(ui.Center).Children(func() {
			if components.SidebarItem(c, "全部笔记", lucide.FileText(), a.view == viewNotes).Clicked() {
				a.setView(viewNotes)
			}
			components.Badge(c, itoa(len(a.notes)))
		})
		ui.Row(c).Gap(t.Space(1)).AlignItems(ui.Center).Children(func() {
			if components.SidebarItem(c, "回收站", lucide.Trash(), a.view == viewTrash).Clicked() {
				a.setView(viewTrash)
			}
			if len(a.trash) > 0 {
				components.Badge(c, itoa(len(a.trash)))
			}
		})
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

// tagTree 渲染多级标签树（FR-TAG-06），点击过滤笔记列表。
func (a *app) tagTree(c *ui.Context) {
	var render func(nodes []TagNode, depth int)
	render = func(nodes []TagNode, depth int) {
		for _, node := range nodes {
			node := node
			kc := c.Key("tag:" + node.Path)
			ui.Row(kc).Gap(6).AlignItems(ui.Center).
				Padding(0, 0, 0, float32(depth)*14).Children(func() {
				label := node.Name
				if label == "" {
					label = node.Path
				}
				item := components.SidebarItem(c, label, lucide.Hash(), a.view == viewNotes && a.tagFilter == node.Path)
				if item.Clicked() {
					a.view = viewNotes
					if a.tagFilter == node.Path {
						a.tagFilter = ""
					} else {
						a.tagFilter = node.Path
					}
					a.refreshLists()
				}
				components.Text(c, itoa(node.Count)).FontSize(11).TextColor(c.Theme().TextMuted)
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
