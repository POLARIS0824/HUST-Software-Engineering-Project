package main

import (
	"time"

	"github.com/egoist/mygo/ui"
	"github.com/oneo-me/onevra/source/components"
	"github.com/oneo-me/onevra/source/shells/sidebar"
)

// trashView 是回收站视图（FR-NOTE-07）：还原、彻底删除、清空。
func (a *app) trashView(c *ui.Context) {
	t := c.Theme()
	ui.Column(c).Key("page-trash").Transition(pageEnter).Fill().Gap(t.Space(3)).Padding(sidebar.HeaderHeight(c)+t.Space(2), t.Space(5), t.Space(4), t.Space(5)).Children(func() {
		components.Text(c, "回收站").FontSize(22).Bold()
		components.Text(c, "删除的笔记在此保留 30 天，之后自动清除。").TextColor(t.TextMuted)

		if len(a.trash) == 0 {
			components.Text(c, "回收站是空的").TextColor(t.TextMuted)
			return
		}
		ui.Scroll(c).Grow(1).FillWidth().Children(func() {
			for _, n := range a.trash {
				n := n
				ui.Row(c.Key(n.ID)).Gap(t.Space(3)).AlignItems(ui.Center).Padding(10, 14).
					Radius(t.Radius).Background(t.Surface).FillWidth().Children(func() {
					ui.Column(c).Gap(2).Grow(1).Children(func() {
						components.Text(c, n.Title).Bold()
						components.Text(c, "原路径："+n.RelativePath).FontSize(12).TextColor(t.TextMuted)
					})
					if components.Button(c, "恢复", components.ButtonOutline).Clicked() {
						// 恢复失败（回收站文件被外部删除、目标路径被占用等）
						// 不能再弹"已恢复笔记"：提示由 restoreFromTrash 给出。
						if a.restoreFromTrash(n.ID) == nil {
							c.Toast("已恢复笔记")
						}
					}
					del := components.Button(c, "彻底删除", components.ButtonSecondary)
					del.TextColor(t.Danger)
					if del.Clicked() {
						a.confirm = confirmState{
							open:    true,
							title:   "彻底删除",
							message: "「" + n.Title + "」将被永久粉碎，无法恢复。确定继续吗？",
							danger:  true,
							onOK: func() {
								if _, err := a.vault.DeleteForever(n.ID); err != nil {
									a.pendingToast = "删除失败：" + err.Error()
								}
								a.refreshLists()
							},
						}
					}
				})
			}
		})
		if components.Button(c, "清空回收站", components.ButtonSecondary).Clicked() {
			a.confirm = confirmState{
				open:    true,
				title:   "清空回收站",
				message: "回收站中的全部笔记将被永久粉碎，无法恢复。确定继续吗？",
				danger:  true,
				onOK: func() {
					if _, err := a.vault.EmptyTrash(); err != nil {
						a.pendingToast = "部分笔记删除失败：" + err.Error()
					}
					a.refreshLists()
				},
			}
		}
	})
}

// settingsView 是设置与数据页：工作区、导入导出、索引维护。
func (a *app) settingsView(c *ui.Context) {
	t := c.Theme()
	info := a.vault.GetWorkspace()

	group := func(title, desc string, body func()) {
		ui.Column(c).Gap(t.Space(2)).Padding(t.Space(3)).Radius(t.Radius).Background(t.Surface).FillWidth().Children(func() {
			components.Text(c, title).Bold()
			components.Text(c, desc).FontSize(12).TextColor(t.TextMuted).Wrap()
			body()
		})
	}

	ui.Scroll(c).Key("page-settings").Transition(pageEnter).Grow(1).FillWidth().Children(func() {
		ui.Column(c).FillWidth().Gap(t.Space(3)).MaxWidth(860).Padding(sidebar.HeaderHeight(c)+t.Space(2), t.Space(5), t.Space(4), t.Space(5)).Children(func() {
			components.Text(c, "设置与数据").FontSize(22).Bold()
			components.Text(c, "全部数据保存在本地文件系统，无账号、无网络依赖（BR-08/BR-10）。").TextColor(t.TextMuted).Wrap()

			group("知识库位置", "笔记正文以标准 UTF-8 Markdown 文件保存在该文件夹的 notes/ 目录中，可随时更换或用其他工具直接打开。", func() {
				ui.Row(c).Gap(t.Space(2)).AlignItems(ui.Center).Children(func() {
					components.Text(c, info.Root).FontSize(12).Grow(1).Wrap()
					if components.Button(c, "更换文件夹…", components.ButtonSecondary).Clicked() {
						// 换库会重置全部编辑器：先保存草稿（评审 #3）。
						if a.flushAllOrToast() {
							go a.chooseWorkspaceFlow()
						}
					}
				})
			})

			group("导入", "从外部导入 .md 文件或整个文件夹（兼容 GBK 编码，自动转 UTF-8；同名文件逐一询问处理方式）。", func() {
				ui.Row(c).Gap(t.Space(2)).Children(func() {
					if components.Button(c, "导入 Markdown 文件…", components.ButtonSecondary).Clicked() {
						go a.importFlow()
					}
					// 系统文件对话框一次只能选文件或选目录：文件夹导入单独入口，
					// 否则"导入整个文件夹"只是文案承诺（对话框里选不中目录）。
					if components.Button(c, "导入文件夹…", components.ButtonSecondary).Clicked() {
						go a.importFolderFlow()
					}
				})
			})

			group("导出", "把完整知识库打包为标准 ZIP 归档（notes/ + attachments/ + manifest.json 校验清单），可在其他设备或其他软件中打开。", func() {
				if components.Button(c, "导出完整知识库为 ZIP…", components.ButtonSecondary).Clicked() {
					// 导出取一致性快照：先保存全部草稿（评审 #3）。
					if a.flushAllOrToast() {
						go a.exportVaultFlow()
					}
				}
			})

			group("索引维护", "标签、双链与检索索引均为可重建的派生数据（BR-02/BR-03）。正文文件永远安然无恙，需要时一键从 Markdown 事实源重建。", func() {
				if components.Button(c, "重建索引", components.ButtonSecondary).Clicked() {
					// 重建会重置全部编辑器：先保存草稿（评审 #3）。
					if a.flushAllOrToast() {
						go a.rebuildFlow()
					}
				}
			})
		})
	})
}

