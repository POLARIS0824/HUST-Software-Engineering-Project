<script setup lang="ts">
import { store, selectTag, createNote, type TagNode } from "../lib/store";

function tagNodes(nodes: TagNode[], depth: number): Array<{ node: TagNode; depth: number }> {
  const out: Array<{ node: TagNode; depth: number }> = [];
  for (const n of nodes) {
    out.push({ node: n, depth });
    if (n.children.length > 0) out.push(...tagNodes(n.children, depth + 1));
  }
  return out;
}
</script>

<template>
  <aside class="sidebar">
    <button class="btn-primary" @click="createNote">＋ 新建笔记</button>

    <button class="nav-item" :class="{ active: store.view === 'notes' && !store.selectedTag }" @click="store.view = 'notes'; selectTag(null)">
      <span>📄</span><span>全部笔记</span>
      <span class="count">{{ store.workspace.noteCount }}</span>
    </button>
    <button class="nav-item" :class="{ active: store.view === 'trash' }" @click="store.view = 'trash'">
      <span>🗑️</span><span>回收站</span>
      <span class="count">{{ store.workspace.trashCount }}</span>
    </button>
    <button class="nav-item" :class="{ active: store.view === 'settings' }" @click="store.view = 'settings'">
      <span>⚙️</span><span>设置与数据</span>
    </button>

    <div v-if="store.tags.length > 0" class="nav-section">标签</div>
    <div class="tag-tree">
      <div v-for="{ node, depth } in tagNodes(store.tags, 0)" :key="node.path" class="tag-node">
        <button
          class="tag-row"
          :class="{ active: store.view === 'notes' && store.selectedTag === node.path }"
          :style="{ paddingLeft: 8 + depth * 12 + 'px' }"
          @click="store.view = 'notes'; selectTag(node.path)"
        >
          <span class="tag-hash">#</span><span>{{ node.name }}</span>
          <span class="count">{{ node.count }}</span>
        </button>
      </div>
    </div>
  </aside>
</template>
