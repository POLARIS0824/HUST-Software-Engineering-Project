package main

import (
	"net/url"
	"os"
	"strconv"
	"strings"
	"time"

	"github.com/egoist/mygo/ui"
	"github.com/egoist/mygo/ui/editor"
	"github.com/egoist/mygo/ui/markdown"
	"github.com/oneo-me/onevra/source/components"
	"github.com/oneo-me/onevra/source/icons/lucide"
	"github.com/oneo-me/onevra/source/shells/sidebar"
)

// 列表/面板/内容的动画曲线：弹簧用于布局形变（可打断、保速度），
// 缓出曲线用于轻量进出场；参考 Neverland 演示的节奏。
var (
	// panelMotion 是反链面板宽度的弹簧：每一帧按动画宽度重新布局，
	// 编辑区 Grow(1) 精确互补，任何中间帧都不会重叠。
	panelMotion = ui.Animation{Spring: ui.SpringOptions{Duration: 380 * time.Millisecond, Bounce: 0.1}}
	paneSwap    = ui.ElementTransition{
		Animation: &ui.Animation{Duration: 170 * time.Millisecond, Ease: ui.CubicBezier(.22, 1, .36, 1)},
		Enter:     &ui.Motion{Y: 10, Opacity: 0},
		Exit:      &ui.Motion{Opacity: 0},
	}
	fadeSwap = ui.ElementTransition{
		Animation: &ui.Animation{Duration: 150 * time.Millisecond, Ease: ui.EaseOut},
		Enter:     &ui.Motion{Opacity: 0},
		Exit:      &ui.Motion{Opacity: 0},
	}
	hoverEase = ui.Animation{Duration: 130 * time.Millisecond, Ease: ui.EaseOut}
)

// rowTransition 是笔记列表行的进出场：淡入上移、淡出收拢；
// 排序重排时平滑滑动；同批入场按行号错峰（Delay）。
func rowTransition(i int) ui.ElementTransition {
	delay := time.Duration(min(i, 8)) * 28 * time.Millisecond
	return ui.ElementTransition{
		Animation: &ui.Animation{Duration: 230 * time.Millisecond, Ease: ui.CubicBezier(.22, 1, .36, 1), Delay: delay},
		Position:  true,
		Enter:     &ui.Motion{Y: 10, Opacity: 0},
		Exit:      &ui.Motion{Opacity: 0, Collapse: true},
	}
}

// notesView 是主页面：搜索与笔记列表 | 编辑器或阅读页 | 反链面板。
func (a *app) notesView(c *ui.Context) {
	t := c.Theme()
	ui.Row(c).Key("page-notes").Transition(pageEnter).Fill().AlignItems(ui.Stretch).Children(func() {
		// 中栏：搜索 + 排序 + 列表。顶部让出浮动标题栏高度。
		ui.Column(c).Width(300).Shrink(0).Gap(t.Space(2)).Padding(sidebar.HeaderHeight(c), t.Space(2), t.Space(2), t.Space(2)).Background(t.Surface).Children(func() {
			a.searchBar(c)
			a.sortBar(c)
			a.noteList(c)
		})
		// 右侧：编辑器/阅读 + 反链面板。面板宽度是弹簧动画值，
		// 编辑区 Grow(1) 每帧精确互补（Neverland 式面板收展）。
		if a.currentID != "" {
			ui.Column(c).Key("editor-col").Grow(1).MinWidth(0).Children(func() {
				a.editorArea(c)
			})
			a.backlinksView(c)
		} else {
			a.emptyState(c)
		}
	})
}

// searchBar 是全文检索输入框（FR-SEARCH-01，1 步可达）。
func (a *app) searchBar(c *ui.Context) {
	box := components.TextBox(c, &a.query, components.TextBoxOptions{LeadingIcon: lucide.Search()}).
		Label("搜索标题与正文").Placeholder("搜索标题与正文…").FillWidth()
	if box.Changed() {
		a.scheduleSearch()
	}
}

