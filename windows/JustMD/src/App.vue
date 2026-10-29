<script setup lang="ts">
import { onMounted, computed } from "vue";
import { store, initApp, saveNow, createNote, startImport, chooseWorkspace, importDroppedFiles } from "./lib/store";
import TitleBar from "./components/TitleBar.vue";
import SidebarNav from "./components/SidebarNav.vue";
import NoteListPane from "./components/NoteListPane.vue";
import EditorPane from "./components/EditorPane.vue";
import TrashView from "./components/TrashView.vue";
import SettingsView from "./components/SettingsView.vue";
import ModalHost from "./components/ModalHost.vue";

onMounted(() => {
  void initApp();
});

const showListPane = computed(() => store.view === "notes");

// 全局快捷键（角色 2：Ctrl+S 强制保存、Ctrl+F 检索、Ctrl+N 新建、Ctrl+E 切换编辑/阅读）。
function onKeydown(e: KeyboardEvent) {
  const ctrl = e.ctrlKey || e.metaKey;
  if (!ctrl) return;
  const key = e.key.toLowerCase();
  if (key === "s") {
    e.preventDefault();
    void saveNow();
  } else if (key === "f") {
    e.preventDefault();
    (document.getElementById("global-search") as HTMLInputElement | null)?.focus();
  } else if (key === "n") {
    e.preventDefault();
    void createNote();
  } else if (key === "e") {
    e.preventDefault();
    store.mode = store.mode === "edit" ? "read" : "edit";
  } else if (key === "i" && e.shiftKey) {
    e.preventDefault();
    void startImport();
  }
}

// 拖放导入：md 文件入库，图片进 attachments。
async function onDrop(e: DragEvent) {
  e.preventDefault();
  const files = e.dataTransfer?.files;
  if (!files || files.length === 0) return;
  const paths = Array.from(files)
    .map((f) => (f as File & { path?: string }).path ?? "")
    .filter((p) => p !== "");
  if (paths.length === 0) return;
  await importDroppedFiles(paths);
}
function onDragOver(e: DragEvent) {
  e.preventDefault();
}
</script>

<template>
  <TitleBar />
  <div v-if="store.ready && store.workspace.ready" class="layout" @drop="onDrop" @dragover="onDragOver" @keydown="onKeydown">
    <SidebarNav />
    <NoteListPane v-if="showListPane" />
    <div class="content">
      <EditorPane v-if="store.view === 'notes'" />
      <TrashView v-else-if="store.view === 'trash'" />
      <SettingsView v-else />
    </div>
  </div>
  <div v-else-if="store.ready" class="layout">
    <div class="plain-pane">
      <div class="plain-inner">
        <h2>欢迎使用 JustMD</h2>
        <p class="sub">选择一个文件夹作为你的知识库。笔记以标准 Markdown 文件保存在本地，随时可以用其他工具打开。</p>
        <button class="btn primary" @click="chooseWorkspace">选择知识库文件夹…</button>
      </div>
    </div>
  </div>
  <ModalHost />
</template>
