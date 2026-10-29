<script setup lang="ts">
import { store, restoreNote, deleteForever, emptyTrash } from "../lib/store";

function fmtTime(ms: number): string {
  const d = new Date(ms);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")} ${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
}
</script>

<template>
  <div class="plain-pane">
    <div class="plain-inner">
      <h2>回收站</h2>
      <p class="sub">删除的笔记在此保留 30 天，之后自动清除。</p>

      <div v-for="n in store.trash" :key="n.id" class="trash-item">
        <div class="info">
          <div class="t">{{ n.title }}</div>
          <div class="d">原路径：{{ n.relativePath }} · 删除于 {{ fmtTime(n.updatedAtUtc) }}</div>
        </div>
        <button class="btn" @click="restoreNote(n.id)">恢复</button>
        <button class="btn danger" @click="deleteForever(n.id)">彻底删除</button>
      </div>
      <div v-if="store.trash.length === 0" class="empty-hint">回收站是空的</div>

      <div v-if="store.trash.length > 0" style="margin-top: 16px">
        <button class="btn danger" @click="emptyTrash">清空回收站</button>
      </div>
    </div>
  </div>
</template>
