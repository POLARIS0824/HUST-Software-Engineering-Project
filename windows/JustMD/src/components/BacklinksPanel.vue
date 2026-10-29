<script setup lang="ts">
import { store, openNote } from "../lib/store";
</script>

<template>
  <aside class="backlinks-pane">
    <div class="pane-head">🔗 反向链接（{{ store.backlinks.length }}）</div>
    <div class="pane-body">
      <button v-for="b in store.backlinks" :key="b.sourceNoteId + b.line" class="backlink-card" @click="openNote(b.sourceNoteId)">
        <span class="src">{{ b.sourceTitle }}</span>
        <span class="snippet">{{ b.snippet }}</span>
        <span class="line-no">第 {{ b.line }} 行</span>
      </button>
      <div v-if="store.backlinks.length === 0" class="empty-hint">
        还没有笔记引用「{{ store.current?.title }}」。<br /><br />
        在其他笔记里写下 <b>[[{{ store.current?.title }}]]</b> 就会出现在这里。
      </div>
    </div>
  </aside>
</template>
