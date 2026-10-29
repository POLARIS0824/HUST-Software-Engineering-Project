<script setup lang="ts">
import { computed, ref, watch, nextTick } from "vue";
import { store, followWikilink } from "../lib/store";
import { renderMarkdown } from "../lib/markdown";

// 阅读器只读取当前快照，不回写正文（FR-EDIT-01、BR-06）。
const html = computed(() => {
  if (!store.current) return "";
  const dir = store.current.relativePath.includes("/")
    ? store.current.relativePath.slice(0, store.current.relativePath.lastIndexOf("/"))
    : "";
  return renderMarkdown(store.current.markdown, {
    noteId: store.current.id,
    noteDir: dir,
    links: store.current.links,
    highlightTerms: store.highlightTerms,
  });
});

const container = ref<HTMLElement | null>(null);

// 点击代理：双链跳转（resolved/unresolved/ambiguous 统一入口）。
function onClick(e: MouseEvent) {
  const a = (e.target as HTMLElement).closest("a.wikilink") as HTMLAnchorElement | null;
  if (a) {
    e.preventDefault();
    void followWikilink(a.dataset.target ?? "", a.dataset.noteId);
  }
}

// 检索跳转时滚动到首个高亮命中（FR-SEARCH-04）。
watch(
  () => [store.currentId, store.mode] as const,
  async () => {
    await nextTick();
    const first = container.value?.querySelector("mark.search-hit");
    first?.scrollIntoView({ behavior: "smooth", block: "center" });
  },
);
</script>

<template>
  <div ref="container" class="reader" @click="onClick">
    <div class="reader-inner" v-html="html"></div>
  </div>
</template>
