package main

import (
	"math"
	"time"

	"github.com/egoist/mygo/ui"
)

// 删除笔记的"碎纸机"动画：把这一行真实渲染出来的内容按竖条切片，
// 每片错峰下落、左右交替旋转并淡出，像纸张被碎纸机咬碎。
//
// 实现原理（MyGo 自带能力，不用截屏）：
//   - Element.Effect 会把元素及其子树渲染成一张位图交给自定义 Effect；
//   - Painter.ImageRegion 能把位图的任意像素矩形画到指定位置（可在
//     Painter.Transform 的位移/旋转之下），所以每一片就是"原行位图的一条"。
//
// 设计取舍：
//   - 动画期间**不**提前删除：行留在原位播放，结束后才真正移入回收站。
//     这样被切的就是这一行自己的渲染结果（标题/摘要/标签/悬停底色都原样），
//     不需要额外的浮层或截图；进程中途被杀也只是"没删成"，不会出现
//     界面说删了、磁盘还在的中间态。
//   - 秒级时长（约 0.56s）+ 纯视觉：删除语义、撤销 Toast、快捷键行为不变。
const (
	// shredDuration 是碎纸动画时长：太短看不出分片，太长会挡住连续操作。
	shredDuration = 560 * time.Millisecond
	// shredStrips 是竖条数量，9 条在 300px 宽的列表里既有"碎"感又不糊。
	shredStrips = 9
)

// shredAnim 是一次正在播放的删除动画。
type shredAnim struct {
	// note 是被删除笔记的快照：动画期间笔记还在库里（结束时才删），
	// 这里只留撤销文案与按 ID 匹配所需的最小信息。
	note  NoteSummary
	start time.Time
	dur   time.Duration
	seed  uint32
}

// progress 返回 0..1 的动画进度；未开始（start 为零值）时为 0。
func (s *shredAnim) progress(now time.Time) float32 {
	if s == nil || s.dur <= 0 || s.start.IsZero() {
		return 0
	}
	return clamp01(float32(now.Sub(s.start)) / float32(s.dur))
}

// begin 记录动画起点（首帧渲染时调用：Tester 的虚拟时钟也从这里对齐）。
func (s *shredAnim) begin(now time.Time) {
	if s != nil && s.start.IsZero() {
		s.start = now
	}
}

// shredEffect 把元素渲染出的内容切成竖条，按进度下落/旋转/淡出。
type shredEffect struct {
	t      float32 // 0..1
	strips int
	seed   uint32
}

// EffectMargin 不读取盒子外的内容：分片只往外飞，不需要额外边距。
func (s *shredEffect) EffectMargin() ui.Point { return ui.Point{} }

// PaintEffect 绘制分片：content 是元素（含子树）渲染出的位图（像素），
// box 是元素在视图里的盒子（DIP），inset 是盒子左上角在位图中的位置。
func (s *shredEffect) PaintEffect(p *ui.Painter, box ui.Rect, content *ui.Bitmap, inset ui.Point) {
	cw, ch := content.Size()
	if cw <= 0 || ch <= 0 || box.W <= 0 || box.H <= 0 {
		return
	}
	n := s.strips
	if n < 3 {
		n = shredStrips
	}
	t := clamp01(s.t)
	// 只在行自己的盒子里画：分片下落时不会盖到下面的行（列表按顺序绘制，
	// 越界的分片会被后续行压住，看起来像残影）。裁在盒子里也更像
	// "卡片被下面的碎纸机咬掉"：碎片沉到卡片底边就消失。
	p.Clip(box, 8, func() {
		for i := 0; i < n; i++ {
			// 源矩形按像素取整，片与片之间不重不漏，避免重采样出现接缝。
			x0 := float32(int(float32(cw)*float32(i)/float32(n) + 0.5))
			x1 := float32(int(float32(cw)*float32(i+1)/float32(n) + 0.5))
			if x1 <= x0 {
				continue
			}
			// 目标位置同理按盒子宽度切分，未动时拼回原样。
			dx0 := box.X + box.W*float32(i)/float32(n)
			dx1 := box.X + box.W*float32(i+1)/float32(n)

			// 每片错峰约 26ms，从右往左依次被"咬"下去。
			stagger := float32(i) * 0.026
			local := clamp01((t - stagger) / (1 - float32(n-1)*0.026))

			// 重力感：先慢后快；左右交替旋转 + 轻微外飘；中后段淡出。
			// 下落距离控制在约一行高：碎片在卡片内散开，不会盖住下面的内容。
			fall := box.H * (0.85*local*local + 0.10*local)
			dir := float32(1)
			if i%2 == 1 {
				dir = -1
			}
			jitter := float32((s.seed>>uint(i%8))&7) / 7 // 0..1
			rot := dir * (5 + 9*local) * (0.7 + 0.6*jitter)
			if i == 0 {
				// 最左一片几乎不转，读起来更像"被切碎"而不是整体散架。
				rot *= 0.35
			}
			drift := (float32(i) - float32(n-1)/2) * (dx1 - dx0) * 0.28 * local
			sway := math.Sin(float64(local)*math.Pi*1.7+float64(i)*1.3) * 3.5 * float64(local)
			alpha := 1 - smoothstep(0.45, 0.95, local)

			opts := ui.ImageOptions{}
			if alpha < 0.999 {
				// 白色乘以 alpha：只淡出，不改色（Tint 是乘法滤镜）。
				opts.Tint = ui.RGBA(255, 255, 255, alpha)
			}
			src := ui.Rect{X: inset.X + x0, Y: inset.Y, W: x1 - x0, H: float32(ch)}
			// 目标宽度留 1px 缝：分片下落时看得出是"一条条"，而不是整块在滑。
			dw := dx1 - dx0 - 1
			if dw < 1 {
				dw = dx1 - dx0
			}
			dst := ui.Rect{X: dx0, Y: box.Y, W: dw, H: box.H}
			cx, cy := dst.X+dst.W/2, dst.Y+dst.H/2
			// 先整体位移（下落 + 外飘 + 摆动），再绕这一片自己的中心旋转。
			offY := fall + float32(sway)
			p.Transform(ui.Translation(drift, offY), 0, 0, func() {
				p.Transform(ui.Rotation(rot), cx, cy, func() {
					p.ImageRegion(content, src, dst, opts)
				})
			})
		}
	})
}

// clamp01 把浮点值夹到 [0,1]。
func clamp01(v float32) float32 {
	if v < 0 {
		return 0
	}
	if v > 1 {
		return 1
	}
	return v
}

// smoothstep 是 0..1 的平滑插值（配合淡出用，端点导数为 0）。
func smoothstep(edge0, edge1, x float32) float32 {
	if edge1 <= edge0 {
		return 0
	}
	t := clamp01((x - edge0) / (edge1 - edge0))
	return t * t * (3 - 2*t)
}
