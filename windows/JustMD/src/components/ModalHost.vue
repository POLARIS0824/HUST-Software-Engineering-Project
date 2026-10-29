<script setup lang="ts">
import { ref, watch } from "vue";
import { store, closeModal, dismissSnackbar, openNote } from "../lib/store";
import type { ImportFile } from "../mygo";

// 冲突文件逐项决策（基线 4.3：自动重命名 / 覆盖 / 跳过）。
const choices = ref<Record<string, string>>({});

const actionOptions: Array<{ key: "rename" | "overwrite" | "skip"; label: string }> = [
  { key: "rename", label: "自动重命名" },
  { key: "overwrite", label: "覆盖现有" },
  { key: "skip", label: "跳过" },
];

watch(
  () => store.modal,
  (m) => {
    if (m?.kind === "conflict" && m.plan) {
      const init: Record<string, string> = {};
      for (const f of m.plan.files) {
        if (f.status === "conflict") init[f.sourcePath] = "rename";
      }
      choices.value = init;
    }
  },
);

function setChoice(f: ImportFile, action: string) {
  choices.value = { ...choices.value, [f.sourcePath]: action };
}

const allSame = ref<"rename" | "overwrite" | "skip" | null>(null);
function applyAll(action: "rename" | "overwrite" | "skip") {
  allSame.value = action;
  const next: Record<string, string> = { ...choices.value };
  for (const f of store.modal?.plan?.files ?? []) {
    if (f.status === "conflict") next[f.sourcePath] = action;
  }
  choices.value = next;
}

function finishConflict() {
  closeModal("__per_file__");
}

async function followCandidate(id: string) {
  closeModal(null);
  await openNote(id);
}
</script>

<template>
  <!-- 模态弹层：确认 / 导入冲突 / 同名候选 -->
  <div v-if="store.modal" class="modal-mask" @click.self="store.modal?.kind === 'confirm' && closeModal(false)">
    <div class="modal">
      <div class="modal-head">{{ store.modal.title }}</div>

      <div v-if="store.modal.kind === 'confirm'" class="modal-body">
        <p>{{ store.modal.message }}</p>
      </div>
      <div class="modal-foot" v-if="store.modal.kind === 'confirm'">
        <button class="btn" @click="closeModal(false)">好的</button>
        <button v-if="store.modal.danger" class="btn danger" @click="closeModal(true)">确认删除</button>
        <button v-else-if="store.modal.message" class="btn primary" @click="closeModal(true)">确定</button>
      </div>

      <div v-else-if="store.modal.kind === 'conflict' && store.modal.plan" class="modal-body">
        <p>以下文件与现有笔记同名。请为每个文件选择处理方式（严禁静默覆盖）：</p>
        <div style="display: flex; gap: 8px; margin: 10px 0">
          <span style="font-size: 12px; color: var(--text-3); align-self: center">全部应用：</span>
          <button
            v-for="opt in actionOptions"
            :key="opt.key"
            class="btn"
            style="padding: 4px 12px; font-size: 12px"
            :class="{ primary: allSame === opt.key }"
            @click="applyAll(opt.key)"
          >
            {{ opt.label }}
          </button>
        </div>
        <div v-for="f in store.modal.plan.files" :key="f.sourcePath" class="conflict-file">
          <div class="name">
            {{ f.fileName }}
            <span v-if="f.status === 'invalid'" style="color: var(--danger); font-weight: 400">（{{ f.reason }}，将跳过）</span>
          </div>
          <div v-if="f.status === 'conflict'" class="choices">
            <button v-for="opt in actionOptions" :key="opt.key" :class="{ sel: choices[f.sourcePath] === opt.key }" @click="setChoice(f, opt.key)">
              {{ opt.label }}
            </button>
          </div>
          <div v-else style="font-size: 12px; color: var(--text-3); margin-top: 3px">目标：{{ f.targetRel }}</div>
        </div>
      </div>
      <div class="modal-foot" v-if="store.modal.kind === 'conflict'">
        <button class="btn" @click="closeModal('__cancel__')">取消导入</button>
        <button class="btn primary" @click="finishConflict">执行导入</button>
      </div>

      <div v-else-if="store.modal.kind === 'candidates'" class="modal-body">
        <p>请选择要打开的笔记：</p>
        <button v-for="c in store.modal.candidates ?? []" :key="c.id" class="candidate-row" @click="followCandidate(c.id)">
          <b>{{ c.title }}</b>
          <div style="font-size: 12px; color: var(--text-3)">{{ c.relativePath }}</div>
        </button>
      </div>
      <div class="modal-foot" v-if="store.modal.kind === 'candidates'">
        <button class="btn" @click="closeModal(null)">取消</button>
      </div>
    </div>
  </div>

  <!-- 撤销 Snackbar（BR-11） -->
  <div v-if="store.snackbar" class="snackbar">
    <span>{{ store.snackbar.message }}</span>
    <span v-if="store.snackbar.actionLabel" class="act" @click="store.snackbar?.onAction?.(); dismissSnackbar()">
      {{ store.snackbar.actionLabel }}
    </span>
  </div>

  <!-- 全局忙碌遮罩 -->
  <div v-if="store.busy" class="busy-mask">
    <div class="busy-card"><span class="spinner"></span>{{ store.busy }}</div>
  </div>
</template>