// sortBar 是排序选择（FR-NOTE-02）。
func (a *app) sortBar(c *ui.Context) {
	ui.Row(c).AlignItems(ui.Center).Children(func() {
		if a.searching {
			components.Spinner(c, components.SpinnerRing)
		}
		ui.Spacer(c).Grow(1)
		labels := map[string]string{
			"updated_desc": "更新时间 ↓", "updated_asc": "更新时间 ↑",
			"created_desc": "创建时间 ↓", "created_asc": "创建时间 ↑",
			"title_asc": "标题 A-Z", "title_desc": "标题 Z-A",
		}
		sel := labels[a.sort]
		if ui.Select(c, &sel, []string{"更新时间 ↓", "更新时间 ↑", "创建时间 ↓", "创建时间 ↑", "标题 A-Z", "标题 Z-A"}).Width(140).Label("排序方式").Changed() {
			for v, l := range labels {
				if l == sel && v != a.sort {
					a.setSort(v)
				}
			}
		}
	})
}

// noteList 是笔记卡片列表；检索态显示检索结果（FR-SEARCH-02/05）。
func (a *app) noteList(c *ui.Context) {
	t := c.Theme()
	if a.query != "" && strings.TrimSpace(a.query) != "" {
		ui.Textf(c, "检索结果 %d", len(a.hits)).FontSize(12).TextColor(t.TextMuted)
		if len(a.hits) == 0 && !a.searching {
			components.Text(c, "没有匹配「"+a.query+"」的笔记").TextColor(t.TextMuted)
		}
		ui.Scroll(c).Grow(1).FillWidth().Children(func() {
			for i, hit := range a.hits {
				hit := hit
				kc := c.Key(hit.NoteID)
				row := ui.Column(kc).Key("hit:"+hit.NoteID).Gap(2).Padding(8, 10).Radius(t.Radius).FillWidth()
				row.Transition(rowTransition(i))
				row.Background(row.AnimateColor("bg", hoverBg(t, row.Hovered()), hoverEase)).Children(func() {
					ui.Row(c).Gap(6).AlignItems(ui.Center).Children(func() {
						components.Text(c, hit.Title).Bold().Wrap()
						field := map[string]string{"title": "标题", "fileName": "文件名", "body": "正文"}[hit.MatchedField]
						components.Badge(c, field)
					})
					if spans := highlightSpans(hit, t); len(spans) > 0 {
						components.RichText(c, spans...)
					} else {
						components.Text(c, hit.Snippet).FontSize(12).TextColor(t.TextMuted).Wrap()
					}
				}).OnClick(func() {
					a.openNote(hit.NoteID)
				})
			}
		})
		return
	}

	notes := a.visibleNotes()
	if len(notes) == 0 {
		ui.Column(c).Fill().Center().Children(func() {
			components.Text(c, "还没有笔记，点击左侧「＋ 新建笔记」开始记录").TextColor(t.TextMuted)
		})
		return
	}
	ui.Scroll(c).Grow(1).FillWidth().Children(func() {
		for i, n := range notes {
			n := n
			kc := c.Key(n.ID)
			row := ui.Column(kc).Key("note:"+n.ID).Gap(2).Padding(8, 10).Radius(t.Radius).FillWidth()
			row.Transition(rowTransition(i))
			row.Background(row.AnimateColor("bg", hoverBg(t, row.Hovered()), hoverEase)).Children(func() {
				components.Text(c, n.Title).Bold().Wrap()
				if n.Summary != "" {
					components.Text(c, n.Summary).FontSize(12).TextColor(t.TextMuted).Wrap()
				}
				// 时间与标签分行显示：同排时标签会把时间挤成竖排换行。
				components.Text(c, formatTime(n.UpdatedAtUtc)).FontSize(11).TextColor(t.TextMuted)
				if len(n.Tags) > 0 {
					ui.Row(c).Gap(6).AlignItems(ui.Center).Children(func() {
						for j, tag := range n.Tags {
							if j >= 3 {
								break
							}
							components.Badge(c, "#"+tag)
						}
					})
				}
			}).OnClick(func() {
				a.openNote(n.ID)
			}).ContextMenu(func(m *ui.Menu) {
				if m.Item("移入回收站").Chosen() {
					a.trashWithUndo(c, n.ID, n.Title)
				}
			})
		}
	})
}

