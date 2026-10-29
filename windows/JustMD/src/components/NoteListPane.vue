<script setup lang="ts">
import { computed } from "vue";
import { store, setSearchQuery, setSort, openNote, createNote, refreshLists, showUndo, showSnackbar } from "../lib/store";
import { Vault, type SearchHit } from "../mygo";

// 展示列表：检索态显示检索结果，标签筛选态做前缀过滤（支持多级标签父级），否则全部笔记。
const shown = computed<"notes" | "search">(() => (store.searchResults !== null ? "search" : "notes"));

const filteredNotes = computed(() => {
  if (!store.selectedTag) return store.notes;
  const tag = store.selectedTag.toLowerCase();
  return store.notes.filter((n) => n.tags.some((t) => t.toLowerCase().startsWith(tag)));
});

async function trashFromList(id: string) {
  const doc = await Vault.openNote(id);
  await Vault.trashNote(id);
  if (store.currentId === id) {
    store.current = null;
    store.currentId = null;
  }
  await refreshLists();
  // 5 秒撤销窗口（BR-11 / AC-NOTE-02）。
  showUndo(`已将「${doc.title}」移入回收站`, 5000, async () => {
    await Vault.restoreFromTrash(id);
    await refreshLists();
    showSnackbar("已撤销删除");
  });
}

async function openWithTerms(id: string) {
  const terms = store.searchQuery.trim().split(/\s+/).filter(Boolean);
  await openNote(id, terms);
}

function fieldLabel(f: string): string {
  return f === "title" ? "标题" : f === "fileName" ? "文件名" : "正文";
}

// 检索摘要按后端给的 rune 区间加高亮。
function highlightSnippet(snippet: string, ranges: Array<[number, number]>): string {
  const chars = Array.from(snippet);
  const marks = new Array<boolean>(chars.length).fill(false);
  for (const [s, e] of ranges ?? []) {
    for (let i = s; i < e && i < marks.length; i++) marks[i] = true;
  }
  let html = "";
  let openMark = false;
  for (let i = 0; i < chars.length; i++) {
    const esc = chars[i].replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
    if (marks[i] && !openMark) {
      html += "<mark class='search-hit'>";
      openMark = true;
    } else if (!marks[i] && openMark) {
      html += "</mark>";
      openMark = false;
    }
    html += esc;
  }
  if (openMark) html += "</mark>";
  return html;
}

function fmtTime(ms: number): string {
  const d = new Date(ms);
  const now = new Date();
  const hm = `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
  if (d.toDateString() === now.toDateString()) return hm;
  return `${d.getMonth() + 1}月${d.getDate()}日 ${hm}`;
}
</script>

<template>
  <section class="listpane">
    <div class="listpane-head">
      <div class="search-box">
        <span class="icon">🔍</span>
        <input
          id="global-search"
          type="text"
          placeholder="搜索标题与正文… (Ctrl+F)"
          :value="store.searchQuery"
          @input="setSearchQuery(($event.target as HTMLInputElement).value)"
          @keydown.esc="setSearchQuery('')"
        />
      </div>
      <div class="list-tools">
        <span class="label">
          {{ shown === "search" ? `检索结果 ${store.searchResults?.length ?? 0}` : filteredNotes.length > 0 ? `共 ${filteredNotes.length} 篇` : "" }}
        </span>
        <select :value="store.sortOption" @change="setSort(($event.target as HTMLSelectElement).value)">
          <option value="updated_desc">按更新时间 ↓</option>
          <option value="updated_asc">按更新时间 ↑</option>
          <option value="created_desc">按创建时间 ↓</option>
          <option value="created_asc">按创建时间 ↑</option>
          <option value="title_asc">按标题 A-Z</option>
          <option value="title_desc">按标题 Z-A</option>
        </select>
      </div>
    </div>

    <div class="list-scroll">
      <template v-if="shown === 'notes'">
        <button
          v-for="n in filteredNotes"
          :key="n.id"
          class="note-card"
          :class="{ active: n.id === store.currentId }"
          @click="openNote(n.id)"
        >
          <div class="note-title">{{ n.title }}</div>
          <div class="note-summary">{{ n.summary || "（空笔记）" }}</div>
          <div class="note-meta">
            <span>{{ fmtTime(n.updatedAtUtc) }}</span>
            <span class="tags">
              <span v-for="t in n.tags.slice(0, 3)" :key="t" class="mini-tag">#{{ t }}</span>
            </span>
          </div>
          <span class="card-actions">
            <button title="移入回收站" @click.stop="trashFromList(n.id)">🗑</button>
          </span>
        </button>
        <div v-if="filteredNotes.length === 0 && !store.selectedTag && store.notes.length === 0" class="empty-hint">
          还没有笔记<br /><br />
          <button class="btn primary" @click="createNote">＋ 新建第一篇笔记</button>
        </div>
        <div v-else-if="filteredNotes.length === 0 && store.selectedTag" class="empty-hint">该标签下还没有笔记</div>
      </template>

      <template v-else>
        <button
          v-for="hit in store.searchResults ?? []"
          :key="hit.noteId"
          class="search-result"
          @click="openWithTerms(hit.noteId)"
        >
          <div>
            <span class="note-title" style="display: inline">{{ hit.title }}</span>
            <span class="field-badge">{{ fieldLabel(hit.matchedField) }}</span>
          </div>
          <div class="snippet" v-html="highlightSnippet(hit.snippet, hit.ranges)"></div>
        </button>
        <div v-if="(store.searchResults?.length ?? 0) === 0 && !store.searching" class="empty-hint">
          没有匹配「{{ store.searchQuery }}」的笔记
        </div>
      </template>
    </div>
  </section>
</template>
