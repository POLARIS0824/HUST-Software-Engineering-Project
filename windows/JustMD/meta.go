package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"time"
)

// noteState 是笔记的软删除状态（BR-11）。
type noteState string

const (
	stateActive     noteState = "ACTIVE"
	stateTrashed    noteState = "TRASHED"
	originOriginal            = "ORIGINAL"
	originRecovered           = "RECOVERED"
)

// trashRetentionMs 是回收站保留期：30 天（基线 FR-NOTE-05/设计 6.2）。
const trashRetentionMs int64 = 30 * 24 * 3600 * 1000

// NoteMeta 是每篇笔记的开放 JSON 元数据（设计 6.2，schemaVersion 1）。
// 正文事实源始终是 .md 文件；元数据只补齐 UUID 与原始时间等不可推导字段。
type NoteMeta struct {
	SchemaVersion  int       `json:"schemaVersion"`
	NoteID         string    `json:"noteId"`
	RelativePath   string    `json:"relativePath"` // 相对 notes/ 的正斜杠路径
	CreatedAtUtc   int64     `json:"createdAtUtc"`
	UpdatedAtUtc   int64     `json:"updatedAtUtc"`
	Revision       int64     `json:"revision"`
	ContentSha256  string    `json:"contentSha256"`
	State          noteState `json:"state"`
	TrashedAtUtc   int64     `json:"trashedAtUtc,omitempty"`
	MetadataOrigin string    `json:"metadataOrigin"`
}

// PendingOp 是崩溃恢复协议的操作意图记录（设计 6.2）。
// 先持久化记录，再提交正文与元数据；恢复按哈希幂等补完。
type PendingOp struct {
	OperationID      string `json:"operationId"`
	NoteID           string `json:"noteId"`
	Kind             string `json:"kind"` // save / rename / trash / restore / delete
	OldRelPath       string `json:"oldRelPath,omitempty"`
	NewRelPath       string `json:"newRelPath,omitempty"`
	OldSha           string `json:"oldSha,omitempty"`
	NewSha           string `json:"newSha,omitempty"`
	ExpectedRevision int64  `json:"expectedRevision"`
	// Cascade 记录本次改名已经级联改写过的其它笔记（改写前后的正文与元数据）。
	//
	// 为什么需要它：改名的落盘顺序是「写 pending → 级联改写其它笔记 →
	// 改名本篇」。若在「级联已成功、本篇尚未改名」之间崩溃，重启对账只看得到
	// 一条 rename pending，却不知道其它笔记的正文已被改成新标题，
	// 结果是留下指向不存在笔记的 [[新标题]] 与凭空消失的反链（评审 #2）。
	// 把改写前的正文记进 pending，对账时就能把这一批精确回滚。
	//
	// 为空表示级联尚未开始，或没有可改写的笔记。
	Cascade []PendingCascadeNote `json:"cascade,omitempty"`
}

// PendingCascadeNote 记录级联改写中某篇笔记的改写前正文与元数据，
// 供崩溃恢复时精确回滚（评审 #2）。
type PendingCascadeNote struct {
	NoteID  string `json:"noteId"`
	RelPath string `json:"relPath"`
	// OldBody 是改写前的磁盘正文（已归一化）。
	OldBody string `json:"oldBody"`
	// NewBody 是级联改写准备写入的正文；对账时用它判断这一篇
	// 到底改没改过（崩溃可能发生在级联写到一半的时候）。
	NewBody         string `json:"newBody"`
	OldRevision     int64  `json:"oldRevision"`
	OldUpdatedAtUtc int64  `json:"oldUpdatedAtUtc"`
}

// metaDir 等工作区子目录的固定命名（设计 6.2）。
const (
	dirNotes       = "notes"
	dirAttachments = "attachments"
	dirMeta        = ".meta"
	dirMetaNotes   = ".meta/notes"
	dirMetaPending = ".meta/pending"
	dirTrash       = ".trash"
	dirTmp         = ".tmp"
	metaSchemaV1   = 1
)

// loadNoteMeta 读取单篇元数据；文件不存在返回 nil。
func loadNoteMeta(path string) (*NoteMeta, error) {
	data, err := os.ReadFile(path)
	if os.IsNotExist(err) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	var m NoteMeta
	if err := json.Unmarshal(normalizeContent(data), &m); err != nil {
		return nil, err
	}
	return &m, nil
}

// saveNoteMeta 原子写回元数据 JSON。
func saveNoteMeta(metaDir string, m *NoteMeta) error {
	data, err := json.MarshalIndent(m, "", "  ")
	if err != nil {
		return err
	}
	return atomicWriteFile(filepath.Join(metaDir, m.NoteID+".json"), data)
}

// writePending 先落盘操作意图记录。
func writePending(pendingDir string, op *PendingOp) error {
	data, err := json.MarshalIndent(op, "", "  ")
	if err != nil {
		return err
	}
	return atomicWriteFile(filepath.Join(pendingDir, op.OperationID+".json"), data)
}

// clearPending 在提交完成后移除操作记录。
func clearPending(pendingDir, opID string) {
	os.Remove(filepath.Join(pendingDir, opID+".json"))
}

// nowMs 当前 UTC 毫秒时间戳（基线 4.4）。
func nowMs() int64 {
	return time.Now().UTC().UnixMilli()
}