// trashWithUndo 删除并给出 5 秒撤销窗口（BR-11、AC-NOTE-02）。
func (a *app) trashWithUndo(c *ui.Context, id, title string) {
	a.deleteFromList(id)
	c.ToastAction("已将「"+title+"」移入回收站", "撤销", func() {
		a.vault.RestoreFromTrash(id)
		a.refreshLists()
		c.Toast("已撤销删除")
	})
}

// editorArea 是右侧编辑/阅读区（FR-EDIT-01/03）。
func (a *app) editorArea(c *ui.Context) {
	t := c.Theme()
	ed := a.currentEditor()
	if ed == nil {
		return
	}
	// 标题与工具行。顶部让出浮动标题栏高度（窗口按钮悬浮于此）。
	ui.Column(c).Grow(1).MinWidth(0).Gap(t.Space(2)).Padding(sidebar.HeaderHeight(c), t.Space(3), t.Space(2), t.Space(2)).Children(func() {
		ui.Row(c).Gap(t.Space(2)).AlignItems(ui.Center).Children(func() {
			// 标题编辑缓冲持久保存（app.titleEdit）。绑定到每帧重建的局部
			// 变量会在"删空→改名被拒"时触发框架的值回同步，把旧字符弹回
			// 输入框——表现为"最后一个字符删不掉"。草稿允许为空，失焦时回填。
			if !a.titleFocused && a.titleEdit != a.currentTitle {
				a.titleEdit = a.currentTitle
			}
			input := components.TextBox(c, &a.titleEdit, components.TextBoxOptions{}).
				Label("笔记标题").Placeholder("标题").Grow(1)
			a.titleFocused = input.Focused()
			if input.Changed() || input.Submitted() {
				trimmed := strings.TrimSpace(a.titleEdit)
				switch {
				case trimmed == "":
					if input.Submitted() {
						// 空标题被领域拒绝（ErrTitleEmpty）：提交时回填原标题。
						a.titleEdit = a.currentTitle
					}
					// 删空过程保持草稿态，不改名、不回弹。
				case trimmed != a.currentTitle:
					a.renameCurrent(trimmed)
				}
			}
			a.saveBadge(c)
			a.modeSwitch(c)
		})
		ui.Row(c).Gap(t.Space(1)).AlignItems(ui.Center).Children(func() {
			ui.Spacer(c).Grow(1)
			if components.IconButton(c, lucide.Download(), "导出本篇", components.IconButtonPlain).Clicked() {
				// 导出读磁盘事实源：先落盘草稿保证导出内容完整（评审 #3）。
				if a.flushSaveNote(a.currentID) {
					go a.exportNoteFlow(a.currentID)
				} else {
					a.pendingToast = "保存失败，已中止导出"
				}
			}
			if components.IconButton(c, lucide.Trash(), "移入回收站", components.IconButtonPlain).Clicked() {
				a.trashWithUndo(c, a.currentID, a.currentTitle)
				return
			}
			if components.IconButton(c, lucide.Link2(), "反向链接", components.IconButtonPlain).Clicked() {
				a.showBacklinks = !a.showBacklinks
				a.loadBacklinks(a.currentID)
			}
		})

		// 内容区：源码编辑器或 Markdown 阅读页。
		// 按 笔记+模式 打键：换篇/切换模式时整块进出场过渡。
		if a.mode == "edit" {
			ui.Column(c).Key("pane:" + a.currentID + ":edit").Transition(paneSwap).Grow(1).MinWidth(0).Children(func() {
				a.autosave(c, ed)
				editor.View(c, ed.state).Grow(1).FillWidth()
				a.statsBar(c, ed)
			})
		} else {
			ui.Column(c).Key("pane:" + a.currentID + ":read").Transition(paneSwap).Grow(1).MinWidth(0).Children(func() {
				a.readerView(c)
			})
		}
	})
}

