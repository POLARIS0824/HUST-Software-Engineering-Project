package main

import "testing"

// TestCreateNoteInSelectedCategory 在某个分类视图里点「新建笔记」，
// 新笔记直接落进该分类，不需要再移动一次；未分类视图里新建则留在根目录。
func TestCreateNoteInSelectedCategory(t *testing.T) {
	a, _, _ := newCategoryTestApp(t)
	a.toggleCategoryFilter("课程/软件工程")
	before := len(a.vault.ListNotes("updated_desc"))
	a.createNote()
	if a.currentID == "" {
		t.Fatal("新建后应打开新笔记")
	}
	a.refreshLists()
	notes := a.vault.ListNotes("updated_desc")
	if len(notes) != before+1 {
		t.Fatalf("应新增 1 篇，实际 %d → %d", before, len(notes))
	}
	var created *NoteSummary
	for i := range notes {
		if notes[i].ID == a.currentID {
			created = &notes[i]
		}
	}
	if created == nil {
		t.Fatal("新笔记应在列表里")
	}
	if created.Category != "课程/软件工程" {
		t.Fatalf("新笔记应落在当前分类，实际 %q", created.Category)
	}

	// 未分类视图里新建则留在根目录。
	a.toggleCategoryFilter("")
	a.createNote()
	a.refreshLists()
	for _, n := range a.vault.ListNotes("updated_desc") {
		if n.ID == a.currentID && n.Category != "" {
			t.Fatalf("未分类视图新建的笔记应留在根目录，实际 %q", n.Category)
		}
	}
}
