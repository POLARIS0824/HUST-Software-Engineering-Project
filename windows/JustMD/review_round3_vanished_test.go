package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// 本文件覆盖 PR #1 第三轮评审的评审 #4（NoteFileExists/DropVanishedNote
// 把任意 Stat 错误当"已删除"，中）。
//
// 旧实现用 !NoteFileExists 判断外部删除，但 NoteFileExists 内部 Stat 出错
// （权限问题、共享冲突、网络盘断开）时返回 false，于是被误判为"文件已被
// 删除" → 误清内存索引、误标 RECOVERED → 笔记下次启动变成新的身份，
// 双链在两侧都被清空。

// TestR4_NoteVanishedOnlyOnNotExist 只有拿到明确的"文件不存在"
// 才是僵尸笔记；状态未知（权限/共享冲突/网络盘断开）不算（评审 #4）。
func TestR4_NoteVanishedOnlyOnNotExist(t *testing.T) {
	v := newTestVault(t)
	doc := mustCreate(t, v, "存在性")

	if v.NoteVanished(doc.ID) {
		t.Fatal("文件还在时不应判定为消失")
	}
	if !v.NoteFileExists(doc.ID) {
		t.Fatal("文件还在时 NoteFileExists 应为 true")
	}

	abs := filepath.Join(v.notesDir(), filepath.FromSlash(doc.RelativePath))
	os.Remove(abs)

	if !v.NoteVanished(doc.ID) {
		t.Fatal("文件确实被删除后应判定为消失")
	}
	if v.NoteFileExists(doc.ID) {
		t.Fatal("文件被删除后 NoteFileExists 应为 false")
	}
}

// TestR4_DropVanishedNoteKeepsNoteOnUnknownState 状态未知时
// DropVanishedNote 必须拒绝移除索引与元数据（评审 #4）。
//
// 构造方式：给笔记一个含非法文件名字符（"<"）的相对路径。
// Windows 上 Stat 这样的路径返回 "The filename, directory name, or volume
// label syntax is incorrect."——这不是 os.IsNotExist（实测确认），
// 属于"状态未知"：文件其实还在，只是路径本身不合法。
func TestR4_DropVanishedNoteKeepsNoteOnUnknownState(t *testing.T) {
	v := newTestVault(t)
	// 手工造一篇路径含非法字符的笔记
	rel := "非法<名字>.md"
	meta := &NoteMeta{
		SchemaVersion: metaSchemaV1, NoteID: newID(), RelativePath: rel,
		CreatedAtUtc: nowMs(), UpdatedAtUtc: nowMs(), Revision: 1,
		ContentSha256: sha256Hex([]byte("正文")), State: stateActive, MetadataOrigin: originOriginal,
	}
	metaDir, _ := v.metaDirs()
	if err := saveNoteMeta(metaDir, meta); err != nil {
		t.Fatal(err)
	}
	v.indexNote(meta, "正文")

	// 前置确认：Stat 确实返回非 IsNotExist 的错误
	abs := filepath.Join(v.notesDir(), filepath.FromSlash(rel))
	if _, err := os.Stat(abs); err == nil {
		t.Skip("本平台上该路径可正常 Stat，跳过（无法构造状态未知的场景）")
	} else if os.IsNotExist(err) {
		t.Skipf("本平台把该错误映射成 NotExist（%v），无法构造状态未知的场景", err)
	}

	// 状态未知：不得判定为消失
	if v.NoteVanished(meta.NoteID) {
		t.Fatal("Stat 报非 ENOENT 错误时不应判定为消失（评审 #4）")
	}
	// 索引与元数据必须保留
	if _, err := v.DropVanishedNote(meta.NoteID); err == nil {
		t.Fatal("状态未知时 DropVanishedNote 必须拒绝移除")
	}
	if _, ok := v.notes[meta.NoteID]; !ok {
		t.Fatal("状态未知时索引条目必须保留")
	}
	if _, err := os.Stat(filepath.Join(metaDir, meta.NoteID+".json")); err != nil {
		t.Fatal("状态未知时元数据必须保留")
	}
}

// TestR4_AppLayerStillDropsTrulyVanishedNote 真正被外部删除的笔记，
// 界面层仍应提示并从列表移除（评审 #4 不能矫枉过正）。
func TestR4_AppLayerStillDropsTrulyVanishedNote(t *testing.T) {
	a := newTestApp(t)
	doc := mustCreateForApp(t, a, "真僵尸")
	other := mustCreateForApp(t, a, "正常")
	a.refreshLists()
	a.openNote(doc.ID)

	abs := filepath.Join(a.vault.notesDir(), filepath.FromSlash(doc.RelativePath))
	if err := os.Remove(abs); err != nil {
		t.Fatal(err)
	}

	a.openNote(doc.ID)
	if !strings.Contains(a.pendingToast, "外部删除") {
		t.Fatalf("应提示文件被外部删除，得到 %q", a.pendingToast)
	}
	for _, n := range a.notes {
		if n.ID == doc.ID {
			t.Fatal("僵尸笔记应从列表移除")
		}
	}
	for _, n := range a.notes {
		if n.ID == other.ID {
			return
		}
	}
	t.Fatal("其它笔记不应受影响")
}