// modeSwitch 切换编辑/阅读（一次点击，OBJ-01）。
// 自绘分段控件：滑块与侧栏胶囊同款弹簧形变。不用 components.Tabs——
// 其 slideBehind 在 paint-only 帧持有过期 Element（上游缺陷，动画下会崩）。
func (a *app) modeSwitch(c *ui.Context) {
	t := c.Theme()
	pill := t.Space(20)
	var active ui.Element // 同 pass 的选中段；Draw 在布局后读取它的矩形
	track := ui.Row(c).Key("mode-track").Padding(t.Space(0.5)).Gap(t.Space(0.5)).Radius(pill).
		Background(t.Text.Alpha(.06)).AlignSelf(ui.Start)
	track.Draw(func(p *ui.Painter, r ui.Rect) {
		if !active.Valid() {
			return
		}
		b := active.LayoutBounds()
		x := track.AnimateTo("mode-thumb-x", b.X-r.X, navPillMorph)
		w := track.AnimateTo("mode-thumb-w", b.W, navPillMorph)
		p.Fill(ui.Rect{X: r.X + x, Y: b.Y, W: w, H: b.H}, t.Surface, pill)
	})
	track.Children(func() {
		for _, seg := range []struct {
			label string
			read  bool
		}{{"编辑", false}, {"阅读", true}} {
			seg := seg
			on := (a.mode == "read") == seg.read
			// 不用 Selected 着色：它的选中面会瞬跳，遮住下方的滑动胶囊。
			el := ui.ButtonBase(c).Key(seg.label).Label(seg.label).
				Padding(t.Space(1.5), t.Space(3)).Radius(pill).
				Background(ui.Color{}).TextColor(t.Text)
			if !on {
				el.TextColor(t.Text.Alpha(.55))
			}
			el.Children(func() {
				txt := components.Text(c, seg.label)
				if on {
					txt.Bold()
				}
			})
			if on {
				active = el
			}
			if el.Clicked() {
				if seg.read {
					a.enterReadMode()
				} else {
					a.mode = "edit"
				}
			}
		}
	})
}

// enterReadMode 先落盘草稿再生成阅读快照（评审 #8：快照只生成一次，不逐帧重算）。
func (a *app) enterReadMode() {
	a.flushSave()
	a.mode = "read"
	a.readerSrc = a.vault.RenderReaderSource(a.currentID)
}

// saveBadge 显示保存状态（BR-05）。
func (a *app) saveBadge(c *ui.Context) {
	t := c.Theme()
	var text string
	var color ui.Color
	switch a.saveState {
	case saveSaved:
		text, color = "已保存", t.Success
	case saveSaving:
		text, color = "保存中…", t.Warning
	case saveError:
		text, color = "保存失败", t.Danger
	default:
		text, color = "正在输入…", t.Warning
	}
	badge := components.Text(c, "● "+text).FontSize(12).TextColor(color)
	if a.saveState == saveError {
		// 用户显式点击重试：强制覆盖保存（含外部修改冲突，评审 #7）。
		badge.OnClick(func() { a.saveNowForce() })
	}
}

// autosave 检测未落盘修改并安排防抖保存，同时节流更新字数统计。
func (a *app) autosave(c *ui.Context, ed *noteEditor) {
	buf := ed.state.Buffer
	if buf.Version() != ed.saved {
		if a.saveState != saveSaving && a.saveState != saveError {
			a.saveState = saveDirty
		}
		a.scheduleSave()
	}
	if buf.Version() != a.statsVersion {
		a.currentStats = computeTextStats(buf.String())
		a.statsVersion = buf.Version()
		c.After(400 * time.Millisecond) // 节流：稍后再刷一帧继续统计
	}
}

