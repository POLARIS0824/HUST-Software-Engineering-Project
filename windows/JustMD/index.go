package main

import (
	"os"
	"path/filepath"
)

// indexNote 以给定正文重建单篇笔记的派生数据（BR-02），原地更新 v.notes。
// 不自动重算链接：调用方在标题集合变化后调用 recomputeLinks，
// 仅正文变化时调用 resolveNoteLinks（避免全量重算）。
func (v *Vault) indexNote(meta *NoteMeta, body string) {
	title := titleOfRelPath(meta.RelativePath)
	tags := extractTags(body)
	links := extractLinks(body)
	note := &indexedNote{
		meta:          meta,
		title:         title,
		titleLower:    foldString(title),
		fileName:      filepath.Base(meta.RelativePath),
		fileNameLower: foldString(filepath.Base(meta.RelativePath)),
		parentDir:     parentDirOfRelPath(meta.RelativePath),
		body:          body,
		bodyFold:      foldString(body),
		tags:          tags,
		links:         links,
		linkStates:    make([]linkState, len(links)),
		resolvedIDs:   make([]string, len(links)),
		stats:         computeTextStats(body),
		summary:       plainSummary(body, 120),
	}
	if info, err := fileInfo(v.notesDir(), meta.RelativePath); err == nil {
		note.fileSize = info
	}
	if note.tags == nil {
		note.tags = []ExtractedTag{}
	}
	v.notes[meta.NoteID] = note
}

// reindexOne 重新解析一篇已有条目的正文（重命名/级联改写后调用）。
func (v *Vault) reindexOne(n *indexedNote, body string) {
	title := titleOfRelPath(n.meta.RelativePath)
	n.title = title
	n.titleLower = foldString(title)
	n.fileName = filepath.Base(n.meta.RelativePath)
	n.fileNameLower = foldString(n.fileName)
	n.parentDir = parentDirOfRelPath(n.meta.RelativePath)
	n.body = body
	n.bodyFold = foldString(body)
	n.tags = extractTags(body)
	if n.tags == nil {
		n.tags = []ExtractedTag{}
	}
	n.links = extractLinks(body)
	n.linkStates = make([]linkState, len(n.links))
	n.resolvedIDs = make([]string, len(n.links))
	n.stats = computeTextStats(body)
	n.summary = plainSummary(body, 120)
}

// recomputeLinks 重建 byTitle 索引并解析全部 WikiLink（基线 4.6）：
// 唯一候选 → RESOLVED；无候选 → UNRESOLVED（BR-07 合法悬空）；
// 多候选时同目录唯一命中 → RESOLVED，否则 AMBIGUOUS。
// 仅在标题集合变化（增删改名/回收/恢复）后调用；单篇正文保存用 resolveNoteLinks。
func (v *Vault) recomputeLinks() {
	byTitle := map[string][]string{}
	for id, n := range v.notes {
		byTitle[n.titleLower] = append(byTitle[n.titleLower], id)
	}
	v.byTitle = byTitle
	for _, n := range v.notes {
		v.resolveNoteLinks(n)
	}
}

// resolveNoteLinks 按当前 byTitle 解析单篇笔记的出链状态。
func (v *Vault) resolveNoteLinks(n *indexedNote) {
	for i, l := range n.links {
		candidates := v.byTitle[l.TargetLower]
		switch {
		case len(candidates) == 0:
			n.linkStates[i] = linkUnresolved
			n.resolvedIDs[i] = ""
		case len(candidates) == 1:
			n.linkStates[i] = linkResolved
			n.resolvedIDs[i] = candidates[0]
		default:
			sameDir := ""
			for _, cid := range candidates {
				if v.notes[cid].parentDir == n.parentDir {
					if sameDir != "" {
						sameDir = "?" // 同目录仍有多个同名
						break
					}
					sameDir = cid
				}
			}
			if sameDir != "" && sameDir != "?" {
				n.linkStates[i] = linkResolved
				n.resolvedIDs[i] = sameDir
			} else {
				n.linkStates[i] = linkAmbiguous
				n.resolvedIDs[i] = ""
			}
		}
	}
}

// fileInfo 返回笔记文件大小。
func fileInfo(notesDir, rel string) (int64, error) {
	info, err := os.Stat(filepath.Join(notesDir, filepath.FromSlash(rel)))
	if err != nil {
		return 0, err
	}
	return info.Size(), nil
}
