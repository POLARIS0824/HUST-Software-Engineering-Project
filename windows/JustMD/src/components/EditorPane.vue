<script setup lang="ts">
import { ref, computed, watch, nextTick } from "vue";
import { store, onEditorInput, saveNow, renameNote, trashCurrentNote, exportCurrentNote } from "../lib/store";
import { wrapSelection, prefixLines } from "../lib/markdown";
import ReaderView from "./ReaderView.vue";
import BacklinksPanel from "./BacklinksPanel.vue";

const textarea = ref<HTMLTextAreaElement | null>(null);
const titleInput = ref<HTMLInputElement | null>(null);
const titleDraft = ref("");

watch(
  () => store.current?.id,
  async () => {
    titleDraft.value = store.current?.title ?? "";
    store.showBacklinks = false;
    await nextTick();
    textarea.value?.focus();
  },
  { immediate: true },
);

const saveLabel = computed(() => {
  switch (store.saveState) {
    case "saved": return "已保存";
    case "dirty": return "正在输入…";
    case "saving": return "保存中…";
    case "error": return "保存失败，点击重试";
  }
});

const statsLabel = computed(() => {
  const s = store.current?.stats;
  if (!s) return "";
  const parts = [`${s.charCount} 字符`];
  if (s.cjkCharCount > 0) parts.push(`${s.cjkCharCount} 汉字`);
  if (s.enWordCount > 0) parts.push(`${s.enWordCount} 英文词`);
  parts.push(`约 ${s.readingTimeMinute} 分钟阅读`);
  return parts.join(" · ");
});

// 换篇前等待最新内容提交（FR-EDIT-05）。
async function onTitleBlur() {
  if (titleDraft.value.trim() !== (store.current?.title ?? "")) {
    await renameNote(titleDraft.value);
  }
}
async function onTitleKeydown(e: KeyboardEvent) {
  if (e.key === "Enter") {
    (e.target as HTMLInputElement).blur();
  }
}

function applyFormat(kind: string) {
  const el = textarea.value;
  if (!el || !store.current) return;
  const sel: Parameters<typeof wrapSelection>[0] = {
    text: store.current.markdown,
    start: el.selectionStart,
    end: el.selectionEnd,
  };
  let next = store.current.markdown;
  let caretDelta = 0;
  switch (kind) {
    case "bold": next = wrapSelection(sel, "**", "**", "加粗文本"); caretDelta = 2; break;
    case "italic": next = wrapSelection(sel, "*", "*", "斜体文本"); caretDelta = 1; break;
    case "code": next = wrapSelection(sel, "`", "`", "代码"); caretDelta = 1; break;
    case "h2": next = prefixLines(store.current.markdown, sel.start, sel.end, "## "); break;
    case "list": next = prefixLines(store.current.markdown, sel.start, sel.end, "- "); break;
    case "task": next = prefixLines(store.current.markdown, sel.start, sel.end, "- [ ] "); break;
    case "quote": next = prefixLines(store.current.markdown, sel.start, sel.end, "> "); break;
    case "tag": next = wrapSelection(sel, "#", "", "标签"); break;
    case "wikilink": next = wrapSelection(sel, "[[", "]]", "笔记名称"); caretDelta = 2; break;
  }
  onEditorInput(next);
  // 输入后再渲染，尽量保持选区与光标位置（FR-EDIT-03）。
  requestAnimationFrame(() => {
    el.focus();
    if (kind === "bold" || kind === "italic" || kind === "code" || kind === "wikilink") {
      const pos = sel.start + caretDelta + (sel.text ? 0 : 0);
      el.setSelectionRange(pos + (sel.text ? sel.text.length : 0), pos + (sel.text ? sel.text.length : 0));
    }
  });
}

async function onRetrySave() {
  if (store.saveState === "error") await saveNow();
}
</script>

<template>
  <div v-if="store.current" class="editor-pane">
    <div class="editor-topbar">
      <input
        ref="titleInput"
        v-model="titleDraft"
        class="title-input"
        spellcheck="false"
        @blur="onTitleBlur"
        @keydown="onTitleKeydown"
      />
      <span class="save-badge" :class="store.saveState" :title="store.saveState === 'error' ? '点击重试' : ''" @click="onRetrySave">
        <span class="dot"></span>{{ saveLabel }}
      </span>
      <div class="mode-toggle">
        <button :class="{ active: store.mode === 'edit' }" @click="store.mode = 'edit'">编辑</button>
        <button :class="{ active: store.mode === 'read' }" @click="store.mode = 'read'">阅读</button>
      </div>
      <button class="icon-btn" title="导出本篇 Markdown" @click="exportCurrentNote">⬇</button>
      <button class="icon-btn" title="移入回收站" @click="trashCurrentNote">🗑</button>
    </div>

    <div v-if="store.mode === 'edit'" class="format-bar">
      <button title="加粗" @click="applyFormat('bold')"><b>B</b></button>
      <button title="斜体" @click="applyFormat('italic')"><i>I</i></button>
      <button title="行内代码" @click="applyFormat('code')">&lt;/&gt;</button>
      <button title="二级标题" @click="applyFormat('h2')">H2</button>
      <button title="无序列表" @click="applyFormat('list')">• 列表</button>
      <button title="任务列表" @click="applyFormat('task')">☑ 任务</button>
      <button title="引用" @click="applyFormat('quote')">❝ 引用</button>
      <button title="插入标签" @click="applyFormat('tag')"># 标签</button>
      <button title="插入双链" @click="applyFormat('wikilink')">[[ 双链 ]]</button>
    </div>

    <div class="editor-body">
      <textarea
        v-show="store.mode === 'edit'"
        ref="textarea"
        :value="store.current.markdown"
        spellcheck="false"
        placeholder="用 Markdown 记录…（#标签、[[双链]] 均可使用）"
        @input="onEditorInput(($event.target as HTMLTextAreaElement).value)"
      ></textarea>
      <ReaderView v-if="store.mode === 'read'" />
    </div>

    <div class="editor-bottombar">
      <span>{{ statsLabel }}</span>
      <span class="spacer"></span>
      <span class="linkish" @click="store.showBacklinks = !store.showBacklinks">
        反向链接 ({{ store.backlinks.length }})
      </span>
    </div>
  </div>
  <div v-else class="editor-pane" style="display: grid; place-items: center">
    <div class="empty-hint" style="max-width: 340px">
      <div style="font-size: 34px; margin-bottom: 12px">📝</div>
      在左侧选择或新建一篇笔记开始记录。<br />
      支持 <b>#标签</b>、<b>[[双链]]</b>、全文检索与自动保存。
    </div>
  </div>
  <BacklinksPanel v-if="store.current && store.showBacklinks" />
</template>