// statsBar 显示实时统计（FR-NOTE-08）。
func (a *app) statsBar(c *ui.Context, ed *noteEditor) {
	t := c.Theme()
	s := a.currentStats
	ui.Row(c).Gap(t.Space(3)).Children(func() {
		parts := []string{itoa(s.CharCount) + " 字符"}
		if s.CJKCharCount > 0 {
			parts = append(parts, itoa(s.CJKCharCount)+" 汉字")
		}
		if s.EnWordCount > 0 {
			parts = append(parts, itoa(s.EnWordCount)+" 英文词")
		}
		parts = append(parts, "约 "+itoa(s.ReadingTimeMinute)+" 分钟阅读")
		components.Text(c, strings.Join(parts, " · ")).FontSize(12).TextColor(t.TextMuted).Grow(1)
		components.Text(c, "反向链接 ("+itoa(len(a.backlinks))+")").FontSize(12).TextColor(t.Accent)
	})
}

// readerView 是独立阅读页：渲染 Markdown 快照（FR-EDIT-01、BR-06）。
// 快照在进入阅读模式时生成（a.readerSrc），避免每帧重新改写双链（评审 #8）。
func (a *app) readerView(c *ui.Context) {
	ui.Scroll(c).Grow(1).FillWidth().Children(func() {
		ui.Column(c).FillWidth().MaxWidth(820).Children(func() {
			markdown.View(c, a.readerSrc, &markdown.Options{
				OnLink:    func(u string) { a.onReaderLink(c, u) },
				LoadImage: a.loadImage,
				WrapCode:  true,
			})
		})
	})
}

// onReaderLink 处理阅读页链接（FR-LINK-03/04/05）。
func (a *app) onReaderLink(c *ui.Context, raw string) {
	switch {
	case strings.HasPrefix(raw, "http://"), strings.HasPrefix(raw, "https://"), strings.HasPrefix(raw, "mailto:"):
		c.OpenURL(raw)
	case strings.HasPrefix(raw, readerSchemeNote):
		id := strings.TrimPrefix(raw, readerSchemeNote)
		if a.vault.LookupNote(id) {
			a.openNote(id)
			return
		}
		if target, err := url.PathUnescape(id); err == nil {
			a.confirmCreateFromLink(c, target)
		}
	case strings.HasPrefix(raw, readerSchemeOpen):
		target, err := url.PathUnescape(strings.TrimPrefix(raw, readerSchemeOpen))
		if err != nil {
			return
		}
		a.confirmCreateFromLink(c, target)
	}
}

// confirmCreateFromLink 弹出确认框，一键基于标题创建笔记（BR-07）。
func (a *app) confirmCreateFromLink(c *ui.Context, target string) {
	a.confirm = confirmState{
		open:    true,
		title:   "创建笔记",
		message: "笔记「" + target + "」尚未创建，是否立即创建？",
		onOK: func() {
			if doc, err := a.vault.CreateNoteFromLink(target); err == nil {
				a.refreshLists()
				a.openNote(doc.ID)
			}
		},
	}
}

// loadImage 为阅读页加载本地图片：相对笔记目录或 notes/ 根，
// 校验不越出工作区（NFR-SEC-03），远程图片不加载。
func (a *app) loadImage(u string) (ui.ImageSource, error) {
	if strings.Contains(u, "://") || strings.HasPrefix(u, "data:") {
		return nil, os.ErrNotExist
	}
	abs, err := a.vault.NoteAssetPath(a.currentID, u)
	if err != nil {
		return nil, err
	}
	data, err := os.ReadFile(abs)
	if err != nil {
		return nil, err
	}
	return ui.DecodeImage(data)
}

