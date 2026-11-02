package main

import (
	"strings"

	"github.com/egoist/mygo/ui"
	"github.com/oneo-me/onevra/source/components"
	"github.com/oneo-me/onevra/source/icons/lucide"
)

// 分类 = notes/ 目录下的真实文件夹（Markdown 文件仍是唯一事实源，
// 导出 ZIP 会原样保留层级）。这里只有界面：树的渲染、过滤选择、
// 移动/新建/删除分类的弹窗；文件操作全部在 Vault 的领域方法里。

// walkCategories 深度优先遍历分类树（路径已排序），depth 从 0 开始。
func (a *app) walkCategories(fn func(node CategoryNode, depth int)) {
	var walk func(nodes []CategoryNode, depth int)
	walk = func(nodes []CategoryNode, depth int) {
		for _, n := range nodes {
			fn(n, depth)
			walk(n.Children, depth+1)
		}
	}
	walk(a.categories, 0)
}

// categoryTree 渲染侧栏的分类树：未分类（根目录）→ 各级分类 → 新建入口。
// 选中项交给调用方（侧栏滑动胶囊）绘制。
func (a *app) categoryTree(c *ui.Context, sel *ui.Element) {
	// 未分类（notes/ 根目录）永远存在，是"无主笔记"的归宿。
	row := components.SidebarItemWithSlots(c, "未分类", false,
		sidebarIcon(lucide.Folder()), sidebarCount(a.unfiledCount))
	if row.Clicked() {
		a.toggleCategoryFilter("")
	}
	if a.navSelection() == "cat:" {
		*sel = row
	}

	a.walkCategories(func(node CategoryNode, depth int) {
		kc := c.Key("cat:" + node.Path)
		// 层级用左侧内边距表示；右键可新建子分类或删除空分类。
		wrap := ui.Row(kc).AlignItems(ui.Center).
			Padding(0, 0, 0, float32(depth)*14)
		wrap.ContextMenu(func(m *ui.Menu) {
			if m.Item("新建子分类…").Chosen() {
				a.catDialog = catDialogState{open: true, newName: node.Path + "/"}
			}
			if m.Item("删除分类").Chosen() {
				path := node.Path
				a.confirm = confirmState{
					open:    true,
					title:   "删除分类",
					message: "删除分类「" + categoryDisplayName(path) + "」？只有空分类能删除，里面的笔记不会被删除。",
					danger:  true,
					onOK:    func() { a.deleteCategory(path) },
				}
			}
		})
		wrap.Children(func() {
			item := components.SidebarItemWithSlots(c, node.Name, false,
				sidebarIcon(lucide.Folder()), sidebarCount(node.Total))
			if item.Clicked() {
				a.toggleCategoryFilter(node.Path)
			}
			if a.navSelection() == "cat:"+node.Path {
				*sel = item
			}
		})
	})

	// 新建分类入口：与分类行同宽，点击打开弹窗。
	newRow := components.SidebarItemWithSlots(c, "新建分类…", false,
		sidebarIcon(lucide.FolderPlus()), nil)
	if newRow.Clicked() {
		a.openNewCategoryDialog()
	}
}

// categoryDialog 是分类弹窗：把笔记移动到分类，或只新建分类。
func (a *app) categoryDialog(c *ui.Context) {
	if !a.catDialog.open {
		return
	}
	t := c.Theme()
	moving := a.catDialog.noteID != ""
	title := "新建分类"
	if moving {
		title = "移动到分类"
	}
	closeDialog := func() { a.catDialog = catDialogState{} }

	components.Dialog(c, &a.catDialog.open, components.DialogOptions{Label: title}, func(panel ui.Element) {
		ui.Column(c).Gap(t.Space(3)).Padding(t.Space(4)).MaxWidth(460).Children(func() {
			components.Text(c, title).FontSize(17).Bold()
			if moving {
				components.Text(c, "把「"+a.catDialog.noteTitle+"」移动到：").
					TextColor(t.TextMuted).Wrap()
				// 目标分类列表：未分类（根目录）+ 全部分类（含空分类）。
				ui.Scroll(c).Height(220).FillWidth().Children(func() {
					ui.Column(c).FillWidth().Gap(t.Space(1)).Children(func() {
						components.Radio(c, &a.catDialog.target, "", "未分类（根目录）")
						a.walkCategories(func(node CategoryNode, depth int) {
							ui.Row(c).Padding(0, 0, 0, float32(depth)*14).Children(func() {
								components.Radio(c, &a.catDialog.target, node.Path,
									node.Name+"（"+itoa(node.Total)+"）")
							})
						})
					})
				})
			}

			// 新建分类：可多级（如 课程/软件工程），创建后可继续移动。
			ui.Row(c).Gap(t.Space(2)).AlignItems(ui.Center).Children(func() {
				box := components.TextBox(c, &a.catDialog.newName, components.TextBoxOptions{}).
					Label("新建分类").Placeholder("新建分类，可多级：课程/软件工程").Grow(1)
				submitNew := box.Submitted() ||
					components.Button(c, "新建", components.ButtonOutline).Clicked()
				if submitNew && strings.TrimSpace(a.catDialog.newName) != "" {
					path, err := a.createCategory(a.catDialog.newName)
					if err == nil {
						a.catDialog.newName = ""
						if moving {
							if a.moveNoteToCategory(a.catDialog.noteID, path) == nil {
								closeDialog()
							}
						} else {
							// 仅新建：保持弹窗打开，方便连续建多个分类。
							a.catDialog.target = path
						}
					}
				}
			})

			ui.Row(c).Gap(t.Space(2)).AlignItems(ui.Center).Children(func() {
				ui.Spacer(c).Grow(1)
				if components.Button(c, "取消", components.ButtonGhost).Clicked() {
					closeDialog()
				}
				if !moving {
					return
				}
				if components.Button(c, "移动到所选分类", components.ButtonPrimary).Clicked() {
					if a.moveNoteToCategory(a.catDialog.noteID, a.catDialog.target) == nil {
						closeDialog()
					}
				}
			})
		})
	})
}
