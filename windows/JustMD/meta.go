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