// confirmDialog 是应用内确认弹窗（NFR-USE-03：不可逆操作二次确认）。
func (a *app) confirmDialog(c *ui.Context) {
	if !a.confirm.open {
		return
	}
	components.Dialog(c, &a.confirm.open, components.DialogOptions{Label: a.confirm.title}, func(panel ui.Element) {
		t := c.Theme()
		ui.Column(c).Gap(t.Space(3)).Padding(t.Space(4)).MaxWidth(420).Children(func() {
			components.Text(c, a.confirm.title).FontSize(17).Bold()
			components.Text(c, a.confirm.message).TextColor(t.TextMuted).Wrap()
			ui.Row(c).Gap(t.Space(2)).AlignItems(ui.Center).Children(func() {
				ui.Spacer(c).Grow(1)
				if components.Button(c, "取消", components.ButtonGhost).Clicked() {
					a.confirm.open = false
					a.confirm.onOK = nil
				}
				ok := components.Button(c, "确定", components.ButtonPrimary)
				if a.confirm.danger {
					ok.TextColor(t.Danger)
				}
				if ok.Clicked() {
					a.confirm.open = false
					if fn := a.confirm.onOK; fn != nil {
						fn()
					}
					a.confirm.onOK = nil
				}
			})
		})
	})
}

// importDialog 是导入冲突逐项决策弹窗（基线 4.3：自动重命名 / 覆盖 / 跳过）。
func (a *app) importDialog(c *ui.Context) {
	if a.importPlan == nil {
		return
	}
	plan := a.importPlan
	components.Dialog(c, &a.importDialogOpen, components.DialogOptions{Label: "导入冲突", DisableBackdropClose: true, DisableEscapeClose: true}, func(panel ui.Element) {
		t := c.Theme()
		ui.Column(c).Gap(t.Space(3)).Padding(t.Space(4)).MaxWidth(560).Children(func() {
			components.Text(c, "发现同名文件").FontSize(17).Bold()
			components.Text(c, "以下文件与现有笔记同名。请为每个文件选择处理方式（严禁静默覆盖）。").TextColor(t.TextMuted).Wrap()
			ui.Scroll(c).Grow(1).FillWidth().Children(func() {
				ui.Column(c).FillWidth().Gap(t.Space(2)).Children(func() {
					for _, f := range plan.Files {
						f := f
						ui.Column(c.Key(f.SourcePath)).Gap(2).Padding(8, 12).Radius(t.Radius).Background(t.Surface).FillWidth().Children(func() {
							components.Text(c, f.FileName).Bold().Wrap()
							if f.Status == "invalid" {
								components.Text(c, f.Reason+"，将跳过").FontSize(12).TextColor(t.Danger).Wrap()
								return
							}
							if f.Status != "conflict" {
								components.Text(c, "目标："+f.TargetRel).FontSize(12).TextColor(t.TextMuted).Wrap()
								return
							}
							choice := a.conflictSel[f.SourcePath]
							ui.Row(c).Gap(t.Space(3)).Children(func() {
								components.Radio(c, &choice, "rename", "自动重命名")
								components.Radio(c, &choice, "overwrite", "覆盖现有")
								components.Radio(c, &choice, "skip", "跳过")
							})
							a.conflictSel[f.SourcePath] = choice
						})
					}
				})
			})
			ui.Row(c).Gap(t.Space(2)).AlignItems(ui.Center).Children(func() {
				ui.Spacer(c).Grow(1)
				if components.Button(c, "取消导入", components.ButtonGhost).Clicked() {
					a.importPlan = nil
					return
				}
				if components.Button(c, "执行导入", components.ButtonPrimary).Clicked() {
					a.applyImport(*plan, a.conflictSel)
				}
			})
		})
	})
}

// formatTime 把毫秒时间戳格式化为列表显示。
func formatTime(ms int64) string {
	return time.UnixMilli(ms).Format("01-02 15:04")
}