// backlinksView 是反向引用面板（FR-LINK-06/07）。
// 宽度用弹簧补间（收起→0 / 展开→280），编辑区每帧精确互补；
// 内容在面板足够宽后淡入，避免窄宽下的挤压换行。
func (a *app) backlinksView(c *ui.Context) {
	t := c.Theme()
	panel := ui.Column(c).Key("backlinks").Shrink(0).Gap(t.Space(2)).
		Padding(sidebar.HeaderHeight(c), t.Space(2), t.Space(2), t.Space(2)).Background(t.Surface)
	target := float32(0)
	if a.showBacklinks {
		target = 280
	}
	w := panel.AnimateTo("w", target, panelMotion)
	panel.Width(w)
	if w < 150 {
		return
	}
	panel.Children(func() {
		body := ui.Column(c).Key("backlinks-body").FillWidth().Gap(t.Space(2))
		body.Transition(fadeSwap)
		body.Children(func() {
			components.Text(c, "🔗 反向链接（"+itoa(len(a.backlinks))+"）").Bold()
			if len(a.backlinks) == 0 {
				components.Text(c, "还没有笔记引用「"+a.currentTitle+"」。").FontSize(12).TextColor(t.TextMuted).Wrap()
				components.Text(c, "在其他笔记里写下 [[ "+a.currentTitle+" ]] 就会出现在这里。").FontSize(12).TextColor(t.TextMuted).Wrap()
			}
			ui.Scroll(c).Grow(1).FillWidth().Children(func() {
				for _, b := range a.backlinks {
					b := b
					kc := c.Key(b.SourceNoteID + itoa(b.Line))
					ui.Column(kc).Gap(2).Padding(8, 10).Radius(t.Radius).FillWidth().Children(func() {
						components.Text(c, b.SourceTitle).Bold().Wrap()
						components.Text(c, b.Snippet).FontSize(12).TextColor(t.TextMuted).Wrap()
						components.Text(c, "第 "+itoa(b.Line)+" 行").FontSize(11).TextColor(t.TextMuted)
					}).OnClick(func() {
						a.openNote(b.SourceNoteID)
					})
				}
			})
		})
	})
}

// emptyState 是未选中笔记时的占位。
func (a *app) emptyState(c *ui.Context) {
	t := c.Theme()
	ui.Column(c).Grow(1).Center().Gap(t.Space(3)).Children(func() {
		components.Text(c, "在左侧选择或新建一篇笔记开始记录。").TextColor(t.TextMuted)
		components.Text(c, "支持 #标签、[[双链]]、全文检索与自动保存。").TextColor(t.TextMuted)
	})
}

// exportNoteFlow 导出单篇（FR-DATA-02）。
func (a *app) exportNoteFlow(id string) {
	path, err := a.vault.ExportNote(a.win, id)
	if err != nil || path == "" {
		return
	}
	a.onUIThread(func() { a.pendingToast = "已导出到 " + path })
}

// itoa 是 strconv.Itoa 的短别名，视图里大量使用。
func itoa(n int) string { return strconv.Itoa(n) }

// hoverBg 返回卡片悬停时的底色目标：悬停轻微着色，离开回到透明，
// 配合 AnimateColor 形成渐变而非硬切。
func hoverBg(t *ui.Theme, on bool) ui.Color {
	if on {
		return t.Text.Alpha(.06)
	}
	return ui.Color{}
}

// highlightSpans 把检索摘要按后端给出的 rune 区间切成富文本片段，
// 命中部分用主题强调色背景高亮（FR-SEARCH-05）。
func highlightSpans(hit SearchHit, t *ui.Theme) []ui.Span {
	chars := []rune(hit.Snippet)
	if len(hit.Ranges) == 0 {
		return nil
	}
	marked := make([]bool, len(chars))
	for _, r := range hit.Ranges {
		for i := r[0]; i < r[1] && i < len(marked); i++ {
			marked[i] = true
		}
	}
	spans := []ui.Span{}
	run := func(from, to int, mark bool) {
		if from >= to {
			return
		}
		text := string(chars[from:to])
		if mark {
			spans = append(spans, ui.Span{Text: text, Background: t.Warning.Alpha(0.45)})
		} else {
			spans = append(spans, ui.Span{Text: text, Color: t.TextMuted})
		}
	}
	start := 0
	for i := 1; i <= len(marked); i++ {
		if i == len(marked) || marked[i] != marked[start] {
			run(start, i, marked[start])
			start = i
		}
	}
	return spans
}
