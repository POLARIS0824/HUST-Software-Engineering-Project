<script setup lang="ts">
import { store, chooseWorkspace, startImport, exportVault, rebuildIndex } from "../lib/store";
</script>

<template>
  <div class="plain-pane">
    <div class="plain-inner">
      <h2>设置与数据</h2>
      <p class="sub">全部数据保存在本地文件系统，无账号、无网络依赖（BR-08/BR-10）。</p>

      <div class="settings-group">
        <h3>知识库位置</h3>
        <div class="desc">笔记正文以标准 UTF-8 Markdown 文件保存在该文件夹的 notes/ 目录中，可随时更换或用其他工具直接打开。</div>
        <div class="settings-row">
          <code class="path-code">{{ store.workspace.root || "（未选择）" }}</code>
          <button class="btn" @click="chooseWorkspace">更换文件夹…</button>
        </div>
      </div>

      <div class="settings-group">
        <h3>导入</h3>
        <div class="desc">从外部导入 .md 文件或整个文件夹（兼容 GBK 编码，自动转 UTF-8；同名文件逐一询问处理方式）。</div>
        <div class="settings-row">
          <button class="btn" @click="startImport">导入 Markdown… (Ctrl+Shift+I)</button>
        </div>
      </div>

      <div class="settings-group">
        <h3>导出</h3>
        <div class="desc">把完整知识库打包为标准 ZIP 归档（notes/ + attachments/ + manifest.json 校验清单），可在其他设备或其他软件中打开。</div>
        <div class="settings-row">
          <button class="btn" @click="exportVault">导出完整知识库为 ZIP…</button>
        </div>
      </div>

      <div class="settings-group">
        <h3>索引维护</h3>
        <div class="desc">标签、双链与检索索引均为可重建的派生数据（BR-02/BR-03）。正文文件永远安然无恙，需要时一键从 Markdown 事实源重建。</div>
        <div class="settings-row">
          <button class="btn" @click="rebuildIndex">重建索引</button>
        </div>
      </div>
    </div>
  </div>
</template>
