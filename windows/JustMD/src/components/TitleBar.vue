<script setup lang="ts">
import { windowControls } from "../lib/store";
</script>

<template>
  <header class="titlebar">
    <div class="logo">J</div>
    <span class="app-name">JustMD</span>
    <span style="font-size: 12px; color: var(--text-3)">本地 Markdown 笔记与知识管理</span>
    <div class="win-controls">
      <button title="最小化" @click="windowControls.minimize">─</button>
      <button title="最大化/还原" @click="windowControls.toggleMaximize">▢</button>
      <button class="close" title="关闭" @click="windowControls.close">✕</button>
    </div>
  </header>
</template>
